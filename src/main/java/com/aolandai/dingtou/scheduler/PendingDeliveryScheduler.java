package com.aolandai.dingtou.scheduler;

import com.aolandai.dingtou.config.DingtouProperties;
import com.aolandai.dingtou.config.SchedulerProperties;
import com.aolandai.dingtou.dto.plan.PendingRow;
import com.aolandai.dingtou.dto.sheet.DeliveryRow;
import com.aolandai.dingtou.service.plan.DefaultProcessPendingService;
import com.aolandai.dingtou.service.plan.ProcessPendingService;
import com.aolandai.dingtou.source.DeliverySheetReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>定时轮询：服务自己扫素材表 → 跑投放 → 直写回素材表</b>。
 *
 * <h2>老板 2026-10-08 的需求</h2>
 * <blockquote>
 * 「这个项目我想要做成<b>轮询</b>的，<b>不用 post 来调度</b>」
 * </blockquote>
 * <p>即：不再依赖影刀 {@code POST /api/v1/task/processPending}，
 * 服务自己按周期扫表、自己处理、自己写回（所以必须先开
 * {@code dingtou.source.direct-write}，否则结果落不下去）。</p>
 *
 * <h2>⚠️ 四个必须做对的点</h2>
 * <ol>
 *   <li><b>默认关闭</b>（{@code dingtou.scheduler.pending-enabled=false}）——
 *       上线前要确认影刀那边已停掉，否则两边同时写素材表会互相覆盖；</li>
 *   <li><b>防重入</b>：上一轮没跑完就不启动下一轮（素材表 + 千川接口都扛不住并发跑）；</li>
 *   <li><b>「投放中 M.D」当天不再处理</b>：与影刀同一套口径 ——
 *       {@code N 投放状态} 是 {@code 投放中 M.D} 且 M.D == 今天 → 跳过（当天已检测过）；
 *       日期 < 今天 → 重新处理（自动顺延重测）；</li>
 *   <li><b>异常不能把调度线程打死</b>：整轮包 try-catch，否则 Spring 调度器会静默停掉。</li>
 * </ol>
 */
@Component
public class PendingDeliveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(PendingDeliveryScheduler.class);

    /** 匹配 {@code 投放中 M.D}（与 {@code DefaultPlanBuildService.blockedStatusText} 同格式）。 */
    private static final Pattern BLOCKED_STATUS =
            Pattern.compile("^\\s*投放中\\s+(\\d{1,2})\\.(\\d{1,2})\\s*$");

    private final DeliverySheetReader deliverySheetReader;
    private final ProcessPendingService processPendingService;
    private final SchedulerProperties schedulerProperties;
    private final DingtouProperties dingtouProperties;

    /** 防重入。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public PendingDeliveryScheduler(DeliverySheetReader deliverySheetReader,
                                    DefaultProcessPendingService processPendingService,
                                    SchedulerProperties schedulerProperties,
                                    DingtouProperties dingtouProperties) {
        this.deliverySheetReader = deliverySheetReader;
        this.processPendingService = processPendingService;
        this.schedulerProperties = schedulerProperties;
        this.dingtouProperties = dingtouProperties;
    }

    /**
     * 按 cron 跑一轮（cron 见 {@code dingtou.scheduler.pending-cron}）。
     *
     * <p>⚠️ 默认 {@code pending-enabled=false}，方法会被直接跳过（但 Spring 仍会按 cron 唤醒）。</p>
     */
    @Scheduled(cron = "${dingtou.scheduler.pending-cron:0 */10 * * * *}")
    public void runOnce() {
        if (!schedulerProperties.isPendingEnabled()) {
            log.debug("定时投放未启用（dingtou.scheduler.pending-enabled=false），跳过");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.warn("上一轮定时投放还没跑完，本轮跳过（防重入）");
            return;
        }
        long startedAt = System.currentTimeMillis();
        try {
            log.info("========== 定时投放开始（dry-run={}）==========", dingtouProperties.isDryRun());
            List<PendingRow> rows = readPendingRows();
            if (rows.isEmpty()) {
                log.info("定时投放：没有待处理行，本轮结束");
                return;
            }
            log.info("定时投放：读到 {} 行待处理", rows.size());

            var result = processPendingService.process(
                    new com.aolandai.dingtou.dto.plan.ProcessPendingParam(rows, null));
            log.info("定时投放完成：{} 个组合（成功 {} / 失败 {} / 跳过 {}），回写 {} 个单元格，耗时 {}s",
                    result.combinations(), result.succeeded(), result.failed(), result.skipped(),
                    result.cells().size(), (System.currentTimeMillis() - startedAt) / 1000);
            if (!result.warnings().isEmpty()) {
                log.warn("定时投放有 {} 条 warning（只列前 5 条）：", result.warnings().size());
                result.warnings().stream().limit(5).forEach(w -> log.warn("  - {}", w));
            }
        } catch (Exception e) {
            // ⚠️ 不能让异常逃出去 —— Spring 调度器遇到未捕获异常会静默停掉后续调度
            log.error("定时投放失败（本轮结束，下一轮照常）: {}", e.getMessage(), e);
        } finally {
            running.set(false);
        }
    }

    /**
     * 读「待处理行」，并排除「今天已经检测过、仍是投放中」的行。
     *
     * <p>⚠️ 与影刀同一套口径：{@code N 投放状态 = 投放中 M.D}</p>
     * <ul>
     *   <li>{@code M.D == 今天} → <b>跳过</b>（当天已检测过，不重复跑）；</li>
     *   <li>{@code M.D < 今天} → <b>重新处理</b>（自动顺延重测）。</li>
     * </ul>
     *
     * <p>⚠️ 用 {@code public} 是为了让探针（跨包）也能复用同一段读表+过滤逻辑 ——
     * 否则探针只能复制一份，两边逻辑容易漂移。</p>
     */
    public List<PendingRow> readPendingRows() throws Exception {
        LocalDate today = LocalDate.now();
        List<DeliveryRow> awaiting = deliverySheetReader.readAwaitingDelivery();
        Map<String, PendingRow> out = new LinkedHashMap<>();
        int skippedToday = 0;

        for (DeliveryRow row : awaiting) {
            if (!row.hasAweme() || !row.hasProduct()) {
                continue;
            }
            if (isBlockedToday(row.deliveryStatus(), today)) {
                skippedToday++;
                continue;
            }
            // ⚠️ 视频id 可能是空/非数字（素材表人工维护）→ 解析不了就传 null，
            //    后面 planBuild 的 resolveVideoIds 会自己挑并留 warning
            Long videoId = null;
            if (row.videoId() != null && !row.videoId().isBlank()) {
                try {
                    videoId = Long.parseLong(row.videoId().trim());
                } catch (NumberFormatException ignored) {
                    log.debug("第 {} 行视频id 不是数字：{}", row.rowNumber(), row.videoId());
                }
            }
            // 同一组合多行 → 只提交一次（processPending 会按组合聚合）
            out.putIfAbsent(row.awemeShowId() + "|" + row.productName(),
                    new PendingRow(row.rowNumber(), row.awemeShowId(), row.productName(), videoId));
        }
        if (skippedToday > 0) {
            log.info("定时投放：{} 行因「投放中且今天已检测」被跳过（等明天自动重测）", skippedToday);
        }
        return new ArrayList<>(out.values());
    }

    /** {@code 投放中 M.D} 且 M.D == 今天 → true（{@code public} 供单测/探针复用）。 */
    public static boolean isBlockedToday(String deliveryStatus, LocalDate today) {
        if (deliveryStatus == null) {
            return false;
        }
        Matcher m = BLOCKED_STATUS.matcher(deliveryStatus);
        if (!m.find()) {
            return false;
        }
        try {
            int month = Integer.parseInt(m.group(1));
            int day = Integer.parseInt(m.group(2));
            return month == today.getMonthValue() && day == today.getDayOfMonth();
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 当前是否在跑（供探针/排障观察）。 */
    public boolean isRunning() {
        return running.get();
    }
}
