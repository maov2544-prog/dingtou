package com.aolandai.dingtou.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 定时任务配置（前缀 {@code dingtou.scheduler}）。
 *
 * <p>老板 2026-10-08：「这个项目我想要做成<b>轮询</b>的，<b>不用 post 来调度</b>」。</p>
 *
 * <pre>
 * dingtou:
 *   scheduler:
 *     pending-enabled: false          # ⚠️ 默认关！上线前先确认影刀已停
 *     pending-cron: "0 *&#47;10 * * * *"   # 每 10 分钟一轮
 * </pre>
 */
@Validated
@ConfigurationProperties(prefix = "dingtou.scheduler")
public class SchedulerProperties {

    /**
     * 是否启用「定时扫素材表 → 跑投放 → 直写回」。
     *
     * <p>⚠️ <b>默认 false</b> —— 因为：
     * <ul>
     *   <li>它<b>会花钱</b>（真建计划/建单）；</li>
     *   <li>它<b>会和影刀抢写素材表</b>（必须先确认影刀已停，且
     *       {@code dingtou.source.direct-write=true}）；</li>
     *   <li>上线前应该先 dry-run 观察几轮。</li>
     * </ul>
     */
    private boolean pendingEnabled = false;

    /**
     * 定时投放的 cron（Spring 6 段式：秒 分 时 日 月 周）。
     *
     * <p>默认<b>每 30 分钟</b>一轮。</p>
     *
     * <p>⚠️⚠️ <b>别调太密</b>：实测<b>单组合 ≈ 31 秒</b>（主要是查千川计划 + 频控退避）
     * → 50 个组合一轮要 <b>≈26 分钟</b>。设成 10 分钟会一直重叠空转
     * （虽有防重入兜底，但实际周期变成 26+10=36 分钟，白白多算一轮）。</p>
     */
    private String pendingCron = "0 */30 * * * *";

    public boolean isPendingEnabled() {
        return pendingEnabled;
    }

    public void setPendingEnabled(boolean pendingEnabled) {
        this.pendingEnabled = pendingEnabled;
    }

    public String getPendingCron() {
        return pendingCron;
    }

    public void setPendingCron(String pendingCron) {
        this.pendingCron = pendingCron;
    }

    /** 启动横幅用。 */
    public String describe() {
        return "定时投放=" + (pendingEnabled ? ("开（cron " + pendingCron + "）") : "关");
    }
}
