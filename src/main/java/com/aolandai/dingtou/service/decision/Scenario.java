package com.aolandai.dingtou.service.decision;

/**
 * 投放场景。四种，由「累计随心推笔数 N」与「是否已有千川计划」共同决定。
 *
 * <p>来源：老板 2026-09-30 09:18 给出的步骤 3 决策表。</p>
 */
public enum Scenario {

    /** N &lt; 3：让影刀 RPA 新建随心推计划。 */
    A("新建随心推"),

    /** N = 3 且无千川计划：建千川（乘方）计划，并加入该组合的全部素材。 */
    B("建千川计划(达标)"),

    /** 已存在千川计划：往该计划加本条素材并改名（日期改成最新 M.D）。 */
    C1("复用已有千川计划"),

    /** N &gt; 3 且无千川计划：建千川（乘方）计划，并加入该组合的全部素材。 */
    C2("建千川计划(存量补建)");

    private final String label;

    Scenario(String label) {
        this.label = label;
    }

    /** 中文短标签，用于日志与应答，避免上游看到裸枚举名。 */
    public String label() {
        return label;
    }
}
