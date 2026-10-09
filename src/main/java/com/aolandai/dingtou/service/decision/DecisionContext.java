package com.aolandai.dingtou.service.decision;

/**
 * 决策引擎的输入：一个【达人 + 商品】组合的当前状态。
 *
 * <p>字段全部来自步骤 3 的前置查询：</p>
 * <ul>
 *   <li>{@code cumulativeCount} —— 累计随心推笔数 N。来源见
 *       {@code docs/NEXT-REQUIREMENT-CONFIRM.md} §5：先用两表预筛，再用随心推接口取权威笔数；</li>
 *   <li>{@code hasQianchuanPlan} —— 该组合是否已存在千川计划（接口校验结果）。</li>
 * </ul>
 */
public record DecisionContext(
        String awemeShowId,
        String productName,
        int cumulativeCount,
        boolean hasQianchuanPlan) {

    /** 紧凑构造器做参数自检：宁可在这里炸，也不要把脏数据带到投放环节。 */
    public DecisionContext {
        if (awemeShowId == null || awemeShowId.isBlank()) {
            throw new IllegalArgumentException("awemeShowId（抖音号）不能为空");
        }
        if (productName == null || productName.isBlank()) {
            throw new IllegalArgumentException("productName（商品名）不能为空");
        }
        if (cumulativeCount < 0) {
            throw new IllegalArgumentException("cumulativeCount（累计笔数）不能为负: " + cumulativeCount);
        }
    }
}
