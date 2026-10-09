package com.aolandai.dingtou.dto.kol;

import java.util.List;

/**
 * 「消耗达标计划」查询结果 —— <b>行 + 可诊断信息</b>。
 *
 * <p>为什么不只返回一个 {@code List<PlanCostRow>}：与 {@code PlanQueryOutcome} 同样的理由 ——
 * 「查不到」和「查失败」都表现为「结果为空」，但处理方式完全相反。所以把
 * {@code accountsScanned} / {@code plansScanned} / {@code warnings} 一并带出来：</p>
 * <ul>
 *   <li>{@code plansScanned == 0} → 这段时间该账户确实没有「投放中 + 有消耗」的计划；</li>
 *   <li>{@code warnings} 非空 → <b>有账户没查成功</b>，结果不完整，不能当成「没有达标计划」。</li>
 * </ul>
 *
 * @param windowStart     统计窗口开始（{@code yyyy-MM-dd HH:mm:ss}）
 * @param windowEnd       统计窗口结束（{@code yyyy-MM-dd HH:mm:ss}）
 * @param accountsScanned 实际查询的账户数
 * @param plansScanned    接口返回的计划总数（<b>过滤前</b>，跨账户跨场景去重后）
 * @param rows            消耗达标的计划（已按消耗降序）
 * @param delivery      交付状态检查结果；{@code null} = 本次没做（没传 {@code checkDelivery}）
 * @param warnings        过程中的异常情况（如某账户查询失败），供排障
 */
public record PlanCostQueryResult(
        String windowStart,
        String windowEnd,
        int accountsScanned,
        int plansScanned,
        List<PlanCostRow> rows,
        List<String> warnings,
        DeliveryStatusResult delivery) {

    public PlanCostQueryResult {
        windowStart = windowStart == null ? "" : windowStart;
        windowEnd = windowEnd == null ? "" : windowEnd;
        rows = rows == null ? List.of() : List.copyOf(rows);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** 不带交付状态检查的构造（老调用方 / 单测用）。 */
    public PlanCostQueryResult(String windowStart, String windowEnd, int accountsScanned,
                               int plansScanned, List<PlanCostRow> rows,
                               List<String> warnings) {
        this(windowStart, windowEnd, accountsScanned, plansScanned, rows, warnings, null);
    }

    /** 附上交付状态检查结果。 */
    public PlanCostQueryResult withDelivery(DeliveryStatusResult newDelivery) {
        return new PlanCostQueryResult(windowStart, windowEnd, accountsScanned, plansScanned,
                rows, warnings, newDelivery);
    }

    /** 达标行数。 */
    public int size() {
        return rows.size();
    }
}
