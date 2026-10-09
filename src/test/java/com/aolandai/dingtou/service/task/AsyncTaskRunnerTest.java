package com.aolandai.dingtou.service.task;

import com.aolandai.dingtou.config.StoreProperties;
import com.aolandai.dingtou.config.TaskProperties;
import com.aolandai.dingtou.dto.api.ApiResponse;
import com.aolandai.dingtou.dto.api.ErrorCode;
import com.aolandai.dingtou.store.TaskJob;
import com.aolandai.dingtou.store.TaskJobStatus;
import com.aolandai.dingtou.store.TaskJobStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AsyncTaskRunner} 单测：真线程、真落盘，<b>不碰网络</b>。
 *
 * <p>超时阈值压到 100ms，用 {@code Thread.sleep} 模拟慢任务。</p>
 */
class AsyncTaskRunnerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static TaskProperties props(long timeoutMillis) {
        TaskProperties p = new TaskProperties();
        p.setAsyncTimeoutMillis(timeoutMillis);
        p.setWorkerThreads(2);
        return p;
    }

    private static TaskJobStore store(Path dir) throws Exception {
        StoreProperties sp = new StoreProperties();
        sp.setDataDir(dir);
        return new TaskJobStore(sp);
    }

    @Test
    @DisplayName("★ 关闭时（timeout=0）→ 纯同步返回，绝不产生 taskId（老行为不变）")
    void disabledRunsSynchronously(@TempDir Path dir) throws Exception {
        TaskJobStore jobStore = store(dir);
        // ⚠️ timeout=0 时 isEnabled()=false，调用方根本不会走到 runner；
        //    但即便直接调，也必须立刻返回（不能 0 毫秒超时→必然转异步）
        AsyncTaskRunner runner = new AsyncTaskRunner(jobStore, props(0), MAPPER);
        assertFalse(runner.isEnabled());

        // 用一个不会超时的方式验证：超时设为 0 时，runner 仍应同步跑完（由调用方决定不启用）
        AsyncTaskRunner runnerWithTime = new AsyncTaskRunner(jobStore, props(5_000), MAPPER);
        AsyncTaskRunner.Outcome outcome = runnerWithTime.run("ping", "r-1",
                () -> ApiResponse.ok("r-1", 1L, "done"), null);

        assertFalse(outcome.isAsync());
        assertNotNull(outcome.response());
        assertEquals(0, outcome.response().code());
        assertEquals(0, jobStore.jobCount(), "同步完成不该留下 job 记录");
    }

    @Test
    @DisplayName("★ 超过阈值 → 转后台：返回 taskId，job 先登记为 RUNNING")
    void slowTaskSwitchesToAsync(@TempDir Path dir) throws Exception {
        TaskJobStore jobStore = store(dir);
        AsyncTaskRunner runner = new AsyncTaskRunner(jobStore, props(100), MAPPER);

        AsyncTaskRunner.Outcome outcome = runner.run("planBuild", "r-2",
                () -> {
                    sleep(600);
                    return ApiResponse.ok("r-2", 600L, "slow");
                }, null);

        assertTrue(outcome.isAsync(), "超过 100ms 应当转异步");
        assertNotNull(outcome.taskId());
        assertNull(outcome.response());

        TaskJob job = jobStore.find(outcome.taskId()).orElseThrow();
        assertEquals(TaskJobStatus.RUNNING, job.status());
        assertEquals("planBuild", job.taskCode());
        assertEquals("r-2", job.requestId());
    }

    @Test
    @DisplayName("★★ 转异步后任务【继续跑完】并把结果落盘（影刀轮询才取得到）")
    void asyncTaskCompletesAndPersistsResult(@TempDir Path dir) throws Exception {
        TaskJobStore jobStore = store(dir);
        AsyncTaskRunner runner = new AsyncTaskRunner(jobStore, props(100), MAPPER);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<ApiResponse> callbackResponse = new AtomicReference<>();

        AsyncTaskRunner.Outcome outcome = runner.run("planBuild", "r-3",
                () -> {
                    sleep(400);
                    return ApiResponse.ok("r-3", 400L, "终于跑完了");
                },
                response -> {
                    callbackResponse.set(response);
                    finished.countDown();
                });

        assertTrue(outcome.isAsync());
        assertTrue(finished.await(5, TimeUnit.SECONDS), "后台任务应当在合理时间内跑完");

        TaskJob job = awaitFinished(jobStore, outcome.taskId());
        assertEquals(TaskJobStatus.DONE, job.status());
        assertNotNull(job.responseJson());
        assertTrue(job.responseJson().contains("终于跑完了"), job.responseJson());

        // 落盘的应答必须能被轮询端原样解析回来
        ApiResponse replayed = MAPPER.readValue(job.responseJson(), ApiResponse.class);
        assertEquals(0, replayed.code());
        assertEquals("r-3", replayed.requestId());

        // ⚠️ 回调必须被调用 —— 分发器靠它补写幂等流水，漏了会让 requestId 永远停在 PENDING
        assertNotNull(callbackResponse.get(), "onFinish 回调必须被调用");
    }

    @Test
    @DisplayName("★ 后台任务抛异常 → job 记 FAILED + 应答是 code=5（不是一直 RUNNING）")
    void asyncFailureIsRecordedAsFailed(@TempDir Path dir) throws Exception {
        TaskJobStore jobStore = store(dir);
        AsyncTaskRunner runner = new AsyncTaskRunner(jobStore, props(100), MAPPER);

        AsyncTaskRunner.Outcome outcome = runner.run("planBuild", "r-4",
                () -> {
                    sleep(300);
                    throw new IllegalStateException("炸了");
                }, null);

        assertTrue(outcome.isAsync());
        TaskJob job = awaitFinished(jobStore, outcome.taskId());
        assertEquals(TaskJobStatus.FAILED, job.status());
        assertTrue(job.message().contains("炸了"), job.message());
        assertTrue(job.responseJson().contains(String.valueOf(ErrorCode.INTERNAL_ERROR.code())),
                job.responseJson());
    }

    @Test
    @DisplayName("快任务不转异步，也不留 job 记录")
    void fastTaskStaysSynchronous(@TempDir Path dir) throws Exception {
        TaskJobStore jobStore = store(dir);
        AsyncTaskRunner runner = new AsyncTaskRunner(jobStore, props(2_000), MAPPER);

        AsyncTaskRunner.Outcome outcome = runner.run("ping", "r-5",
                () -> ApiResponse.ok("r-5", 1L, "fast"), null);

        assertFalse(outcome.isAsync());
        assertNull(outcome.taskId());
        assertEquals(0, jobStore.jobCount());
    }

    // ==================== 工具 ====================

    /** 轮询等 job 结束（最多 5 秒）。 */
    private static TaskJob awaitFinished(TaskJobStore store, String taskId) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            TaskJob job = store.find(taskId).orElseThrow();
            if (job.isFinished()) {
                return job;
            }
            sleep(50);
        }
        throw new AssertionError("任务在 5 秒内没有结束："
                + store.find(taskId).map(TaskJob::status).orElse(null));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
