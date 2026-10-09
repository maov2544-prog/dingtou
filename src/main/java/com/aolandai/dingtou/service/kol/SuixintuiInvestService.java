package com.aolandai.dingtou.service.kol;

import com.aolandai.dingtou.dto.kol.SuixintuiInvestResult;

/**
 * A 场景：给【达人 + 商品】建一个<b>随心推</b>，并告诉影刀该往素材表写什么。
 *
 * <p>⚠️ <b>这是花钱的操作</b>（建随心推），受 {@code dingtou.dry-run} 保护：
 * 演练模式下只组装请求体、<b>不真建</b>。</p>
 */
public interface SuixintuiInvestService {

    /**
     * 执行 A 场景。
     *
     * <p>⚠️ 返回结果里的 {@code warnings} 必须看：非空说明<b>没有全部办成</b>，
     * 不能当成「都投了」。</p>
     *
     * @param awemeShowId 抖音号（如 {@code 610129yiyi}）
     * @param productName 商品名（如 {@code 护臀膏}）
     * @throws Exception 数据定位不到 / 跨多账户等需要人工介入的情况
     */
    SuixintuiInvestResult invest(String awemeShowId, String productName) throws Exception;
}
