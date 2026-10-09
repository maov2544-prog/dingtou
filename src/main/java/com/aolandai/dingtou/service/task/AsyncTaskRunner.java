package com.aolandai.dingtou.service.task;

import com.aolandai.dingtou.config.TaskProperties;
import com.aolandai.dingtou.dto.api.ApiResponse;
import com.aolandai.dingtou.dto.api.ErrorCode;
import com.aolandai.dingtou.store.TaskJobStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 「超时转异步」的执行器：<b>同步等一小会儿，等不到就转后台</b>。
 *
 * <h2>它解决的问题</h2>
 * <p>影刀同步等待 → 超时 → 重试 → <b>重复建计划（花钱）</b>。
 * 实测 {@code planBuild} 34~160 秒、{@code kolPlanCostQuery} 约 245 秒，
 * 都远超影刀默认超时。转异步后影刀<b>立刻</b>拿到 taskId 去轮询，不会超时重试。</p>
 *
 * <h2>⚠️ 超时后任务【继续跑】，不会被取消</h2>
 * <p>这是刻意的：任务可能已经走到「建计划」那一步，中途掐掉会留下
 * 「计划建了一半」的状态。所以超时只是「<b>不再等</b>」，不是「停止执行」——
 * 后台线程会把它跑完，结果写进 {@link TaskJobStore}，影刀轮询就能取到。</p>
 *
 * <h2>⚠️ 默认关闭</h2>
 * <p>{@code dingtou.task.async-timeout-millis} 默认 0 = 全部同步，行为与改造前一致。
 * 转异步要求影刀支持轮询，所以<b>影刀改好之前不要打开</b>。</p>
 */
@Component
public class AsyncTaskRunner {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskRunner.class);

    /** 后台任务结束后的回调（在后台线程里执行）—— 用于写幂等流水等收尾工作。 */
    @FunctionalInterface
    public interface CompletionHandler {
        void onFinish(ApiResponse response);
    }

    /**
     * 执行结果。
     *
     * @param response 同步完成时的应答；转异步时为 {@code null}
     * @param taskId   转异步时的任务 ID；同步完成时为 {@code null}
     */
    public record Outcome(ApiResponse response, String taskId) {

        public boolean isAsync() {
            return taskId != null;
        }

        static Outcome done(ApiResponse response) {
            return new Outcome(response, null);
        }

        static Outcome accepted(String taskId) {
            return new Outcome(null, taskId);
        }
    }

    private final TaskJobStore jobStore;
    private final TaskProperties properties;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;

    public AsyncTaskRunner(TaskJobStore jobStore, TaskProperties properties,
                           ObjectMapper objectMapper) {
        this.jobStore = jobStore;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.executor = Executors.newFixedThreadPool(
                Math.max(1, properties.getWorkerThreads()), namedThreadFactory());
    }

    /** 是否开启「超时转异步」。 */
    public boolean isEnabled() {
        return properties.isAsyncEnabled();
    }

    /** 当前的同步等待上限（毫秒）—— 供调用方回显「等了多久才转的异步」。 */
    public long timeoutMillis() {
        return properties.getAsyncTimeoutMillis();
    }

    /**
     * 执行任务；超过 {@code async-timeout-millis} 仍未完成则转后台。
     *
     * @param taskCode  任务码（写进 job 记录，便于轮询时回显）
     * @param requestId 影刀的请求号（可能为 {@code null}）
     * @param work      真正的执行体（<b>自己吞掉业务异常</b>，返回应答而不是抛）
     * @param onFinish  后台完成后的回调；同步完成时不会被调用
     */
    public Outcome run(String taskCode, String requestId, Supplier<ApiResponse> work,
                       CompletionHandler onFinish) {
        long timeoutMillis = properties.getAsyncTimeoutMillis();
        CompletableFuture<ApiResponse> future = CompletableFuture.supplyAsync(work, executor);

        try {
            ApiResponse response = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            return Outcome.done(response);
        } catch (TimeoutException e) {
            return switchToAsync(taskCode, requestId, future, timeoutMillis, onFinish);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.done(ApiResponse.error(ErrorCode.INTERNAL_ERROR, requestId,
                    "等待任务结果时被中断"));
        } catch (Exception e) {
            // work 自己应该吞掉异常；能到这里说明是框架层的意外（如线程池拒绝）
            log.error("提交后台任务失败 code={} requestId={}", taskCode, requestId, e);
            return Outcome.done(ApiResponse.error(ErrorCode.INTERNAL_ERROR, requestId,
                    "任务执行异常: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    private Outcome switchToAsync(String taskCode, String requestId,
                                  CompletableFuture<ApiResponse> future, long timeoutMillis,
                                  CompletionHandler onFinish) {
        String taskId = "t-" + UUID.randomUUID();
        log.warn("任务超过 {}ms 未完成，转后台执行 taskId={} code={} requestId={}",
                timeoutMillis, taskId, taskCode, requestId);
        try {
            jobStore.create(taskId, requestId, taskCode, "已受理，后台执行中");
        } catch (IOException e) {
            // ⚠️ 登记不上就不能转异步 —— 影刀会轮询到一个查不到的任务。
            //    宁可让它现在就知道失败，也不要给它一个假 taskId 去空转。
            log.error("登记异步任务失败 taskId={} code={}", taskId, taskCode, e);
            return Outcome.done(ApiResponse.error(ErrorCode.INTERNAL_ERROR, requestId,
                    "任务超时后转后台失败（无法登记任务记录）: " + e.getMessage()));
        }

        // ⚠️ 超时只是「不再等」，任务继续跑；跑完把结果写进 job，影刀轮询即可取到
        future.whenComplete((response, error) -> finishInBackground(
                taskId, taskCode, requestId, response, error, onFinish));

        return Outcome.accepted(taskId);
    }

    private void finishInBackground(String taskId, String taskCode, String requestId,
                                    ApiResponse response, Throwable error,
                                    CompletionHandler onFinish) {
        ApiResponse finalResponse = response;
        if (error != null) {
            log.error("后台任务执行异常 taskId={} code={}", taskId, taskCode, error);
            Throwable cause = error instanceof CompletionException ? error.getCause() : error;
            String detail = cause == null ? error.toString() : cause.getMessage();
            finalResponse = ApiResponse.error(ErrorCode.INTERNAL_ERROR, requestId,
                    "后台任务异常: " + detail);
        }
        try {
            String json = finalResponse == null ? "" : objectMapper.writeValueAsString(finalResponse);
            if (finalResponse != null && finalResponse.code() == ErrorCode.INTERNAL_ERROR.code()) {
                jobStore.fail(taskId, json, finalResponse.message());
            } else {
                jobStore.finish(taskId, json);
            }
        } catch (IOException e) {
            // ⚠️ 这里失败等于「任务跑完了但结果丢了」—— 影刀会一直轮询到 24h 过期。
            //    必须吵，让运维能从日志里发现。
            log.error("写异步任务结果失败 taskId={} code={}（影刀轮询会取不到结果）",
                    taskId, taskCode, e);
        }
        if (onFinish != null && finalResponse != null) {
            try {
                onFinish.onFinish(finalResponse);
            } catch (RuntimeException e) {
                log.error("异步任务收尾回调失败 taskId={}", taskId, e);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("关闭任务执行线程池……");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private static ThreadFactory namedThreadFactory() {
        AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "dingtou-task-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
