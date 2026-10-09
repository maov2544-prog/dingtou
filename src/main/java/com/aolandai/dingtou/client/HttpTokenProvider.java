package com.aolandai.dingtou.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 带缓存的 Access-Token 提供者（千川 / 随心推共用一套实现，靠 {@link TokenEndpoint#state()} 区分）。
 *
 * <p>⚠️ 老项目踩过的三个点，这里都保留：</p>
 * <ul>
 *   <li><b>凭据放 Windows 系统级环境变量</b>：服务以 LocalSystem 运行时读不到用户级变量；</li>
 *   <li><b>校验返回的 state</b>：两套 token 同源，拿错会一直鉴权失败且很难查；</li>
 *   <li><b>缓存 token</b>：逐条任务都换一次 token，会让一次批量投放变成几百次多余往返。</li>
 * </ul>
 */
public class HttpTokenProvider implements AccessTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(HttpTokenProvider.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TokenEndpoint endpoint;
    private final HttpClient httpClient;

    private final AtomicReference<Cached> cached = new AtomicReference<>();

    private record Cached(String token, long expiresAtMillis) {
        boolean valid(long now) {
            return token != null && !token.isBlank() && now < expiresAtMillis;
        }
    }

    public HttpTokenProvider(TokenEndpoint endpoint) {
        this.endpoint = endpoint;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(endpoint.connectTimeoutSeconds()))
                .build();
    }

    /** 该提供者对应哪套 token，用于日志与探活。 */
    public String state() {
        return endpoint.state();
    }

    @Override
    public String token() throws Exception {
        Cached current = cached.get();
        if (current != null && current.valid(System.currentTimeMillis())) {
            return current.token();
        }
        synchronized (this) {
            Cached again = cached.get();
            if (again != null && again.valid(System.currentTimeMillis())) {
                return again.token();
            }
            return refresh();
        }
    }

    @Override
    public void invalidate() {
        if (cached.getAndSet(null) != null) {
            log.warn("{} 的 Access-Token 已被主动作废，下次调用将重新换取", endpoint.state());
        }
    }

    @Override
    public long remainingSeconds() {
        Cached current = cached.get();
        if (current == null) {
            return 0L;
        }
        long remaining = current.expiresAtMillis() - System.currentTimeMillis();
        return remaining <= 0 ? 0L : remaining / 1000L;
    }

    // ==================== 换取 ====================

    private String refresh() throws Exception {
        String bearer = System.getenv(endpoint.bearerEnvVar());
        if (bearer == null || bearer.isBlank()) {
            throw new IllegalStateException(
                    "未配置环境变量 " + endpoint.bearerEnvVar()
                            + "（服务以 LocalSystem 运行时读不到用户级环境变量，必须配系统级）");
        }

        log.info("正在换取 Access-Token（state={}）", endpoint.state());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint.url()))
                .timeout(Duration.ofSeconds(endpoint.readTimeoutSeconds()))
                .header("Authorization", "Bearer " + bearer)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("换取 Token 失败（state=" + endpoint.state()
                    + "），HTTP " + response.statusCode() + "，响应：" + response.body());
        }

        JsonNode root = MAPPER.readTree(response.body());
        if (!root.path("success").asBoolean(false)) {
            throw new IllegalStateException("换取 Token 失败（state=" + endpoint.state()
                    + "），服务端返回：" + root.path("message").asText("未知错误"));
        }

        String state = root.path("state").asText();
        if (!endpoint.state().equals(state)) {
            throw new IllegalStateException("换取 Token 失败：返回的 state 不正确，实际 " + state
                    + "，期望 " + endpoint.state() + "（两套 token 同源，拿错会一直鉴权失败）");
        }

        String accessToken = root.path("access_token").asText("");
        if (accessToken.isBlank()) {
            throw new IllegalStateException("换取 Token 失败：响应中没有 access_token");
        }

        cached.set(new Cached(accessToken,
                System.currentTimeMillis() + endpoint.ttlMinutes() * 60_000L));
        log.info("Access-Token 已刷新（state={}），缓存 {} 分钟",
                endpoint.state(), endpoint.ttlMinutes());
        return accessToken;
    }
}
