package com.aolandai.dingtou.dto.kol;

/**
 * 《达人巡查配置表》的一行：<b>投放账户 + 消耗金额阈值</b>。
 *
 * <p>实测表头（逐字，1-based 列）：</p>
 * <pre>
 * A 账户名称 | B 账户 ID | C 消耗金额阈值（元） | D 周期完成状态
 * </pre>
 *
 * <p>⚠️ <b>账户 ID 是文本型</b>（16 位数字，按数值读末位会被改写），所以这里按字符串保存。</p>
 *
 * @param accountName   投放账户名称，如 {@code 官方【母婴商品】}（后续原样回传给影刀做展示）
 * @param advertiserId  投放账户 ID（<b>16 位数字，文本</b>）
 * @param thresholdYuan 消耗金额阈值，<b>单位：元</b>
 * @param rowNumber     Excel 行号（含表头，1-based），排障时定位用
 */
public record KolAccountConfig(
        String accountName,
        String advertiserId,
        double thresholdYuan,
        int rowNumber) {

    public KolAccountConfig {
        accountName = accountName == null ? "" : accountName.trim();
        advertiserId = advertiserId == null ? "" : advertiserId.trim();
    }

    public boolean hasAdvertiserId() {
        return !advertiserId.isEmpty();
    }

    /**
     * 账户 ID 转 {@code long}，供千川 SDK 使用。
     *
     * <p>16 位数字在 {@code long} 范围内（且 &lt; 2^53），转换无损；不合法时返回 {@code null}。</p>
     */
    public Long advertiserIdAsLong() {
        if (!hasAdvertiserId()) {
            return null;
        }
        try {
            return Long.parseLong(advertiserId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 阈值换算成 {@code stat_cost} 的原始单位（千分之一分），用于和接口返回值直接比较。 */
    public long thresholdRaw() {
        return StatCostUnit.yuanToRaw(thresholdYuan);
    }
}
