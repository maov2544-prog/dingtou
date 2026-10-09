package com.aolandai.dingtou.dto;

import com.aolandai.dingtou.service.aggregate.CountSource;

/**
 * 【达人 + 商品】的累计随心推笔数统计结果。
 *
 * <p>⚠️ 之所以把 {@code source} 和 {@code detail} 一并返回，而不是只回一个数字：
 * <b>决策层需要知道这个 N 有多可信</b>。来自订单快照的 N 可以直接用来定档；
 * 来自素材表预筛的 N 只是近似，日志里要能看出差别，出问题时才能追。</p>
 *
 * @param awemeShowId 抖音号
 * @param productName 商品名（定投素材表里的「产品」）
 * @param productId   商品 ID；映射不到时为空字符串（素材表里本来就没有商品 ID 列）
 * @param count       累计笔数 N
 * @param source      取数来源
 * @param blocked           该组合<b>是否有订单仍存续（占用互斥名额）</b> —— 这类组合
 *                          <b>建不了新订单/新计划</b>，要跳过等它们结束
 * @param blockingStatuses  占用名额的具体状态名（如 {@code FROZEN/DELIVERY_OK}），仅用于排查
 * @param detail      人类可读的取数说明，直接进日志 / 决策 reason
 */
public record CountResult(
        String awemeShowId,
        String productName,
        String productId,
        int count,
        CountSource source,
        boolean blocked,
        String blockingStatuses,
        String detail) {

    /** 老构造（不带占用标记）—— 供单测/内部桩用。 */
    public CountResult(String awemeShowId, String productName, String productId, int count,
                       CountSource source, String detail) {
        this(awemeShowId, productName, productId, count, source, false, "", detail);
    }

    public CountResult {
        productId = productId == null ? "" : productId;
        blockingStatuses = blockingStatuses == null ? "" : blockingStatuses;
        detail = detail == null ? "" : detail;
    }

    /**
     * 该组合<b>有订单正在投放中</b>（{@code DELIVERY_OK}）。
     *
     * <p>⚠️ 老板 2026-10-08：建<b>千川乘方计划</b>不受「互斥状态」限制，
     * 判「是否投放中」只是<b>想让这个随心推跑完再建</b>。</p>
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean delivering() {
        return blockingStatuses.contains("DELIVERY_OK");
    }

    /** 这个 N 是不是权威来源（可直接用于定档）。 */
    public boolean authoritative() {
        return source.authoritative();
    }

    /** 是否达到了转千川的阈值。 */
    public boolean reachedThreshold(int threshold) {
        return count >= threshold;
    }
}
