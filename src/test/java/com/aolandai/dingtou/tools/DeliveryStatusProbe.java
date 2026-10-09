package com.aolandai.dingtou.tools;

import com.aolandai.dingtou.DingtouApplication;
import com.aolandai.dingtou.dto.kol.DeliveryStatusResult;
import com.aolandai.dingtou.service.kol.DeliveryStatusChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.time.LocalDate;

/**
 * 手工探针：跑一次「交付状态」检查（<b>dry-run，不会写表</b>）。
 *
 * <pre>
 * java -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 \
 *   -cp "target/test-classes;target/classes;&lt;依赖&gt;" \
 *   com.aolandai.dingtou.tools.DeliveryStatusProbe [参考日期 yyyy-MM-dd]
 * </pre>
 *
 * <p>不传参考日期就用今天。参考日期用来定位「上一个周期」。</p>
 *
 * <p>⚠️ 会真调千川【乘方计划详情】接口（只读、不花钱），一个周期 160+ 个计划，
 * 大概要跑几分钟。</p>
 *
 * <p>⚠️ <b>永远以 dry-run 跑</b> —— 这个探针不写 Excel。要真写请通过
 * {@code kolPlanCostQuery + checkDelivery=true}（受 {@code dingtou.dry-run} 保护）。</p>
 */
public final class DeliveryStatusProbe {

    private static final Logger log = LoggerFactory.getLogger(DeliveryStatusProbe.class);

    private static final String PROBE_AUTH_TOKEN = "probe-only-not-a-real-token";

    private DeliveryStatusProbe() {
    }

    public static void main(String[] args) throws Exception {
        LocalDate referenceDate = (args != null && args.length > 0 && !args[0].isBlank())
                ? LocalDate.parse(args[0].trim()) : LocalDate.now();

        log.info("========== DeliveryStatusProbe 开始（dry-run，不写表）==========");
        log.info("参考日期 = {}（用来定位「上一个周期」）", referenceDate);

        try (var context = new SpringApplicationBuilder(DingtouApplication.class)
                .web(WebApplicationType.NONE)
                .run("--dingtou.auth-token=" + PROBE_AUTH_TOKEN, "--server.port=0")) {

            DeliveryStatusChecker checker = context.getBean(DeliveryStatusChecker.class);
            DeliveryStatusResult r = checker.check(referenceDate, true);

            log.info("========== 检查结果（dry-run）==========");
            log.info("处理的 sheet      : {}", r.sheetName());
            log.info("J 列周期时间过滤  : {}（只处理该周期的行，不是整个 sheet）", r.cycleTime());
            log.info("判定窗口          : [{} ~ {}]（闭区间）", r.windowStart(), r.windowEnd());
            log.info("该周期行数        : {}", r.totalRows());
            log.info("已交付            : {}", r.delivered());
            log.info("未交付            : {}", r.notDelivered());
            log.info("  解析不出日期(留空): {}", r.unresolved());
            log.info("本应写入 Excel 行数: {}", r.written());
            log.info("是否演练          : {}", r.dryRun() ? "是（没有真写）" : "否");

            if (!r.warnings().isEmpty()) {
                log.warn("warning 共 {} 条（只列前 15 条）：", r.warnings().size());
                r.warnings().stream().limit(15).forEach(w -> log.warn("  - {}", w));
            }
        }
        log.info("========== DeliveryStatusProbe 结束 ==========");
    }
}
