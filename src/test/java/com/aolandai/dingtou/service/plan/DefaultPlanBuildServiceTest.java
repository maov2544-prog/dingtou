package com.aolandai.dingtou.service.plan;

import com.aolandai.dingtou.client.QianchuanClientFactory;
import com.aolandai.dingtou.dto.CountResult;
import com.aolandai.dingtou.dto.kol.SuixintuiInvestResult;
import com.aolandai.dingtou.service.aggregate.CumulativeCountService;
import com.aolandai.dingtou.service.aggregate.CountSource;
import com.aolandai.dingtou.service.kol.SuixintuiInvestService;
import com.aolandai.dingtou.config.DingtouProperties;
import com.aolandai.dingtou.config.PlanProperties;
import com.aolandai.dingtou.dto.plan.PlanBuildParam;
import com.aolandai.dingtou.dto.plan.PlanBuildResult;
import com.aolandai.dingtou.dto.qianchuan.MaterialRef;
import com.aolandai.dingtou.dto.qianchuan.PlanInfo;
import com.aolandai.dingtou.dto.qianchuan.PlanQueryOutcome;
import com.aolandai.dingtou.dto.qianchuan.PlanWriteOutcome;
import com.aolandai.dingtou.dto.sheet.DeliveryRowRef;
import com.aolandai.dingtou.exception.BizException;
import com.aolandai.dingtou.service.qianchuan.MaterialPoolService;
import com.aolandai.dingtou.service.qianchuan.PlanMaterialAddService;
import com.aolandai.dingtou.service.qianchuan.PlanQueryService;
import com.aolandai.dingtou.service.qianchuan.PlanRenameService;
import com.aolandai.dingtou.service.qianchuan.QianchuanOverallVideoCreateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultPlanBuildService} 单测：全部依赖都 mock，<b>不读真表、不调接口、不花钱</b>。
 *
 * <p>覆盖两条主路径（建计划 / 复用计划）+ 三条「宁可中止也不猜」的保护。</p>
 */
class DefaultPlanBuildServiceTest {

    private static final String AWEME = "610129yiyi";
    private static final String PRODUCT = "护臀膏";
    private static final String ACC_NAME = "官方【母婴商品】";
    private static final long ACC_ID = 1823379540880396L;
    private static final long AWEME_UID = 2071074583877891L;
    private static final long PRODUCT_ID = 3593275488396811650L;
    private static final long AD_ID = 1877746196794116L;
    private static final long VIDEO_ID = 7687379351705059825L;
    private static final LocalDate DATE = LocalDate.of(2026, 10, 5);

    private PlanTargetResolver resolver;
    private PlanQueryService planQueryService;
    private MaterialPoolService materialPoolService;
    private PlanMaterialAddService materialAddService;
    private PlanRenameService renameService;
    private QianchuanClientFactory clientFactory;
    private DingtouProperties dingtouProperties;
    private CumulativeCountService countService;
    private SuixintuiInvestService suixintuiInvestService;
    private DefaultPlanBuildService service;

    @BeforeEach
    void setUp() {
        resolver = mock(PlanTargetResolver.class);
        planQueryService = mock(PlanQueryService.class);
        materialPoolService = mock(MaterialPoolService.class);
        materialAddService = mock(PlanMaterialAddService.class);
        renameService = mock(PlanRenameService.class);
        clientFactory = mock(QianchuanClientFactory.class);
        countService = mock(CumulativeCountService.class);
        suixintuiInvestService = mock(SuixintuiInvestService.class);

        dingtouProperties = new DingtouProperties();
        dingtouProperties.setDryRun(true);
        dingtouProperties.setAuthToken("test-token-for-unit-test");

        service = new DefaultPlanBuildService(resolver, planQueryService, materialPoolService,
                materialAddService, renameService, clientFactory, new PlanProperties(),
                dingtouProperties, countService, suixintuiInvestService,
                mock(com.aolandai.dingtou.source.AnomalySheetWriter.class),
                mock(com.aolandai.dingtou.source.TargetingRegistryWriter.class));

        // 默认 N=3（= 达标阈值）→ 走建计划/复用计划那条路；要测 A 场景的用例自己改桩
        try {
            when(countService.count(anyString(), anyString())).thenReturn(
                    new CountResult(AWEME, PRODUCT, String.valueOf(PRODUCT_ID), 3,
                            CountSource.ORDER_SNAPSHOT, "测试桩"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static PlanTargetResolver.PlanTarget target(int... pendingRows) {
        List<DeliveryRowRef> all = new ArrayList<>();
        List<DeliveryRowRef> pending = new ArrayList<>();
        for (int row : pendingRows) {
            DeliveryRowRef ref = new DeliveryRowRef(row, AWEME, PRODUCT, ACC_NAME, ACC_NAME, "",
                    String.valueOf(VIDEO_ID));
            all.add(ref);
            pending.add(ref);
        }
        return new PlanTargetResolver.PlanTarget(all, pending, ACC_ID, ACC_NAME, AWEME_UID, PRODUCT_ID);
    }

    private static PlanQueryOutcome noPlan() {
        return new PlanQueryOutcome(AWEME, PRODUCT, String.valueOf(PRODUCT_ID),
                List.of(), Optional.empty(), 8, 0, List.of(), List.of());
    }

    private static PlanQueryOutcome hasPlan() {
        PlanInfo plan = new PlanInfo(ACC_ID, String.valueOf(AD_ID), "旧名字", "DELIVERY_OK",
                List.of(String.valueOf(PRODUCT_ID)));
        return new PlanQueryOutcome(AWEME, PRODUCT, String.valueOf(PRODUCT_ID),
                List.of(plan), Optional.empty(), 8, 1, List.of(), List.of());
    }

    private PlanBuildParam param() {
        return PlanBuildParam.of(AWEME, PRODUCT, null, DATE);
    }

    // ==================== 「投放中」标记 ====================

    @Test
    @DisplayName("★「投放中」标记文本：`投放中 M.D`（M.D 无前导零，与素材表日期一致）")
    void blockedStatusTextFormat() {
        assertEquals("投放中 10.7",
                DefaultPlanBuildService.blockedStatusText(LocalDate.of(2026, 10, 7)));
        assertEquals("投放中 9.2",
                DefaultPlanBuildService.blockedStatusText(LocalDate.of(2026, 9, 2)));
        assertEquals("投放中 12.31",
                DefaultPlanBuildService.blockedStatusText(LocalDate.of(2026, 12, 31)));
    }

    // ==================== B / C2：建计划 ====================

    @Test
    @DisplayName("★ 无计划（B/C2）：查素材池 → 建计划（素材随建计划带上）→ 命名 + 回写 K/N 两列")
    void createsPlanWhenNoneExists() throws Exception {
        when(resolver.resolve(anyString(), anyString(), any())).thenReturn(Optional.of(target(4992)));
        when(planQueryService.query(eq(AWEME), eq(PRODUCT), anyLong())).thenReturn(noPlan());
        when(materialPoolService.list(ACC_ID, AWEME_UID, PRODUCT_ID)).thenReturn(List.of(
                new MaterialRef(null, VIDEO_ID, MaterialRef.IMAGE_MODE_VERTICAL),
                new MaterialRef(null, VIDEO_ID + 1, MaterialRef.IMAGE_MODE_LARGE)));
        // dry-run 只用 prepare()，不需要 token → 用真实例（构造器无 IO）
        when(clientFactory.createService())
                .thenReturn(new QianchuanOverallVideoCreateService(null));

        PlanBuildResult result = service.build(param());

        assertEquals(PlanBuildResult.Action.CREATE_PLAN, result.action());
        assertNull(result.adId(), "dry-run 不该有计划 ID");
        assertTrue(result.dryRun());
        assertEquals("610129yiyi-护臀膏-10.5", result.planName());
        assertEquals(2, result.materialCount());
        assertTrue(result.warnings().isEmpty(), result.warnings().toString());

        // 请求体里必须有配置来的预算 / ROI 与两条素材
        assertTrue(result.requestBody().contains("20000"), result.requestBody());
        assertTrue(result.requestBody().contains("2.0") || result.requestBody().contains("\"roi2_goal\":2"),
                result.requestBody());
        assertTrue(result.requestBody().contains(String.valueOf(VIDEO_ID)), result.requestBody());
        // 回写：该组合待处理行 → K=全域PC + N=已投
        assertEquals(2, result.cells().size());
        assertEquals("K", result.cells().get(0).column());
        assertEquals("全域PC", result.cells().get(0).value());
        assertEquals("N", result.cells().get(1).column());
        assertEquals("已投", result.cells().get(1).value());
        assertEquals(4992, result.cells().get(0).row());
    }

    @Test
    @DisplayName("★ 建计划请求体与官方文档逐字段对齐（2026-10-05 核对结论）")
    void createPlanRequestBodyMatchesOfficialDoc() throws Exception {
        when(resolver.resolve(anyString(), anyString(), any())).thenReturn(Optional.of(target(4992)));
        when(planQueryService.query(eq(AWEME), eq(PRODUCT), anyLong())).thenReturn(noPlan());
        when(materialPoolService.list(ACC_ID, AWEME_UID, PRODUCT_ID)).thenReturn(List.of(
                new MaterialRef(null, VIDEO_ID, MaterialRef.IMAGE_MODE_VERTICAL)));
        when(clientFactory.createService())
                .thenReturn(new QianchuanOverallVideoCreateService(null));

        String body = service.build(param()).requestBody();

        // 必填项（官方 + SDK openapiRequiredFields 一致）
        assertTrue(body.contains("\"advertiser_id\""), body);
        assertTrue(body.contains("\"name\""), body);
        assertTrue(body.contains("\"product_ids\""), body);
        assertTrue(body.contains("\"delivery_setting\""), body);
        assertTrue(body.contains("\"budget\""), body);
        assertTrue(body.contains("\"roi2_goal\""), body);
        // 有号商家：aweme_uid 条件必填
        assertTrue(body.contains("\"aweme_uid\""), body);
        // 素材：全是抖音主页视频 → 用 aweme_item_id，且【不带标题】
        assertTrue(body.contains("\"aweme_item_id\""), body);
        assertFalse(body.contains("title_material"), "素材全是主页视频时不能带标题：" + body);

        // ⚠️ 老板 2026-10-05 定：hide_in_aweme 【不传】（文档说我们这种场景传了无效）
        assertFalse(body.contains("hide_in_aweme"), "不该传 hide_in_aweme：" + body);

        // ⚠️ 老板 2026-10-05 定：成本项两个开关都【显式传 false】
        assertTrue(body.contains("overall_cost_items"), "要显式传成本项：" + body);
        assertTrue(body.contains("star_task_material_switch"), body);
        assertTrue(body.contains("alliance_commision_switch"),
                "⚠️ 接口拼写是 alliance_commision_switch（少一个 s）：" + body);

        // ⚠️ AIGC 动态创意：显式传 false（不是省略）
        assertTrue(body.contains("enable_aigc_creative"), body);
        assertTrue(body.contains("\"enable_aigc_creative\":false"), body);
    }

    @Test
    @DisplayName("★★ A 场景（N<3）：必须投【随心推】，绝不建千川计划（回归：之前漏了这层判断）")
    void lowCountGoesSuixintuiNotPlan() throws Exception {
        when(resolver.resolve(anyString(), anyString(), any())).thenReturn(Optional.of(target(4992)));
        when(planQueryService.query(eq(AWEME), eq(PRODUCT), anyLong())).thenReturn(noPlan());
        when(countService.count(AWEME, PRODUCT)).thenReturn(
                new CountResult(AWEME, PRODUCT, String.valueOf(PRODUCT_ID), 1,
                        CountSource.ORDER_SNAPSHOT, "测试桩 N=1"));
        when(suixintuiInvestService.invest(AWEME, PRODUCT)).thenReturn(
                new SuixintuiInvestResult(AWEME, PRODUCT, ACC_ID, ACC_NAME, AWEME_UID,
                        String.valueOf(PRODUCT_ID), 200L, 24.0, "MANUAL_BID", 1.7, true, null,
                        "{\"order\":1}",
                        List.of(new com.aolandai.dingtou.dto.sheet.CellWrite(4992, "K", "全域随心推")),
                        List.of()));

        PlanBuildResult result = service.build(param());

        assertEquals(PlanBuildResult.Action.CREATE_SUIXINTUI, result.action());
        assertEquals(1, result.cumulativeCount(), "要把 N 回显出来便于排查");
        assertNull(result.adId(), "A 场景不该有千川计划 ID");
        assertTrue(result.cells().stream().anyMatch(c -> "全域随心推".equals(c.value())),
                "K 列要写「全域随心推」：" + result.cells());

        // ⚠️ 关键：绝不能去建千川计划（那是花钱且投错的）
        verify(clientFactory, never()).createService();
        verify(materialPoolService, never()).list(anyLong(), anyLong(), anyLong());
        verify(materialAddService, never()).add(anyLong(), anyLong(), anyLong(), any(), any(), anyBoolean());
        verify(renameService, never()).rename(anyLong(), anyLong(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("★ 素材池为空 → 不建计划，warning 说清原因（宁可不动也不建空计划）")
    void emptyMaterialPoolDoesNotCreatePlan() throws Exception {
        when(resolver.resolve(anyString(), anyString(), any())).thenReturn(Optional.of(target(4992)));
        when(planQueryService.query(eq(AWEME), eq(PRODUCT), anyLong())).thenReturn(noPlan());
        when(materialPoolService.list(ACC_ID, AWEME_UID, PRODUCT_ID)).thenReturn(List.of());

        PlanBuildResult result = service.build(param());

        assertNull(result.action());
        assertNull(result.adId());
        assertTrue(result.cells().isEmpty());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("素材池")), result.warnings().toString());
        verify(clientFactory, never()).createService();
    }

    @Test
    @DisplayName("★ 没有 aweme_item_id 的素材被跳过并记 warning（用 video_id 会强制要求标题）")
    void materialWithoutAwemeItemIdIsSkipped() throws Exception {
        when(resolver.resolve(anyString(), anyString(), any())).thenReturn(Optional.of(target(4992)));
        when(planQueryService.query(eq(AWEME), eq(PRODUCT), anyLong())).thenReturn(noPlan());
        when(materialPoolService.list(ACC_ID, AWEME_UID, PRODUCT_ID)).thenReturn(List.of(
                new MaterialRef(null, VIDEO_ID, MaterialRef.IMAGE_MODE_VERTICAL),
                new MaterialRef("v0200fg10000abc", null, MaterialRef.IMAGE_MODE_VERTICAL)));
        when(clientFactory.createService())
                .thenReturn(new QianchuanOverallVideoCreateService(null));

        PlanBuildResult result = service.build(param());

        assertEquals(1, result.materialCount(), "只有带 aweme_item_id 的那条该被用");
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("aweme_item_id")),
                result.warnings().toString());
    }

    // ==================== C1：复用计划 ====================

    @Test
    @DisplayName("★ 有计划（C1）：加本条素材 → 改名，回写 K/N 两列")
    void reusesExistingPlan() throws Exception {
        when(resolver.resolve(anyString(), anyString(), any())).thenReturn(Optional.of(target(4992)));
        when(planQueryService.query(eq(AWEME), eq(PRODUCT), anyLong())).thenReturn(hasPlan());
        when(materialAddService.add(anyLong(), anyLong(), anyLong(), any(), any(), anyBoolean()))
                .thenReturn(new PlanWriteOutcome(true, 1, "{\"add\":1}", null, List.of()));
        when(renameService.rename(anyLong(), anyLong(), anyString(), anyBoolean()))
                .thenReturn(new PlanWriteOutcome(true, 0, "{\"rename\":1}", null, List.of()));

        PlanBuildResult result = service.build(param());

        assertEquals(PlanBuildResult.Action.REUSE_PLAN, result.action());
        assertEquals(AD_ID, result.adId());
        assertEquals("610129yiyi-护臀膏-10.5", result.planName());
        assertEquals(1, result.materialCount());
        assertEquals(2, result.cells().size());

        // 加素材用的必须是「本条」= 素材表 H 列的视频id
        ArgumentCaptor<List<MaterialRef>> captor = ArgumentCaptor.forClass(List.class);
        verify(materialAddService).add(eq(ACC_ID), eq(AD_ID), eq(PRODUCT_ID), eq(AWEME_UID),
                captor.capture(), eq(true));
        assertEquals(1, captor.getValue().size());
        assertEquals(VIDEO_ID, captor.getValue().get(0).awemeItemId());

        // 改名用的是同一个计划
        verify(renameService).rename(eq(ACC_ID), eq(AD_ID), eq("610129yiyi-护臀膏-10.5"), eq(true));
    }

    @Test
    @DisplayName("★ 调用方传了 videoId 时以它为准（影刀扫到哪行就传哪行，最准）")
    void explicitVideoIdWins() throws Exception {
        when(resolver.resolve(anyString(), anyString(), any())).thenReturn(Optional.of(target(4992)));
        when(planQueryService.query(eq(AWEME), eq(PRODUCT), anyLong())).thenReturn(hasPlan());
        when(materialAddService.add(anyLong(), anyLong(), anyLong(), any(), any(), anyBoolean()))
                .thenReturn(new PlanWriteOutcome(true, 1, "{}", null, List.of()));
        when(renameService.rename(anyLong(), anyLong(), anyString(), anyBoolean()))
                .thenReturn(new PlanWriteOutcome(true, 0, "{}", null, List.of()));

        long explicit = 1111222233334444555L;
        service.build(PlanBuildParam.of(AWEME, PRODUCT, explicit, DATE));

        ArgumentCaptor<List<MaterialRef>> captor = ArgumentCaptor.forClass(List.class);
        verify(materialAddService).add(anyLong(), anyLong(), anyLong(), any(), captor.capture(),
                anyBoolean());
        assertEquals(explicit, captor.getValue().get(0).awemeItemId());
    }

    @Test
    @DisplayName("★ 该组合有多行待处理且都带视频id → 报错不瞎挑（加错素材等于投错内容）")
    void multiplePendingRowsIsWarnedNotGuessed() throws Exception {
        when(resolver.resolve(anyString(), anyString(), any())).thenReturn(Optional.of(target(4992, 4993)));
        when(planQueryService.query(eq(AWEME), eq(PRODUCT), anyLong())).thenReturn(hasPlan());

        PlanBuildResult result = service.build(param());

        assertNull(result.action());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("无法确定「本条」")),
                result.warnings().toString());
        verify(materialAddService, never()).add(anyLong(), anyLong(), anyLong(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("★ 查计划时传【素材表 E 列解析出的账户】→ 只查它一个（命中就省下其余 7 个账户）")
    void passesDeliverySheetAccountToPlanQuery() throws Exception {
        when(resolver.resolve(anyString(), anyString(), any())).thenReturn(Optional.of(target(4992)));
        when(planQueryService.query(eq(AWEME), eq(PRODUCT), eq(ACC_ID))).thenReturn(hasPlan());
        when(materialAddService.add(anyLong(), anyLong(), anyLong(), any(), any(), anyBoolean()))
                .thenReturn(new PlanWriteOutcome(true, 1, "{}", null, List.of()));
        when(renameService.rename(anyLong(), anyLong(), anyString(), anyBoolean()))
                .thenReturn(new PlanWriteOutcome(true, 0, "{}", null, List.of()));

        service.build(param());

        // ⚠️ 必须是 E 列解析出来的那个账户，不是 null（null 会退化成遍历全部 8 个账户）
        verify(planQueryService).query(eq(AWEME), eq(PRODUCT), eq(ACC_ID));
    }

    // ==================== 三条「宁可中止也不猜」的保护 ====================

    @Test
    @DisplayName("★★ 有账户查询失败 → 直接中止，绝不猜「没有计划」（猜错就是重复建计划 = 花钱）")
    void failedAccountsAbortToAvoidDuplicatePlan() throws Exception {
        when(resolver.resolve(anyString(), anyString(), any())).thenReturn(Optional.of(target(4992)));
        when(planQueryService.query(eq(AWEME), eq(PRODUCT), anyLong())).thenReturn(new PlanQueryOutcome(
                AWEME, PRODUCT, String.valueOf(PRODUCT_ID), List.of(), Optional.empty(),
                8, 0, List.of(),
                List.of("账户 X 查询失败: 40100"),
                List.of("账户 'X' 查询失败: 40100")));

        BizException e = assertThrows(BizException.class, () -> service.build(param()));
        assertTrue(e.getMessage().contains("重复建计划"), e.getMessage());
        verify(materialPoolService, never()).list(anyLong(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("★ 回归：warnings 里有「登记表没填、回退全量扫描」这类【正常提示】时不该中止")
    void informationalWarningsDoNotAbort() throws Exception {
        // 实测踩过：把 warnings 当「查询不完整」→ 正常流程被误判成失败，planBuild 直接跑不动
        when(resolver.resolve(anyString(), anyString(), any())).thenReturn(Optional.of(target(4992)));
        when(planQueryService.query(eq(AWEME), eq(PRODUCT), anyLong())).thenReturn(new PlanQueryOutcome(
                AWEME, PRODUCT, String.valueOf(PRODUCT_ID), List.of(), Optional.empty(),
                8, 0, List.of(),
                List.of("定向投放登记表未命中【610129yiyi + 护臀膏】，回退全量扫描 8 个账户"),
                List.of()));
        when(materialPoolService.list(ACC_ID, AWEME_UID, PRODUCT_ID)).thenReturn(List.of(
                new MaterialRef(null, VIDEO_ID, MaterialRef.IMAGE_MODE_VERTICAL)));
        when(clientFactory.createService())
                .thenReturn(new QianchuanOverallVideoCreateService(null));

        PlanBuildResult result = service.build(param());

        assertEquals(PlanBuildResult.Action.CREATE_PLAN, result.action());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("回退全量扫描")),
                "提示要原样带出，便于排障：" + result.warnings());
    }

    @Test
    @DisplayName("★ 判定「已有计划」但拿不到唯一 ID（只有台账命中）→ 中止，不新建")
    void planWithoutUniqueIdAborts() throws Exception {
        when(resolver.resolve(anyString(), anyString(), any())).thenReturn(Optional.of(target(4992)));
        // plans 为空、ledgerHit 有 → hasPlan() 为真但 single() 为空
        when(planQueryService.query(eq(AWEME), eq(PRODUCT), anyLong())).thenReturn(new PlanQueryOutcome(
                AWEME, PRODUCT, String.valueOf(PRODUCT_ID), List.of(),
                Optional.of(new com.aolandai.dingtou.store.PlanEntry(AWEME, PRODUCT,
                        String.valueOf(PRODUCT_ID), ACC_ID, String.valueOf(AD_ID), "old",
                        "2026-10-01T00:00:00", "2026-10-01T00:00:00")),
                8, 0, List.of(), List.of()));

        BizException e = assertThrows(BizException.class, () -> service.build(param()));
        assertTrue(e.getMessage().contains("唯一的计划 ID"), e.getMessage());
        verify(clientFactory, never()).createService();
    }

    @Test
    @DisplayName("上下文解析不出来（没账户/未授权等）→ 返回空结果 + warning，不抛异常")
    void unresolvedTargetReturnsEmptyResult() throws Exception {
        when(resolver.resolve(anyString(), anyString(), any()))
                .thenReturn(Optional.empty());

        PlanBuildResult result = service.build(param());

        assertNull(result.action());
        assertTrue(result.cells().isEmpty());
    }

    @Test
    @DisplayName("必填参数缺失 → code=2（参数错，影刀不该重试）")
    void missingRequiredParamIsBadRequest() {
        BizException e1 = assertThrows(BizException.class,
                () -> service.build(PlanBuildParam.of("", PRODUCT, null, DATE)));
        assertEquals(com.aolandai.dingtou.dto.api.ErrorCode.BAD_REQUEST, e1.errorCode());

        BizException e2 = assertThrows(BizException.class,
                () -> service.build(PlanBuildParam.of(AWEME, "", null, DATE)));
        assertEquals(com.aolandai.dingtou.dto.api.ErrorCode.BAD_REQUEST, e2.errorCode());
    }

    @Test
    @DisplayName("executionDate 为空时用今天（注入是为了补跑历史日期与单测固定）")
    void nullExecutionDateFallsBackToToday() throws Exception {
        when(resolver.resolve(anyString(), anyString(), any())).thenReturn(Optional.of(target(4992)));
        when(planQueryService.query(eq(AWEME), eq(PRODUCT), anyLong())).thenReturn(hasPlan());
        when(materialAddService.add(anyLong(), anyLong(), anyLong(), any(), any(), anyBoolean()))
                .thenReturn(new PlanWriteOutcome(true, 1, "{}", null, List.of()));
        when(renameService.rename(anyLong(), anyLong(), anyString(), anyBoolean()))
                .thenReturn(new PlanWriteOutcome(true, 0, "{}", null, List.of()));

        PlanBuildResult result = service.build(PlanBuildParam.of(AWEME, PRODUCT, null, null));

        assertNotNull(result.planName());
        assertTrue(result.planName().startsWith("610129yiyi-护臀膏-"), result.planName());
    }
}
