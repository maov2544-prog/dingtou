package com.aolandai.dingtou.dto.qianchuan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SuixintuiConstraints} 单测 —— 钉死官方文档给的两条硬约束。
 *
 * <p>这些用例的价值在于：约束一旦被人「顺手放宽」（比如觉得「36 小时也该允许」），
 * 这里会立刻红 —— 而不是等到线上被接口报 40000、或者按错预算真建了单。</p>
 */
class SuixintuiConstraintsTest {

    @ParameterizedTest(name = "delivery_time = {0} 小时应当合法")
    @ValueSource(doubles = {2.0, 6.0, 12.0, 24.0, 48.0, 72.0, 96.0, 120.0, 144.0, 168.0})
    @DisplayName("★ 白名单里的 10 个时长全部合法")
    void allWhitelistedDeliveryTimesAreAllowed(double deliveryTime) {
        assertTrue(SuixintuiConstraints.isDeliveryTimeAllowed(deliveryTime));
        assertNull(SuixintuiConstraints.deliveryTimeProblem(deliveryTime));
    }

    @ParameterizedTest(name = "delivery_time = {0} 小时应当被拒")
    @ValueSource(doubles = {0.0, 1.0, 3.0, 5.0, 36.0, 100.0, 200.0, -6.0})
    @DisplayName("★ 时长是【白名单】不是范围：3 / 36 这类「看着合理」的值也必须被拒")
    void nonWhitelistedDeliveryTimesAreRejected(double deliveryTime) {
        assertFalse(SuixintuiConstraints.isDeliveryTimeAllowed(deliveryTime),
                deliveryTime + " 不在官方白名单里，不该被放行");
        String problem = SuixintuiConstraints.deliveryTimeProblem(deliveryTime);
        assertNotNull(problem);
        // 提示里必须带上「实际收到的值」，否则排查时不知道到底填了什么
        assertTrue(problem.contains(String.valueOf((long) deliveryTime)), problem);
    }

    @Test
    @DisplayName("★ 预算边界值：100 与 5000000 合法，99 与 5000010 非法")
    void amountBoundaries() {
        assertTrue(SuixintuiConstraints.isAmountAllowed(100L), "下限 100 应当合法");
        assertTrue(SuixintuiConstraints.isAmountAllowed(5_000_000L), "上限 5000000 应当合法");
        assertFalse(SuixintuiConstraints.isAmountAllowed(99L), "低于下限应当被拒");
        assertFalse(SuixintuiConstraints.isAmountAllowed(5_000_010L), "高于上限应当被拒");
        assertFalse(SuixintuiConstraints.isAmountAllowed(0L));
        assertFalse(SuixintuiConstraints.isAmountAllowed(-100L));
    }

    @Test
    @DisplayName("★ 预算必须是 10 的倍数：105 / 1000001 被拒，110 合法")
    void amountMustBeMultipleOfTen() {
        assertTrue(SuixintuiConstraints.isAmountAllowed(110L));
        assertTrue(SuixintuiConstraints.isAmountAllowed(200L));
        assertFalse(SuixintuiConstraints.isAmountAllowed(105L));
        assertFalse(SuixintuiConstraints.isAmountAllowed(1_000_001L));

        String problem = SuixintuiConstraints.amountProblem(105L);
        assertNotNull(problem);
        assertTrue(problem.contains("105"), problem);
        assertTrue(problem.contains("10"), problem);
    }

    @Test
    @DisplayName("合法值的问题说明为 null（调用方据此判断是否放行）")
    void problemIsNullWhenValid() {
        assertNull(SuixintuiConstraints.amountProblem(200L));
        assertNull(SuixintuiConstraints.amountProblem(5_000_000L));
        assertNull(SuixintuiConstraints.deliveryTimeProblem(24.0));
    }

    @Test
    @DisplayName("提示文案：时长打印成整小时（不带 .0），预算规则含区间与倍数")
    void describeTexts() {
        String times = SuixintuiConstraints.describeDeliveryTimes();
        assertTrue(times.contains("2 / 6 / 12 / 24"), times);
        assertTrue(times.endsWith("168"), times);
        assertFalse(times.contains(".0"), "不该打印成 2.0 / 6.0：" + times);

        String rule = SuixintuiConstraints.describeAmountRule();
        assertTrue(rule.contains("100"), rule);
        assertTrue(rule.contains("5000000"), rule);
        assertTrue(rule.contains("10"), rule);
    }

    @Test
    @DisplayName("白名单只增不减地钉住：数量与内容都要对（防被顺手加值）")
    void whitelistIsPinned() {
        assertEquals(10, SuixintuiConstraints.ALLOWED_DELIVERY_TIMES.size(),
                "官方白名单是 10 个值，实际：" + SuixintuiConstraints.ALLOWED_DELIVERY_TIMES);
        assertTrue(SuixintuiConstraints.ALLOWED_DELIVERY_TIMES.contains(168.0));
        assertFalse(SuixintuiConstraints.ALLOWED_DELIVERY_TIMES.contains(36.0));
    }

    // ==================== roi_goal（条件必填） ====================

    @Test
    @DisplayName("★ 条件必填：bid_type=MANUAL_BID 时 roi_goal 不能为空")
    void roiGoalIsRequiredWhenManualBid() {
        assertTrue(SuixintuiConstraints.isRoiGoalRequired("MANUAL_BID"));
        String problem = SuixintuiConstraints.roiGoalProblem(null, "MANUAL_BID");
        assertNotNull(problem, "MANUAL_BID 下缺 roi_goal 必须报错");
        assertTrue(problem.contains("必填"), problem);
        assertTrue(problem.contains("MANUAL_BID"), problem);
    }

    @Test
    @DisplayName("★ 条件必填的另一半：bid_type=AUTO_BID 时 roi_goal 可以不传")
    void roiGoalOptionalWhenAutoBid() {
        assertFalse(SuixintuiConstraints.isRoiGoalRequired("AUTO_BID"));
        assertNull(SuixintuiConstraints.roiGoalProblem(null, "AUTO_BID"));
    }

    @Test
    @DisplayName("★ roi_goal 取值范围 [0.01, 10000]：边界合法，越界被拒")
    void roiGoalRange() {
        assertNull(SuixintuiConstraints.roiGoalProblem(0.01, "MANUAL_BID"), "下限 0.01 应合法");
        assertNull(SuixintuiConstraints.roiGoalProblem(10_000.0, "MANUAL_BID"), "上限 10000 应合法");
        assertNull(SuixintuiConstraints.roiGoalProblem(1.7, "MANUAL_BID"), "老板给的 1.7 应合法");

        assertNotNull(SuixintuiConstraints.roiGoalProblem(0.0, "MANUAL_BID"));
        assertNotNull(SuixintuiConstraints.roiGoalProblem(10_000.5, "MANUAL_BID"));
        assertNotNull(SuixintuiConstraints.roiGoalProblem(-1.0, "MANUAL_BID"));
    }

    @Test
    @DisplayName("★ roi_goal 最多两位小数：1.7 / 1.75 合法，1.755 被拒")
    void roiGoalMaxTwoDecimals() {
        assertNull(SuixintuiConstraints.roiGoalProblem(1.7, "MANUAL_BID"));
        assertNull(SuixintuiConstraints.roiGoalProblem(1.75, "MANUAL_BID"));
        assertNull(SuixintuiConstraints.roiGoalProblem(2.0, "MANUAL_BID"), "整数应合法");

        String problem = SuixintuiConstraints.roiGoalProblem(1.755, "MANUAL_BID");
        assertNotNull(problem, "3 位小数应被拒");
        assertTrue(problem.contains("1.755"), problem);
        assertTrue(problem.contains("2"), problem);
    }

    @Test
    @DisplayName("★ 小数位判断用 BigDecimal.valueOf —— 若用 new BigDecimal(double) 会把 1.7 判成几十位小数")
    void roiGoalScaleUsesBigDecimalValueOf() {
        // 这条用例的价值：一旦有人把实现改成 new BigDecimal(roiGoal)，1.7 会被展开成
        // 1.6999999999999999555910790149937383830547332763671875 → 合法值全被判非法
        assertNull(SuixintuiConstraints.roiGoalProblem(1.7, "MANUAL_BID"));
        assertNull(SuixintuiConstraints.roiGoalProblem(0.01, "MANUAL_BID"));
        assertNull(SuixintuiConstraints.roiGoalProblem(9999.99, "MANUAL_BID"));
    }

    @Test
    @DisplayName("roi_goal 提示文案：区间打印成 [0.01, 10000]（不带科学计数法/多余零）")
    void describeRoiGoalRuleText() {
        String rule = SuixintuiConstraints.describeRoiGoalRule();
        assertTrue(rule.contains("0.01"), rule);
        assertTrue(rule.contains("10000"), rule);
        assertFalse(rule.contains("1E+4"), "不该出现科学计数法：" + rule);
        assertFalse(rule.contains("10000.0"), "不该带多余的 .0：" + rule);
    }
}
