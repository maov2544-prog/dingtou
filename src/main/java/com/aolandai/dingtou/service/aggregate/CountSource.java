package com.aolandai.dingtou.service.aggregate;

/**
 * 累计笔数 N 的取数来源。
 *
 * <p>⚠️ 两者<b>可信度不同</b>，调用方要能区分 —— 决定投放档位时应当优先采信权威来源。</p>
 */
public enum CountSource {

    /**
     * 随心推订单快照（<b>权威</b>）。
     *
     * <p>由每日定时任务调 {@code QianchuanAwemeUniPromotionOrderGetV10Api} 拉取订单、
     * 本地按【抖音号 + 商品 ID】聚合后落盘。</p>
     */
    ORDER_SNAPSHOT("随心推订单快照", true),

    /**
     * 定投素材表预筛（<b>近似</b>）。
     *
     * <p>数该组合在素材表里的行数。便宜，但依赖人工维护的表；且无法区分「投过几次」
     * 和「登记了几行」。</p>
     */
    DELIVERY_SHEET("定投素材表预筛", false),

    /** 两个来源都没数据。 */
    NONE("无数据", false);

    private final String label;
    private final boolean authoritative;

    CountSource(String label, boolean authoritative) {
        this.label = label;
        this.authoritative = authoritative;
    }

    public String label() {
        return label;
    }

    /** 是否权威来源。 */
    public boolean authoritative() {
        return authoritative;
    }
}
