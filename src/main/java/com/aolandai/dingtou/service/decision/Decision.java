package com.aolandai.dingtou.service.decision;

import java.util.Optional;

/**
 * 决策结果：该走哪个场景、千川计划该叫什么名、以及「为什么这么判」。
 *
 * <p>{@code reason} 不是给人看的装饰 —— 它是<b>排障依据</b>：
 * 投放错单时，日志里有这一行就能立刻看出是 N 算错了还是计划校验错了。</p>
 *
 * @param scenario 场景 A / B / C1 / C2
 * @param planName 千川计划名；<b>仅 B / C1 / C2 有</b>，A 场景为空
 * @param reason   人类可读的判定依据
 */
public record Decision(Scenario scenario, Optional<String> planName, String reason) {

    public Decision {
        if (scenario == null) {
            throw new IllegalArgumentException("scenario 不能为空");
        }
        planName = planName == null ? Optional.empty() : planName;
        reason = reason == null ? "" : reason;
    }

    /** A：交给影刀 RPA 新建随心推。 */
    public boolean createsSuixintui() {
        return scenario == Scenario.A;
    }

    /** B / C2：需要新建千川（乘方）计划。 */
    public boolean createsQianchuanPlan() {
        return scenario == Scenario.B || scenario == Scenario.C2;
    }

    /** C1：复用已有千川计划，加本条素材 + 改名。 */
    public boolean reusesExistingPlan() {
        return scenario == Scenario.C1;
    }

    /** B / C2 要「加入该组合的全部素材」，A 只要新建、C1 只加本条。 */
    public boolean addsAllMaterials() {
        return createsQianchuanPlan();
    }
}
