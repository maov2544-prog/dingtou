package com.aolandai.dingtou.config;

import jakarta.validation.constraints.Positive;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 达人巡查 / 催更 相关配置（前缀 {@code dingtou.kol}）。
 *
 * <pre>
 * dingtou:
 *   kol:
 *     cost-window-months: 1     # 消耗统计窗口 = 最近 N 个月
 *     delivery-status-sheet: "\\\\192.168.10.253\\...\\高消耗达人7天未更新素材总表.xlsx"
 *     delivery-status-column: 交付状态
 *     delivered-value: 已交付
 *     not-delivered-value: 未交付
 * </pre>
 */
@Validated
@ConfigurationProperties(prefix = "dingtou.kol")
public class KolProperties {

    /**
     * 消耗统计窗口：<b>最近 N 个月</b>。
     *
     * <p>窗口的算法是「结束 = 昨天，开始 = 结束往前推 N 个月」——
     * 例如今天是 2026-10-04、N=1，则窗口是 {@code 2026-09-04 ~ 2026-10-03}。
     * <b>结束取昨天</b>而不是今天，是因为当天的数据还没跑完，统计进去会偏低。</p>
     */
    @Positive
    private int costWindowMonths = 1;

    /**
     * 《高消耗达人 7 天未更新素材总表》路径（<b>共享盘</b>）。
     *
     * <p>⚠️ <b>必须带 IP</b>（{@code \\192.168.10.253\...}）—— 机器名解析不了（踩过）。</p>
     *
     * <p>用于「交付状态」检查：读上一周期的计划 ID → 查计划名里的日期 → 写 P 列。</p>
     */
    private Path deliveryStatusSheet;

    /** 「交付状态」列的表头名（默认 {@code 交付状态}，实测在 P 列）。 */
    private String deliveryStatusColumn = "交付状态";

    /** 已交付时写什么。 */
    private String deliveredValue = "已交付";

    /** 未交付时写什么（老板确认：没命中的也要写，便于看出「检查过了」）。 */
    private String notDeliveredValue = "未交付";

    public int getCostWindowMonths() {
        return costWindowMonths;
    }

    public void setCostWindowMonths(int costWindowMonths) {
        this.costWindowMonths = costWindowMonths;
    }

    public Path getDeliveryStatusSheet() {
        return deliveryStatusSheet;
    }

    public void setDeliveryStatusSheet(Path deliveryStatusSheet) {
        this.deliveryStatusSheet = deliveryStatusSheet;
    }

    public String getDeliveryStatusColumn() {
        return deliveryStatusColumn;
    }

    public void setDeliveryStatusColumn(String deliveryStatusColumn) {
        this.deliveryStatusColumn = deliveryStatusColumn;
    }

    public String getDeliveredValue() {
        return deliveredValue;
    }

    public void setDeliveredValue(String deliveredValue) {
        this.deliveredValue = deliveredValue;
    }

    public String getNotDeliveredValue() {
        return notDeliveredValue;
    }

    public void setNotDeliveredValue(String notDeliveredValue) {
        this.notDeliveredValue = notDeliveredValue;
    }
}
