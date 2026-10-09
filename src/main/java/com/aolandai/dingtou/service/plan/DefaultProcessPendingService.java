package com.aolandai.dingtou.service.plan;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link ProcessPendingService} 的默认实现。
 *
 * <h2>流程</h2>
 * <pre>
 * 1. 按【抖音号 + 商品名】分组去重（同一组合多行 → 只处理一次，素材合并）
 * 2. 逐组合调用 PlanBuildService（算 N → 判场景 → 执行）
 * 3. 汇总所有组合的 cells + warnings
 * </pre>
 *
 * <h2>⚠️ 三个刻意的设计</h2>
 * <ol>
 *   <li><b>去重是为了少查计划</b>：同一组合多行时，只查一次计划（查计划是整条链路里最贵的一步）。
 *       多行的 {@code videoId} 会<b>合并</b>，C1 时一次全加进去 —— 不会漏素材。</li>
 *   <li><b>单个组合失败不中断整批</b>：没账户 / 未授权 / 商品映射不到 都是「等人工补数据」的情况，
 *       不该拖累其他组合。记进该组合的 {@code FAILED} + warnings 继续跑。</li>
 *   <li><b>但频控要中断整批</b>：撞上 40100 时继续跑只会加重惩罚（40110 有 5 分钟惩罚），
 *       剩下的标记 {@code SKIPPED} 让影刀稍后原样重跑（重跑安全，见接口注释）。</li>
 * </ol>
 */
@Component
public class DefaultProcessPendingService implements ProcessPendingService {

    private static final Logger log = LoggerFactory.getLogger(DefaultProcessPendingService.class);

    private final PlanBuildService planBuildService;
    private final OrderSnapshotService orderSnapshotService;
    private final AggregateProperties aggregateProperties;
    private final com.aolandai.dingtou.source.DeliverySheetWriter deliverySheetWriter;
    private final com.aolandai.dingtou.config.DingtouProperties dingtouProperties;

    public DefaultProcessPendingService(PlanBuildService planBuildService,
                                        OrderSnapshotService orderSnapshotService,
                                        AggregateProperties aggregateProperties,
                                        com.aolandai.dingtou.source.DeliverySheetWriter deliverySheetWriter,
                                        com.aolandai.dingtou.config.DingtouProperties dingtouProperties) {
        this.planBuildService = planBuildService;
        this.orderSnapshotService = orderSnapshotService;
        this.aggregateProperties = aggregateProperties;
        this.deliverySheetWriter = deliverySheetWriter;
        this.dingtouProperties = dingtouProperties;
    }

    @Override
    public ProcessPendingResult process(ProcessPendingParam param) throws Exception {
        List<PendingRow> rows = param.rows();
        if (rows.isEmpty()) {
            throw BizException.badRequest("rows 必须是非空数组，形如 "
                    + "[{\"row\":4992,\"awemeShowId\":\"272886488\",\"productName\":\"益生元身体乳\"}]");
        }
        LocalDate date = param.executionDate() == null ? LocalDate.now() : param.executionDate();
        List<String> warnings = new ArrayList<>();

        // ⚠️ 老板 2026-10-07：「每次投放前跑一次」订单增量 —— N 只认订单数，不刷新就没数。
        //    带节流：10 分钟内刷过就不重复拉（否则逐条调用会重复拉全账户订单）。
        refreshOrdersIfNeeded(warnings);

        // 1) 分组去重
        Map<String, List<PendingRow>> grouped = new LinkedHashMap<>();
        int invalidRows = 0;
        for (PendingRow row : rows) {
            if (!row.isComplete()) {
                invalidRows++;
                warnings.add("第 " + row.row() + " 行缺少抖音号或商品名，已跳过"
                        + "（抖音号=" + row.awemeShowId() + "，商品名=" + row.productName() + "）");
                continue;
            }
            grouped.computeIfAbsent(row.combinationKey(), k -> new ArrayList<>()).add(row);
        }
        if (invalidRows > 0) {
            warnings.add("共 " + invalidRows + " 行因字段不全被跳过，请人工核对素材表");
        }
        if (grouped.isEmpty()) {
            warnings.add("没有任何可处理的【抖音号 + 商品名】组合，本批未执行任何投放");
            return new ProcessPendingResult(rows.size(), 0, 0, 0, 0,
                    List.of(), List.of(), warnings);
        }

        // 2) 逐组合处理
        List<ProcessPendingResult.CombinationOutcome> outcomes = new ArrayList<>();
        List<CellWrite> cells = new ArrayList<>();
        int succeeded = 0;
        int failed = 0;
        int skipped = 0;
        boolean aborted = false;
        String abortReason = null;

        for (List<PendingRow> group : grouped.values()) {
            PendingRow first = group.get(0);
            if (aborted) {
                skipped++;
                outcomes.add(skipped(first, abortReason));
                continue;
            }
            try {
                List<Long> videoIds = mergeVideoIds(group);
                PlanBuildResult result = planBuildService.build(new PlanBuildParam(
                        first.awemeShowId(), first.productName(), videoIds, date));
                cells.addAll(result.cells());
                warnings.addAll(result.warnings());

                if (result.action() == PlanBuildResult.Action.SKIP_BLOCKED) {
                    // ⚠️ 「有订单仍存续（占用互斥名额）→ 主动跳过」不算失败（老板 2026-10-07）。
                    //    算进 failed 会让每次批量都报一堆假失败。
                    skipped++;
                    outcomes.add(skippedBlocked(first, result));
                } else if (result.action() == null) {
                    // 没抛异常但也没动作 = 没办成（没账户 / 未授权 / 素材池为空等），原因在 warnings 里
                    failed++;
                    outcomes.add(failed(first, result));
                } else {
                    succeeded++;
                    outcomes.add(ok(first, result));
                }
            } catch (RateLimitException e) {
                aborted = true;
                abortReason = e.describe();
                skipped++;
                outcomes.add(skipped(first, abortReason));
                warnings.add("撞上频控，本批中止。剩余组合未处理；"
                        + "已处理的不会重复花钱，可稍后【原样重跑整批】");
                log.warn("processPending 撞频控，中止本批（已处理 {} 个组合）: {}",
                        succeeded, e.describe());
            } catch (BizException e) {
                failed++;
                outcomes.add(bizFailed(first, e.getMessage()));
                warnings.add(first.awemeShowId() + " + " + first.productName()
                        + " 处理失败：" + e.getMessage());
            }
        }

        log.info("processPending 完成：提交 {} 行 → {} 个组合（成功 {} / 失败 {} / 跳过 {}），"
                        + "回写 {} 个单元格，警告 {} 条",
                rows.size(), grouped.size(), succeeded, failed, skipped,
                cells.size(), warnings.size());

        // ⚠️⚠️ 老板 2026-10-08 方案 A：**服务直接写素材表**（轮询式的前提）。
        //    ⚠️ 开了之后【影刀就不该再写素材表】，否则两边互相覆盖。
        //    ⚠️ 直写失败【不影响 cells 返回】—— 影刀仍可照 cells 写，只是要人工确认谁写。
        writeCellsIfEnabled(cells, warnings);

        return new ProcessPendingResult(rows.size(), grouped.size(), succeeded, failed, skipped,
                cells, outcomes, warnings);
    }

    /**
     * 服务直写素材表（受 {@code dingtou.source.direct-write} 控制）。
     *
     * <p>⚠️ 直写失败只加 warning，<b>不抛</b> —— cells 照样返回给影刀，</p>
     */
    private void writeCellsIfEnabled(List<CellWrite> cells, List<String> warnings) {
        if (cells.isEmpty() || !deliverySheetWriter.enabled()) {
            return;
        }
        try {
            int written = deliverySheetWriter.write(cells, dingtouProperties.isDryRun());
            if (written > 0) {
                log.info("服务已直写素材表 {} 个单元格", written);
            }
        } catch (Exception e) {
            warnings.add("服务直写素材表失败（cells 仍已返回，可让影刀照写；"
                    + "⚠️ 注意别两边同时写）：" + e.getMessage());
            log.error("直写素材表失败: {}", e.getMessage());
        }
    }

    /**
     * 合并同一组合下所有行的 {@code videoId}（去重、保序）。
     *
     * <p>⚠️ 这一步是「不会漏素材」的关键：同一组合有 3 个新视频时，
     * 三个 {@code videoId} 会一起交给 C1 一次加完，而不是只加第一条。</p>
     */
    /**
     * 投放前自动刷新订单快照（可配置关闭；带节流）。
     *
     * <p>⚠️ 老板 2026-10-07：「refreshOrders 可以自动跑」。
     * N 只认订单数，不刷新就没数（快照不新鲜时 {@code count()} 会直接报错）。</p>
     */
    private void refreshOrdersIfNeeded(List<String> warnings) {
        if (!aggregateProperties.isAutoRefreshOrders()) {
            return;
        }
        try {
            boolean refreshed = orderSnapshotService.ensureFresh(
                    aggregateProperties.getAutoRefreshOrdersThrottleMinutes());
            if (refreshed) {
                log.info("投放前已自动刷新订单快照");
            }
        } catch (Exception e) {
            // ⚠️ 这里只加 warning，不吞掉错误 —— 后面 count() 拿不到 N 时会抛更明确的错。
            warnings.add("投放前自动刷新订单快照失败（若后续 N 取不到，请手工调 refreshOrders）："
                    + e.getMessage());
            log.warn("投放前自动刷新订单快照失败: {}", e.getMessage());
        }
    }

    private static List<Long> mergeVideoIds(List<PendingRow> group) {
        Set<Long> ids = new LinkedHashSet<>();
        for (PendingRow row : group) {
            if (row.videoId() != null) {
                ids.add(row.videoId());
            }
        }
        return new ArrayList<>(ids);
    }

    private static ProcessPendingResult.CombinationOutcome ok(PendingRow row, PlanBuildResult r) {
        return new ProcessPendingResult.CombinationOutcome(row.awemeShowId(), row.productName(),
                ProcessPendingResult.CombinationOutcome.Status.OK, r.action(), r.adId(),
                r.cumulativeCount(), r.materialCount(), r.reason(), r.warnings());
    }

    private static ProcessPendingResult.CombinationOutcome failed(PendingRow row, PlanBuildResult r) {
        return new ProcessPendingResult.CombinationOutcome(row.awemeShowId(), row.productName(),
                ProcessPendingResult.CombinationOutcome.Status.FAILED, null, null,
                r.cumulativeCount(), 0, r.reason(), r.warnings());
    }

    private static ProcessPendingResult.CombinationOutcome bizFailed(PendingRow row, String message) {
        return new ProcessPendingResult.CombinationOutcome(row.awemeShowId(), row.productName(),
                ProcessPendingResult.CombinationOutcome.Status.FAILED, null, null, 0, 0,
                message, List.of(message));
    }

    /** 「有订单仍存续（占用互斥名额）→ 主动跳过」：状态是 SKIPPED，不是 FAILED。 */
    private static ProcessPendingResult.CombinationOutcome skippedBlocked(PendingRow row,
                                                                         PlanBuildResult r) {
        return new ProcessPendingResult.CombinationOutcome(row.awemeShowId(), row.productName(),
                ProcessPendingResult.CombinationOutcome.Status.SKIPPED, r.action(), null,
                r.cumulativeCount(), 0, r.reason(),
                List.of("该组合还有订单占用互斥名额（投放中/审核中/创建中/未开播/投放终止），"
                        + "等它们结束后再跑"));
    }

    private static ProcessPendingResult.CombinationOutcome skipped(PendingRow row, String reason) {
        return new ProcessPendingResult.CombinationOutcome(row.awemeShowId(), row.productName(),
                ProcessPendingResult.CombinationOutcome.Status.SKIPPED, null, null, 0, 0,
                reason == null ? "本批已中止" : reason, List.of());
    }
}
