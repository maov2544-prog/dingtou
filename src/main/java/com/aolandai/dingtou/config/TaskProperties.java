package com.aolandai.dingtou.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 任务执行配置（前缀 {@code dingtou.task}）。
 *
 * <pre>
 * dingtou:
 *   task:
 *     async-timeout-millis: 0     # 0 = 不转异步（默认）
 *     worker-threads: 4
 * </pre>
 *
 * <h2>🔴 {@code async-timeout-millis} 默认是 0 —— 这是刻意的</h2>
 * <p>它控制「任务跑多久就转后台、返回 {@code code=4 + taskId}」。</p>
 *
 * <p><b>默认 0 = 永远同步</b>，行为与改造前<b>完全一致</b> ——
 * 因为转异步要求<b>影刀必须支持轮询</b>（收到 {@code code=4} 去调
 * {@code GET /api/v1/task/{taskId}}）。影刀还没改之前就打开，会让
 * 「本来等 30 秒能拿到结果」的调用变成「拿到一个 taskId 但不知道怎么办」。</p>
 *
 * <p>所以上线节奏是：<b>先部署服务（保持 0），影刀改完并验证通过后，再把它调成正数</b>
 * （建议 30000 = 30 秒，正好卡在影刀 HTTP 超时之前）。</p>
 *
 * <h2>⚠️ 转异步解决的是什么</h2>
 * <p>影刀同步等待 → 超时 → 重试 → <b>重复建计划（花钱）</b>。
 * 实测 {@code planBuild} 一次 34~160 秒、{@code kolPlanCostQuery} 约 245 秒，
 * 都远超影刀默认超时。转异步后影刀立刻拿到 taskId 去轮询，不会超时重试。</p>
 */
@Validated
@ConfigurationProperties(prefix = "dingtou.task")
public class TaskProperties {

    /**
     * 同步等待上限（毫秒）；<b>0 = 不转异步</b>（默认，行为与改造前一致）。
     *
     * <p>超过它就转后台，返回 {@code code=4 + taskId}。</p>
     */
    @PositiveOrZero
    private long asyncTimeoutMillis = 0L;

    /**
     * 后台执行线程数。
     *
     * <p>影刀是串行调用的，正常不会有并发；这个值只是「万一并发时别把服务拖垮」的兜底。
     * ⚠️ 别调太大 —— 每个后台任务都会真的去打千川接口，并发上去只会更容易撞 40100。</p>
     */
    @PositiveOrZero
    private int workerThreads = 4;

    /**
     * 自检：配了超时就必须有线程能跑后台任务。
     *
     * <p>⚠️ 这个组合错了会很隐蔽：任务转异步后没有线程执行，影刀会<b>一直轮询到一个永远
     * {@code RUNNING} 的任务</b>，直到 24 小时后过期 —— 而且没人会发现。</p>
     */
    @AssertTrue(message = "dingtou.task：async-timeout-millis > 0 时 worker-threads 必须 ≥ 1，"
            + "否则转异步的任务永远不会被执行")
    public boolean isWorkerThreadsValid() {
        return asyncTimeoutMillis <= 0L || workerThreads >= 1;
    }

    /** 是否开启「超时转异步」。 */
    public boolean isAsyncEnabled() {
        return asyncTimeoutMillis > 0L;
    }

    /** 启动横幅用。 */
    public String describe() {
        return isAsyncEnabled()
                ? "超时转异步=已开启（超过 " + asyncTimeoutMillis + "ms 转后台，后台线程 "
                        + workerThreads + " 个）"
                : "超时转异步=关闭（全部同步执行；影刀改好轮询后再打开）";
    }

    public long getAsyncTimeoutMillis() {
        return asyncTimeoutMillis;
    }

    public void setAsyncTimeoutMillis(long asyncTimeoutMillis) {
        this.asyncTimeoutMillis = asyncTimeoutMillis;
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public void setWorkerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
    }
}
