package com.aolandai.dingtou.service.kol;

import com.aolandai.dingtou.client.QianchuanClientFactory;
import com.aolandai.dingtou.config.KolProperties;
import com.aolandai.dingtou.config.QianchuanProperties;
import com.aolandai.dingtou.dto.kol.KolAccountConfig;
import com.aolandai.dingtou.dto.kol.KolPlanCostRequest;
import com.aolandai.dingtou.dto.kol.PlanCostQueryResult;
import com.aolandai.dingtou.dto.qianchuan.PlanAweme;
import com.aolandai.dingtou.dto.qianchuan.PlanAwemeLookupResult;
import com.aolandai.dingtou.dto.qianchuan.PlanRef;
import com.aolandai.dingtou.exception.RateLimitException;
import com.aolandai.dingtou.service.qianchuan.PlanAwemeService;
import com.aolandai.dingtou.service.qianchuan.QianchuanPlanService;
import com.aolandai.dingtou.source.KolPatrolConfigReader;
import com.bytedance.ads.ApiException;
import com.bytedance.ads.model.QianchuanUniPromotionListV10AdlabScene;
import com.bytedance.ads.model.QianchuanUniPromotionListV10DataAdListAdInfoAdlabScene;
import com.bytedance.ads.model.QianchuanUniPromotionListV10DataAdListAdInfoSmartBidType;
import com.bytedance.ads.model.QianchuanUniPromotionListV10FilteringHavingCost;
import com.bytedance.ads.model.QianchuanUniPromotionListV10FilteringStatus;
import com.bytedance.ads.model.QianchuanUniPromotionListV10ResponseDataAdListInner;
import com.bytedance.ads.model.QianchuanUniPromotionListV10ResponseDataAdListInnerAdInfo;
import com.bytedance.ads.model.QianchuanUniPromotionListV10ResponseDataAdListInnerStatsInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultKolPlanCostService} 单测：用 Mockito 隔离配置表读取与千川 SDK，<b>绝不真调千川</b>。
 *
 * <p>覆盖：阈值过滤（含 <b>&gt;=</b> 边界）、多账户聚合、某账户失败进 warnings、
 * 配置缺失/填错要留痕、接口没返回 stat_cost 不判达标、结果按消耗降序。</p>
 */
class DefaultKolPlanCostServiceTest {

    private static final long ACC_1 = 1823379540880396L; // 官方【母婴商品】
    private static final long ACC_2 = 1771298760893511L; // 官方【孕产-商品】

    /** 阈值 2000 元 → 200_000_000（千分之一分）。 */
    private static final long THRESHOLD_2000_RAW = 200_000_000L;

    private KolPatrolConfigReader configReader;
    private QianchuanClientFactory clientFactory;
    private QianchuanPlanService planService;
    private PlanAwemeService planAwemeService;

    @BeforeEach
    void setUp() throws Exception {
        configReader = mock(KolPatrolConfigReader.class);
        clientFactory = mock(QianchuanClientFactory.class);
        planService = mock(QianchuanPlanService.class);
        planAwemeService = mock(PlanAwemeService.class);
        when(clientFactory.planService()).thenReturn(planService);
        // 默认：没有达人信息可补（withAweme=false 时压根不会被调用）
        when(planAwemeService.lookupAll(any())).thenReturn(emptyAwemeResult());
    }

    private DefaultKolPlanCostService newService(int windowMonths) {
        KolProperties props = new KolProperties();
        props.setCostWindowMonths(windowMonths);
        return new DefaultKolPlanCostService(configReader, clientFactory, props,
                new QianchuanProperties(), planAwemeService);
    }

    private static PlanAwemeLookupResult emptyAwemeResult() {
        return new PlanAwemeLookupResult(0, 0, List.of(), List.of());
    }

    // ==================== 过滤 ====================

    @Test
    @DisplayName("只返回消耗达标的计划，未达标的被滤掉")
    void filtersByThreshold() throws Exception {
        when(configReader.readAll()).thenReturn(List.of(
                new KolAccountConfig("官方【母婴商品】", String.valueOf(ACC_1), 2000, 2)));
        when(planService.queryAllPlans(anyLong(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(
                        plan(111L, "达标-花得多", 3000.0),
                        plan(222L, "未达标-花得少", 500.0),
                        plan(333L, "达标-刚好超一点", 2000.5)));

        PlanCostQueryResult result = newService(1).query(LocalDate.of(2026, 9, 4),
                LocalDate.of(2026, 10, 3));

        assertEquals(2, result.size(), "应只返回 2 个达标计划: " + result.rows());
        assertEquals(1, result.accountsScanned());
        assertEquals(3, result.plansScanned(), "过滤前应统计到 3 个");
        assertTrue(result.warnings().isEmpty(), "不应有 warning：" + result.warnings());
    }

    @Test
    @DisplayName("★ 边界：消耗恰好等于阈值 → 达标（>=，不是 >）")
    void exactlyAtThresholdIsIncluded() throws Exception {
        when(configReader.readAll()).thenReturn(List.of(
                new KolAccountConfig("官方【母婴商品】", String.valueOf(ACC_1), 2000, 2)));
        when(planService.queryAllPlans(anyLong(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(plan(111L, "刚好 2000 元", 2000.0)));

        PlanCostQueryResult result = newService(1).query(LocalDate.of(2026, 9, 4),
                LocalDate.of(2026, 10, 3));

        assertEquals(1, result.size(), "恰好等于阈值必须算达标（需求：>=）");
        assertEquals(2000.0, result.rows().get(0).statCost());
    }

    @Test
    @DisplayName("返回的 statCost 已换算成【元】，不是原始千分之一分")
    void statCostIsConvertedToYuan() throws Exception {
        when(configReader.readAll()).thenReturn(List.of(
                new KolAccountConfig("官方【母婴商品】", String.valueOf(ACC_1), 2000, 2)));
        // helper 收「元」，内部按 100000 换算成接口原始值（千分之一分）
        when(planService.queryAllPlans(anyLong(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(plan(111L, "计划A", 2500.5)));

        PlanCostQueryResult result = newService(1).query(LocalDate.of(2026, 9, 4),
                LocalDate.of(2026, 10, 3));

        assertEquals(2500.5, result.rows().get(0).statCost(),
                "返回的 statCost 必须是【元】（原始值 250050000 → 2500.5 元）");
    }

    @Test
    @DisplayName("★ 乘方计划 → smartBidType 直接输出「乘方」（业务约定，不是接口原值）")
    void overallProjectIsLabelledChengfang() throws Exception {
        when(configReader.readAll()).thenReturn(List.of(
                new KolAccountConfig("官方【母婴商品】", String.valueOf(ACC_1), 2000, 2)));
        when(planService.queryAllPlans(anyLong(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(planWithScene(111L, "乘方计划", 3000.0, "SMART_BID_CUSTOM",
                        QianchuanUniPromotionListV10DataAdListAdInfoAdlabScene.OVERALL_PROJECT)));

        PlanCostQueryResult result = newService(1).query(LocalDate.of(2026, 9, 4),
                LocalDate.of(2026, 10, 3));

        assertEquals("乘方", result.rows().get(0).smartBidType(),
                "乘方计划按业务约定输出「乘方」，而不是出价方式原值 SMART_BID_CUSTOM");
    }

    @Test
    @DisplayName("全域计划 → smartBidType 保持接口原值（不被改成「乘方」）")
    void uniProjectKeepsRawSmartBidType() throws Exception {
        when(configReader.readAll()).thenReturn(List.of(
                new KolAccountConfig("官方【母婴商品】", String.valueOf(ACC_1), 2000, 2)));
        when(planService.queryAllPlans(anyLong(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(planWithScene(222L, "全域计划", 3000.0, "SMART_BID_CUSTOM",
                        QianchuanUniPromotionListV10DataAdListAdInfoAdlabScene.UNI_PROJECT)));

        PlanCostQueryResult result = newService(1).query(LocalDate.of(2026, 9, 4),
                LocalDate.of(2026, 10, 3));

        assertEquals("SMART_BID_CUSTOM", result.rows().get(0).smartBidType(),
                "全域计划保持接口原值");
    }

    @Test
    @DisplayName("全域计划接口没返回出价方式 → null（不猜、也不返回空串）")
    void missingSmartBidTypeIsNull() throws Exception {
        when(configReader.readAll()).thenReturn(List.of(
                new KolAccountConfig("官方【母婴商品】", String.valueOf(ACC_1), 2000, 2)));
        when(planService.queryAllPlans(anyLong(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(planWithScene(333L, "全域计划", 3000.0, null,
                        QianchuanUniPromotionListV10DataAdListAdInfoAdlabScene.UNI_PROJECT)));

        PlanCostQueryResult result = newService(1).query(LocalDate.of(2026, 9, 4),
                LocalDate.of(2026, 10, 3));

        assertNull(result.rows().get(0).smartBidType(),
                "接口没返回时应为 null（Jackson 会省略该字段），不能是空串");
    }

    @Test
    @DisplayName("多账户聚合，结果按消耗【降序】（花得最多的排最前）")
    void aggregatesAccountsAndSortsByCostDesc() throws Exception {
        when(configReader.readAll()).thenReturn(List.of(
                new KolAccountConfig("官方【母婴商品】", String.valueOf(ACC_1), 2000, 2),
                new KolAccountConfig("官方【孕产-商品】", String.valueOf(ACC_2), 2000, 3)));
        when(planService.queryAllPlans(eq(ACC_1), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(plan(111L, "账户1-计划", 3000.0)));
        when(planService.queryAllPlans(eq(ACC_2), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(plan(222L, "账户2-计划", 9000.0)));

        PlanCostQueryResult result = newService(1).query(LocalDate.of(2026, 9, 4),
                LocalDate.of(2026, 10, 3));

        assertEquals(2, result.accountsScanned());
        assertEquals(2, result.size());
        assertEquals(9000.0, result.rows().get(0).statCost(), "消耗高的应排前面");
        assertEquals(3000.0, result.rows().get(1).statCost());
        assertEquals("官方【孕产-商品】", result.rows().get(0).advertiserName(),
                "账户名必须来自配置表");
    }

    // ==================== 查询口径 ====================

    @Test
    @DisplayName("查询口径：DELIVERY_OK + having_cost=YES + 全域/乘方两个场景 + 时间窗按天补齐时分秒")
    void queryUsesRequiredFiltersAndWindow() throws Exception {
        when(configReader.readAll()).thenReturn(List.of(
                new KolAccountConfig("官方【母婴商品】", String.valueOf(ACC_1), 2000, 2)));
        when(planService.queryAllPlans(anyLong(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        newService(1).query(LocalDate.of(2026, 9, 4), LocalDate.of(2026, 10, 3));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<QianchuanUniPromotionListV10AdlabScene>> scenesCaptor =
                ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> startCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> endCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<QianchuanUniPromotionListV10FilteringStatus> statusCaptor =
                ArgumentCaptor.forClass(QianchuanUniPromotionListV10FilteringStatus.class);
        ArgumentCaptor<QianchuanUniPromotionListV10FilteringHavingCost> havingCostCaptor =
                ArgumentCaptor.forClass(QianchuanUniPromotionListV10FilteringHavingCost.class);
        ArgumentCaptor<Long> thresholdCaptor = ArgumentCaptor.forClass(Long.class);

        verify(planService).queryAllPlans(eq(ACC_1), scenesCaptor.capture(),
                startCaptor.capture(), endCaptor.capture(),
                statusCaptor.capture(), havingCostCaptor.capture(), thresholdCaptor.capture());

        assertEquals(List.of(QianchuanUniPromotionListV10AdlabScene.UNI_PROJECT,
                        QianchuanUniPromotionListV10AdlabScene.OVERALL_PROJECT),
                scenesCaptor.getValue(), "必须同时查全域与乘方");
        assertEquals("2026-09-04 00:00:00", startCaptor.getValue(), "首日应从 00:00:00 起");
        assertEquals("2026-10-03 23:59:59", endCaptor.getValue(), "末日应到 23:59:59 止");
        assertEquals(QianchuanUniPromotionListV10FilteringStatus.DELIVERY_OK, statusCaptor.getValue());
        assertEquals(QianchuanUniPromotionListV10FilteringHavingCost.YES, havingCostCaptor.getValue());
        assertEquals(Long.valueOf(THRESHOLD_2000_RAW), thresholdCaptor.getValue(),
                "必须把阈值传给下层 —— 否则不会按消耗降序提前停止翻页（26 页 vs 1 页）");
    }

    @Test
    @DisplayName("默认窗口 = 昨天往前推 N 个月（N=1：2026-10-04 → 09-04 ~ 10-03）")
    void defaultWindowIsLastNMonths() throws Exception {
        when(configReader.readAll()).thenReturn(List.of());
        when(planService.queryAllPlans(anyLong(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        // 用真实「今天」推导，断言窗口长度与「结束 = 昨天」这条规则
        LocalDate today = LocalDate.now();
        PlanCostQueryResult result = newService(1).query();

        assertEquals(today.minusDays(1) + " 23:59:59", result.windowEnd());
        assertEquals(today.minusMonths(1) + " 00:00:00", result.windowStart());
    }

    // ==================== 容错与留痕 ====================

    @Test
    @DisplayName("某账户查询失败 → 记 warning，其它账户照常返回（不中断整轮）")
    void accountErrorGoesToWarningsAndContinues() throws Exception {
        when(configReader.readAll()).thenReturn(List.of(
                new KolAccountConfig("官方【母婴商品】", String.valueOf(ACC_1), 2000, 2),
                new KolAccountConfig("官方【孕产-商品】", String.valueOf(ACC_2), 2000, 3)));
        when(planService.queryAllPlans(eq(ACC_1), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(plan(111L, "账户1-计划", 3000.0)));
        when(planService.queryAllPlans(eq(ACC_2), any(), any(), any(), any(), any(), any()))
                .thenThrow(new ApiException(40000, "仅支持查询近180天以内的数据"));

        PlanCostQueryResult result = newService(1).query(LocalDate.of(2026, 9, 4),
                LocalDate.of(2026, 10, 3));

        assertEquals(2, result.accountsScanned());
        assertEquals(1, result.size(), "ACC_1 的达标计划仍应返回");
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("官方【孕产-商品】")),
                "失败账户必须进 warnings：" + result.warnings());
    }

    @Test
    @DisplayName("⚠️ 频控必须上抛（吞掉会把『被拦住的账户』误判成『没有达标计划』）")
    void rateLimitIsPropagated() throws Exception {
        when(configReader.readAll()).thenReturn(List.of(
                new KolAccountConfig("官方【母婴商品】", String.valueOf(ACC_1), 2000, 2)));
        when(planService.queryAllPlans(anyLong(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RateLimitException(RateLimitException.Scope.GLOBAL,
                        "系统请求频率超限", 0L));

        assertThrows(RateLimitException.class, () -> newService(1).query(
                LocalDate.of(2026, 9, 4), LocalDate.of(2026, 10, 3)));
    }

    @Test
    @DisplayName("阈值没填 → 跳过该账户 + warning（不能当成『该账户没有达标计划』）")
    void missingThresholdSkipsAccountWithWarning() throws Exception {
        when(configReader.readAll()).thenReturn(List.of(
                new KolAccountConfig("官方【母婴商品】", String.valueOf(ACC_1), Double.NaN, 2)));

        PlanCostQueryResult result = newService(1).query(LocalDate.of(2026, 9, 4),
                LocalDate.of(2026, 10, 3));

        assertEquals(0, result.accountsScanned());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("没有填消耗金额阈值")),
                "必须留痕：" + result.warnings());
    }

    @Test
    @DisplayName("账户 ID 缺失 → 跳过该账户 + warning")
    void missingAdvertiserIdSkipsAccountWithWarning() throws Exception {
        when(configReader.readAll()).thenReturn(List.of(
                new KolAccountConfig("官方【母婴商品】", "", 2000, 2)));

        PlanCostQueryResult result = newService(1).query(LocalDate.of(2026, 9, 4),
                LocalDate.of(2026, 10, 3));

        assertEquals(0, result.accountsScanned());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("没有账户 ID")),
                "必须留痕：" + result.warnings());
    }

    @Test
    @DisplayName("接口没返回 stat_cost → 不判达标，但要记 warning（不能当成消耗为 0）")
    void missingStatCostIsNotTreatedAsZero() throws Exception {
        when(configReader.readAll()).thenReturn(List.of(
                new KolAccountConfig("官方【母婴商品】", String.valueOf(ACC_1), 2000, 2)));
        when(planService.queryAllPlans(anyLong(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(plan(111L, "没有消耗字段", null)));

        PlanCostQueryResult result = newService(1).query(LocalDate.of(2026, 9, 4),
                LocalDate.of(2026, 10, 3));

        assertEquals(0, result.size(), "拿不到消耗不能算达标");
        assertEquals(1, result.plansScanned(), "但接口确实返回了 1 个");
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("stat_cost")),
                "必须留痕：" + result.warnings());
    }

    // ==================== 达人信息（withAweme） ====================

    @Test
    @DisplayName("withAweme=false（默认）→ 不查达人信息，字段为空")
    void withoutAwemeDoesNotCallLookup() throws Exception {
        when(configReader.readAll()).thenReturn(List.of(
                new KolAccountConfig("官方【母婴商品】", String.valueOf(ACC_1), 2000, 2)));
        when(planService.queryAllPlans(anyLong(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(plan(111L, "计划A", 3000.0)));

        PlanCostQueryResult result = newService(1).query(LocalDate.of(2026, 9, 4),
                LocalDate.of(2026, 10, 3));

        assertEquals(1, result.size());
        assertNull(result.rows().get(0).awemeShowId(), "未查达人 → 应为 null（Jackson 会省略该字段）");
        assertNull(result.rows().get(0).awemeName());
        verify(planAwemeService, never()).lookupAll(any());
    }

    @Test
    @DisplayName("withAweme=true → 批量查一次，抖音号/抖音名被填进对应行")
    void withAwemeFillsAwemeFields() throws Exception {
        when(configReader.readAll()).thenReturn(List.of(
                new KolAccountConfig("官方【母婴商品】", String.valueOf(ACC_1), 2000, 2)));
        when(planService.queryAllPlans(anyLong(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(plan(111L, "计划A", 3000.0)));
        when(planAwemeService.lookupAll(any())).thenReturn(new PlanAwemeLookupResult(
                1, 1, List.of(new PlanAweme(111L, "610129yiyi", "伊伊麻麻")), List.of()));

        PlanCostQueryResult result = newService(1).query(
                new KolPlanCostRequest(LocalDate.of(2026, 9, 4), LocalDate.of(2026, 10, 3), true));

        assertEquals(1, result.size());
        assertEquals("610129yiyi", result.rows().get(0).awemeShowId());
        assertEquals("伊伊麻麻", result.rows().get(0).awemeName());
        verify(planAwemeService).lookupAll(any());
    }

    @Test
    @DisplayName("★ 达人信息按 adId 匹配回填，【不按下标】—— 中间有查不到时不能错位")
    void withAwemeMatchesByAdIdNotIndex() throws Exception {
        when(configReader.readAll()).thenReturn(List.of(
                new KolAccountConfig("官方【母婴商品】", String.valueOf(ACC_1), 2000, 2)));
        when(planService.queryAllPlans(anyLong(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(
                        plan(111L, "计划A", 5000.0),
                        plan(222L, "计划B", 4000.0),   // ← 这个查不到达人
                        plan(333L, "计划C", 3000.0)));
        // items 只包含 111 与 333（模拟 222 查失败被跳过）
        when(planAwemeService.lookupAll(any())).thenReturn(new PlanAwemeLookupResult(
                3, 2,
                List.of(new PlanAweme(111L, "id-A", "达人A"),
                        new PlanAweme(333L, "id-C", "达人C")),
                List.of("计划 账户:222 查询失败")));

        PlanCostQueryResult result = newService(1).query(
                new KolPlanCostRequest(LocalDate.of(2026, 9, 4), LocalDate.of(2026, 10, 3), true));

        // 排序后：5000(A) > 4000(B) > 3000(C)
        assertEquals("id-A", result.rows().get(0).awemeShowId(), "A 应对上 A");
        assertNull(result.rows().get(1).awemeShowId(), "B 查不到 → 保持 null（不能错位成 C）");
        assertEquals("id-C", result.rows().get(2).awemeShowId(), "C 应对上 C");
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("222")),
                "查失败的 warning 必须透传：" + result.warnings());
    }

    @Test
    @DisplayName("withAweme=true 且没有任何达标计划 → 不发起达人查询")
    void withAwemeSkipsLookupWhenNoQualifiedPlan() throws Exception {
        when(configReader.readAll()).thenReturn(List.of(
                new KolAccountConfig("官方【母婴商品】", String.valueOf(ACC_1), 2000, 2)));
        when(planService.queryAllPlans(anyLong(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(plan(111L, "未达标", 100.0)));

        PlanCostQueryResult result = newService(1).query(
                new KolPlanCostRequest(LocalDate.of(2026, 9, 4), LocalDate.of(2026, 10, 3), true));

        assertEquals(0, result.size());
        verify(planAwemeService, never()).lookupAll(any());
    }

    @Test
    @DisplayName("withAweme=true 时传给达人查询的是【advertiserId + adId】成对引用")
    void withAwemePassesPlanRefs() throws Exception {
        when(configReader.readAll()).thenReturn(List.of(
                new KolAccountConfig("官方【母婴商品】", String.valueOf(ACC_1), 2000, 2)));
        when(planService.queryAllPlans(anyLong(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(plan(111L, "计划A", 3000.0)));

        newService(1).query(
                new KolPlanCostRequest(LocalDate.of(2026, 9, 4), LocalDate.of(2026, 10, 3), true));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PlanRef>> captor = ArgumentCaptor.forClass(List.class);
        verify(planAwemeService).lookupAll(captor.capture());
        assertEquals(List.of(new PlanRef(ACC_1, 111L)), captor.getValue(),
                "必须带上 advertiserId（详情接口是账户维度鉴权，只有 adId 就得遍历 8 个账户）");
    }

    // ==================== 工具 ====================

    /**
     * 在 {@link #plan} 基础上补出价方式与场景。
     *
     * @param smartBidType 出价方式原值；{@code null} 表示接口没返回
     * @param scene        计划场景（{@code OVERALL_PROJECT}=乘方 / {@code UNI_PROJECT}=全域）
     */
    private static QianchuanUniPromotionListV10ResponseDataAdListInner planWithScene(
            long adId, String name, Double statCostYuan, String smartBidType,
            QianchuanUniPromotionListV10DataAdListAdInfoAdlabScene scene) {
        QianchuanUniPromotionListV10ResponseDataAdListInner inner = plan(adId, name, statCostYuan);
        if (smartBidType != null) {
            inner.getAdInfo().setSmartBidType(
                    QianchuanUniPromotionListV10DataAdListAdInfoSmartBidType.fromValue(smartBidType));
        }
        inner.getAdInfo().setAdlabScene(scene);
        return inner;
    }

    private static QianchuanUniPromotionListV10ResponseDataAdListInner plan(
            long adId, String name, Double statCostYuan) {
        QianchuanUniPromotionListV10ResponseDataAdListInnerAdInfo adInfo =
                new QianchuanUniPromotionListV10ResponseDataAdListInnerAdInfo();
        adInfo.setId(adId);
        adInfo.setName(name);

        QianchuanUniPromotionListV10ResponseDataAdListInner inner =
                new QianchuanUniPromotionListV10ResponseDataAdListInner();
        inner.setAdInfo(adInfo);
        if (statCostYuan != null) {
            QianchuanUniPromotionListV10ResponseDataAdListInnerStatsInfo stats =
                    new QianchuanUniPromotionListV10ResponseDataAdListInnerStatsInfo();
            // 入参是「元」，按单位换算成接口原始值（千分之一分）
            stats.setStatCost(statCostYuan * 100_000);
            inner.setStatsInfo(stats);
        }
        return inner;
    }
}
