package com.aolandai.dingtou.config;

import com.aolandai.dingtou.service.aggregate.PrescreenScope;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 聚合层配置（前缀 {@code dingtou.aggregate}）。
 *
 * <pre>
 * dingtou:
 *   aggregate:
 *     prescreen-scope: ALL          # ALL = 数全部行 / INVESTED = 只数已投放行
 *     prefer-order-snapshot: true   # 优先用订单快照（权威）
 *     prescreen-cache-seconds: 300  # 素材表预筛结果的缓存时长
 * </pre>
 */
@Validated
@ConfigurationProperties(prefix = "dingtou.aggregate")
public class AggregateProperties {

    /**
     * 素材表预筛口径。
     *
     * <p>⚠️ 会直接改变 N → 改变 A/B/C 判定。做成配置就是为了「改口径不改代码」。</p>
     */
    @NotNull
    private PrescreenScope prescreenScope = PrescreenScope.ALL;

    /** 是否优先用订单快照。设 false 则强制走素材表预筛（排障用）。 */
    private boolean preferOrderSnapshot = true;

    /**
     * 素材表预筛结果的缓存时长（秒）。
     *
     * <p>定投素材表有 4992 行，每次都全量读一遍约 2 秒，而 `decide` 是逐条调的 ——
     * 必须缓存。缓存同时受「文件最后修改时间」约束，表一改就失效。</p>
     */
    @PositiveOrZero
    private int prescreenCacheSeconds = 300;

    /**
     * 投放流程开头是否<b>自动跑一次订单快照增量</b>
     * （老板 2026-10-07：「refreshOrders 可以自动跑」）。
     *
     * <p>⚠️ 关了它就必须让影刀显式调 {@code refreshOrders}，
     * 否则 N 取不到（快照不新鲜会直接报错）。</p>
     */
    private boolean autoRefreshOrders = true;

    /**
     * 自动刷新的<b>节流窗口</b>（分钟）：快照在这个时间内刷过就不重复拉。
     *
     * <p>⚠️ <b>必须节流</b>：{@code planBuild} 是<b>逐条</b>调的，
     * 不节流就变成「每条组合都拉一次全账户订单」—— 几十条 × 44 秒 = 半小时。
     * 而增量拉的是「最近 7 天」，10 分钟内重复拉也拿不到新东西。</p>
     */
    @PositiveOrZero
    private int autoRefreshOrdersThrottleMinutes = 10;

    @AssertTrue(message = "dingtou.aggregate.prescreen-cache-seconds 不能为负")
    public boolean isCacheSecondsValid() {
        return prescreenCacheSeconds >= 0;
    }

    /** 启动横幅用。 */
    public String describe() {
        return "预筛口径=" + prescreenScope.name() + "(" + prescreenScope.label()
                + "，⚠️已停用，仅排障用)"
                + "，优先订单快照=" + preferOrderSnapshot
                + "，预筛缓存=" + prescreenCacheSeconds + " 秒"
                + "，投放前自动刷订单=" + (autoRefreshOrders
                        ? ("开（节流 " + autoRefreshOrdersThrottleMinutes + " 分钟）") : "关");
    }

    // ==================== getter / setter ====================

    public PrescreenScope getPrescreenScope() {
        return prescreenScope;
    }

    public void setPrescreenScope(PrescreenScope prescreenScope) {
        this.prescreenScope = prescreenScope;
    }

    public boolean isPreferOrderSnapshot() {
        return preferOrderSnapshot;
    }

    public void setPreferOrderSnapshot(boolean preferOrderSnapshot) {
        this.preferOrderSnapshot = preferOrderSnapshot;
    }

    public boolean isAutoRefreshOrders() {
        return autoRefreshOrders;
    }

    public void setAutoRefreshOrders(boolean autoRefreshOrders) {
        this.autoRefreshOrders = autoRefreshOrders;
    }

    public int getAutoRefreshOrdersThrottleMinutes() {
        return autoRefreshOrdersThrottleMinutes;
    }

    public void setAutoRefreshOrdersThrottleMinutes(int autoRefreshOrdersThrottleMinutes) {
        this.autoRefreshOrdersThrottleMinutes = autoRefreshOrdersThrottleMinutes;
    }

    public int getPrescreenCacheSeconds() {
        return prescreenCacheSeconds;
    }

    public void setPrescreenCacheSeconds(int prescreenCacheSeconds) {
        this.prescreenCacheSeconds = prescreenCacheSeconds;
    }
}
