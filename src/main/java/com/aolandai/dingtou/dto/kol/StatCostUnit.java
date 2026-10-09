package com.aolandai.dingtou.dto.kol;

/**
 * 千川 {@code stat_cost}（整体消耗）的<b>单位换算</b>。
 *
 * <p>⚠️ 接口返回的 {@code stat_cost} 单位是 <b>千分之一分</b>，不是元、不是分：</p>
 * <pre>
 * 1 元 = 100 分 = 100 × 1000 个「千分之一分」 = 100,000
 *
 * 例：想筛「消耗 &gt; 100 元」的计划 → stat_cost &gt; 100 × 100000 = 10,000,000
 *     想筛「消耗 ≥ 2000 元」的计划 → stat_cost ≥ 2000 × 100000 = 200,000,000
 * </pre>
 *
 * <p>把换算集中在这里，是为了让「元 ↔ 原始值」只写一次 ——
 * 否则每个调用点各写一份 {@code × 100000}，迟早有人写成 {@code × 1000}（分）或 {@code × 100}（元→分），
 * 而这类错误不会报错，只会让筛选结果<b>悄悄少一批或多一批</b>。</p>
 */
public final class StatCostUnit {

    /** 1 元对应的 {@code stat_cost} 原始值。 */
    public static final long PER_YUAN = 100_000L;

    private StatCostUnit() {
    }

    /** 元 → {@code stat_cost} 原始值（四舍五入到整数）。 */
    public static long yuanToRaw(double yuan) {
        return Math.round(yuan * PER_YUAN);
    }

    /** {@code stat_cost} 原始值 → 元。 */
    public static double rawToYuan(double raw) {
        return raw / PER_YUAN;
    }
}
