package com.aolandai.dingtou.dto.api;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 统一应答体。影刀只看 {@code code} 与 {@code data}。
 *
 * <pre>
 * { "code": 0, "message": "ok", "requestId": "...", "taskId": "...", "costMs": 1234, "data": {...} }
 * </pre>
 *
 * @param code      见 {@link ErrorCode}
 * @param message   人类可读说明（失败时影刀可以直接贴到企微群里）
 * @param requestId 原样回传影刀的 requestId，便于对账
 * @param taskId    仅 {@code code=4} 时有值；影刀拿它轮询 {@code GET /api/v1/task/{taskId}}
 * @param costMs    服务端耗时，用于发现「哪个任务在变慢」
 * @param data      业务数据；成功时才有意义
 */
public record ApiResponse(
        int code,
        String message,
        String requestId,
        String taskId,
        long costMs,
        Object data) {

    public static ApiResponse ok(String requestId, long costMs, Object data) {
        return new ApiResponse(ErrorCode.OK.code(), "ok", requestId, null, costMs, data);
    }

    public static ApiResponse error(ErrorCode errorCode, String requestId, String detail) {
        String message = detail == null || detail.isBlank()
                ? errorCode.message()
                : errorCode.message() + ": " + detail;
        return new ApiResponse(errorCode.code(), message, requestId, null, 0L, null);
    }

    public static ApiResponse error(ErrorCode errorCode, String requestId) {
        return error(errorCode, requestId, null);
    }

    /** 超时转异步：带上 taskId 让影刀去轮询，而不是重试。 */
    public static ApiResponse timeout(String requestId, String taskId, long costMs) {
        return new ApiResponse(ErrorCode.TIMEOUT.code(), ErrorCode.TIMEOUT.message(),
                requestId, taskId, costMs, null);
    }

    /**
     * 任务<b>仍在后台正常执行</b>（轮询场景）：带上 taskId 让影刀继续轮询。
     *
     * <p>⚠️ 与 {@link #timeout} 分开是有意的：两者 {@code code} 都是 4，
     * 但 {@code timeout} 的消息是「执行超时」—— 对一个<b>正在正常跑</b>的任务说「超时」，
     * 会让人以为出问题了。这里的消息说清「还在跑、已跑多久、别重发」。</p>
     */
    public static ApiResponse running(String requestId, String taskId, long elapsedMillis) {
        return new ApiResponse(ErrorCode.TIMEOUT.code(),
                "任务仍在后台执行（已 " + (elapsedMillis / 1000) + "s），请用 taskId 继续轮询；"
                        + "不要重发原请求（重复提交会重复花钱）",
                requestId, taskId, elapsedMillis, null);
    }

    /**
     * 便捷判断，供<b>服务端内部</b>与单测使用。
     *
     * <p>⚠️ 必须 {@code @JsonIgnore}：Jackson 会把 {@code isXxx()} 当成一个名为 {@code ok} 的属性，
     * 于是每个应答里都会多出一个 {@code "ok": true} —— 影刀根本不需要它，还容易被误当成接口契约。</p>
     */
    @JsonIgnore
    public boolean isOk() {
        return code == ErrorCode.OK.code();
    }
}