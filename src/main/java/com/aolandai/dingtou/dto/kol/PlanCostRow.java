package com.aolandai.dingtou.dto.kol;

/**
 * 一条「消耗达标」的千川计划 —— 这是对外返回的<b>业务行</b>。
 *
 * <p>基础字段（需求点名）：{@code advertiserId / advertiserName / adId / adName / statCost}；
 * 计划属性 {@code smartBidType}；后两个是<b>可选的达人信息</b>，
 * 仅在 {@code withAweme=true} 时才有值。</p>
 *
 * <p>用途：把列表交给影刀后，可据 {@code awemeShowId} 去企微 @达人。</p>
 *
 * @param advertiserId   投放账户 ID
 * @param advertiserName 投放账户名称（<b>来自《达人巡查配置表》</b>，不是接口返回的）
 * @param adId           千川计划 ID
 * @param adName         计划名（接口返回，如 {@code 伊伊麻麻\t护臀膏-10.1}）
 * @param statCost       整体消耗，<b>单位：元</b>（接口原始值是「千分之一分」，
 *                       已按 {@link StatCostUnit} 换算成人看的单位）
 * @param smartBidType   ⚠️ <b>本项目的一条业务约定，不是接口原始语义</b>：
 *                       <b>乘方</b>计划固定输出 <b>{@code "乘方"}</b>；
 *                       <b>全域</b>计划输出接口原值（{@code SMART_BID_CUSTOM} /
 *                       {@code SMART_BID_CONSERVATIVE}）。
 *                       详见 {@code DefaultKolPlanCostService#smartBidTypeOf}；
 *                       拿不到时为 {@code null}
 * @param awemeShowId    抖音号（如 {@code 610129yiyi}）；<b>{@code null} = 未查询或未取到</b>
 * @param awemeName      抖音名（如 {@code 伊伊麻麻}）；<b>{@code null} = 未查询或未取到</b>
 */
public record PlanCostRow(
        long advertiserId,
        String advertiserName,
        long adId,
        String adName,
        double statCost,
        String smartBidType,
        String awemeShowId,
        String awemeName) {

    /**
     * 不带达人信息、也不带出价方式的便捷构造。
     *
     * <p>⚠️ 达人字段用 {@code null} 而不是空串：项目的 Jackson 配了
     * {@code default-property-inclusion: non_absent}，{@code null} 会被<b>整个字段省略</b> ——
     * 这样「没查达人」的行不会输出一堆空的 {@code "awemeShowId": ""}。</p>
     */
    public PlanCostRow(long advertiserId, String advertiserName, long adId,
                       String adName, double statCost) {
        this(advertiserId, advertiserName, adId, adName, statCost, null, null, null);
    }

    /** 是否带上了达人信息。 */
    public boolean hasAweme() {
        return awemeShowId != null && !awemeShowId.isBlank();
    }
}
