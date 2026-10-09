package com.aolandai.dingtou.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SuixintuiProperties} 的启动自检单测。
 *
 * <p>这些 {@code isXxxValid()} 是 Spring Boot 启动期的 {@code @AssertTrue} 钩子 ——
 * 配错了要让<b>服务起不来</b>，而不是等影刀真调时才发现（那时已经在花钱路径上了）。</p>
 */
class SuixintuiPropertiesTest {

    @Test
    @DisplayName("★ 默认值（200 元 / 24 小时 / MANUAL_BID / ROI 1.7）本身必须合法，否则服务起不来")
    void defaultsAreValid() {
        SuixintuiProperties props = new SuixintuiProperties();
        assertTrue(props.isAmountValid(), "默认预算 200 元应当合法");
        assertTrue(props.isDeliveryTimeValid(), "默认时长 24 小时应当合法");
        assertTrue(props.isRoiGoalValid(), "默认出价方式 MANUAL_BID + ROI 1.7 应当合法");
        assertTrue(props.isBidTypeValid(), "默认出价方式 MANUAL_BID 应当合法");
    }

    @Test
    @DisplayName("★ 预算不是 10 的倍数 / 越界 → 自检失败（启动即炸）")
    void invalidAmountFailsSelfCheck() {
        SuixintuiProperties props = new SuixintuiProperties();

        props.setDefaultAmount(105L);
        assertFalse(props.isAmountValid(), "105 不是 10 的倍数，应当自检失败");

        props.setDefaultAmount(99L);
        assertFalse(props.isAmountValid(), "低于下限 100，应当自检失败");

        props.setDefaultAmount(5_000_010L);
        assertFalse(props.isAmountValid(), "高于上限，应当自检失败");
    }

    @Test
    @DisplayName("★ 时长不在白名单 → 自检失败（36 小时看着合理但接口会拒）")
    void invalidDeliveryTimeFailsSelfCheck() {
        SuixintuiProperties props = new SuixintuiProperties();

        props.setDefaultDeliveryTime(36.0);
        assertFalse(props.isDeliveryTimeValid(), "36 小时不在官方白名单，应当自检失败");

        props.setDefaultDeliveryTime(1.0);
        assertFalse(props.isDeliveryTimeValid(), "1 小时不在白名单，应当自检失败");

        props.setDefaultDeliveryTime(48.0);
        assertTrue(props.isDeliveryTimeValid(), "48 小时在白名单里");
    }

    @Test
    @DisplayName("★ 联动自检：bid-type=MANUAL_BID 时不给 roi-goal → 启动即失败（两个配置必须成对改）")
    void manualBidWithoutRoiGoalFailsSelfCheck() {
        SuixintuiProperties props = new SuixintuiProperties();
        props.setDefaultBidType("MANUAL_BID");
        props.setDefaultRoiGoal(null);
        assertFalse(props.isRoiGoalValid(), "MANUAL_BID 缺 ROI 目标应当自检失败");

        props.setDefaultRoiGoal(1.7);
        assertTrue(props.isRoiGoalValid(), "补上 ROI 目标后应当通过");
    }

    @Test
    @DisplayName("AUTO_BID 时 roi-goal 可以不传")
    void autoBidDoesNotRequireRoiGoal() {
        SuixintuiProperties props = new SuixintuiProperties();
        props.setDefaultBidType("AUTO_BID");
        props.setDefaultRoiGoal(null);
        assertTrue(props.isRoiGoalValid());
    }

    @Test
    @DisplayName("★ roi-goal 越界 / 超过两位小数 → 自检失败")
    void invalidRoiGoalFailsSelfCheck() {
        SuixintuiProperties props = new SuixintuiProperties();

        props.setDefaultRoiGoal(1.755);
        assertFalse(props.isRoiGoalValid(), "3 位小数应当自检失败");

        props.setDefaultRoiGoal(0.0);
        assertFalse(props.isRoiGoalValid(), "低于下限 0.01 应当自检失败");

        props.setDefaultRoiGoal(10_000.5);
        assertFalse(props.isRoiGoalValid(), "高于上限 10000 应当自检失败");

        props.setDefaultRoiGoal(1.75);
        assertTrue(props.isRoiGoalValid(), "两位小数应当合法");
    }

    @Test
    @DisplayName("枚举自检仍然生效：qcpxMode 必须是 QCPX_MODE_*，bidType 必须是 AUTO_BID/MANUAL_BID")
    void enumSelfChecks() {
        SuixintuiProperties props = new SuixintuiProperties();
        assertTrue(props.isBidTypeValid());
        assertTrue(props.isQcpxModeValid(), "默认留空应当合法");

        props.setDefaultBidType("BID_SOMETHING");
        assertFalse(props.isBidTypeValid());

        props.setDefaultBidType("");
        props.setDefaultQcpxMode("ON");
        assertFalse(props.isQcpxModeValid(), "⚠️ 取值是 QCPX_MODE_ON，不是 ON");

        props.setDefaultQcpxMode("QCPX_MODE_ON");
        assertTrue(props.isQcpxModeValid());
    }

    @Test
    @DisplayName("启动横幅文案带上单位与取值（元 / 小时 / 出价方式 / ROI），避免运维看横幅时猜")
    void describeCarriesUnits() {
        String text = new SuixintuiProperties().describe();
        assertTrue(text.contains("200元"), text);
        assertTrue(text.contains("24.0小时"), text);
        assertTrue(text.contains("MANUAL_BID"), text);
        assertTrue(text.contains("1.7"), text);
    }
}
