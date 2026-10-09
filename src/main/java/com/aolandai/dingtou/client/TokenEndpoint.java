package com.aolandai.dingtou.client;

/**
 * 换取 Access-Token 的端点参数。
 *
 * <p>自建服务端是<b>同一个地址、用 {@code state} 区分两套 token</b>：
 * {@code shop_qianchuan}（千川 PC）与 {@code shop_suixintui}（随心推）。
 * 拿错一套的表现是「一直鉴权失败」，而且日志里看不出原因 —— 所以把 state 显式化。</p>
 *
 * @param tokenUrl            换取端点（不含 state 参数）
 * @param state               业务标识，同时用于校验返回结果
 * @param bearerEnvVar        存放凭据的环境变量名
 * @param ttlMinutes          缓存有效期（分钟）
 * @param connectTimeoutSeconds 连接超时（秒）
 * @param readTimeoutSeconds    读超时（秒）
 */
public record TokenEndpoint(
        String tokenUrl,
        String state,
        String bearerEnvVar,
        int ttlMinutes,
        int connectTimeoutSeconds,
        int readTimeoutSeconds) {

    public TokenEndpoint {
        if (tokenUrl == null || tokenUrl.isBlank()) {
            throw new IllegalArgumentException("tokenUrl 不能为空");
        }
        if (state == null || state.isBlank()) {
            throw new IllegalArgumentException("state 不能为空");
        }
        if (bearerEnvVar == null || bearerEnvVar.isBlank()) {
            throw new IllegalArgumentException("bearerEnvVar 不能为空");
        }
        if (ttlMinutes <= 0) {
            throw new IllegalArgumentException("ttlMinutes 必须为正");
        }
    }

    /** 拼上 state 参数的完整 URL。 */
    public String url() {
        String separator = tokenUrl.contains("?") ? "&" : "?";
        return tokenUrl + separator + "action=token&state=" + state;
    }
}
