package com.aolandai.dingtou.service.decision;

import com.aolandai.dingtou.service.normalize.MdDate;

import java.time.LocalDate;

/**
 * 千川计划命名规则。
 *
 * <p>格式：{@code 抖音号-商品-新建计划日期MD}（老板 2026-09-30 09:18 步骤 3 给出）。</p>
 *
 * <p>示例：{@code 602085838-益生元面霜-9.30}</p>
 *
 * <p>⚠️ 日期部分用 {@link MdDate#format(LocalDate)} 生成，<b>无前导零</b>
 * （{@code 9.2} 而不是 {@code 09.02}）。</p>
 *
 * <p>⚠️ 改名场景（C1）<b>必须整名重建</b>，不要靠字符串替换改日期段 ——
 * 商品名里本身就可能含 {@code -}，按分隔符切会切错位。</p>
 */
public final class PlanNamer {

    /** 命名分隔符。⚠️ 商品名里也可能含它，所以解析计划名永远不可靠，只能整名重建。 */
    public static final String SEPARATOR = "-";

    private PlanNamer() {
    }

    /**
     * 生成千川计划名。
     *
     * @param awemeShowId 抖音号
     * @param productName 商品名
     * @param date        新建（或改名时的最新）日期
     */
    public static String build(String awemeShowId, String productName, LocalDate date) {
        if (awemeShowId == null || awemeShowId.isBlank()) {
            throw new IllegalArgumentException("awemeShowId（抖音号）不能为空");
        }
        if (productName == null || productName.isBlank()) {
            throw new IllegalArgumentException("productName（商品名）不能为空");
        }
        if (date == null) {
            throw new IllegalArgumentException("date 不能为空");
        }
        return awemeShowId + SEPARATOR + productName + SEPARATOR + MdDate.format(date);
    }
}
