package com.aolandai.dingtou.dto.qianchuan;

import java.util.List;

/**
 * 一个千川计划在<b>本工程内部</b>的表示。
 *
 * <p>刻意不复用 SDK 的 {@code QianchuanUniPromotionListV10ResponseDataAdListInner}：
 * SDK 类型一旦升级就会波及业务代码，而且它字段很多、序列化出来对影刀没意义。
 * 这里只留业务真正要用的几个字段。</p>
 *
 * @param advertiserId 所属投放账户
 * @param adId         千川计划 ID（<b>台账与加素材都靠它</b>）
 * @param name         计划名
 * @param status       计划状态
 * @param productIds   该计划挂载的商品 ID
 */
public record PlanInfo(
        long advertiserId,
        String adId,
        String name,
        String status,
        List<String> productIds) {

    public PlanInfo {
        productIds = productIds == null ? List.of() : List.copyOf(productIds);
    }

    /**
     * 计划是否<b>正在投放中</b>（{@code DELIVERY_OK}）。
     *
     * <p>⚠️ 必须<b>精确匹配</b>，不能 {@code contains("DELIVERY")} ——
     * 计划状态枚举里有 {@code DAILY_DELIVERY_SECONDS_DONE}（当日投放时长跑完），
     * 用 contains 会把它误判成「投放中」（踩过）。</p>
     */
    public boolean isDelivering() {
        return STATUS_DELIVERY_OK.equals(status);
    }

    /**
     * 该计划是否<b>要排除</b>（当成「没有计划」）—— 老板 2026-10-08 的口径。
     *
     * <ul>
     *   <li>{@code DELETE} —— 已删除
     *       ⚠️ <b>接口的实际枚举值是 {@code DELETE}，不是老板口述的 {@code DELETED}</b>
     *       （已用 {@code javap} 核对 SDK 1.1.96）；</li>
     *   <li>{@code OFFLINE_AUDIT} —— 审核不通过。</li>
     * </ul>
     *
     * <p>⚠️ 排除 ≠ 记异常：被排除的计划<b>当作不存在</b> → 会去建新计划。
     * 而「有计划但不是投放中」是<b>另一种</b>情况 → 记异常表、不建计划。</p>
     */
    public boolean isExcluded() {
        return STATUS_DELETE.equals(status) || STATUS_OFFLINE_AUDIT.equals(status);
    }

    /** 投放中。 */
    public static final String STATUS_DELIVERY_OK = "DELIVERY_OK";

    /** 已删除（⚠️ 接口实际值是 DELETE，不是 DELETED）。 */
    public static final String STATUS_DELETE = "DELETE";

    /** 审核不通过。 */
    public static final String STATUS_OFFLINE_AUDIT = "OFFLINE_AUDIT";
}
