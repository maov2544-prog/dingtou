package com.aolandai.dingtou.exception;

import com.aolandai.dingtou.dto.api.ErrorCode;

/**
 * 业务异常。
 *
 * <p>业务代码里<b>只抛这个</b>，不要写 try-catch 兜底 ——
 * 由 {@code controller/ApiExceptionHandler} 统一转成 {@link ErrorCode} 应答。</p>
 *
 * <p>⚠️ 抛 {@link ErrorCode#BUSINESS_FAIL} 时，影刀应当<b>上报人工</b>而不是重试：
 * 重试一个必然失败的请求，在「建千川计划」这种动作上意味着可能重复花钱。</p>
 */
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        if (errorCode == null) {
            throw new IllegalArgumentException("errorCode 不能为空");
        }
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        if (errorCode == null) {
            throw new IllegalArgumentException("errorCode 不能为空");
        }
        this.errorCode = errorCode;
    }

    /** 参数错的快捷构造，等价于 {@code new BizException(BAD_REQUEST, message)}。 */
    public static BizException badRequest(String message) {
        return new BizException(ErrorCode.BAD_REQUEST, message);
    }

    /** 业务失败的快捷构造。 */
    public static BizException businessFail(String message) {
        return new BizException(ErrorCode.BUSINESS_FAIL, message);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
