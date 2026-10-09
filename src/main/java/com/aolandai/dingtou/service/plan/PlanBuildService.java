package com.aolandai.dingtou.service.plan;

import com.aolandai.dingtou.dto.plan.PlanBuildParam;
import com.aolandai.dingtou.dto.plan.PlanBuildResult;

/**
 * B / C1 / C2：把【抖音号 + 商品名】投到<b>乘方计划</b>上。
 *
 * <p>服务自己判断该走哪条路（调用方不需要知道场景）：</p>
 * <ul>
 *   <li><b>没有千川计划</b>（B / C2）→ 查素材池拿<b>全部</b>素材 → 建乘方计划（素材随建计划一起带上）+ 命名；</li>
 *   <li><b>已有千川计划</b>（C1）→ 加<b>本条</b>素材 → 改名（日期改最新）。</li>
 * </ul>
 *
 * <p>⚠️ <b>建计划是花钱的操作</b>，受 {@code dingtou.dry-run} 保护：演练模式下只组装请求体、不真调。</p>
 */
public interface PlanBuildService {

    /**
     * 执行 B / C1 / C2。
     *
     * <p>⚠️ 返回结果里的 {@code warnings} 必须看：非空说明<b>没有全部办成</b>，不能当成「都投了」。</p>
     *
     * @throws Exception 数据定位不到 / 跨多账户 / 查计划不完整等需要人工介入的情况
     */
    PlanBuildResult build(PlanBuildParam param) throws Exception;
}
