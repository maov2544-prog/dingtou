package com.aolandai.dingtou.dto.kol;

/**
 * 「高消耗达人 7 天未更新素材总表」里的一行（只取定位计划要用的字段）。
 *
 * @param rowNumber      Excel 行号（1-based，含表头）—— 写回时定位
 * @param advertiserName 投放千川账户（<b>名称</b>，如 {@code 官方【母婴商品】}）
 * @param adId           计划 ID（C 列）
 */
public record DeliveryStatusRow(int rowNumber, String advertiserName, String adId) {

    public DeliveryStatusRow {
        advertiserName = advertiserName == null ? "" : advertiserName.trim();
        adId = adId == null ? "" : adId.trim();
    }

    /** 该行是否可处理（账户名和计划 ID 都得有）。 */
    public boolean isComplete() {
        return !advertiserName.isEmpty() && !adId.isEmpty();
    }
}
