package com.aolandai.dingtou.tools;

import com.aolandai.dingtou.DingtouApplication;
import com.aolandai.dingtou.dto.kol.KolPlanCostRequest;
import com.aolandai.dingtou.dto.kol.PlanCostQueryResult;
import com.aolandai.dingtou.dto.kol.PlanCostRow;
import com.aolandai.dingtou.service.kol.KolPlanCostService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.LocalDate;
import java.util.List;

/**
 * 手工探针：验证 {@code kolPlanCostQuery}（查消耗达标的千川计划）。
 *
 * <h2>怎么跑</h2>
 * <pre>
 * 方式一（默认窗口 = 最近 1 个月）：
 *   直接 run KolPlanCostProbe.main()
 *
 * 方式二（显式窗口）：
 *   run KolPlanCostProbe.main("2026-09-04", "2026-10-03")
 *
 * 方式三（命令行，第三参数 true = 附带抖音号/抖音名）：
 *   java -cp "target/classes;target/test-classes;&lt;依赖&gt;" \
 *       com.aolandai.dingtou.tools.KolPlanCostProbe 2026-09-04 2026-10-03 true
 * </pre>
 *
 * <p><b>控制台编码提示：</b>Windows GBK 控制台下中文可能显示为乱码，
 * 加 JVM 参数 {@code -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8} 即可 ——
 * 这只是控制台编码问题，<b>不影响查询结果</b>。</p>
 *
 * <p>⚠️ 本探针会<b>真调千川接口</b>（只读、不花钱、不建计划），
 * 且会读<b>共享盘</b>上的《达人巡查配置表》。</p>
 *
 * <p>⚠️ 这是个手工探针，不进自动化测试（{@code pom.xml} 的 surefire 已排除 {@code **&#47;tools/**}）。</p>
 */
public final class KolPlanCostProbe {

    private static final Logger log = LoggerFactory.getLogger(KolPlanCostProbe.class);

    /** 探针专用的假 auth-token（`dingtou.auth-token` 有占位值校验，必须给一个「一看就不是生产」的值）。 */
    private static final String PROBE_AUTH_TOKEN = "probe-only-not-a-real-token";

    private KolPlanCostProbe() {
        // 工具类，禁止实例化
    }

    public static void main(String[] args) {
        LocalDate start = args != null && args.length > 0 ? LocalDate.parse(args[0]) : null;
        LocalDate end = args != null && args.length > 1 ? LocalDate.parse(args[1]) : null;
        boolean withAweme = args != null && args.length > 2 && Boolean.parseBoolean(args[2]);

        log.info("========== KolPlanCostProbe 开始 ==========");
        log.info("统计窗口 = {}", start == null ? "（默认：最近 1 个月）" : start + " ~ " + end);
        log.info("附带达人信息 = {}", withAweme);

        ConfigurableApplicationContext context = null;
        try {
            // ⚠️ auth-token 必须用【命令行参数】注入：.properties(...) 是 defaultProperties、
            //    优先级最低，会被 application.yml 覆盖 → @NotBlank 失败 → 启动直接挂。
            context = new SpringApplicationBuilder(DingtouApplication.class)
                    .web(WebApplicationType.NONE)
                    .run("--dingtou.auth-token=" + PROBE_AUTH_TOKEN, "--server.port=0");

            KolPlanCostService service = context.getBean(KolPlanCostService.class);
            log.info("已取得 KolPlanCostService Bean：{}", service.getClass().getName());
            log.info("开始查询（只读，不花钱）……");

            PlanCostQueryResult result =
                    service.query(new KolPlanCostRequest(start, end, withAweme));
            printResult(result);
        } catch (Exception e) {
            log.error("KolPlanCostProbe 执行失败：{}", e.getMessage(), e);
            log.error("排查提示：确认千川 token 有效、共享盘可达（\\192.168.10.253\\文件共享rpa\\...）。");
            System.exit(1);
        } finally {
            if (context != null) {
                context.close();
            }
        }
    }

    /**
     * 把一行的达人信息格式化成 {@code 抖音号 / 抖音名}。
     *
     * <p>⚠️ 不用 {@link PlanCostRow#hasAweme()} 来判定「有没有」——
     * 实测存在「抖音名有值、抖音号为空」的真实数据，那种情况<b>达人信息其实取到了</b>，
     * 显示成「未取到」会误导。</p>
     */
    private static String formatAweme(PlanCostRow row) {
        if (row.awemeShowId() == null && row.awemeName() == null) {
            return "（未查询）";
        }
        String showId = row.awemeShowId() == null || row.awemeShowId().isBlank()
                ? "-" : row.awemeShowId();
        String name = row.awemeName() == null || row.awemeName().isBlank()
                ? "-" : row.awemeName();
        return showId + " / " + name;
    }

    private static void printResult(PlanCostQueryResult result) {
        log.info("========== 查询结果 ==========");
        log.info("统计窗口        : {} ~ {}", result.windowStart(), result.windowEnd());
        log.info("扫描账户数      : {}", result.accountsScanned());
        log.info("接口返回计划数  : {} 个（过滤前，跨账户跨场景去重后）", result.plansScanned());
        log.info("消耗达标计划数  : {} 个", result.size());

        List<PlanCostRow> rows = result.rows();
        if (rows.isEmpty()) {
            log.info("  —— 没有消耗达标的计划");
        } else {
            // ⚠️ SLF4J 的 {} 不支持宽度对齐（{:<24} 会原样打印），所以这里用 String.format
            boolean anyAweme = rows.stream().anyMatch(PlanCostRow::hasAweme);
            log.info(String.format("  %-4s %-26s %-20s %-30s %10s  %-24s%s",
                    "序", "投放账户", "计划ID", "计划名", "消耗(元)", "出价方式",
                    anyAweme ? "  抖音号 / 抖音名" : ""));
            int index = 1;
            for (PlanCostRow row : rows) {
                log.info(String.format("  %-4d %-26s %-20d %-30s %10.2f  %-24s%s",
                        index++, row.advertiserName(), row.adId(), row.adName(), row.statCost(),
                        row.smartBidType() == null ? "-" : row.smartBidType(),
                        anyAweme ? "  " + formatAweme(row) : ""));
            }
        }

        List<String> warnings = result.warnings();
        if (!warnings.isEmpty()) {
            log.warn("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
            log.warn("[WARN] 有 {} 条异常，本次结果可能不完整！", warnings.size());
            log.warn("[WARN] 不要把「没查到达标计划」当成「确实没有」——漏查的账户可能正好有达标计划！");
            for (int i = 0; i < warnings.size(); i++) {
                log.warn("  warning[{}]: {}", i + 1, warnings.get(i));
            }
            log.warn("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
        } else {
            log.info("warnings        : 无（全部账户查询成功）");
        }
        log.info("========== KolPlanCostProbe 结束 ==========");
    }
}
