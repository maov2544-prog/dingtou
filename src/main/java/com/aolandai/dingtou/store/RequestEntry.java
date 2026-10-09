package com.aolandai.dingtou.store;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 幂等流水的一条记录：一个 {@code requestId} 对应一次业务动作。
 *
 * <p>⚠️ <b>这是本工程最重要的防护。</b>影刀的重试策略不可控，而重复建千川计划是要花钱的。
 * 只要影刀用同一个 {@code requestId} 重发，服务就必须<b>直接回放上次结果</b>，绝不重复执行。</p>
 *
 * @param requestId    影刀生成的唯一请求号（主键）
 * @param taskCode     任务码，用于排查「同一个 requestId 被用在两个任务上」
 * @param status       见 {@link RequestStatus}
 * @param responseJson 成功时的完整应答 JSON，用于原样回放
 * @param message      失败原因
 * @param createdAt    首次受理时间
 * @param updatedAt    最后一次状态变更时间
 */
public record RequestEntry(
        String requestId,
        String taskCode,
        RequestStatus status,
        String responseJson,
        String message,
        String createdAt,
        String updatedAt) {

    /** 状态流转，保持不可变语义（记录类不提供 setter）。 */
    public RequestEntry transition(RequestStatus newStatus, String newResponseJson,
                                   String newMessage, String newUpdatedAt) {
        return new RequestEntry(requestId, taskCode, newStatus, newResponseJson, newMessage,
                createdAt, newUpdatedAt);
    }

    /**
     * ⚠️ 必须 {@code @JsonIgnore}。
     *
     * <p>Jackson 会把 {@code isDone()} 当成名为 {@code done} 的属性写进 JSONL；
     * 回读时 record 只能靠<b>构造器参数</b>绑定，{@code done} 就成了「未知字段」，
     * 而默认 {@code FAIL_ON_UNKNOWN_PROPERTIES=true} → <b>整个流水文件读不回来，服务起不来</b>。</p>
     *
     * <p>这是持久化格式特有的坑：内存里的 DTO 多一个派生 getter 只是多一个 JSON 字段，
     * 但落到磁盘上的格式多一个字段，就可能让下次启动直接失败。</p>
     */
    @JsonIgnore
    public boolean isDone() {
        return status == RequestStatus.DONE;
    }

    /** 见 {@link #isDone()} 的说明 —— 派生 getter 一律 {@code @JsonIgnore}。 */
    @JsonIgnore
    public boolean isPending() {
        return status == RequestStatus.PENDING;
    }
}

