package com.aolandai.dingtou.dto;

import com.aolandai.dingtou.service.decision.Decision;

import java.util.Optional;

/**
 * 决策结果对外的视图对象。
 *
 * <p>为什么不直接把 {@code service.decision.Decision} 抛给 HTTP：那是<b>业务层类型</b>，
 * 直接暴露会让「改一个内部字段」变成「改接口契约」。这里做一层转换，边界清晰。</p>
 *
 * <p>⚠️ 同时带上「N 是多少、从哪来的」—— 决策错了的时候，
 * 第一件要查的事就是「N 算错了还是计划校验错了」，不带上就得再去翻日志。</p>
 *
 * @param scenario        场景枚举名（{@code A} / {@code B} / {@code C1} / {@code C2}），影刀按它分支
 * @param scenarioLabel   场景中文说明，便于直接贴到企微群
 * @param planName        千川计划名；A 场景为空
 * @param reason          判定依据，排障用
 * @param cumulativeCount 本次判定用的累计笔数 N
 * @param countSource     N 的取数来源（{@code ORDER_SNAPSHOT} 权威 / {@code DELIVERY_SHEET} 近似）
 * @param countDetail     N 的取数说明
 */
public record DecisionView(
        String scenario,
        String scenarioLabel,
        Optional<String> planName,
        String reason,
        int cumulativeCount,
        String countSource,
        String countDetail) {

    public static DecisionView of(Decision decision, CountResult count) {
        return new DecisionView(
                decision.scenario().name(),
                decision.scenario().label(),
                decision.planName(),
                decision.reason(),
                count == null ? 0 : count.count(),
                count == null ? null : count.source().name(),
                count == null ? "" : count.detail());
    }
}
