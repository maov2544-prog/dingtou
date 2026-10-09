package com.aolandai.dingtou.dto.sheet;

/**
 * 商品 ID 映射表（`商品 ID 映射表(1).xlsx`）的一行。
 *
 * <p>实测表头：A {@code 商品id} / B {@code 投放商品} / C {@code 店铺}，共 103 行。</p>
 *
 * <p>⚠️ 这张表有两个已实测的坑，用的时候必须当心：</p>
 * <ul>
 *   <li><b>存在空商品 ID</b>：{@code 猫爪润唇膏（专卖店）} 的 A 列为空 —— 该商品在映射表里根本没有 ID；</li>
 *   <li><b>商品名会重名</b>：{@code 青少年身体乳}、{@code 猫爪润唇膏} 各出现两次（分属不同店铺/不同 ID）
 *       → <b>绝对不能拿商品名当主键</b>；</li>
 *   <li><b>「（次链）」后缀</b>：映射表用 {@code （次链）} 区分同名不同 ID 的 SKU，
 *       而定投素材表<b>从不带这个后缀</b> → 直接按名称映射会把次链商品错配成主链 ID。</li>
 * </ul>
 *
 * @param productId   商品 ID（19 位数字，字符串保存）
 * @param productName 投放商品名
 * @param shop        店铺
 * @param rowNumber   Excel 行号（含表头，1-based）
 */
public record ProductRow(String productId, String productName, String shop, int rowNumber) {

    public boolean hasProductId() {
        return productId != null && !productId.isBlank();
    }

    /** 是否「次链」商品（名称里带 {@code （次链）} 或半角变体 {@code （次链)}）。 */
    public boolean isSecondaryChain() {
        return productName != null && productName.contains("次链");
    }

    /** 去掉「（次链）」后缀后的商品名，用于和定投素材表的「产品」列比对。 */
    public String normalizedName() {
        if (productName == null) {
            return "";
        }
        return productName.replace("（次链）", "").replace("（次链)", "").trim();
    }
}
