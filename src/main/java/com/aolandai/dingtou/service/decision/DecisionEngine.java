package com.aolandai.dingtou.service.decision;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 决策引擎：按【累计随心推笔数 N + 是否已有千川计划】判定该走 A / B / C1 / C2。
 *
 * <p><b>纯函数、无 IO、无 Spring 依赖</b> —— 规则会反复变（B 就从 {@code >=3} 改成了 {@code ==3}），
 * 放在这里能单测 100% 覆盖；放影刀里改一次就要人肉回归一次。</p>
 *
 * <h2>判定规则</h2>
 * <pre>
 *   已存在千川计划          → C1（复用该计划：加本条素材 + 改名）
 *   否则 N &lt; 3             → A （影刀新建随心推）
 *   否则 N = 3             → B （建乘方计划 + 加全部素材）
 *   否则 N &gt; 3             → C2（建乘方计划 + 加全部素材）
 * </pre>
 *
 * <p>⚠️ <b>这里对老板原始决策表做了两处「补全」，需要老板确认：</b></p>
 * <ul>
 *   <li>原表只写了 {@code N>3 且已有计划 → C1}，没写 {@code N=3 且已有计划}。
 *       本实现按<b>步骤 5</b> 的原话
 *       「一旦千川计划创建完成，后续同达人同商品新素材全部复用这个千川计划新增视频，
 *       不再新建随心推、不再重复新建千川计划」，
 *       判定为 <b>只要计划存在就一律 C1</b> —— 即「计划存在」优先于「N 的取值」。</li>
 *   <li>原表 {@code N<3} 一行「已有计划」列写的是「—」（不适用）。本实现同样按上述优先级处理：
 *       若真出现 {@code N<3 且已有计划}，判 C1 而不是 A。这通常意味着存量表数据滞后，
 *       日志里 {@code reason} 会带上实际 N，便于追查。</li>
 * </ul>
 */
public final class DecisionEngine {

    /** 达标阈值：累计随心推笔数达到 3 笔即转千川。 */
    public static final int CONVERSION_THRESHOLD = 3;

    private DecisionEngine() {
    }

    /**
     * 执行决策。
     *
     * @param context       该【达人 + 商品】组合的当前状态
     * @param executionDate 执行日期，用于生成计划名里的 M.D（注入而非取 {@code LocalDate.now()}，
     *                      否则单测没法固定日期）
     */
    public static Decision decide(DecisionContext context, LocalDate executionDate) {
        if (context == null) {
            throw new IllegalArgumentException("context 不能为空");
        }
        if (executionDate == null) {
            throw new IllegalArgumentException("executionDate 不能为空");
        }

        int count = context.cumulativeCount();
        String aweme = context.awemeShowId();
        String product = context.productName();

        // 优先级 1：计划已存在 → 一律复用（步骤 5）
        if (context.hasQianchuanPlan()) {
            String name = PlanNamer.build(aweme, product, executionDate);
            return new Decision(Scenario.C1, Optional.of(name),
                    "已存在千川计划 → 复用该计划：加本条素材 + 改名，"
                            + "累计笔数=" + count + "（步骤5：计划建成后不再新建随心推/千川）");
        }

        // 优先级 2：未达标 → 影刀新建随心推
        if (count < CONVERSION_THRESHOLD) {
            return new Decision(Scenario.A, Optional.empty(),
                    "累计随心推 " + count + " 笔 < " + CONVERSION_THRESHOLD
                            + " 且无千川计划 → 新建随心推");
        }

        String name = PlanNamer.build(aweme, product, executionDate);

        // 优先级 3：刚好达标 → 建计划
        if (count == CONVERSION_THRESHOLD) {
            return new Decision(Scenario.B, Optional.of(name),
                    "累计随心推 " + count + " 笔 = " + CONVERSION_THRESHOLD
                            + " 且无千川计划 → 建乘方计划 + 加全部素材");
        }

        // 优先级 4：历史存量已超 3 笔但一直没建 → 补建
        return new Decision(Scenario.C2, Optional.of(name),
                "累计随心推 " + count + " 笔 > " + CONVERSION_THRESHOLD
                        + " 且无千川计划（历史存量未建）→ 建乘方计划 + 加全部素材");
    }
}
