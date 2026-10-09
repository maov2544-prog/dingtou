package com.aolandai.dingtou.service.task;

import com.aolandai.dingtou.config.StoreProperties;
import com.aolandai.dingtou.dto.api.ApiRequest;
import com.aolandai.dingtou.dto.api.ApiResponse;
import com.aolandai.dingtou.dto.api.ErrorCode;
import com.aolandai.dingtou.exception.BizException;
import com.aolandai.dingtou.exception.RateLimitException;
import com.aolandai.dingtou.config.TaskProperties;
import com.aolandai.dingtou.store.RequestLedger;
import com.aolandai.dingtou.store.TaskJobStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 任务注册表 + 分发器单测：应答码翻译规则、启动期校验、幂等保护。 */
class TaskDispatcherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ==================== 测试用 handler ====================

    private static TaskHandler handler(String code, TaskBody body) {
        return new TaskHandler() {
            @Override
            public String code() {
                return code;
            }

            @Override
            public Object handle(JsonNode params) throws Exception {
                return body.run(params);
            }
        };
    }

    @FunctionalInterface
    private interface TaskBody {
        Object run(JsonNode params) throws Exception;
    }

    private static TaskRegistry registryOf(TaskHandler... handlers) {
        return new TaskRegistry(List.of(handlers));
    }

    /** 默认 runner：<b>不转异步</b>（async-timeout=0）→ 行为与改造前完全一致。 */
    private static AsyncTaskRunner syncRunner(StoreProperties props) throws IOException {
        TaskProperties taskProps = new TaskProperties();
        taskProps.setAsyncTimeoutMillis(0L);
        return new AsyncTaskRunner(new TaskJobStore(props), taskProps, MAPPER);
    }

    private static TaskDispatcher dispatcher(TaskRegistry registry, Path dir) throws IOException {
        StoreProperties props = new StoreProperties();
        props.setDataDir(dir);
        return new TaskDispatcher(registry, new RequestLedger(props), MAPPER, syncRunner(props));
    }

    // ==================== TaskRegistry ====================

    @Test
    @DisplayName("code 重复 → 启动就报错，并指出冲突的两个类")
    void duplicateCodeFailsFast() {
        TaskHandler a = handler("dup", p -> "a");
        TaskHandler b = handler("dup", p -> "b");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new TaskRegistry(List.of(a, b)));
        assertTrue(e.getMessage().contains("dup"), e.getMessage());
    }

    @Test
    @DisplayName("code 为空 / 一个 handler 都没有 → 启动就报错")
    void invalidRegistryFailsFast() {
        assertThrows(IllegalStateException.class,
                () -> new TaskRegistry(List.of(handler("  ", p -> "x"))));
        assertThrows(IllegalStateException.class,
                () -> new TaskRegistry(List.of(handler(null, p -> "x"))));
        assertThrows(IllegalStateException.class, () -> new TaskRegistry(List.of()));
    }

    @Test
    @DisplayName("find：命中返回 handler，未命中 / 空串返回空")
    void findWorks() {
        TaskRegistry registry = registryOf(handler("ping", p -> "pong"));
        assertTrue(registry.find("ping").isPresent());
        assertTrue(registry.find("nope").isEmpty());
        assertTrue(registry.find(null).isEmpty());
        assertTrue(registry.find("  ").isEmpty());
        assertEquals(1, registry.size());
    }

    // ==================== TaskDispatcher：基本执行与应答码 ====================

    @Test
    @DisplayName("正常执行 → code=0，data 原样带回")
    void dispatchOk(@TempDir Path dir) throws IOException {
        TaskDispatcher dispatcher = dispatcher(registryOf(handler("ping", p -> "pong")), dir);
        ApiResponse response = dispatcher.dispatch("ping", new ApiRequest("req-1", null));

        assertTrue(response.isOk());
        assertEquals("pong", response.data());
        assertEquals("req-1", response.requestId());
    }

    @Test
    @DisplayName("taskCode 为空 / 未知 → code=2（参数错，影刀应改参数而不是重试）")
    void dispatchBadTaskCode(@TempDir Path dir) throws IOException {
        TaskDispatcher dispatcher = dispatcher(registryOf(handler("ping", p -> "pong")), dir);

        assertEquals(ErrorCode.BAD_REQUEST.code(), dispatcher.dispatch(null, null).code());
        assertEquals(ErrorCode.BAD_REQUEST.code(), dispatcher.dispatch("  ", null).code());

        ApiResponse unknown = dispatcher.dispatch("nope", null);
        assertEquals(ErrorCode.BAD_REQUEST.code(), unknown.code());
        assertTrue(unknown.message().contains("ping"), "应提示已注册的任务: " + unknown.message());
    }

    @Test
    @DisplayName("request 为 null 时按空请求处理，不炸")
    void dispatchToleratesNullRequest(@TempDir Path dir) throws IOException {
        TaskDispatcher dispatcher = dispatcher(registryOf(handler("ping", p -> "pong")), dir);
        assertTrue(dispatcher.dispatch("ping", null).isOk());
    }

    @Test
    @DisplayName("BizException → 用它自带的码")
    void bizExceptionKeepsItsCode(@TempDir Path dir) throws IOException {
        TaskDispatcher dispatcher = dispatcher(registryOf(
                handler("bad", p -> {
                    throw BizException.badRequest("缺参数 x");
                }),
                handler("fail", p -> {
                    throw BizException.businessFail("业务不允许");
                })), dir);

        ApiResponse bad = dispatcher.dispatch("bad", null);
        assertEquals(ErrorCode.BAD_REQUEST.code(), bad.code());
        assertTrue(bad.message().contains("缺参数 x"));

        ApiResponse fail = dispatcher.dispatch("fail", null);
        assertEquals(ErrorCode.BUSINESS_FAIL.code(), fail.code());
        assertTrue(fail.message().contains("业务不允许"));
    }

    @Test
    @DisplayName("RateLimitException → code=1 且带频控说明（影刀不得重试）")
    void rateLimitBecomesBusinessFail(@TempDir Path dir) throws IOException {
        TaskDispatcher dispatcher = dispatcher(registryOf(handler("rl", p -> {
            throw new RateLimitException(RateLimitException.Scope.DEVELOPER, "被开发者频控拦截", 330_000L);
        })), dir);

        ApiResponse response = dispatcher.dispatch("rl", null);
        assertEquals(ErrorCode.BUSINESS_FAIL.code(), response.code());
        assertTrue(response.message().contains("开发者频控"), response.message());
        assertFalse(response.isOk());
    }

    @Test
    @DisplayName("其它异常 → code=5（影刀可重试，靠 requestId 幂等兜底）")
    void unexpectedExceptionBecomesInternalError(@TempDir Path dir) throws IOException {
        TaskDispatcher dispatcher = dispatcher(registryOf(handler("boom", p -> {
            throw new IllegalStateException("炸了");
        })), dir);

        ApiResponse response = dispatcher.dispatch("boom", new ApiRequest("req-9", null));
        assertEquals(ErrorCode.INTERNAL_ERROR.code(), response.code());
        assertTrue(response.message().contains("IllegalStateException"));
        assertEquals("req-9", response.requestId());
    }

    @Test
    @DisplayName("params 原样透传给 handler")
    void paramsArePassedThrough(@TempDir Path dir) throws Exception {
        TaskDispatcher dispatcher = dispatcher(registryOf(
                handler("echo", params -> params.path("x").asText())), dir);
        JsonNode params = MAPPER.readTree("{\"x\":\"hello\"}");
        assertEquals("hello", dispatcher.dispatch("echo", new ApiRequest("r", params)).data());
    }

    // ==================== 幂等保护 ====================

    @Test
    @DisplayName("同一 requestId 第二次 → 回放上次应答，handler 只执行一次")
    void sameRequestIdReplaysWithoutReexecuting(@TempDir Path dir) throws IOException {
        AtomicInteger executions = new AtomicInteger();
        TaskDispatcher dispatcher = dispatcher(registryOf(handler("count", p -> {
            executions.incrementAndGet();
            return "executed-" + executions.get();
        })), dir);

        ApiRequest request = new ApiRequest("req-idem", null);
        ApiResponse first = dispatcher.dispatch("count", request);
        ApiResponse second = dispatcher.dispatch("count", request);

        assertEquals(1, executions.get(), "第二次不应再执行 handler");
        assertEquals("executed-1", first.data());
        assertEquals("executed-1", second.data(), "第二次应回放第一次的应答");
        assertTrue(second.isOk());
    }

    @Test
    @DisplayName("★ 同一 requestId 换 taskCode → 拒绝回放旧应答（否则会返回与本次请求无关的结果）")
    void sameRequestIdAcrossDifferentTasksIsRejected(@TempDir Path dir) throws IOException {
        AtomicInteger bExecutions = new AtomicInteger();
        TaskDispatcher dispatcher = dispatcher(registryOf(
                handler("taskA", p -> "A 的结果"),
                handler("taskB", p -> {
                    bExecutions.incrementAndGet();
                    return "B 的结果";
                })), dir);

        ApiRequest request = new ApiRequest("reused-id", null);
        assertEquals("A 的结果", dispatcher.dispatch("taskA", request).data());

        ApiResponse second = dispatcher.dispatch("taskB", request);

        assertEquals(ErrorCode.BAD_REQUEST.code(), second.code(),
                "跨 taskCode 复用 requestId 必须报参数错，而不是静默回放 A 的结果");
        assertTrue(second.message().contains("taskA"),
                "应说明该 requestId 被哪个任务占用了: " + second.message());
        assertEquals(0, bExecutions.get(), "B 不该被执行（更不能绕过幂等保护）");
    }

    @Test
    @DisplayName("回归：同一 taskCode 下仍正常回放（上面的校验别把正常幂等也挡了）")
    void sameTaskSameRequestIdStillReplays(@TempDir Path dir) throws IOException {
        AtomicInteger executions = new AtomicInteger();
        TaskDispatcher dispatcher = dispatcher(registryOf(handler("taskA", p -> {
            executions.incrementAndGet();
            return "A" + executions.get();
        })), dir);

        ApiRequest request = new ApiRequest("same-id", null);
        assertEquals("A1", dispatcher.dispatch("taskA", request).data());
        assertEquals("A1", dispatcher.dispatch("taskA", request).data(), "同任务应照常回放");
        assertEquals(1, executions.get());
    }

    @Test
    @DisplayName("没有 requestId → 不做幂等保护，每次都执行（并留 WARN 日志）")
    void missingRequestIdSkipsIdempotency(@TempDir Path dir) throws IOException {
        AtomicInteger executions = new AtomicInteger();
        TaskDispatcher dispatcher = dispatcher(registryOf(handler("count", p -> {
            executions.incrementAndGet();
            return "executed-" + executions.get();
        })), dir);

        dispatcher.dispatch("count", new ApiRequest(null, null));
        dispatcher.dispatch("count", new ApiRequest(null, null));
        dispatcher.dispatch("count", ApiRequest.empty());

        assertEquals(3, executions.get());
    }

    @Test
    @DisplayName("同一 requestId 正在执行中（PENDING）→ 拒绝重复提交，handler 不执行")
    void pendingRequestIsRejected(@TempDir Path dir) throws IOException {
        StoreProperties props = new StoreProperties();
        props.setDataDir(dir);
        RequestLedger ledger = new RequestLedger(props);
        ledger.tryBegin("req-running", "count");   // 模拟上一次调用还没结束

        AtomicInteger executions = new AtomicInteger();
        TaskDispatcher dispatcher = new TaskDispatcher(
                registryOf(handler("count", p -> {
                    executions.incrementAndGet();
                    return "x";
                })), ledger, MAPPER, syncRunner(props));

        ApiResponse response = dispatcher.dispatch("count", new ApiRequest("req-running", null));
        assertEquals(ErrorCode.BUSINESS_FAIL.code(), response.code());
        assertTrue(response.message().contains("正在执行中"), response.message());
        assertEquals(0, executions.get());
    }

    @Test
    @DisplayName("内部错误记 FAILED → 同 requestId 可以重试（handler 会再执行一次）")
    void failedRequestCanBeRetried(@TempDir Path dir) throws IOException {
        AtomicInteger executions = new AtomicInteger();
        TaskDispatcher dispatcher = dispatcher(registryOf(handler("flaky", p -> {
            int attempt = executions.incrementAndGet();
            if (attempt == 1) {
                throw new IllegalStateException("第一次故意失败");
            }
            return "recovered";
        })), dir);

        ApiRequest request = new ApiRequest("req-retry", null);
        assertEquals(ErrorCode.INTERNAL_ERROR.code(), dispatcher.dispatch("flaky", request).code());
        ApiResponse retry = dispatcher.dispatch("flaky", request);

        assertEquals(2, executions.get(), "FAILED 应允许重试");
        assertTrue(retry.isOk());
        assertEquals("recovered", retry.data());
    }

    @Test
    @DisplayName("业务失败记 DONE → 同 requestId 重试直接回放同一个失败，不再执行")
    void businessFailureIsDeterministicAndReplayed(@TempDir Path dir) throws IOException {
        AtomicInteger executions = new AtomicInteger();
        TaskDispatcher dispatcher = dispatcher(registryOf(handler("biz", p -> {
            executions.incrementAndGet();
            throw BizException.businessFail("这个组合不允许投放");
        })), dir);

        ApiRequest request = new ApiRequest("req-biz", null);
        ApiResponse first = dispatcher.dispatch("biz", request);
        ApiResponse second = dispatcher.dispatch("biz", request);

        assertEquals(1, executions.get(), "业务失败是确定性的，不该重复执行");
        assertEquals(ErrorCode.BUSINESS_FAIL.code(), second.code());
        assertTrue(second.message().contains("这个组合不允许投放"));
        assertEquals(first.code(), second.code());
    }

    @Test
    @DisplayName("幂等流水能跨实例恢复（模拟服务重启）")
    void ledgerSurvivesRestart(@TempDir Path dir) throws IOException {
        AtomicInteger executions = new AtomicInteger();
        TaskHandler counter = handler("count", p -> {
            executions.incrementAndGet();
            return "executed-" + executions.get();
        });
        ApiRequest request = new ApiRequest("req-restart", null);

        dispatcher(registryOf(counter), dir).dispatch("count", request);
        // 新建一整套（新 ledger，从同一个文件加载）＝ 重启
        ApiResponse afterRestart = dispatcher(registryOf(counter), dir).dispatch("count", request);

        assertEquals(1, executions.get(), "重启后也不该重复执行");
        assertEquals("executed-1", afterRestart.data());
    }

    @Test
    @DisplayName("⚠️ 落盘格式里不该出现派生 getter（isDone/isPending）—— 否则下次启动直接读不回来")
    void persistedFormatHasNoDerivedGetters(@TempDir Path dir) throws IOException {
        TaskDispatcher dispatcher = dispatcher(registryOf(handler("ping", p -> "pong")), dir);
        dispatcher.dispatch("ping", new ApiRequest("req-json", null));

        Path file = dir.resolve("request-ledger.jsonl");
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(content.contains("\"done\""),
                "isDone() 泄漏进了持久化格式，重启会因未知字段而拒绝启动: " + content);
        assertFalse(content.contains("\"pending\""),
                "isPending() 泄漏进了持久化格式: " + content);

        // 关键：真的能重新加载
        StoreProperties props = new StoreProperties();
        props.setDataDir(dir);
        assertTrue(new RequestLedger(props).find("req-json").isPresent());
    }
}
