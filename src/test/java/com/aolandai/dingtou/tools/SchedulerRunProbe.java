package com.aolandai.dingtou.tools;

import com.aolandai.dingtou.DingtouApplication;
import com.aolandai.dingtou.dto.plan.PendingRow;
import com.aolandai.dingtou.dto.plan.ProcessPendingParam;
import com.aolandai.dingtou.scheduler.PendingDeliveryScheduler;
import com.aolandai.dingtou.service.plan.DefaultProcessPendingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.util.List;

/**
 * 手工探针：<b>验证轮询链路</b>（读待处理行 → 跑投放 → 直写回素材表）。
 *
 * <pre>
 * java -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 \
 *   -cp "target/test-classes;target/classes;&lt;依赖&gt;" \
 *   com.aolandai.dingtou.tools.SchedulerRunProbe [最多处理几个组合，默认 3]
 * </pre>
 *
 * <h2>⚠️ 为什么限制组合数</h2>
 * <p>dry-run 下虽然不花钱，但每个组合都要**真查一次千川计划**（~10~30 秒）——
 * 几十个组合会跑几十分钟。所以探针默认只处理前 3 个，用于验证链路。</p>
 *
 * <p>⚠️ dry-run 时不会真建计划、不会真写素材表（{@code DeliverySheetWriter} 只打印）。</p>
 */
public final class SchedulerRunProbe {

    private static final Logger log = LoggerFactory.getLogger(SchedulerRunProbe.class);

    private static final String PROBE_AUTH_TOKEN = "probe-only-not-a-real-token";

    private SchedulerRunProbe() {
    }

    public static void main(String[] args) throws Exception {
        int limit = args != null && args.length > 0 && !args[0].isBlank()
                ? Integer.parseInt(args[0].trim()) : 3;

        log.info("========== SchedulerRunProbe 开始（dry-run，最多处理 {} 个组合）==========", limit);

        try (var context = new SpringApplicationBuilder(DingtouApplication.class)
                .web(WebApplicationType.NONE)
                // ⚠️ 用命令行参数打开定时开关（优先级最高）；dry-run 仍由 yml 控制
                .run("--dingtou.auth-token=" + PROBE_AUTH_TOKEN,
                        "--server.port=0",
                        "--dingtou.scheduler.pending-enabled=true")) {

            PendingDeliveryScheduler scheduler = context.getBean(PendingDeliveryScheduler.class);
            DefaultProcessPendingService processService =
                    context.getBean(DefaultProcessPendingService.class);

            // 1) 读待处理行（与定时任务同一段逻辑）
            long t1 = System.currentTimeMillis();
            List<PendingRow> all = scheduler.readPendingRows();
            long readCost = System.currentTimeMillis() - t1;
            log.info("① 读到待处理行 {} 条（耗时 {}ms）", all.size(), readCost);
            if (all.isEmpty()) {
                log.info("没有待处理行，链路验证结束（读表 + 过滤逻辑正常）");
                return;
            }
            all.stream().limit(5).forEach(r -> log.info("    第{}行 {} + {} videoId={}",
                    r.row(), r.awemeShowId(), r.productName(), r.videoId()));

            // 2) 只取前 N 个组合跑一遍
            List<PendingRow> slice = all.stream().limit(limit).toList();
            log.info("② 只处理前 {} 个组合（dry-run）……", slice.size());

            long t2 = System.currentTimeMillis();
            var result = processService.process(new ProcessPendingParam(slice, null));
            long runCost = System.currentTimeMillis() - t2;

            log.info("========== 结果（耗时 {}s）==========", runCost / 1000);
            log.info("提交行数    : {}", result.submittedRows());
            log.info("组合数      : {}", result.combinations());
            log.info("成功/失败/跳过: {} / {} / {}", result.succeeded(), result.failed(),
                    result.skipped());
            log.info("回写单元格  : {}", result.cells().size());
            result.cells().stream().limit(6).forEach(c -> log.info("    {}", c.describe()));
            if (!result.warnings().isEmpty()) {
                log.warn("warning {} 条（列前 5）：", result.warnings().size());
                result.warnings().stream().limit(5).forEach(w -> log.warn("    {}", w));
            }
            log.info("结论：{}", result.submittedRows() > 0
                    ? "✅ 轮询链路跑通（读表 → 处理 → 返回 cells）" : "⚠️ 没有提交任何行");
        }
        log.info("========== SchedulerRunProbe 结束 ==========");
    }
}
