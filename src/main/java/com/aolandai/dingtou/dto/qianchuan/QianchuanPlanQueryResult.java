package com.aolandai.dingtou.dto.qianchuan;

import com.bytedance.ads.model.QianchuanUniPromotionListV10ResponseDataAdListInner;

import java.util.Collections;
import java.util.List;

/**
 * 千川达人 + 商品计划查询结果。
 *
 * <p><b>为什么要带 {@code rawPlanCount} 和 {@code unmatchedPlans}：</b>
 * 只返回「按商品过滤后」的 {@code plans}，会分不清两种截然不同的情况 ——</p>
 * <ul>
 *   <li>接口<b>压根没返回</b>任何计划（{@code rawPlanCount == 0}）→ 可能参数/场景不对；</li>
 *   <li>接口<b>返回了但商品 ID 没匹配上</b>（{@code rawPlanCount > 0} 但 {@code plans} 为空）
 *       → 商品映射或商品挂载有问题。</li>
 * </ul>
 * <p>这两种情况都表现为「0 个计划」，但排查方向完全相反。把原始数量暴露出来，
 * 下次再出假阴性就不用靠猜。</p>
 */
public final class QianchuanPlanQueryResult {

    public enum MatchType {
        NONE,
        SINGLE,
        MULTIPLE
    }

    private final String awemeId;
    private final String productId;
    private final List<QianchuanUniPromotionListV10ResponseDataAdListInner> plans;
    private final List<QianchuanUniPromotionListV10ResponseDataAdListInner> unmatchedPlans;
    private final int rawPlanCount;
    private final int pageCount;

    /**
     * @param awemeId        抖音号
     * @param productId      目标商品 ID
     * @param plans          商品 ID <b>匹配上</b>的计划
     * @param unmatchedPlans 接口返回了、但商品 ID <b>没匹配上</b>的计划
     * @param rawPlanCount   接口<b>总共</b>返回的计划数（= plans + unmatchedPlans 的去重计数）
     * @param pageCount      实际翻页次数
     */
    public QianchuanPlanQueryResult(
            String awemeId,
            String productId,
            List<QianchuanUniPromotionListV10ResponseDataAdListInner> plans,
            List<QianchuanUniPromotionListV10ResponseDataAdListInner> unmatchedPlans,
            int rawPlanCount,
            int pageCount) {
        this.awemeId = awemeId;
        this.productId = productId;
        this.plans = plans == null ? Collections.emptyList() : List.copyOf(plans);
        this.unmatchedPlans = unmatchedPlans == null
                ? Collections.emptyList() : List.copyOf(unmatchedPlans);
        this.rawPlanCount = rawPlanCount;
        this.pageCount = pageCount;
    }

    public String getAwemeId() {
        return awemeId;
    }

    public String getProductId() {
        return productId;
    }

    /** 商品 ID 匹配上的计划。 */
    public List<QianchuanUniPromotionListV10ResponseDataAdListInner> getPlans() {
        return plans;
    }

    /** 接口返回了、但商品 ID 没匹配上的计划。 */
    public List<QianchuanUniPromotionListV10ResponseDataAdListInner> getUnmatchedPlans() {
        return unmatchedPlans;
    }

    /** 接口总共返回的计划数（未按商品过滤）。 */
    public int getRawPlanCount() {
        return rawPlanCount;
    }

    public int size() {
        return plans.size();
    }

    public int getPageCount() {
        return pageCount;
    }

    /**
     * 是否「接口返回了计划，但全都因商品 ID 不匹配被滤掉」。
     *
     * <p>这是假阴性的典型信号：接口有返回，说明场景/参数是对的，
     * 问题出在商品维度（映射错、计划没挂该商品等）。</p>
     */
    public boolean productMismatchOnly() {
        return plans.isEmpty() && rawPlanCount > 0;
    }

    public MatchType getMatchType() {
        if (plans.isEmpty()) {
            return MatchType.NONE;
        }
        return plans.size() == 1 ? MatchType.SINGLE : MatchType.MULTIPLE;
    }

    public QianchuanUniPromotionListV10ResponseDataAdListInner getSingle() {
        if (getMatchType() != MatchType.SINGLE) {
            throw new IllegalStateException("当前结果不是唯一计划：" + getMatchType());
        }
        return plans.get(0);
    }
}
