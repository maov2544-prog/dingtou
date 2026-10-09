package com.aolandai.dingtou.controller;

import com.aolandai.dingtou.dto.api.ApiResponse;
import com.aolandai.dingtou.dto.api.ErrorCode;
import com.aolandai.dingtou.exception.BizException;
import com.aolandai.dingtou.exception.RateLimitException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常兜底：把漏到 MVC 层的异常转成统一应答。
 *
 * <p>{@code TaskDispatcher} 已经处理了业务异常；这里兜的是<b>它接不到的那些</b> ——
 * 请求体不是合法 JSON、路径变量缺失等，都属于「影刀传错了」，必须回 {@code code=2} 让它改参数，
 * 而不是回 {@code code=5} 让它重试。</p>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ApiResponse handleBiz(BizException e) {
        log.warn("业务异常: {}", e.getMessage());
        return ApiResponse.error(e.errorCode(), null, e.getMessage());
    }

    @ExceptionHandler(RateLimitException.class)
    public ApiResponse handleRateLimit(RateLimitException e) {
        log.warn("频控: {}", e.describe());
        return ApiResponse.error(ErrorCode.BUSINESS_FAIL, null, e.describe());
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MissingPathVariableException.class,
            IllegalArgumentException.class,
    })
    public ApiResponse handleBadRequest(Exception e) {
        log.warn("参数错误: {}", e.getMessage());
        return ApiResponse.error(ErrorCode.BAD_REQUEST, null, e.getMessage());
    }

    /**
     * Content-Type 不支持 —— 这是<b>客户端传错</b>，必须回 {@code code=2}。
     *
     * <p>⚠️ 不能让它落到兜底的 {@code code=5}：{@code code=5} 按契约是「可重试」，
     * 而 Content-Type 传错是<b>确定性错误</b>，重试一万次也一样失败 —— 只会白白占用影刀的
     * 重试预算、把日志刷满。</p>
     *
     * <p>（注：{@code text/plain} 已在 {@code WebConfig} 里被主动接受，走到这里的通常是
     * 更冷门的类型，如 {@code application/xml}。）</p>
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ApiResponse handleUnsupportedMediaType(HttpMediaTypeNotSupportedException e) {
        log.warn("Content-Type 不支持: {}", e.getContentType());
        return ApiResponse.error(ErrorCode.BAD_REQUEST, null,
                "Content-Type '" + e.getContentType() + "' 不支持；请用 application/json"
                        + "（当前也兼容 text/plain）");
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse handleOther(Exception e) {
        log.error("未预期异常", e);
        return ApiResponse.error(ErrorCode.INTERNAL_ERROR, null,
                e.getClass().getSimpleName() + ": " + e.getMessage());
    }
}
