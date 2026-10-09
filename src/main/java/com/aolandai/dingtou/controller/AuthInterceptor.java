package com.aolandai.dingtou.controller;

import com.aolandai.dingtou.config.DingtouProperties;
import com.aolandai.dingtou.dto.api.ApiResponse;
import com.aolandai.dingtou.dto.api.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 鉴权拦截器：校验 {@code X-Auth-Token}。
 *
 * <p>⚠️ 这个服务是被<b>影刀从进程外调用</b>的，不像老工程那样只绑回环就够了 ——
 * <b>至少必须上 token</b>，否则任何人都能触发建计划（要花钱）。</p>
 *
 * <p>放行 {@code /actuator/**}：探活端点需要免鉴权，否则监控探针拿不到健康状态。
 * 但 actuator 只暴露 health/info（见 application.yml），不会泄露敏感信息。</p>
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthInterceptor.class);

    /** 鉴权头名。 */
    public static final String TOKEN_HEADER = "X-Auth-Token";

    private final DingtouProperties properties;
    private final ObjectMapper objectMapper;

    public AuthInterceptor(DingtouProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws IOException {
        String provided = request.getHeader(TOKEN_HEADER);
        String expected = properties.getAuthToken();

        if (expected != null && !expected.isBlank() && expected.equals(provided)) {
            return true;
        }

        log.warn("鉴权失败 uri={} remote={} tokenProvided={}",
                request.getRequestURI(), request.getRemoteAddr(), provided != null);
        writeUnauthorized(response);
        return false;
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiResponse body = ApiResponse.error(ErrorCode.UNAUTHORIZED, null,
                "缺少或错误的 " + TOKEN_HEADER + " 请求头");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
