package com.aolandai.dingtou.dto.sheet;

/**
 * 定投素材表（`26年视频交付表.xlsx`）的一行。
 *
 * <p>字段顺序与表头 A~N 一一对应。全部是<b>原始字符串</b>，
 * 不做归一化、不做类型转换 —— 归一化是 {@code service/normalize/} 的职责，
 * 这一层只负责「把 Excel 的行原样搬成对象」。</p>
 *
 * @param deliveryDate   A 日期（M.D 文本，如 {@code 9.2}）
 * @param business       B 对接商务
 * @param awemeName      C 抖音名
 * @param awemeShowId    D 抖音号（<b>达人主键</b>）
 * @param globalAccount  E 全域账号（含脏值，需清洗）
 * @param publishDate    F 视频发布日期（M.D 文本）
 * @param productName    G 产品（<b>商品维度</b>；表里没有商品 ID）
 * @param videoId        H 视频id（抖音短视频 ID，≠ 千川素材 ID）
 * @param videoLink      I 视频链接（分享口令/短链）
 * @param targeting      J 定向情况
 * @param deliveryType   K 投放类型。<b>空 = 尚未投放；非空 = 老数据（已投放，不用管）</b>
 * @param invalidReason  L 视频无效
 * @param deliveryGroup  M 投放群
 * @param deliveryStatus N 投放状态（原「投放备注」）
 * @param rowNumber      Excel 行号（含表头，1-based），用于报错时精确定位
 */
public record DeliveryRow(
        String deliveryDate,
        String business,
        String awemeName,
        String awemeShowId,
        String globalAccount,
        String publishDate,
        String productName,
        String videoId,
        String videoLink,
        String targeting,
        String deliveryType,
        String invalidReason,
        String deliveryGroup,
        String deliveryStatus,
        int rowNumber) {

    /** 是否「老数据」：投放类型非空 ⇒ 已经投过，本轮不处理。 */
    public boolean alreadyInvested() {
        return deliveryType != null && !deliveryType.isBlank();
    }

    /**
     * 是否需要进入投放环节。
     *
     * <p>两个条件同时满足：① 投放类型为空（尚未投过）；② 投放状态 = 待投放。</p>
     */
    public boolean awaitingDelivery() {
        return !alreadyInvested() && DeliveryStatus.isPendingDelivery(deliveryStatus);
    }

    /** 达人维度是否可用（抖音号非空）。 */
    public boolean hasAweme() {
        return awemeShowId != null && !awemeShowId.isBlank();
    }

    /** 商品维度是否可用（产品名非空）。 */
    public boolean hasProduct() {
        return productName != null && !productName.isBlank();
    }

    /** 能否参与【达人 + 商品】聚合。 */
    public boolean aggregatable() {
        return hasAweme() && hasProduct();
    }
}
