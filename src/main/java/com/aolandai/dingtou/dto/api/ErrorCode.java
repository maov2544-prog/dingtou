package com.aolandai.dingtou.dto.api;

/**
 * 统一应答码。
 *
 * <p>影刀只认 {@code code} 一个字段决定怎么走：<b>0 成功、1 业务失败、2 参数错、3 未授权、4 超时转异步</b>。</p>
 *
 * <p>⚠️ {@code code=4} 不是失败 —— 它表示「任务还在后台跑，请用 {@code taskId} 轮询」。
 * 设计它的原因是：千川接口 + 翻页可能跑几分钟，而<b>影刀死等会导致它自己超时重试 →
 * 重复建千川计划 → 花钱</b>。宁可主动放弃等待，也不能让影刀重试。</p>
 */
public enum ErrorCode {

    /** 成功。 */
    OK(0, "成功"),

    /** 业务失败（附 message，影刀应上报人工，不要重试）。 */
    BUSINESS_FAIL(1, "业务失败"),

    /** 参数错误（影刀传错了，改参数再调，不要重试）。 */
    BAD_REQUEST(2, "参数错误"),

    /** 鉴权失败（X-Auth-Token 不对）。 */
    UNAUTHORIZED(3, "未授权"),

    /** 执行超时：任务已转后台，请用 taskId 轮询。 */
    TIMEOUT(4, "执行超时，请用 taskId 轮询"),

    /** 服务内部错误。 */
    INTERNAL_ERROR(5, "服务内部错误");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }
}
