package com.aolandai.dingtou.dto.kol;

import java.time.LocalDate;
import java.util.List;

/**
 * 「交付状态」检查的结果。
 *
 * <h2>判定口径（老板 2026-10-06 确认）</h2>
 *
 * <p>⚠️ <b>处理的不是整个 sheet，而是 J 列「周期时间」== 上一个周日 的那些行</b> ——
 * 一个 sheet 是好几周合并的（如 {@code 2026-09-13到2026-10-04} 里 J 列有
 * {@code 09-13} / {@code 09-20} / {@code 10-04} 三个值，分别 50 / 56 / 57 行）。</p>
 *
 * <p>⚠️ <b>解析不出日期的行【留空】</b>（老板：属于异常情况不用管）——
 * 不写「未交付」，免得污染人工判断，也便于看出「这行没检查成功」。</p>
 * <p>从【乘方计划详情】接口拿计划名（如 {@code 朗朗呀-儿童彩妆套装-10.4更新}），
 * 提取里面的 {@code M.D} 日期，若落在 <b>[上一周期结束日, 本周期开始日]</b>
 * （闭区间）内 → 写「已交付」；否则写「未交付」。</p>
 *
 * <p>例：上一周期 = {@code 2026-09-13到2026-10-04}，本周期 = {@code 2026-10-11到2026-11-01}
 * → 判定窗口 = {@code [2026-10-04, 2026-10-11]}。</p>
 *
 * @param sheetName    实际处理的 sheet
 * @param windowStart  判定窗口开始（= 上一周期结束日）
 * @param windowEnd    判定窗口结束（= 本周期开始日）
 * @param cycleTime    J 列「周期时间」过滤值（= 上一个周日）
 * @param totalRows    该周期（J 列 = 上一个周日）里有计划 ID 的行数
 * @param delivered    判定为「已交付」的行数
 * @param notDelivered 判定为「未交付」的行数
 * @param unresolved   查不到计划名 / 解析不出日期的行数（这些<b>留空不写</b>，单独统计）
 * @param written      实际写入 Excel 的行数
 * @param dryRun       {@code true} = 演练，没真写 Excel
 * @param warnings     过程中的提示
 */
public record DeliveryStatusResult(
        String sheetName,
        LocalDate windowStart,
        LocalDate windowEnd,
        LocalDate cycleTime,
        int totalRows,
        int delivered,
        int notDelivered,
        int unresolved,
        int written,
        boolean dryRun,
        List<String> warnings) {

    public DeliveryStatusResult {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
