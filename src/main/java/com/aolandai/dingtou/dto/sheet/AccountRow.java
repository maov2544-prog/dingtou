package com.aolandai.dingtou.dto.sheet;

/**
 * 投放账户字典（`投放账户信息表.xlsx`）的一行。
 *
 * <p>实测表头：A {@code 投放账户名称} / B {@code 投放账户ID} / C {@code 投放账户所属店铺}，共 8 个账户。</p>
 *
 * @param name         投放账户名称，如 {@code 官方【孕产-商品】}
 * @param advertiserId 投放账户 ID（<b>16 位数字，以字符串保存</b>；Excel 数值只保证 15 位有效，
 *                     按数值存末位会被改写，所以这张表里它本来就是文本型）
 * @param shop         所属店铺：官方店 / 婴童店 / 专卖店
 * @param rowNumber    Excel 行号（含表头，1-based）
 */
public record AccountRow(String name, String advertiserId, String shop, int rowNumber) {

    public boolean hasAdvertiserId() {
        return advertiserId != null && !advertiserId.isBlank();
    }

    /**
     * 账户 ID 转 {@code long}，供千川 SDK 使用。
     *
     * <p>16 位数字在 {@code long} 范围内（且 &lt; 2^53），转换无损。</p>
     */
    public Long advertiserIdAsLong() {
        if (!hasAdvertiserId()) {
            return null;
        }
        try {
            return Long.parseLong(advertiserId.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
