package com.aolandai.dingtou.tools;

import com.aolandai.dingtou.DingtouApplication;
import com.aolandai.dingtou.dto.plan.PlanBuildParam;
import com.aolandai.dingtou.dto.plan.PlanBuildResult;
import com.aolandai.dingtou.dto.sheet.CellWrite;
import com.aolandai.dingtou.service.plan.PlanBuildService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.LocalDate;
import java.util.List;

/**
 * 手工探针：验证 {@code planBuild}（B / C1 / C2：建乘方计划 / 加素材 / 改名）。
 *
 * <pre>
 * 不传参数时用素材表里【投放类型为空】的真实行：
 *   行 4992：抖音号 272886488 + 益生元身体乳（全域账号=婴童【儿童-商品】）
 *
 * java -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 \
 *   -cp "target/test-classes;target/classes;&lt;依赖&gt;" \
 *   com.aolandai.dingtou.tools.PlanBuildProbe [抖音号] [商品名] [视频id]
 * </pre>
 *
 * <p>⚠️ <b>默认 dry-run</b>（{@code dingtou.dry-run=true}）：只组装请求体、<b>不会真建计划</b>。
 * 要真建必须先把配置里的 {@code dry-run} 改成 false —— 那是<b>花钱</b>的。</p>
 *
 * <p>⚠️ 手工探针，不进自动化测试（surefire 已排除 {@code **&#47;tools/**}）。</p>
 */
public final class PlanBuildProbe {

    private static final Logger log = LoggerFactory.getLogger(PlanBuildProbe.class);

    /** 探针专用假 auth-token（`dingtou.auth-token` 有占位值校验）。 */
    private static final String PROBE_AUTH_TOKEN = "probe-only-not-a-real-token";

    /** 默认抖音号 —— 素材表里「投放类型」为空、且有账户的真实行。 */
    private static final String DEFAULT_AWEME = "272886488";

    /** 默认商品名 —— 同上。 */
    private static final String DEFAULT_PRODUCT = "益生元身体乳";

    private PlanBuildProbe() {
    }

    public static void main(String[] args) {
        String awemeShowId = arg(args, 0, DEFAULT_AWEME);
        String productName = arg(args, 1, DEFAULT_PRODUCT);
        String videoIdRaw = arg(args, 2, "");

        log.info("========== PlanBuildProbe 开始 ==========");
        log.info("抖音号 = {}，商品名 = {}，videoId = {}", awemeShowId, productName,
                videoIdRaw.isEmpty() ? "（不传，由服务自己定位「本条」）" : videoIdRaw);

        ConfigurableApplicationContext context = null;
        try {
            // ⚠️ auth-token 必须用【命令行参数】注入（.properties() 优先级最低，会被 yml 覆盖）
            context = new SpringApplicationBuilder(DingtouApplication.class)
                    .web(WebApplicationType.NONE)
                    .run("--dingtou.auth-token=" + PROBE_AUTH_TOKEN, "--server.port=0",
                        "--logging.file.name=target/probe-logs/probe.log");

            PlanBuildService service = context.getBean(PlanBuildService.class);
            log.info("已取得 PlanBuildService Bean：{}", service.getClass().getName());
            log.info("开始执行（dry-run 时不会真建计划/加素材/改名）……");

            Long videoId = videoIdRaw.isEmpty() ? null : Long.parseLong(videoIdRaw.trim());
            printResult(service.build(PlanBuildParam.of(awemeShowId, productName, videoId,
                    LocalDate.now())));
        } catch (Exception e) {
            log.error("PlanBuildProbe 执行失败：{}", e.getMessage(), e);
            System.exit(1);
        } finally {
            if (context != null) {
                context.close();
            }
        }
    }

    private static String arg(String[] args, int index, String fallback) {
        if (args == null || args.length <= index || args[index] == null || args[index].isBlank()) {
            return fallback;
        }
        return args[index].trim();
    }

    private static void printResult(PlanBuildResult r) {
        log.info("========== 执行结果 ==========");
        log.info("抖音号 / 商品名   : {} / {}", r.awemeShowId(), r.productName());
        log.info("投放账户          : {}（id={}）", r.advertiserName(), r.advertiserId());
        log.info("达人数值UID       : {}", r.awemeUid());
        log.info("商品ID            : {}", r.productId());
        log.info("计划名            : {}", r.planName());
        log.info("动作              : {}", r.action() == null ? "（没执行）" : r.action());
        log.info("计划ID            : {}", r.adId() == null ? "（无）" : r.adId());
        log.info("本次加入素材      : {} 条", r.materialCount());
        log.info("是否演练          : {}", r.dryRun() ? "是（没有真调）" : "否");

        if (!r.requestBody().isEmpty()) {
            log.info("即将发出的请求体  : {}", r.requestBody());
            log.warn("⚠️ 请核对上面的预算 / ROI / 素材是否如预期，"
                    + "确认无误后再把 dry-run 改成 false —— 建计划是花钱的");
        }

        List<CellWrite> cells = r.cells();
        log.info("请影刀写入的单元格: {} 个", cells.size());
        for (CellWrite cell : cells) {
            log.info("  - {}", cell.describe());
        }

        List<String> warnings = r.warnings();
        if (!warnings.isEmpty()) {
            log.warn("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
            log.warn("[WARN] 有 {} 条异常，本次【没有全部办成】！", warnings.size());
            for (int i = 0; i < warnings.size(); i++) {
                log.warn("  warning[{}]: {}", i + 1, warnings.get(i));
            }
            log.warn("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
        } else {
            log.info("warnings          : 无");
        }
        log.info("========== PlanBuildProbe 结束 ==========");
    }
}
