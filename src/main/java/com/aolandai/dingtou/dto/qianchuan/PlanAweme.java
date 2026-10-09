package com.aolandai.dingtou.dto.qianchuan;

/**
 * 一个千川计划对应的<b>达人（抖音）信息</b>。
 *
 * <p>数据来自【乘方计划详情】接口 {@code /qianchuan/uni_promotion/ad/detail/}
 * 的 {@code data.aweme_infos[]}：</p>
 * <pre>
 * aweme_name    → 抖音名（如 伊伊麻麻）
 * unique_id     → 抖音号（如 610129yiyi）   ⚠️ 抖音号在 unique_id，不叫 aweme_show_id
 * aweme_uid     → 抖音 UID（如 2071074583877891）
 * </pre>
 *
 * <p>⚠️ <b>为什么要带 {@code adId}</b>：不带它就无法把结果对回原计划 ——
 * 批量查询时尤其致命（N 条结果不知道哪条对应哪个计划）。</p>
 *
 * <p>⚠️ {@code unique_id} 有时候是<b>空字符串</b>（实测 12.6%）。这时靠 {@code awemeUid}
 * 去【抖音号授权列表】反查抖音号兜底（覆盖率实测 92%）。</p>
 *
 * <p>⚠️ 虽然类名叫「达人信息」，但它其实是<b>计划详情接口的一次投影</b> ——
 * 计划名（{@code name}）也一并带出来，因为「交付状态」检查要靠它判断日期。</p>
 *
 * @param adId        千川计划 ID（关联键）
 * @param planName    计划名称，如 {@code 朗朗呀-儿童彩妆套装-10.4更新}；可能为空
 * @param awemeShowId 抖音号，如 {@code 610129yiyi}；可能为空（见上）
 * @param awemeName   抖音名，如 {@code 伊伊麻麻}
 * @param awemeUid    抖音数值 UID，如 {@code 65628672972}；
 *                    <b>{@code awemeShowId} 为空时用它去反查</b>；{@code null} = 接口没给
 */
public record PlanAweme(long adId, String planName, String awemeShowId, String awemeName,
                        Long awemeUid) {

    public PlanAweme {
        planName = planName == null ? "" : planName.trim();
        awemeShowId = awemeShowId == null ? "" : awemeShowId.trim();
        awemeName = awemeName == null ? "" : awemeName.trim();
    }

    /** 便捷构造：不带计划名 / UID（老调用方、单测用）。 */
    public PlanAweme(long adId, String awemeShowId, String awemeName) {
        this(adId, "", awemeShowId, awemeName, null);
    }

    /** 补上抖音号（兜底反查成功后调用）。 */
    public PlanAweme withAwemeShowId(String newShowId) {
        return new PlanAweme(adId, planName, newShowId, awemeName, awemeUid);
    }

    /** 能否用 UID 反查（抖音号为空 + UID 有值）。 */
    public boolean canReverseLookup() {
        return awemeShowId.isEmpty() && awemeUid != null;
    }

    /** 抖音号是否有效（拿不到时不该当成「这个达人没有抖音号」）。 */
    public boolean hasAwemeShowId() {
        return !awemeShowId.isEmpty();
    }
}
