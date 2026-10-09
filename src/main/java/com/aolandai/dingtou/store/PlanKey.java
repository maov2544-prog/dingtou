package com.aolandai.dingtou.store;

/**
 * 计划台账的主键：<b>抖音号 + 商品名</b>。
 *
 * <p>为什么用商品名而不是商品 ID：定投素材表里<b>只有「产品」名称、没有商品 ID 列</b>，
 * 而台账是拿素材表的行去查的。商品 ID 作为附属信息一起存下来，供建计划时使用。</p>
 */
public record PlanKey(String awemeShowId, String productName) {

    public PlanKey {
        if (awemeShowId == null || awemeShowId.isBlank()) {
            throw new IllegalArgumentException("awemeShowId（抖音号）不能为空");
        }
        if (productName == null || productName.isBlank()) {
            throw new IllegalArgumentException("productName（商品名）不能为空");
        }
        awemeShowId = awemeShowId.trim();
        productName = productName.trim();
    }
}
