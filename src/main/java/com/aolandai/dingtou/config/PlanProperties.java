package com.aolandai.dingtou.config;

import com.aolandai.dingtou.util.Decimals;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * B / C1 / C2（乘方计划）的默认参数（前缀 {@code dingtou.plan}）。
 *
 * <pre>
 * dingtou:
 *   plan:
 *     default-budget: 20000                  # 预算（元）
 *     default-roi2-goal: 2                   # 支付 ROI 目标，最多两位小数
 *     default-video-schedule-type: SCHEDULE_FROM_NOW
 *     default-smart-bid-type: SMART_BID_CUSTOM
 *     default-deep-external-action: ""       # 留空 = 不传，用平台默认
 *     default-qcpx-mode: ""                  # 留空 = 不传
 *     default-creative-type: PROGRAMMATIC_CREATIVE
 *     enable-aigc-creative: false             # AIGC 动态创意：显式传 false（= 关）
 *     star-task-material-switch: false        # 千川星选素材投放：关
 *     alliance-commission-switch: false       # 达人带货佣金优化：关
 *     material-pool-max-rounds: 20
 *     material-pool-page-interval-millis: 500
 * </pre>
 *
 * <h2>为什么这些是「配置默认值」</h2>
 * <p>老板 2026-10-05 拍板：建乘方计划的预算 / ROI 等参数<b>配在服务端</b>，影刀不用每次传。
 * 好处是影刀侧调用最简单；代价是<b>改这些值要改配置并重启服务</b>。</p>
 *
 * <h2>⚠️ 哪些是官方必填</h2>
 * <p>{@code QianchuanOverallVideoCreateService#validate} 要求：
 * {@code name}（由代码生成，不配）、{@code product_ids}（查表得到）、
 * <b>{@code delivery_setting.budget}</b>、<b>{@code delivery_setting.roi2_goal}</b>、
 * 以及每个商品至少一条素材。<b>所以 budget / roi2Goal 这两个配置项是必填的</b> ——
 * 留空会在启动自检时被拦住，而不是等到花钱那一步才炸。</p>
 *
 * <h2>⚠️ 取值枚举的「常量名 ≠ 接口值」坑</h2>
 * <p>{@code video_schedule_type} 的 SDK 常量名是 {@code FROM_NOW} / {@code START_END}，
 * 但<b>接口值（真正发出去、也是 fromValue 接受的）</b>是
 * {@code SCHEDULE_FROM_NOW} / {@code SCHEDULE_START_END} —— 已用 {@code getValue()} 实测确认。
 * 配错了会抛 {@code Unexpected value}，所以这里做启动自检。</p>
 */
@Validated
@ConfigurationProperties(prefix = "dingtou.plan")
public class PlanProperties {

    /** 合法的排期方式（接口值，不是 SDK 常量名）。 */
    public static final String SCHEDULE_FROM_NOW = "SCHEDULE_FROM_NOW";
    public static final String SCHEDULE_START_END = "SCHEDULE_START_END";

    /** 乘方计划目前只支持这一种出价方式（控成本投放）。 */
    public static final String SMART_BID_CUSTOM = "SMART_BID_CUSTOM";

    /** 程序化创意（默认）。 */
    public static final String CREATIVE_TYPE_PROGRAMMATIC = "PROGRAMMATIC_CREATIVE";

    /**
     * 建乘方计划的<b>默认预算</b>，<b>单位：元</b>（老板 2026-10-05 定：20000 元）。
     *
     * <p>⚠️ 官方必填，且没有默认值可依赖 —— 配成 0 或负数启动就会失败。</p>
     */
    @Positive
    private double defaultBudget = 20_000D;

    /**
     * 建乘方计划的<b>默认支付 ROI 目标</b>（老板 2026-10-05 定：2）。
     *
     * <p>⚠️ 官方要求<b>最多两位小数</b>（见 {@link #isRoi2GoalValid()}）。</p>
     *
     * <p>⚠️ 取值范围官方文档未给出，这里<b>只校验「正数 + 最多两位小数」</b>，
     * 不擅自编一个区间 —— 编错了会把合法值拦下。</p>
     */
    @Positive
    private double defaultRoi2Goal = 2D;

    /** 排期方式：{@code SCHEDULE_FROM_NOW}（从今天起长期投放）/ {@code SCHEDULE_START_END}（设起止日期）。 */
    private String defaultVideoScheduleType = SCHEDULE_FROM_NOW;

    /** 出价方式；乘方目前仅支持 {@code SMART_BID_CUSTOM}。 */
    private String defaultSmartBidType = SMART_BID_CUSTOM;

    /**
     * 深度转化目标；<b>留空 = 不传该字段</b>（用平台默认）。
     *
     * <p>⚠️ SDK 里该枚举只有一个取值 {@code AD_CONVERT_TYPE_LIVE_PURE_PAY_ROI}，
     * 名字里带 LIVE 但这是「净成交 ROI」的口径 —— 短视频带货是否适用<b>没有实测过</b>，
     * 所以默认<b>不传</b>，交给平台默认，别自作主张。</p>
     */
    private String defaultDeepExternalAction = "";

    /** 智能优惠券开关：{@code QCPX_MODE_ON} / {@code QCPX_MODE_OFF} / {@code QCPX_MODE_DEFAULT}；留空 = 不传。 */
    private String defaultQcpxMode = "";

    /** 创意类型；默认程序化创意。 */
    private String defaultCreativeType = CREATIVE_TYPE_PROGRAMMATIC;

    /**
     * AIGC 动态创意开关（{@code enable_aigc_creative}）。
     *
     * <p>⚠️ 用 {@code boolean} 基本类型是<b>刻意的</b>：它保证这个字段<b>每次都会显式传出去</b>
     * （自动装箱成 {@code Boolean.FALSE}，不会被 Jackson 当 null 省略）。
     * 老板 2026-10-05 确认「<b>保持显式传 false</b>」—— 行为确定，不依赖平台默认值。</p>
     */
    private boolean enableAigcCreative = false;

    /**
     * 乘方-成本项：<b>千川星选素材投放</b>开关（{@code overall_cost_items.star_task_material_switch}）。
     *
     * <p>开启后，存在星选素材采买任务的商品可自动生效千川星选素材。老板 2026-10-05 定：<b>关</b>。</p>
     */
    private boolean starTaskMaterialSwitch = false;

    /**
     * 乘方-成本项：<b>达人带货佣金优化</b>开关（{@code overall_cost_items.alliance_commision_switch}）。
     *
     * <p>官方原文：「当且仅当用户在白名单，且 {@code marketing_goal=VIDEO_PROM_GOODS} 商品投放。
     * 无号不支持该选项」。我们是有号 + 商品投放，<b>条件满足</b>，但是否开启取决于账号是否在白名单 ——
     * 老板 2026-10-05 定：<b>关</b>。</p>
     *
     * <p>⚠️ 接口 / SDK 里的字段名拼写是 {@code alliance_commision_switch}（<b>少一个 s</b>），
     * 别「顺手改对」—— 改了会传不出去。</p>
     */
    private boolean allianceCommissionSwitch = false;

    /**
     * 查素材池的<b>游标翻页上限</b>（兜底，防止服务端 {@code has_more} 一直 true 死循环）。
     *
     * <p>⚠️ 别调太小 —— 素材多的达人会被静默截断（同 {@code max-pages} 踩过的坑）。</p>
     */
    @Positive
    private int materialPoolMaxRounds = 20;

    /** 素材池翻页间隔（毫秒），避免触发 40100「系统请求频率超限」。 */
    @PositiveOrZero
    private long materialPoolPageIntervalMillis = 500L;

    // ==================== 启动自检 ====================

    /** ROI 目标必须是最多两位小数（官方约束）。 */
    @AssertTrue(message = "dingtou.plan.default-roi2-goal 不合法：必须为正数且最多两位小数")
    public boolean isRoi2GoalValid() {
        return defaultRoi2Goal > 0 && Decimals.scaleOf(defaultRoi2Goal) <= 2;
    }

    /** 排期方式必须是接口值 {@code SCHEDULE_FROM_NOW} / {@code SCHEDULE_START_END}（不是 SDK 常量名）。 */
    @AssertTrue(message = "dingtou.plan.default-video-schedule-type 只能是 SCHEDULE_FROM_NOW / "
            + "SCHEDULE_START_END（⚠️ 不是 SDK 常量名 FROM_NOW / START_END）")
    public boolean isVideoScheduleTypeValid() {
        return SCHEDULE_FROM_NOW.equals(defaultVideoScheduleType)
                || SCHEDULE_START_END.equals(defaultVideoScheduleType);
    }

    /** 出价方式：乘方仅支持 {@code SMART_BID_CUSTOM}。 */
    @AssertTrue(message = "dingtou.plan.default-smart-bid-type 只能是 SMART_BID_CUSTOM（乘方目前仅支持控成本投放）")
    public boolean isSmartBidTypeValid() {
        return SMART_BID_CUSTOM.equals(defaultSmartBidType);
    }

    /** 创意类型：只能是 {@code PROGRAMMATIC_CREATIVE}。 */
    @AssertTrue(message = "dingtou.plan.default-creative-type 只能是 PROGRAMMATIC_CREATIVE")
    public boolean isCreativeTypeValid() {
        return CREATIVE_TYPE_PROGRAMMATIC.equals(defaultCreativeType);
    }

    /** 智能优惠券取值必须是 {@code QCPX_MODE_*}（留空合法）。 */
    @AssertTrue(message = "dingtou.plan.default-qcpx-mode 只能是 QCPX_MODE_ON / QCPX_MODE_OFF / "
            + "QCPX_MODE_DEFAULT（或留空）—— 注意不是 ON/OFF")
    public boolean isQcpxModeValid() {
        return isBlank(defaultQcpxMode) || "QCPX_MODE_ON".equals(defaultQcpxMode)
                || "QCPX_MODE_OFF".equals(defaultQcpxMode)
                || "QCPX_MODE_DEFAULT".equals(defaultQcpxMode);
    }

    /** 启动横幅用。⚠️ 只打印参数，不含任何凭据。 */
    public String describe() {
        return "默认预算=" + Decimals.plain(defaultBudget) + "元"
                + "，默认ROI目标=" + Decimals.plain(defaultRoi2Goal)
                + "，排期=" + defaultVideoScheduleType
                + "，出价方式=" + defaultSmartBidType
                + "，深转目标=" + (isBlank(defaultDeepExternalAction) ? "（不传）" : defaultDeepExternalAction)
                + "，成本项开关[星选素材/达人佣金]=" + (starTaskMaterialSwitch ? "开" : "关")
                + "/" + (allianceCommissionSwitch ? "开" : "关")
                + "，素材池翻页上限=" + materialPoolMaxRounds;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // ==================== getter / setter ====================

    public double getDefaultBudget() {
        return defaultBudget;
    }

    public void setDefaultBudget(double defaultBudget) {
        this.defaultBudget = defaultBudget;
    }

    public double getDefaultRoi2Goal() {
        return defaultRoi2Goal;
    }

    public void setDefaultRoi2Goal(double defaultRoi2Goal) {
        this.defaultRoi2Goal = defaultRoi2Goal;
    }

    public String getDefaultVideoScheduleType() {
        return defaultVideoScheduleType;
    }

    public void setDefaultVideoScheduleType(String defaultVideoScheduleType) {
        this.defaultVideoScheduleType = defaultVideoScheduleType;
    }

    public String getDefaultSmartBidType() {
        return defaultSmartBidType;
    }

    public void setDefaultSmartBidType(String defaultSmartBidType) {
        this.defaultSmartBidType = defaultSmartBidType;
    }

    public String getDefaultDeepExternalAction() {
        return defaultDeepExternalAction;
    }

    public void setDefaultDeepExternalAction(String defaultDeepExternalAction) {
        this.defaultDeepExternalAction = defaultDeepExternalAction;
    }

    public String getDefaultQcpxMode() {
        return defaultQcpxMode;
    }

    public void setDefaultQcpxMode(String defaultQcpxMode) {
        this.defaultQcpxMode = defaultQcpxMode;
    }

    public String getDefaultCreativeType() {
        return defaultCreativeType;
    }

    public void setDefaultCreativeType(String defaultCreativeType) {
        this.defaultCreativeType = defaultCreativeType;
    }

    public boolean isEnableAigcCreative() {
        return enableAigcCreative;
    }

    public void setEnableAigcCreative(boolean enableAigcCreative) {
        this.enableAigcCreative = enableAigcCreative;
    }

    public boolean isStarTaskMaterialSwitch() {
        return starTaskMaterialSwitch;
    }

    public void setStarTaskMaterialSwitch(boolean starTaskMaterialSwitch) {
        this.starTaskMaterialSwitch = starTaskMaterialSwitch;
    }

    public boolean isAllianceCommissionSwitch() {
        return allianceCommissionSwitch;
    }

    public void setAllianceCommissionSwitch(boolean allianceCommissionSwitch) {
        this.allianceCommissionSwitch = allianceCommissionSwitch;
    }

    public int getMaterialPoolMaxRounds() {
        return materialPoolMaxRounds;
    }

    public void setMaterialPoolMaxRounds(int materialPoolMaxRounds) {
        this.materialPoolMaxRounds = materialPoolMaxRounds;
    }

    public long getMaterialPoolPageIntervalMillis() {
        return materialPoolPageIntervalMillis;
    }

    public void setMaterialPoolPageIntervalMillis(long materialPoolPageIntervalMillis) {
        this.materialPoolPageIntervalMillis = materialPoolPageIntervalMillis;
    }
}
