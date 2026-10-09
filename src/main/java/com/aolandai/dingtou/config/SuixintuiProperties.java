package com.aolandai.dingtou.config;

import com.aolandai.dingtou.dto.qianchuan.SuixintuiConstraints;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * A 场景（建随心推）的默认参数（前缀 {@code dingtou.suixintui}）。
 *
 * <pre>
 * dingtou:
 *   suixintui:
 *     default-amount: 200            # 预算（元，[100,5000000] 且 10 的倍数）
 *     default-delivery-time: 24      # 投放时长（小时，白名单 10 个值）
 *     default-bid-type: MANUAL_BID   # AUTO_BID / MANUAL_BID；留空=不传
 *     default-roi-goal: 1.7          # 支付ROI（[0.01,10000] 且最多 2 位小数）；MANUAL_BID 时必填
 *     default-qcpx-mode: ""          # QCPX_MODE_ON / _OFF / _DEFAULT；留空=不传
 * </pre>
 *
 * <h2>⚠️ 为什么这些参数是「配置默认值」而不是「影刀传入」</h2>
 * <p>老板 2026-10-05 拍板：预算、时长、出价方式、ROI 目标<b>都配在服务端</b>，影刀不用每次传。
 * 好处是影刀侧调用最简单；代价是<b>改这些值要改配置并重启服务</b>。</p>
 *
 * <h2>单位与取值约束（2026-10-05 官方文档确认，不再是推断）</h2>
 * <table border="1">
 *   <tr><th>字段</th><th>类型</th><th>单位</th><th>约束</th></tr>
 *   <tr><td>{@code amount}</td><td>number</td><td><b>元</b></td>
 *       <td>必填；取值范围 <b>[100, 5000000]</b>，且<b>必须是 10 的倍数</b></td></tr>
 *   <tr><td>{@code delivery_time}</td><td>double</td><td><b>小时</b></td>
 *       <td>必填；<b>只能是白名单 10 个值</b>：2/6/12/24/48/72/96/120/144/168</td></tr>
 *   <tr><td>{@code roi_goal}</td><td>double</td><td>支付 ROI</td>
 *       <td><b>条件必填</b>；<b>最多两位小数</b>，范围 <b>[0.01, 10000]</b>；
 *           <b>当 {@code marketing_goal=VIDEO_PROM_GOODS} 且 {@code bid_type=MANUAL_BID} 时必填</b></td></tr>
 * </table>
 *
 * <p>⚠️ 约束集中在 {@code dto/qianchuan/SuixintuiConstraints}，本类只负责<b>启动时自检</b>
 * （见 {@link #isAmountValid()} / {@link #isDeliveryTimeValid()} / {@link #isRoiGoalValid()}）——
 * 配错了要在启动时就炸，<b>不能拖到花钱路径上才发现</b>。</p>
 *
 * <p>⚠️ 尽管单位已确认，本任务仍<b>默认 dry-run</b>：第一次跑会打印组装好的请求体，
 * 便于与千川后台的实际效果对一眼。建随心推是<b>花钱</b>的，多核一次不吃亏。</p>
 */
@Validated
@ConfigurationProperties(prefix = "dingtou.suixintui")
public class SuixintuiProperties {

    /**
     * 建随心推的<b>默认预算</b>，<b>单位：元</b>（老板 2026-10-05 定：200 元）。
     *
     * <p>⚠️ 官方约束（见 {@link #isAmountValid()}）：必填、范围 <b>[100, 5000000]</b>、
     * 且<b>必须是 10 的倍数</b>。</p>
     */
    @Positive
    private long defaultAmount = 200L;

    /**
     * 建随心推的<b>默认投放时长</b>，<b>单位：小时</b>（老板 2026-10-05 定：24 小时 = 1 天）。
     *
     * <p>⚠️ 官方约束（见 {@link #isDeliveryTimeValid()}）：必填、且<b>只能是白名单 10 个值</b> ——
     * 2 / 6 / 12 / 24 / 48 / 72 / 96 / 120 / 144 / 168。<b>不是任意小时数</b>。</p>
     */
    @Positive
    private double defaultDeliveryTime = 24.0;

    /**
     * 建随心推的<b>默认出价方式</b>（老板 2026-10-05 定：{@code MANUAL_BID} 手动出价）。
     *
     * <p>取值只能是 {@code AUTO_BID} / {@code MANUAL_BID}，或留空 = 不传该字段
     * （见 {@link #isBidTypeValid()}）。</p>
     *
     * <p>⚠️ <b>{@code MANUAL_BID} 会让 {@code roi_goal} 变成必填</b> ——
     * 官方规定「{@code marketing_goal=VIDEO_PROM_GOODS} 且 {@code bid_type=MANUAL_BID} 时
     * {@code roi_goal} 必填」，而本服务的 {@code marketing_goal} 恒为 {@code VIDEO_PROM_GOODS}
     * → 所以这两个配置项<b>必须成对改</b>，见 {@link #isRoiGoalValid()}。</p>
     */
    private String defaultBidType = SuixintuiConstraints.BID_TYPE_MANUAL;

    /**
     * 乘方模式：{@code QCPX_MODE_ON} / {@code QCPX_MODE_OFF} / {@code QCPX_MODE_DEFAULT}；
     * 留空 = 不传该字段。
     *
     * <p>⚠️ A 场景是「<b>随心推</b>」，不是乘方计划，所以默认<b>不传</b>（用平台默认）。
     * 只有确认需要时才打开。</p>
     *
     * <p>⚠️ 注意取值是 {@code QCPX_MODE_*} 而不是 {@code ON/OFF}（见 {@link #isQcpxModeValid()}）。</p>
     */
    private String defaultQcpxMode = "";

    /**
     * 建随心推的<b>默认支付 ROI 目标</b>（老板 2026-10-05 定：{@code 1.7}）。
     *
     * <p>⚠️ 官方约束（见 {@link #isRoiGoalValid()}）：<b>最多两位小数</b>、范围 <b>[0.01, 10000]</b>，
     * 且<b>当 {@code bid_type=MANUAL_BID} 时必填</b>（本服务的 {@code marketing_goal}
     * 恒为 {@code VIDEO_PROM_GOODS}，条件必然成立）。</p>
     *
     * <p>⚠️ {@code null} = 不传该字段，但那只在 {@code AUTO_BID} 下才合法。</p>
     */
    private Double defaultRoiGoal = 1.7;

    /**
     * 「账户 → 已授权达人列表」的缓存秒数。
     *
     * <p>建一单要查一次授权达人列表（用于把抖音号换成数值 {@code awemeId}），
     * 而同一批任务会反复查同一账户 —— 不缓存会白打很多次接口。</p>
     */
    @PositiveOrZero
    private long awemeUidCacheSeconds = 600L;

    /**
     * 枚举值自检：配错了要在<b>启动时</b>炸，而不是等影刀真调时才发现。
     *
     * <p>⚠️ 更不能拖到「组装请求体」时才炸 —— 那时已经走到花钱路径上了。</p>
     */
    @AssertTrue(message = "dingtou.suixintui.default-bid-type 只能是 AUTO_BID / MANUAL_BID（或留空）")
    public boolean isBidTypeValid() {
        return isBlank(defaultBidType) || SuixintuiConstraints.BID_TYPE_AUTO.equals(defaultBidType)
                || SuixintuiConstraints.BID_TYPE_MANUAL.equals(defaultBidType);
    }

    /**
     * ⚠️ 取值必须是 <b>{@code QCPX_MODE_ON} / {@code QCPX_MODE_OFF} / {@code QCPX_MODE_DEFAULT}</b>，
     * <b>不是</b> {@code ON}/{@code OFF}。
     *
     * <p>实测（javap 逐字节核对）：SDK 枚举的常量名是 {@code ON}，但<b>它接受的字符串值</b>是
     * {@code QCPX_MODE_ON} —— 传 {@code ON} 会抛 {@code IllegalArgumentException: Unexpected value 'ON'}。
     * （与 {@code smartBidType} 同类的坑：常量名 ≠ 接口值。）</p>
     */
    @AssertTrue(message = "dingtou.suixintui.default-qcpx-mode 只能是 QCPX_MODE_ON / QCPX_MODE_OFF / "
            + "QCPX_MODE_DEFAULT（或留空）—— 注意不是 ON/OFF")
    public boolean isQcpxModeValid() {
        return isBlank(defaultQcpxMode) || "QCPX_MODE_ON".equals(defaultQcpxMode)
                || "QCPX_MODE_OFF".equals(defaultQcpxMode)
                || "QCPX_MODE_DEFAULT".equals(defaultQcpxMode);
    }

    /**
     * 预算自检：官方要求 <b>[100, 5000000] 且为 10 的倍数</b>。
     *
     * <p>⚠️ 这条必须在<b>启动时</b>拦住。等到「组装请求体」才发现，说明已经走在
     * 花钱路径上了；而更坏的情况是接口没拒、<b>按一个错的预算真建了单</b>。</p>
     */
    @AssertTrue(message = "dingtou.suixintui.default-amount 不合法：必须是 [100, 5000000] 且为 10 的倍数（单位：元）")
    public boolean isAmountValid() {
        return SuixintuiConstraints.isAmountAllowed(defaultAmount);
    }

    /**
     * 投放时长自检：官方是<b>白名单</b>，不是范围。
     *
     * <p>⚠️ 填 {@code 3} / {@code 36} 这类「看起来合理的小时数」会被接口拒 ——
     * 所以不能在配置里自由发挥。</p>
     */
    @AssertTrue(message = "dingtou.suixintui.default-delivery-time 不合法：只能是 2/6/12/24/48/72/96/120/144/168（单位：小时）")
    public boolean isDeliveryTimeValid() {
        return SuixintuiConstraints.isDeliveryTimeAllowed(defaultDeliveryTime);
    }

    /**
     * ROI 目标自检：官方要求<b>最多两位小数</b>、范围 <b>[0.01, 10000]</b>，
     * 且 <b>{@code bid_type=MANUAL_BID} 时必填</b>。
     *
     * <p>⚠️ 这条是<b>跨字段</b>校验：{@code default-bid-type} 与 {@code default-roi-goal}
     * 必须<b>成对改</b> —— 只把出价方式改成 {@code MANUAL_BID} 而不给 ROI 目标，
     * 启动就会失败（这是好事：否则会带着一个必填缺失的请求体走到花钱那一步）。</p>
     */
    @AssertTrue(message = "dingtou.suixintui.default-roi-goal 不合法：须为 [0.01, 10000] 且最多两位小数；"
            + "且当 default-bid-type=MANUAL_BID 时必填（本服务 marketing_goal 恒为 VIDEO_PROM_GOODS）")
    public boolean isRoiGoalValid() {
        return SuixintuiConstraints.roiGoalProblem(defaultRoiGoal, defaultBidType) == null;
    }

    /** 启动横幅用。⚠️ 只打印参数，不含任何凭据。 */
    public String describe() {
        return "默认预算=" + defaultAmount + "元"
                + "，默认时长=" + defaultDeliveryTime + "小时"
                + "，出价方式=" + (isBlank(defaultBidType) ? "（不传）" : defaultBidType)
                + "，乘方模式=" + (isBlank(defaultQcpxMode) ? "（不传）" : defaultQcpxMode)
                + "，ROI目标=" + (defaultRoiGoal == null ? "（不传）" : defaultRoiGoal)
                + "，授权达人缓存=" + awemeUidCacheSeconds + "s";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // ==================== getter / setter ====================

    public long getDefaultAmount() {
        return defaultAmount;
    }

    public void setDefaultAmount(long defaultAmount) {
        this.defaultAmount = defaultAmount;
    }

    public double getDefaultDeliveryTime() {
        return defaultDeliveryTime;
    }

    public void setDefaultDeliveryTime(double defaultDeliveryTime) {
        this.defaultDeliveryTime = defaultDeliveryTime;
    }

    public String getDefaultBidType() {
        return defaultBidType;
    }

    public void setDefaultBidType(String defaultBidType) {
        this.defaultBidType = defaultBidType;
    }

    public String getDefaultQcpxMode() {
        return defaultQcpxMode;
    }

    public void setDefaultQcpxMode(String defaultQcpxMode) {
        this.defaultQcpxMode = defaultQcpxMode;
    }

    public Double getDefaultRoiGoal() {
        return defaultRoiGoal;
    }

    public void setDefaultRoiGoal(Double defaultRoiGoal) {
        this.defaultRoiGoal = defaultRoiGoal;
    }

    public long getAwemeUidCacheSeconds() {
        return awemeUidCacheSeconds;
    }

    public void setAwemeUidCacheSeconds(long awemeUidCacheSeconds) {
        this.awemeUidCacheSeconds = awemeUidCacheSeconds;
    }
}
