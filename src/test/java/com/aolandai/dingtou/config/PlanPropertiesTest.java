package com.aolandai.dingtou.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PlanProperties} 的启动自检单测。
 *
 * <p>这些 {@code isXxxValid()} 是 Spring Boot 启动期的 {@code @AssertTrue} 钩子 ——
 * 配错了要让<b>服务起不来</b>，而不是等影刀真调时才发现（那时已经在花钱路径上了）。</p>
 */
class PlanPropertiesTest {

    @Test
    @DisplayName("★ 默认值（20000 元 / ROI 2）本身必须合法，否则服务起不来")
    void defaultsAreValid() {
        PlanProperties props = new PlanProperties();
        assertTrue(props.isRoi2GoalValid(), "默认 ROI 2 应当合法");
        assertTrue(props.isVideoScheduleTypeValid());
        assertTrue(props.isSmartBidTypeValid());
        assertTrue(props.isCreativeTypeValid());
        assertTrue(props.isQcpxModeValid());
        assertTrue(props.getDefaultBudget() > 0);
    }

    @Test
    @DisplayName("★ ROI 目标最多两位小数（官方约束）；2.755 被拒，2.75 合法")
    void roi2GoalMaxTwoDecimals() {
        PlanProperties props = new PlanProperties();

        props.setDefaultRoi2Goal(2.755);
        assertFalse(props.isRoi2GoalValid(), "3 位小数应当自检失败");

        props.setDefaultRoi2Goal(2.75);
        assertTrue(props.isRoi2GoalValid());

        props.setDefaultRoi2Goal(0);
        assertFalse(props.isRoi2GoalValid(), "0 不是正数，应当自检失败");
    }

    @Test
    @DisplayName("★ 排期方式要用【接口值】SCHEDULE_FROM_NOW，不是 SDK 常量名 FROM_NOW")
    void videoScheduleTypeUsesApiValue() {
        PlanProperties props = new PlanProperties();

        props.setDefaultVideoScheduleType("FROM_NOW");
        assertFalse(props.isVideoScheduleTypeValid(),
                "⚠️ 常量名不是接口值，传它会抛 Unexpected value");

        props.setDefaultVideoScheduleType("SCHEDULE_START_END");
        assertTrue(props.isVideoScheduleTypeValid());
    }

    @Test
    @DisplayName("出价方式：乘方仅支持 SMART_BID_CUSTOM")
    void smartBidTypeIsRestricted() {
        PlanProperties props = new PlanProperties();
        props.setDefaultSmartBidType("SMART_BID_CONSERVATIVE");
        assertFalse(props.isSmartBidTypeValid());
    }

    @Test
    @DisplayName("qcpxMode 必须是 QCPX_MODE_*（留空合法），传 ON 会被拒")
    void qcpxModeMustUseFullValue() {
        PlanProperties props = new PlanProperties();
        assertTrue(props.isQcpxModeValid(), "默认留空应当合法");

        props.setDefaultQcpxMode("ON");
        assertFalse(props.isQcpxModeValid(), "⚠️ 取值是 QCPX_MODE_ON，不是 ON");

        props.setDefaultQcpxMode("QCPX_MODE_ON");
        assertTrue(props.isQcpxModeValid());
    }

    @Test
    @DisplayName("启动横幅带上预算与 ROI（元 / 目标值），避免运维看横幅时猜")
    void describeCarriesValues() {
        String text = new PlanProperties().describe();
        assertTrue(text.contains("20000元"), text);
        assertTrue(text.contains("2"), text);
        assertTrue(text.contains("SCHEDULE_FROM_NOW"), text);
    }
}
