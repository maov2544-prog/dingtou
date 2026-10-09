package com.aolandai.dingtou.service.qianchuan;

import com.aolandai.dingtou.dto.qianchuan.PlanQueryOutcome;

/**
 * 千川计划查询服务（只读）。
 *
 * <p><b>接口与实现分离</b>：{@code service/task/handler} 只依赖这个接口，
 * 不认识 SDK、也不认识 {@code client/}。这样任务层可以脱离千川单测。</p>
 *
 * <h2>🔴 账户是【必填】的，而且只有一个（2026-10-05 起）</h2>
 * <p>老板核实后确认：<b>一个商品只能在它授权的那个店铺下建计划</b> ——
 * 所以【达人 + 商品】的计划只可能在【定投素材表 {@code E 全域账号}】那一个账户下。</p>
 *
 * <p>在此之前这里有过「遍历全部 8 个账户」「定向登记表路由」「未命中回退全量」三套机制，
 * 全部已删除 —— 它们都是「不确定计划在哪个账户」时代的产物。</p>
 *
 * <p>⚠️ 账户从哪来：调用方自己解析（{@code PlanTargetResolver} 从素材表 {@code E 列}
 * 精确匹配账户字典，且<b>必须唯一</b>）。本接口<b>不再自己定位账户</b>。</p>
 */
public interface PlanQueryService {

    /**
     * 查该【抖音号 + 商品名】在<b>指定账户</b>下是否已有千川计划。
     *
     * @param awemeShowId 抖音号
     * @param productName 商品名
     * @param advertiserId <b>必填</b> —— 要查的投放账户 ID（来自素材表 {@code E 全域账号} 列）
     * @return 查询结果；⚠️ 判断「有没有计划」前<b>必须</b>先看
     *         {@code PlanQueryOutcome#queryComplete()} —— 账户查失败时「没有计划」这个结论不成立
     * @throws Exception 换取 token 失败 / 千川接口报错（限流会抛 {@code RateLimitException}）
     */
    PlanQueryOutcome query(String awemeShowId, String productName, long advertiserId)
            throws Exception;
}
