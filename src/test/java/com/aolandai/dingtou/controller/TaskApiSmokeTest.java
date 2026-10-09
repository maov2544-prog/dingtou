package com.aolandai.dingtou.controller;

import com.aolandai.dingtou.dto.api.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Web 链路冒烟测试：<b>真的起一个 Tomcat</b>，走完「HTTP → 拦截器 → Controller → Dispatcher → Handler」。
 *
 * <p>这是影刀视角的验证 —— 单元测试全绿但这条路断了，服务照样用不了。</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "dingtou.auth-token=smoke-test-token-2026",
                // 幂等流水写到临时目录，别污染工程里的 data/
                "dingtou.store.data-dir=${java.io.tmpdir}/dingtou-smoke-test"
        })
class TaskApiSmokeTest {

    private static final String TOKEN = "smoke-test-token-2026";

    @Autowired
    private TestRestTemplate rest;

    private ResponseEntity<ApiResponse> post(String taskCode, String token, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.set(AuthInterceptor.TOKEN_HEADER, token);
        }
        return rest.exchange("/api/v1/task/" + taskCode, HttpMethod.POST,
                new HttpEntity<>(body, headers), ApiResponse.class);
    }

    @Test
    @DisplayName("无 token → 401")
    void rejectsMissingToken() {
        ResponseEntity<ApiResponse> response = post("ping", null, null);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(3, response.getBody().code());
    }

    @Test
    @DisplayName("token 错 → 401")
    void rejectsWrongToken() {
        assertEquals(HttpStatus.UNAUTHORIZED, post("ping", "wrong-token", null).getStatusCode());
    }

    @Test
    @DisplayName("ping：带正确 token → code=0 且返回 status=up")
    void pingWorks() {
        ResponseEntity<ApiResponse> response = post("ping", TOKEN, null);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        ApiResponse body = response.getBody();
        assertNotNull(body);
        assertTrue(body.isOk(), body.message());
        assertNotNull(body.data());
    }

    @Test
    @DisplayName("幂等：同一 requestId 重复提交 → 返回同一份应答（不回放失败）")
    void sameRequestIdReplaysOverHttp() {
        String requestId = java.util.UUID.randomUUID().toString();
        String json = """
                {"requestId":"%s","params":{}}
                """.formatted(requestId);

        ApiResponse first = post("ping", TOKEN, json).getBody();
        ApiResponse second = post("ping", TOKEN, json).getBody();

        assertNotNull(first);
        assertNotNull(second);
        assertTrue(first.isOk(), first.message());
        assertTrue(second.isOk(), second.message());
        assertEquals(String.valueOf(first.data()), String.valueOf(second.data()));
    }

    @Test
    @DisplayName("planBuild：缺必填参数 → code=2")
    void planBuildRejectsMissingParam() {
        ApiResponse body = post("planBuild", TOKEN, "{\"params\":{\"awemeShowId\":\"x\"}}").getBody();
        assertNotNull(body);
        assertEquals(2, body.code());
        assertTrue(body.message().contains("productName"), body.message());
    }

    @Test
    @DisplayName("未知任务码 → code=2")
    void unknownTaskCode() {
        ApiResponse body = post("nope", TOKEN, null).getBody();
        assertNotNull(body);
        assertEquals(2, body.code());
    }

    // ==================== JSON 契约（防止内部字段泄漏到接口上） ====================

    private String postRaw(String taskCode, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(AuthInterceptor.TOKEN_HEADER, TOKEN);
        return rest.exchange("/api/v1/task/" + taskCode, HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class).getBody();
    }

    @Test
    @DisplayName("应答里不该出现内部派生的 ok 字段")
    void internalOkFieldIsNotSerialized() throws Exception {
        String json = postRaw("ping", null);
        assertNotNull(json);
        // ⚠️ 必须解析成 JSON 后只看【根节点】的字段：
        // 直接搜 "\"ok\":" 会把嵌套对象里的同名字段（如 tables 下的可用性标记）误判成泄漏。
        JsonNode root = new ObjectMapper().readTree(json);
        assertFalse(root.has("ok"), "isOk() 泄漏成 JSON 字段了: " + json);
        assertTrue(root.has("code"));
    }

    // ==================== 异步轮询 ====================

    private ResponseEntity<ApiResponse> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.set(AuthInterceptor.TOKEN_HEADER, token);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), ApiResponse.class);
    }

    @Test
    @DisplayName("★ 轮询不存在的 taskId → code=1，且提示里不能写成「已完成」")
    void pollingUnknownTaskReturnsBusinessFail() {
        ApiResponse body = get("/api/v1/task/t-not-exist", TOKEN).getBody();

        assertNotNull(body);
        assertEquals(1, body.code(), "查不到任务要如实说，不能让影刀以为成功");
        assertTrue(body.message().contains("不存在或已过期"), body.message());
    }

    @Test
    @DisplayName("轮询也要鉴权：无 token → 401")
    void pollingRequiresToken() {
        assertEquals(HttpStatus.UNAUTHORIZED,
                get("/api/v1/task/t-whatever", null).getStatusCode());
    }
}
