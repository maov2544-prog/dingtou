package com.aolandai.dingtou.store;

/**
 * 随心推订单快照的复合主键：<b>抖音号 + 商品 ID</b>。
 *
 * <p>用 record 当 Map 的 key，而不是拼一个 {@code "a|b"} 字符串 ——
 * 抖音号是人工维护的，随时可能出现分隔符，拼接主键迟早会撞。</p>
 */
public record OrderKey(String awemeShowId, String productId) {

    public OrderKey {
        if (awemeShowId == null || awemeShowId.isBlank()) {
            throw new IllegalArgumentException("awemeShowId（抖音号）不能为空");
        }
        if (productId == null || productId.isBlank()) {
            throw new IllegalArgumentException("productId（商品 ID）不能为空");
        }
        awemeShowId = awemeShowId.trim();
        productId = productId.trim();
    }
}
