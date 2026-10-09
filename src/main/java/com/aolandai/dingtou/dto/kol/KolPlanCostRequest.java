package com.aolandai.dingtou.dto.kol;

import java.time.LocalDate;

/**
 * 「查消耗达标计划」的查询参数。
 *
 * <p>用一个参数对象而不是「3 个重载 + 裸 boolean」，是为了让调用点一眼看懂：
 * {@code query(new KolPlanCostRequest(start, end, true))} 比 {@code query(start, end, true)}
 * 清楚得多（后者那个 {@code true} 是什么，得去翻方法签名）。</p>
 *
 * @param startDate 统计窗口首日；{@code null} = 用配置的默认窗口（最近 N 个月）
 * @param endDate   统计窗口末日；{@code null} = 用配置的默认窗口
 * @param withAweme 是否附带达人信息（抖音号 / 抖音名）。<b>true 会额外产生
 *                  「每个达标计划一次详情请求」</b>，所以默认关闭。
 */
public record KolPlanCostRequest(LocalDate startDate, LocalDate endDate, boolean withAweme) {

    /** 全部用默认值：默认时间窗 + 不附带达人信息。 */
    public static KolPlanCostRequest defaults() {
        return new KolPlanCostRequest(null, null, false);
    }

    /** 显式时间窗 + 不附带达人信息。 */
    public static KolPlanCostRequest of(LocalDate startDate, LocalDate endDate) {
        return new KolPlanCostRequest(startDate, endDate, false);
    }

    /** 两个日期要么都给、要么都不给 —— 只给一半会造成「窗口边界靠猜」。 */
    public boolean hasExplicitWindow() {
        return startDate != null && endDate != null;
    }

    /** 是否只给了一半日期（非法）。 */
    public boolean hasPartialWindow() {
        return (startDate == null) != (endDate == null);
    }
}
