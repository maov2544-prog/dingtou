package com.aolandai.dingtou.store;

/**
 * 异步任务的状态。
 *
 * <p>⚠️ 与 {@link RequestStatus}（幂等流水）不是一回事，别混：</p>
 * <ul>
 *   <li>{@link RequestStatus} 回答「这个 requestId <b>做过没有</b>」—— 防重复执行；</li>
 *   <li>{@code TaskJobStatus} 回答「这个 taskId <b>跑完了没有</b>」—— 供影刀轮询取结果。</li>
 * </ul>
 */
public enum TaskJobStatus {

    /** 已提交、还在后台跑。影刀应继续轮询。 */
    RUNNING("执行中"),

    /** 跑完了（<b>不代表业务成功</b> —— 业务成败看应答里的 {@code code}）。 */
    DONE("已完成"),

    /** 跑挂了（服务内部异常 / 超时放弃）。应答里会带原因。 */
    FAILED("失败");

    private final String label;

    TaskJobStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
