package com.aolandai.dingtou.dto.sheet;

/**
 * 《定向投放登记表》的一条【达人 + 投放商品】记录。
 *
 * <p>这张表是「3 行复合表头」：</p>
 * <ul>
 *   <li>第 1 行：店铺分组（忽略）；</li>
 *   <li>第 2 行：<b>投放账户名</b>（合并单元格，压在一对列之上，如 {@code A2:B2}）；</li>
 *   <li>第 3 行：字段名（{@code 投放商品} / {@code 授权状态}）；</li>
 *   <li>第 4 行起：数据行。</li>
 * </ul>
 *
 * <p>所以「账户」不在行上、而在<b>列</b>上 —— 一行数据最多可以同时落在 8 个投放账户下
 * （实测第 4 行：同一抖音号 {@code 173701235} 的 {@code 燕窝气垫}→官方店、
 * {@code 山茶花面膜}→婴童店、{@code 猫爪润唇膏}→专卖店）。这也是为什么查询结果是<b>账户列表</b>。</p>
 *
 * @param awemeShowId 抖音号（从 R 列「达人抖音信息」格内的 {@code 抖音号：xxx} 行剥出来的；
 *                    既有纯数字也有字母数字混合，如 {@code jiayinmamaer} / {@code LSW39818}）
 * @param accountName 该商品列对应的投放账户名（如 {@code 官方【母婴商品】}）
 * @param productText 投放商品单元格原文（可能是「多商品用分隔符连接」，
 *                    如 {@code 燕窝气垫-橄榄油}、{@code 燕窝气垫，山茶花面膜}）
 * @param rowNumber   Excel 行号（含表头，1-based），排障时定位用
 */
public record TargetingRow(String awemeShowId, String accountName, String productText, int rowNumber) {

    public TargetingRow {
        awemeShowId = awemeShowId == null ? "" : awemeShowId.trim();
        accountName = accountName == null ? "" : accountName.trim();
        productText = productText == null ? "" : productText.trim();
    }

    public boolean hasAwemeShowId() {
        return !awemeShowId.isEmpty();
    }

    public boolean hasProduct() {
        return !productText.isEmpty();
    }
}
