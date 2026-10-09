package com.aolandai.dingtou.service.task;

import com.aolandai.dingtou.dto.api.ApiRequest;
import com.aolandai.dingtou.dto.api.ApiResponse;
import com.aolandai.dingtou.dto.api.ErrorCode;
import com.aolandai.dingtou.exception.BizException;
import com.aolandai.dingtou.exception.RateLimitException;
import com.aolandai.dingtou.store.RequestEntry;
import com.aolandai.dingtou.store.RequestLedger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;

/**
 * 任务分发器：按 {@code taskCode} 找到 {@link TaskHandler} 执行，
 * 并负责<b>幂等保护</b>与<b>异常 → 应答码的翻译</b>。
 *
 * <h2>幂等（本工程最重要的防护）</h2>
 * <p>影刀超时后会重试，而重试「建千川计划」是要花钱的。所以：</p>
 * <ul>
 *   <li>{@code DONE} → <b>原样回放上次应答</b>，绝不重复执行；</li>
 *   <li>{@code PENDING} → 拒绝并发重复提交；</li>
 *   <li>{@code FAILED} → 允许重试（内部错误是瞬时的）。</li>
 * </ul>
 * <p>⚠️ 没带 {@code requestId} 的请求<b>无法保护</b> —— 会照常执行，但打 WARN 日志留痕。</p>
 *
 * <h2>异常分类</h2>
 * <p>分类直接影响影刀行为，不要图省事一律当内部错误：</p>
 * <ul>
 *   <li>{@link BizException} → 用它自带的码 → 影刀<b>上报人工，不重试</b>；</li>
 *   <li>{@link RateLimitException} → 业务失败 + 频控说明 → 影刀<b>不重试</b>（重试会加重 5 分钟惩罚）；</li>
 *   <li>其它异常 → 内部错误 → 影刀<b>可重试</b>（靠 requestId 幂等兜底）。</li>
 * </ul>
 */
@Component
public class TaskDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TaskDispatcher.class);

    private final TaskRegistry registry;
    private final RequestLedger ledger;
    private final ObjectMapper objectMapper;
    private final AsyncTaskRunner asyncRunner;

    public TaskDispatcher(TaskRegistry registry, RequestLedger ledger, ObjectMapper objectMapper,
                          AsyncTaskRunner asyncRunner) {
        this.registry = registry;
        this.ledger = ledger;
        this.objectMapper = objectMapper;
        this.asyncRunner = asyncRunner;
    }

    public ApiResponse dispatch(String taskCode, ApiRequest request) {
        ApiRequest safeRequest = request == null ? ApiRequest.empty() : request;
        String requestId = safeRequest.requestId();

        if (taskCode == null || taskCode.isBlank()) {
            return ApiResponse.error(ErrorCode.BAD_REQUEST, requestId, "taskCode 不能为空");
        }
        Optional<TaskHandler> found = registry.find(taskCode);
        if (found.isEmpty()) {
            return ApiResponse.error(ErrorCode.BAD_REQUEST, requestId,
                    "未知 taskCode: " + taskCode + "；已注册: " + registry.codes());
        }
        TaskHandler handler = found.get();
        JsonNode params = safeRequest.params();

        // 没有 requestId 就没有幂等保护 —— 照常执行，但必须留痕
        if (!safeRequest.hasRequestId()) {
            log.warn("请求未携带 requestId，本次没有幂等保护 code={}（影刀重试会导致重复执行）", taskCode);
            return execute(handler, taskCode, params, null);
        }

        try {
            Optional<RequestEntry> existing = ledger.tryBegin(requestId, taskCode);
            if (existing.isPresent()) {
                RequestEntry entry = existing.get();
                // ⚠️⚠️ 同一 requestId 换了 taskCode —— 这是调用方复用错了。
                //     绝不能回放旧应答：那会返回一条**与本次请求毫无关系**的结果，
                //     而调用方完全看不出原因（改了 URL、改了参数，返回却纹丝不动）。
                //     实测踩过：requestId=r3 先在 planAwemeLookup 用过并缓存了「items 必须是非空数组」，
                //     之后改打 kolPlanCostQuery 仍原样返回那条错误。
                if (!taskCode.equals(entry.taskCode())) {
                    log.warn("requestId 复用冲突：{} 已用于 taskCode={}（{}），本次却是 {} —— 拒绝回放",
                            requestId, entry.taskCode(), entry.status(), taskCode);
                    return ApiResponse.error(ErrorCode.BAD_REQUEST, requestId,
                            "该 requestId 已用于 taskCode '" + entry.taskCode() + "'；"
                                    + "同一 requestId 不能跨任务复用（否则会回放到无关的旧应答），"
                                    + "请换一个新的 requestId");
                }
                return handleExisting(entry);
            }
            // 没开「超时转异步」→ 老行为（全同步），影刀侧完全无感
            if (!asyncRunner.isEnabled()) {
                ApiResponse response = execute(handler, taskCode, params, requestId);
                recordOutcome(requestId, response);
                return response;
            }
            return dispatchAsync(handler, taskCode, params, requestId);
        } catch (IOException e) {
            log.error("幂等流水读写失败 requestId={} code={}", requestId, taskCode, e);
            return ApiResponse.error(ErrorCode.INTERNAL_ERROR, requestId,
                    "幂等流水读写失败: " + e.getMessage());
        }
    }

    /**
     * 带「超时转异步」的执行。
     *
     * <p>⚠️ 转异步时幂等记录<b>保持 PENDING</b>（任务确实还在跑）——
     * 影刀若在此期间用同一 {@code requestId} 重发，会命中 PENDING 被拒，这是<b>正确</b>行为。
     * 后台跑完后由回调补写 DONE，之后重发才会回放结果。</p>
     */
    private ApiResponse dispatchAsync(TaskHandler handler, String taskCode, JsonNode params,
                                      String requestId) {
        AsyncTaskRunner.Outcome outcome = asyncRunner.run(taskCode, requestId,
                () -> execute(handler, taskCode, params, requestId),
                // ⚠️ 后台完成时必须补写幂等流水 —— 否则这个 requestId 会一直停在 PENDING，
                //    影刀重发永远被拒（而任务其实早就跑完了）
                response -> recordOutcomeQuietly(requestId, response));

        if (!outcome.isAsync()) {
            recordOutcomeQuietly(requestId, outcome.response());
            return outcome.response();
        }
        return ApiResponse.running(requestId, outcome.taskId(), asyncRunner.timeoutMillis());
    }

    /** 收尾写流水，失败只记日志 —— 这个位置已经没法把错误回给影刀了。 */
    private void recordOutcomeQuietly(String requestId, ApiResponse response) {
        try {
            recordOutcome(requestId, response);
        } catch (IOException e) {
            log.error("写幂等流水失败 requestId={}（该 requestId 会一直停在 PENDING，重发会被拒）",
                    requestId, e);
        }
    }

    // ==================== 幂等命中 ====================

    private ApiResponse handleExisting(RequestEntry entry) {
        if (entry.isDone()) {
            log.info("命中幂等记录，回放上次应答 requestId={} code={}",
                    entry.requestId(), entry.taskCode());
            return replay(entry);
        }
        log.warn("同一 requestId 正在执行中，拒绝重复提交 requestId={} code={}",
                entry.requestId(), entry.taskCode());
        return ApiResponse.error(ErrorCode.BUSINESS_FAIL, entry.requestId(),
                "同一 requestId 正在执行中，请勿重复提交（startedAt=" + entry.createdAt() + "）");
    }

    /** 把上次落盘的应答 JSON 原样还原成对象。 */
    private ApiResponse replay(RequestEntry entry) {
        if (entry.responseJson() == null || entry.responseJson().isBlank()) {
            return ApiResponse.error(ErrorCode.INTERNAL_ERROR, entry.requestId(),
                    "幂等记录已标记完成但没有存应答内容，请人工检查流水文件");
        }
        try {
            return objectMapper.readValue(entry.responseJson(), ApiResponse.class);
        } catch (IOException e) {
            log.error("幂等记录里的应答 JSON 解析失败 requestId={}", entry.requestId(), e);
            return ApiResponse.error(ErrorCode.INTERNAL_ERROR, entry.requestId(),
                    "幂等记录里的应答无法解析: " + e.getMessage());
        }
    }

    /**
     * 记下结果。
     *
     * <p>只有「内部错误」算 FAILED（可重试）；业务失败 / 参数错是<b>确定性</b>结果 ——
     * 重试只会得到同一个失败，所以记成 DONE 让重试直接回放，省掉一次无谓执行。</p>
     */
    private void recordOutcome(String requestId, ApiResponse response) throws IOException {
        if (response.code() == ErrorCode.INTERNAL_ERROR.code()) {
            ledger.markFailed(requestId, response.message());
            return;
        }
        ledger.markDone(requestId, objectMapper.writeValueAsString(response));
    }

    // ==================== 执行 ====================

    private ApiResponse execute(TaskHandler handler, String taskCode, JsonNode params,
                                String requestId) {
        long startedAt = System.currentTimeMillis();
        log.info("task 开始 code={} requestId={}", taskCode, requestId);
        try {
            Object data = handler.handle(params);
            long cost = System.currentTimeMillis() - startedAt;
            log.info("task 成功 code={} requestId={} costMs={}", taskCode, requestId, cost);
            return ApiResponse.ok(requestId, cost, data);
        } catch (BizException e) {
            log.warn("task 业务失败 code={} requestId={} err={}", taskCode, requestId, e.getMessage());
            return ApiResponse.error(e.errorCode(), requestId, e.getMessage());
        } catch (RateLimitException e) {
            log.warn("task 被频控拦截 code={} requestId={} {}", taskCode, requestId, e.describe());
            return ApiResponse.error(ErrorCode.BUSINESS_FAIL, requestId, e.describe());
        } catch (Exception e) {
            log.error("task 内部错误 code={} requestId={}", taskCode, requestId, e);
            return ApiResponse.error(ErrorCode.INTERNAL_ERROR, requestId,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
