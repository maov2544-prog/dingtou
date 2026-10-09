package com.aolandai.dingtou.store;

/** 幂等流水里一条请求的状态。 */
public enum RequestStatus {

    /** 已受理、正在执行。⚠️ 看到这个状态说明上一次调用还没结束（或进程崩了没来得及收尾）。 */
    PENDING,

    /** 执行完成，应答已落盘，可直接回放。 */
    DONE,

    /** 执行失败（业务失败 / 参数错 / 内部错误）。 */
    FAILED
}
