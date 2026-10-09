package com.aolandai.dingtou.store;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 一个<b>异步任务</b>的记录：影刀拿 {@code taskId} 轮询取结果。
 *
 * <h2>⚠️ 为什么需要它（而不是复用幂等流水）</h2>
 * <p>两者都在存「应答」，但<b>键和用途都不同</b>：</p>
 * <table border="1">
 *   <tr><th></th><th>{@link RequestEntry}</th><th>{@code TaskJob}</th></tr>
 *   <tr><td>键</td><td>{@code requestId}（<b>影刀</b>生成）</td>
 *       <td>{@code taskId}（<b>服务</b>生成）</td></tr>
 *   <tr><td>回答</td><td>「这个请求做过没有」→ 防重复执行</td>
 *       <td>「这次执行跑完没有」→ 供轮询取结果</td></tr>
 *   <tr><td>写入时机</td><td>受理时占位（PENDING）</td><td>转异步时创建（RUNNING）</td></tr>
 * </table>
 * <p>硬要合成一个会同时牺牲两边的语义（幂等键是影刀可控的，轮询键必须服务可控，
 * 否则影刀重复提交同一个 requestId 会污染轮询）。</p>
 *
 * @param taskId       <b>服务生成</b>的任务 ID（主键），影刀拿它轮询
 * @param requestId    原始请求号（便于把「一次影刀调用」和「一个异步任务」对上）
 * @param taskCode     任务码
 * @param status       见 {@link TaskJobStatus}
 * @param responseJson 跑完后的<b>完整应答</b> JSON（含 {@code code} / {@code data}），原样回给影刀
 * @param message      进行中的说明 / 失败原因
 * @param createdAt    受理时间
 * @param updatedAt    最后一次状态变更时间
 */
public record TaskJob(
        String taskId,
        String requestId,
        String taskCode,
        TaskJobStatus status,
        String responseJson,
        String message,
        String createdAt,
        String updatedAt) {

    /** 状态流转（记录类不可变）。 */
    public TaskJob transition(TaskJobStatus newStatus, String newResponseJson,
                              String newMessage, String newUpdatedAt) {
        return new TaskJob(taskId, requestId, taskCode, newStatus, newResponseJson, newMessage,
                createdAt, newUpdatedAt);
    }

    /**
     * ⚠️ 派生 getter 必须 {@code @JsonIgnore}。
     *
     * <p>Jackson 会把 {@code isRunning()} 当成名为 {@code running} 的属性写进 JSONL；
     * 回读时 record 只能靠<b>构造器参数</b>绑定，{@code running} 就成了「未知字段」，
     * 而默认 {@code FAIL_ON_UNKNOWN_PROPERTIES=true} → <b>整个文件读不回来，服务起不来</b>。</p>
     */
    @JsonIgnore
    public boolean isRunning() {
        return status == TaskJobStatus.RUNNING;
    }

    /** 见 {@link #isRunning()} 的说明。 */
    @JsonIgnore
    public boolean isFinished() {
        return status == TaskJobStatus.DONE || status == TaskJobStatus.FAILED;
    }
}
