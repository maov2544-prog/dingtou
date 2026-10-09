package com.aolandai.dingtou.config;

import com.aolandai.dingtou.controller.AuthInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Web 层装配：注册鉴权拦截器 + 放宽请求体 Content-Type。
 *
 * <ul>
 *   <li>鉴权只拦 {@code /api/**} —— actuator 探活端点必须免鉴权，否则监控探针拿不到健康状态。</li>
 *   <li>请求体<b>额外接受 {@code text/plain}</b> —— 见下。</li>
 * </ul>
 *
 * <h2>⚠️ 为什么要接受 text/plain</h2>
 * <p>契约里写的是 {@code application/json}，但<b>影刀（RPA）的 HTTP 组件默认发
 * {@code Content-Type: text/plain;charset=utf-8}</b>。实测踩到：</p>
 * <pre>
 * HttpMediaTypeNotSupportedException: Content-Type 'text/plain;charset=utf-8' is not supported
 * </pre>
 * <p>结果是<b>对接第一步就卡住</b>。RPA 工具改 Content-Type 往往很别扭（甚至改不了），
 * 而「用 text/plain 装 JSON」在对接场景里极常见 —— 所以这里主动放宽：
 * <b>体内容照样按 JSON 解析</b>，解析不了仍然报 {@code code=2}，不会因此放松校验。</p>
 *
 * <p>契约文档仍推荐 {@code application/json}，只是不再「只认它」。</p>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final ObjectMapper objectMapper;

    public WebConfig(AuthInterceptor authInterceptor, ObjectMapper objectMapper) {
        this.authInterceptor = authInterceptor;
        this.objectMapper = objectMapper;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**");
    }

    /**
     * 追加一个「也认 text/plain」的 Jackson 转换器。
     *
     * <p>⚠️ 用 {@code extendMessageConverters}（<b>追加</b>）而不是
     * {@code configureMessageConverters}（<b>替换</b>）—— 后者会把 Spring Boot 默认装配的
     * 一堆转换器（String / 表单 / 字节流…）全部干掉，副作用远大于收益。</p>
     *
     * <p>默认的 Jackson 转换器只认 {@code application/json}，匹配不上 {@code text/plain}，
     * 所以会自然落到我们追加的这个上。</p>
     */
    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        MappingJackson2HttpMessageConverter textPlainJson =
                new MappingJackson2HttpMessageConverter(objectMapper);
        textPlainJson.setSupportedMediaTypes(List.of(
                MediaType.APPLICATION_JSON,
                MediaType.TEXT_PLAIN,
                new MediaType("application", "*+json")));
        converters.add(textPlainJson);
    }
}
