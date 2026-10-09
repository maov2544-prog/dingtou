package com.aolandai.dingtou.service.task;

import com.aolandai.dingtou.config.StoreProperties;
import com.aolandai.dingtou.dto.api.ApiResponse;
import com.aolandai.dingtou.dto.api.ErrorCode;
import com.aolandai.dingtou.store.TaskJobStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link TaskJobQueryService} 单测：真存储 + 真序列化，不碰网络。 */
class TaskJobQueryServiceTest {

    private TaskJobStore store;
    private TaskJobQueryService service;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        StoreProperties props = new StoreProperties();
        props.setDataDir(dir);
        store = new TaskJobStore(props);
        service = new TaskJobQueryService(store, new ObjectMapper());
    }

    @Test
    @DisplayName("★ 还在跑 → code=4 + taskId（影刀应继续轮询，而不是重发原请求）")
    void runningReturnsTimeoutCode() throws Exception {
        store.create("t-run", "r-1", "planBuild", "已受理，后台执行中");

        ApiResponse response = service.poll("t-run");

        assertEquals(ErrorCode.TIMEOUT.code(), response.code());
        assertEquals("t-run", response.taskId());
        assertEquals("r-1", response.requestId());
        assertTrue(response.message().contains("仍在后台执行"), response.message());
        assertTrue(response.message().contains("不要重发原请求"), response.message());
    }

    @Test
    @DisplayName("★ 已完成 → 原样回放当初那份应答（含 data），不是重新算一遍")
    void doneReplaysOriginalResponse() throws Exception {
        store.create("t-done", "r-2", "planBuild", "已受理");
        store.finish("t-done", "{\"code\":0,\"message\":\"成功\",\"requestId\":\"r-2\","
                + "\"taskId\":null,\"costMs\":1234,\"data\":{\"adId\":1877746196794116}}");

        ApiResponse response = service.poll("t-done");

        assertEquals(0, response.code());
        assertEquals("r-2", response.requestId());
        assertEquals(1234L, response.costMs());
        assertNotNull(response.data());
        assertTrue(String.valueOf(response.data()).contains("1877746196794116"),
                String.valueOf(response.data()));
    }

    @Test
    @DisplayName("★ 跑挂了 → 也回放那份失败应答（影刀得知道「挂了」而不是一直等）")
    void failedReplaysFailureResponse() throws Exception {
        store.create("t-fail", "r-3", "kolPlanCostQuery", "已受理");
        store.fail("t-fail", "{\"code\":5,\"message\":\"SocketTimeoutException: timeout\"}",
                "SocketTimeoutException: timeout");

        ApiResponse response = service.poll("t-fail");

        assertEquals(ErrorCode.INTERNAL_ERROR.code(), response.code());
        assertTrue(response.message().contains("timeout"), response.message());
    }

    @Test
    @DisplayName("★ 查不到 → code=1，且提示里【不能】写成「已完成」（会让影刀以为成功）")
    void unknownTaskIsBusinessFail() {
        ApiResponse response = service.poll("t-nope");

        assertEquals(ErrorCode.BUSINESS_FAIL.code(), response.code());
        assertTrue(response.message().contains("不存在或已过期"), response.message());
        assertTrue(response.message().contains("重新提交"), response.message());
    }

    @Test
    @DisplayName("taskId 为空 → code=2（参数错）")
    void blankTaskIdIsBadRequest() {
        assertEquals(ErrorCode.BAD_REQUEST.code(), service.poll("").code());
        assertEquals(ErrorCode.BAD_REQUEST.code(), service.poll(null).code());
    }

    @Test
    @DisplayName("任务已结束但没存应答 → code=5 + 提示去查文件（不静默返回空成功）")
    void finishedWithoutResponseIsInternalError() throws Exception {
        store.create("t-empty", "r-4", "ping", "已受理");
        // 直接标记 DONE 但应答为空 —— 模拟落盘异常
        store.finish("t-empty", "");

        ApiResponse response = service.poll("t-empty");

        assertEquals(ErrorCode.INTERNAL_ERROR.code(), response.code());
        assertTrue(response.message().contains("没有存应答"), response.message());
    }
}
