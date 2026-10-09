package com.aolandai.dingtou.tools;

import com.aolandai.dingtou.DingtouApplication;
import com.aolandai.dingtou.dto.sheet.CellWrite;
import com.aolandai.dingtou.dto.kol.SuixintuiInvestResult;
import com.aolandai.dingtou.service.kol.SuixintuiInvestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.List;

/**
 * 手工探针：验证 {@code suixintuiCreate}（A 场景：建随心推 + 返回回写指令）。
 *
 * <pre>
 * 不传参数时用素材表里【投放类型为空】的真实行：
 *   行 4992：抖音号 272886488 + 益生元身体乳（全域账号=婴童【儿童-商品】）
 *
 * java -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 \
 *   -cp "target/test-classes;target/classes;&lt;依赖&gt;" \
 *   com.aolandai.dingtou.tools.SuixintuiCreateProbe [抖音号] [商品名]
 * </pre>
 *
 * <p>⚠️ <b>默认 dry-run</b>（{@code dingtou.dry-run=true}）：只组装请求体、<b>不会真建单</b>。
 * 要真建必须先把配置里的 {@code dry-run} 改成 false —— 那是<b>花钱</b>的。</p>
 *
 * <p>⚠️ 手工探针，不进自动化测试（surefire 已排除 {@code **&#47;tools/**}）。</p>
 */
public final class SuixintuiCreateProbe {

    private static final Logger log = LoggerFactory.getLogger(SuixintuiCreateProbe.class);

    /** 探针专用假 auth-token（`dingtou.auth-token` 有占位值校验）。 */
    private static final String PROBE_AUTH_TOKEN = "probe-only-not-a-real-token";

    /** 默认抖音号 —— 素材表里「投放类型」为空、且有账户的真实行。 */
    private static final String DEFAULT_AWEME = "272886488";

    /** 默认商品名 —— 同上。 */
    private static final String DEFAULT_PRODUCT = "益生元身体乳";

    private SuixintuiCreateProbe() {
    }

    public static void main(String[] args) {
        String awemeShowId = args != null && args.length > 0 && !args[0].isBlank()
                ? args[0].trim() : DEFAULT_AWEME;
        String productName = args != null && args.length > 1 && !args[1].isBlank()
                ? args[1].trim() : DEFAULT_PRODUCT;

        log.info("========== SuixintuiCreateProbe 开始 ==========");
        log.info("抖音号 = {}，商品名 = {}", awemeShowId, productName);

        ConfigurableApplicationContext context = null;
        try {
            // ⚠️ auth-token 必须用【命令行参数】注入（.properties() 优先级最低，会被 yml 覆盖）
            context = new SpringApplicationBuilder(DingtouApplication.class)
                    .web(WebApplicationType.NONE)
                    .run("--dingtou.auth-token=" + PROBE_AUTH_TOKEN, "--server.port=0",
                        "--logging.file.name=target/probe-logs/probe.log");

            SuixintuiInvestService service = context.getBean(SuixintuiInvestService.class);
            log.info("已取得 SuixintuiInvestService Bean：{}", service.getClass().getName());
            log.info("开始执行（dry-run 时不会真建单）……");

            printResult(service.invest(awemeShowId, productName));
        } catch (Exception e) {
            log.error("SuixintuiCreateProbe 执行失败：{}", e.getMessage(), e);
            System.exit(1);
        } finally {
            if (context != null) {
                context.close();
            }
        }
    }

    private static void printResult(SuixintuiInvestResult r) {
        log.info("========== 执行结果 ==========");
        log.info("抖音号 / 商品名   : {} / {}", r.awemeShowId(), r.productName());
        log.info("投放账户          : {}（id={}）", r.advertiserName(), r.advertiserId());
        log.info("达人数值UID       : {}", r.awemeId());
        log.info("商品ID            : {}", r.productId());
        log.info("预算 / 时长       : {} / {}", r.amount(), r.deliveryTime());
        log.info("出价方式 / ROI    : {} / {}", r.bidType().isBlank() ? "（不传）" : r.bidType(),
                r.roiGoal() == null ? "（不传）" : r.roiGoal());
        log.info("是否演练          : {}", r.dryRun() ? "是（没有真建单）" : "否");
        log.info("随心推订单ID      : {}", r.orderId() == null ? "（无）" : r.orderId());

        if (!r.requestBody().isEmpty()) {
            log.info("即将发出的请求体  : {}", r.requestBody());
            log.info("单位已由官方文档确认：amount = 元（[100, 5000000] 且 10 的倍数），"
                    + "delivery_time = 小时（白名单 2/6/12/24/48/72/96/120/144/168），"
                    + "roi_goal = 支付ROI（[0.01, 10000] 且最多 2 位小数；MANUAL_BID 时必填）");
            log.warn("⚠️ 仍建议与千川后台的实际效果对一眼（预算 / 时长 / 出价方式 / ROI 是否如预期），"
                    + "确认无误后再把 dry-run 改成 false —— 建单是花钱的");
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
        log.info("========== SuixintuiCreateProbe 结束 ==========");
    }
}
