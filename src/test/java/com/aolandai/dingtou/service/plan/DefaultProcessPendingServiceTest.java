package com.aolandai.dingtou.service.plan;

import com.aolandai.dingtou.config.DingtouProperties;
import com.aolandai.dingtou.source.DeliverySheetWriter;
import com.aolandai.dingtou.config.AggregateProperties;
import com.aolandai.dingtou.dto.plan.PendingRow;
import com.aolandai.dingtou.dto.plan.PlanBuildParam;
import com.aolandai.dingtou.dto.plan.PlanBuildResult;
import com.aolandai.dingtou.dto.plan.ProcessPendingParam;
import com.aolandai.dingtou.dto.plan.ProcessPendingResult;
import com.aolandai.dingtou.dto.sheet.CellWrite;
import com.aolandai.dingtou.exception.BizException;
import com.aolandai.dingtou.service.qianchuan.OrderSnapshotService;
import com.aolandai.dingtou.exception.RateLimitException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultProcessPendingService} 单测：mock 掉 {@link PlanBuildService}，<b>不碰千川</b>。
 */
class DefaultProcessPendingServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 5);

    private PlanBuildService planBuildService;
    private DefaultProcessPendingService service;

    @BeforeEach
    void setUp() {
        planBuildService = mock(PlanBuildService.class);
        // ⚠️ 自动刷订单快照在单测里关掉 —— 否则会真去调千川接口
        AggregateProperties aggregate = new AggregateProperties();
        aggregate.setAutoRefreshOrders(false);
        // ⚠️ 直写素材表在单测里关掉（否则会真去改 Excel）
        DeliverySheetWriter writer = mock(DeliverySheetWriter.class);
        when(writer.enabled()).thenReturn(false);
        service = new DefaultProcessPendingService(planBuildService,
                mock(OrderSnapshotService.class), aggregate, writer, new DingtouProperties());
    }

    private static PlanBuildResult result(String aweme, String product,
                                          PlanBuildResult.Action action, long adId,
                                          int materialCount, List<CellWrite> cells) {
        return new PlanBuildResult(aweme, product, 1823379540880396L, "官方【母婴商品】",
                2071074583877891L, 3593275488396811650L, "plan-" + aweme, 3, "测试桩",
                action, adId, materialCount, true, "{}", cells, List.of());
    }

    @Test
    @DisplayName("★ 同一组合多行 → 只处理【一次】，且 videoIds 合并（不会漏素材）")
    void deduplicatesByCombinationAndMergesVideoIds() throws Exception {
        List<PlanBuildParam> captured = new ArrayList<>();
        when(planBuildService.build(any())).thenAnswer(inv -> {
            PlanBuildParam p = inv.getArgument(0);
            captured.add(p);
            return result(p.awemeShowId(), p.productName(), PlanBuildResult.Action.REUSE_PLAN,
                    1877746196794116L, p.videoIds().size(),
                    List.of(new CellWrite(1, "K", "全域PC")));
        });

        ProcessPendingResult r = service.process(new ProcessPendingParam(List.of(
                new PendingRow(4992, "272886488", "益生元身体乳", 111L),
                new PendingRow(4993, "272886488", "益生元身体乳", 222L),
                new PendingRow(4994, "272886488", "益生元身体乳", 333L)
        ), DATE));

        assertEquals(3, r.submittedRows());
        assertEquals(1, r.combinations(), "同一组合应当只算一个");
        assertEquals(1, captured.size(), "⚠️ 同一组合只能调一次 —— 查计划是整条链路最贵的一步");
        assertEquals(List.of(111L, 222L, 333L), captured.get(0).videoIds(),
                "⚠️ 三个 videoId 必须合并后一次交出去，否则会漏素材");
        assertEquals(1, r.succeeded());
    }

    @Test
    @DisplayName("★ 不同组合各处理一次，cells 汇总在一起")
    void processesEachCombinationAndAggregatesCells() throws Exception {
        when(planBuildService.build(any())).thenAnswer(inv -> {
            PlanBuildParam p = inv.getArgument(0);
            return result(p.awemeShowId(), p.productName(), PlanBuildResult.Action.CREATE_PLAN,
                    100L, 5, List.of(new CellWrite(4992, "K", "全域PC"),
                            new CellWrite(4992, "N", "已投")));
        });

        ProcessPendingResult r = service.process(new ProcessPendingParam(List.of(
                new PendingRow(4992, "272886488", "益生元身体乳", null),
                new PendingRow(4993, "55454664964", "青少年洗发水", null)
        ), DATE));

        assertEquals(2, r.combinations());
        assertEquals(2, r.succeeded());
        assertEquals(4, r.cells().size(), "两个组合各 2 个单元格");
        assertEquals(2, r.results().size());
        assertTrue(r.results().stream().allMatch(o -> o.status()
                == ProcessPendingResult.CombinationOutcome.Status.OK));
    }

    @Test
    @DisplayName("★ 单个组合失败【不中断整批】（没账户/未授权是等人工补数据，不该拖累别人）")
    void oneFailureDoesNotAbortTheBatch() throws Exception {
        when(planBuildService.build(any())).thenAnswer(inv -> {
            PlanBuildParam p = inv.getArgument(0);
            if ("bad".equals(p.awemeShowId())) {
                throw BizException.businessFail("账户定位不到");
            }
            return result(p.awemeShowId(), p.productName(), PlanBuildResult.Action.CREATE_PLAN,
                    1L, 1, List.of(new CellWrite(1, "K", "全域PC")));
        });

        ProcessPendingResult r = service.process(new ProcessPendingParam(List.of(
                new PendingRow(1, "bad", "商品A", null),
                new PendingRow(2, "good", "商品B", null)
        ), DATE));

        assertEquals(1, r.failed());
        assertEquals(1, r.succeeded(), "后面的组合必须照常处理");
        assertEquals(1, r.cells().size());
    }

    @Test
    @DisplayName("★ 撞频控 → 中断整批，剩余标记 SKIPPED（继续跑只会加重 5 分钟惩罚）")
    void rateLimitAbortsTheWholeBatch() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        when(planBuildService.build(any())).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                return result("a", "p", PlanBuildResult.Action.CREATE_PLAN, 1L, 1,
                        List.of(new CellWrite(1, "K", "全域PC")));
            }
            throw new RateLimitException(RateLimitException.Scope.GLOBAL, "被 40100 拦截", 60_000L);
        });

        ProcessPendingResult r = service.process(new ProcessPendingParam(List.of(
                new PendingRow(1, "a", "p", null),
                new PendingRow(2, "b", "q", null),
                new PendingRow(3, "c", "r", null)
        ), DATE));

        assertEquals(1, r.succeeded());
        assertEquals(0, r.failed());
        assertEquals(2, r.skipped(), "频控后的组合应全部标记为跳过");
        assertEquals(2, calls.get(), "⚠️ 撞频控后不该继续调用（继续只会加重惩罚）");
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("原样重跑整批")),
                r.warnings().toString());
    }

    @Test
    @DisplayName("没办成（action 为空）→ 记 FAILED，原因在 warnings 里")
    void nullActionCountsAsFailed() throws Exception {
        when(planBuildService.build(any())).thenAnswer(inv -> {
            PlanBuildParam p = inv.getArgument(0);
            return new PlanBuildResult(p.awemeShowId(), p.productName(), null, "", null, null,
                    "", 0, "", null, null, 0, true, "", List.of(),
                    List.of("素材池里没有可用素材"));
        });

        ProcessPendingResult r = service.process(new ProcessPendingParam(List.of(
                new PendingRow(1, "a", "p", null)), DATE));

        assertEquals(0, r.succeeded());
        assertEquals(1, r.failed());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("素材池")), r.warnings().toString());
    }

    @Test
    @DisplayName("字段不全的行被跳过 + warning 带行号（不静默丢）")
    void incompleteRowsAreSkippedWithWarning() throws Exception {
        when(planBuildService.build(any())).thenAnswer(inv -> {
            PlanBuildParam p = inv.getArgument(0);
            return result(p.awemeShowId(), p.productName(), PlanBuildResult.Action.CREATE_PLAN,
                    1L, 1, List.of());
        });

        ProcessPendingResult r = service.process(new ProcessPendingParam(List.of(
                new PendingRow(7, "", "益生元身体乳", null),
                new PendingRow(8, "272886488", "", null),
                new PendingRow(9, "272886488", "益生元身体乳", null)
        ), DATE));

        assertEquals(3, r.submittedRows());
        assertEquals(1, r.combinations());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("第 7 行")), r.warnings().toString());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("第 8 行")), r.warnings().toString());
    }

    @Test
    @DisplayName("rows 为空 → code=2（参数错）")
    void emptyRowsIsBadRequest() {
        BizException e = assertThrows(BizException.class,
                () -> service.process(new ProcessPendingParam(List.of(), DATE)));
        assertNotNull(e.getMessage());
        assertTrue(e.getMessage().contains("rows"), e.getMessage());
    }

    @Test
    @DisplayName("全部行字段都不全 → 不执行任何投放，返回空结果 + warning")
    void allRowsInvalidDoesNothing() throws Exception {
        ProcessPendingResult r = service.process(new ProcessPendingParam(List.of(
                new PendingRow(1, "", "", null)), DATE));

        assertEquals(0, r.combinations());
        assertEquals(0, r.cells().size());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("未执行任何投放")),
                r.warnings().toString());
    }

    @Test
    @DisplayName("执行日期透传给每个组合（计划名里的 M.D 用它）")
    void executionDateIsPassedThrough() throws Exception {
        ArgumentCaptor<PlanBuildParam> captor = ArgumentCaptor.forClass(PlanBuildParam.class);
        when(planBuildService.build(captor.capture())).thenAnswer(inv -> {
            PlanBuildParam p = inv.getArgument(0);
            return result(p.awemeShowId(), p.productName(), PlanBuildResult.Action.CREATE_PLAN,
                    1L, 1, List.of());
        });

        service.process(new ProcessPendingParam(List.of(
                new PendingRow(1, "a", "p", null)), LocalDate.of(2026, 9, 30)));

        assertEquals(LocalDate.of(2026, 9, 30), captor.getValue().executionDate());
    }
}
