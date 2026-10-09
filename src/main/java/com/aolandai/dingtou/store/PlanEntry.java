package com.aolandai.dingtou.store;

/**
 * 计划台账的一条记录：【达人 + 商品】→ 千川计划 ID。
 *
 * <p>⚠️ <b>为什么必须记计划 ID，而不是靠解析计划名反查：</b></p>
 * <ul>
 *   <li>商品名里本身就可能含 {@code -}，按分隔符切会切错位；</li>
 *   <li>计划名会被<b>人工改</b>，改过就再也解析不出来；</li>
 *   <li>改名（C1 场景）本来就要改日期段，解析出来的值还得反推。</li>
 * </ul>
 * <p>记 ID 是唯一稳的做法。</p>
 *
 * @param awemeShowId 抖音号
 * @param productName 商品名（定投素材表里的「产品」）
 * @param productId   商品 ID（建计划要用；素材表里没有，靠接口反查得到）
 * @param advertiserId 投放账户 ID
 * @param adId        千川计划 ID —— <b>本台账的核心</b>
 * @param planName    最近一次写入时的计划名（仅便于人工核对，不参与任何判断）
 * @param createdAt   首次登记时间
 * @param updatedAt   最后一次变更时间
 */
public record PlanEntry(
        String awemeShowId,
        String productName,
        String productId,
        Long advertiserId,
        String adId,
        String planName,
        String createdAt,
        String updatedAt) {

    public PlanKey key() {
        return new PlanKey(awemeShowId, productName);
    }

    public boolean hasAdId() {
        return adId != null && !adId.isBlank();
    }

    /** 改名 / 换账户时更新，保留首次登记时间。 */
    public PlanEntry update(Long newAdvertiserId, String newPlanName, String newUpdatedAt) {
        return new PlanEntry(awemeShowId, productName, productId,
                newAdvertiserId == null ? advertiserId : newAdvertiserId,
                adId, newPlanName == null ? planName : newPlanName,
                createdAt, newUpdatedAt);
    }
}
