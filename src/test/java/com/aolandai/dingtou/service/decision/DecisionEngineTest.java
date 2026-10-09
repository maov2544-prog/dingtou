package com.aolandai.dingtou.service.decision;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DecisionEngine} 单测 —— 决策层是纯函数，覆盖率要求 100%。
 *
 * <p>用固定日期 {@link #EXEC} 而不是 {@code LocalDate.now()}，否则单测跨天就飘。</p>
 */
class DecisionEngineTest {

    /** 固定执行日：2026-09-30，对应计划名里的 {@code 9.30}。 */
    private static final LocalDate EXEC = LocalDate.of(2026, 9, 30);

    private static DecisionContext ctx(int count, boolean hasPlan) {
        return new DecisionContext("602085838", "益生元面霜", count, hasPlan);
    }

    // ==================== 无千川计划：按 N 分流 ====================

    @ParameterizedTest(name = "无计划 + 累计 {0} 笔 → A")
    @ValueSource(ints = {0, 1, 2})
    @DisplayName("N < 3 且无计划 → A（新建随心推，不出计划名）")
    void belowThresholdGoesToA(int count) {
        Decision decision = DecisionEngine.decide(ctx(count, false), EXEC);

        assertEquals(Scenario.A, decision.scenario());
        assertTrue(decision.createsSuixintui(), "A 场景应新建随心推");
        assertFalse(decision.createsQianchuanPlan(), "A 场景不建千川计划");
        assertFalse(decision.reusesExistingPlan());
        assertFalse(decision.addsAllMaterials());
        assertTrue(decision.planName().isEmpty(), "A 场景不该有计划名");
    }

    @Test
    @DisplayName("N = 3 且无计划 → B（边界：刚好达标）")
    void exactlyThresholdGoesToB() {
        Decision decision = DecisionEngine.decide(ctx(3, false), EXEC);

        assertEquals(Scenario.B, decision.scenario());
        assertTrue(decision.createsQianchuanPlan());
        assertTrue(decision.addsAllMaterials(), "B 要加入全部素材");
        assertFalse(decision.createsSuixintui());
        assertFalse(decision.reusesExistingPlan());
        assertEquals("602085838-益生元面霜-9.30", decision.planName().orElseThrow());
    }

    @ParameterizedTest(name = "无计划 + 累计 {0} 笔 → C2")
    @ValueSource(ints = {4, 5, 20})
    @DisplayName("N > 3 且无计划 → C2（历史存量补建）")
    void aboveThresholdWithoutPlanGoesToC2(int count) {
        Decision decision = DecisionEngine.decide(ctx(count, false), EXEC);

        assertEquals(Scenario.C2, decision.scenario());
        assertTrue(decision.createsQianchuanPlan());
        assertTrue(decision.addsAllMaterials());
        assertFalse(decision.createsSuixintui());
        assertFalse(decision.reusesExistingPlan());
        assertEquals("602085838-益生元面霜-9.30", decision.planName().orElseThrow());
    }

    // ==================== 已有千川计划：一律 C1 ====================

    @ParameterizedTest(name = "有计划 + 累计 {0} 笔 → C1")
    @ValueSource(ints = {0, 3, 4, 20})
    @DisplayName("只要已有千川计划 → C1（步骤5：复用，不再新建）")
    void existingPlanAlwaysWins(int count) {
        Decision decision = DecisionEngine.decide(ctx(count, true), EXEC);

        assertEquals(Scenario.C1, decision.scenario());
        assertTrue(decision.reusesExistingPlan());
        assertFalse(decision.createsSuixintui(), "计划已存在，不得再新建随心推");
        assertFalse(decision.createsQianchuanPlan(), "计划已存在，不得重复建千川计划");
        assertFalse(decision.addsAllMaterials(), "C1 只加本条素材，不是全部");
        assertEquals("602085838-益生元面霜-9.30", decision.planName().orElseThrow());
    }

    @Test
    @DisplayName("reason 里带上实际笔数，便于排障")
    void reasonCarriesCount() {
        assertTrue(DecisionEngine.decide(ctx(7, true), EXEC).reason().contains("7"));
        assertTrue(DecisionEngine.decide(ctx(2, false), EXEC).reason().contains("2"));
    }

    // ==================== 计划名 ====================

    @Test
    @DisplayName("B/C1/C2 的计划名都按「抖音号-商品-M.D」生成")
    void planNameFollowsRule() {
        String expected = "602085838-益生元面霜-9.30";
        assertEquals(expected, DecisionEngine.decide(ctx(3, false), EXEC).planName().orElseThrow());
        assertEquals(expected, DecisionEngine.decide(ctx(9, false), EXEC).planName().orElseThrow());
        assertEquals(expected, DecisionEngine.decide(ctx(9, true), EXEC).planName().orElseThrow());
    }

    @ParameterizedTest(name = "执行日 {0} → 计划名日期 {1}")
    @CsvSource({
            "2026-09-02, 9.2",
            "2026-09-30, 9.30",
            "2026-10-05, 10.5",
            "2026-12-31, 12.31",
    })
    @DisplayName("计划名日期无前导零")
    void planNameHasNoLeadingZero(String date, String md) {
        LocalDate exec = LocalDate.parse(date);
        String name = DecisionEngine.decide(ctx(3, false), exec).planName().orElseThrow();
        assertEquals("602085838-益生元面霜-" + md, name);
    }

    // ==================== 参数校验 ====================

    @Test
    @DisplayName("context / executionDate 为 null 时拒绝")
    void rejectsNullArguments() {
        assertThrows(IllegalArgumentException.class, () -> DecisionEngine.decide(null, EXEC));
        assertThrows(IllegalArgumentException.class, () -> DecisionEngine.decide(ctx(3, false), null));
    }

    @Test
    @DisplayName("DecisionContext 拒绝空抖音号 / 空商品名 / 负笔数")
    void contextSelfValidates() {
        assertThrows(IllegalArgumentException.class,
                () -> new DecisionContext(null, "商品", 1, false));
        assertThrows(IllegalArgumentException.class,
                () -> new DecisionContext("  ", "商品", 1, false));
        assertThrows(IllegalArgumentException.class,
                () -> new DecisionContext("602085838", null, 1, false));
        assertThrows(IllegalArgumentException.class,
                () -> new DecisionContext("602085838", "  ", 1, false));
        assertThrows(IllegalArgumentException.class,
                () -> new DecisionContext("602085838", "商品", -1, false));
    }

    @Test
    @DisplayName("Decision 容错：planName / reason 传 null 不炸")
    void decisionToleratesNulls() {
        Decision decision = new Decision(Scenario.A, null, null);
        assertTrue(decision.planName().isEmpty());
        assertEquals("", decision.reason());
    }

    @Test
    @DisplayName("Decision 拒绝 null scenario")
    void decisionRejectsNullScenario() {
        assertThrows(IllegalArgumentException.class, () -> new Decision(null, null, "x"));
    }

    @Test
    @DisplayName("Scenario 都有中文标签")
    void scenarioHasLabel() {
        for (Scenario scenario : Scenario.values()) {
            assertFalse(scenario.label().isBlank(), scenario + " 缺少中文标签");
        }
    }
}
