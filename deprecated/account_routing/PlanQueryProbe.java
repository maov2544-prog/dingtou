package com.aolandai.dingtou.tools;

import com.aolandai.dingtou.DingtouApplication;
import com.aolandai.dingtou.dto.qianchuan.PlanInfo;
import com.aolandai.dingtou.dto.qianchuan.PlanQueryOutcome;
import com.aolandai.dingtou.service.qianchuan.PlanQueryService;
import com.aolandai.dingtou.store.PlanEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.List;

/**
 * 手工探针：验证 {@code planQuery}（「抖音号 + 商品名」→ 是否已有千川计划）。
 *
 * <p><b>为什么有它：</b>{@code planQuery} 以前只能通过 HTTP curl 调，排障时很别扭；
 * 这个探针让人能直接在 IDE 里跑 {@code main()}，把接口返回的人类可读结果打到日志上。</p>
 *
 * <h2>怎么跑</h2>
 * <pre>
 * 方式一（默认参数，已实测跑通的组合）：
 *   直接 run PlanQueryProbe.main()
 *
 * 方式二（指定抖音号 + 商品名）：
 *   run PlanQueryProbe.main("86826747130", "燕窝润唇膏")
 *
 * 方式三（命令行）：
 *   java -cp "target/classes;target/test-classes;&lt;依赖&gt;" \
 *       com.aolandai.dingtou.tools.PlanQueryProbe 86826747130 燕窝润唇膏
 * </pre>
 *
 * <p><b>控制台编码提示：</b>Windows GBK 控制台下，个别中文日志可能显示为乱码
 * （如「开始」变「¿ªÊ¼」）。加 JVM 参数 {@code -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8}
 * 即可正常显示 —— 这只是控制台编码问题，<b>不影响查询结果</b>。</p>
 *
 * <p>⚠️ <b>这是个手工探针，不进自动化测试。</b> 它有 {@code main()}、没有 {@code @Test}，
 * 且 {@code pom.xml} 的 surefire 已按目录排除 {@code **}{@code /tools/**}，
 * 保证它永远不会被 {@code mvn test} 当成自动化测试跑。</p>
 *
 * <p>⚠️ 本探针会<b>真调千川接口</b>（只读、不花钱、不建计划），需要有效的随心推/千川 token 配置。</p>
 */
public final class PlanQueryProbe {

    private static final Logger log = LoggerFactory.getLogger(PlanQueryProbe.class);

    /** 默认抖音号 —— 与 {@link #DEFAULT_PRODUCT_NAME} 的组合已在真账户上实测跑通过。 */
    private static final String DEFAULT_AWEME_SHOW_ID = "610129yiyi";

    /** 默认商品名 —— 与 {@link #DEFAULT_AWEME_SHOW_ID} 的组合已在真账户上实测跑通过。 */
    private static final String DEFAULT_PRODUCT_NAME = "护臀膏";

    /**
     * 探针专用的假 auth-token。
     *
     * <p>⚠️ {@code dingtou.auth-token} 是必填、且有占位值校验（不能是 change-me/test/123456，
     * 长度须 ≥ 8），所以这里显式给一个「一看就知道是探针专用」的值，避免误用到生产。</p>
     */
    private static final String PROBE_AUTH_TOKEN = "probe-only-not-a-real-token";

    private PlanQueryProbe() {
        // 工具类，禁止实例化
    }

    /**
     * 探针入口。
     *
     * @param args {@code args[0]}=抖音号、{@code args[1]}=商品名；不传则用默认值
     *             （{@value #DEFAULT_AWEME_SHOW_ID} + {@value #DEFAULT_PRODUCT_NAME}）
     */
    public static void main(String[] args) {
        String awemeShowId = pickArg(args, 0, DEFAULT_AWEME_SHOW_ID);
        String productName = pickArg(args, 1, DEFAULT_PRODUCT_NAME);

        log.info("========== PlanQueryProbe 开始 ==========");
        log.info("抖音号 = {}，商品名 = {}", awemeShowId, productName);

        // 用非 Web 上下文启动：探针只需要 Spring 容器里的 Bean，不需要起 Tomcat。
        //
        // ⚠️ auth-token 必须用【命令行参数】( --key=value ) 注入，不能用 .properties(...)：
        //    .properties(...) 设置的是 defaultProperties，优先级最低，会被 application.yml 里的
        //    `auth-token: ${DINGTOU_AUTH_TOKEN:}`（默认空串）覆盖 → @NotBlank 校验失败、启动直接挂。
        //    命令行参数优先级最高，能可靠压过 application.yml，从而让探针自带假 token 跑起来。
        // server.port=0 一并给出，防止有组件仍尝试绑定端口。
        ConfigurableApplicationContext context = null;
        try {
            context = new SpringApplicationBuilder(DingtouApplication.class)
                    .web(WebApplicationType.NONE)
                    .run("--dingtou.auth-token=" + PROBE_AUTH_TOKEN, "--server.port=0");

            PlanQueryService planQueryService = context.getBean(PlanQueryService.class);

            log.info("已取得 PlanQueryService Bean：{}", planQueryService.getClass().getName());
            log.info("开始查询（只读，不花钱）……");

            PlanQueryOutcome outcome = planQueryService.query(awemeShowId, productName);

            printOutcome(outcome);
        } catch (Exception e) {
            // 换取 token 失败 / 千川接口报错 / Bean 缺失都会走到这里。
            log.error("PlanQueryProbe 执行失败：{}", e.getMessage(), e);
            log.error("排查提示：确认 token 配置有效、网络可达，且 {} 组合确实在授权账户下。",
                    "「" + awemeShowId + " + " + productName + "」");
            System.exit(1);
        } finally {
            if (context != null) {
                context.close();
            }
        }
    }

    /**
     * 打印人类可读的查询结果。
     *
     * <p>⚠️ warnings 代表「有账户没查成功」——如果被忽略，会被误当成「该组合没有计划」，
     * 进而重复建计划（花钱）。所以这里<b>强制把 warnings 全部打印且显眼</b>。</p>
     */
    private static void printOutcome(PlanQueryOutcome outcome) {
        log.info("========== 查询结果 ==========");
        log.info("抖音号            : {}", outcome.awemeShowId());
        log.info("商品名            : {}", outcome.productName());
        log.info("解析出的 productId: {}", outcome.productId().isEmpty() ? "（未解析到）" : outcome.productId());
        log.info("扫描的账户数      : {}", outcome.accountsScanned());
        // ⚠️ 接口总共返回几个（未按商品过滤）—— 区分「接口没返回」与「返回了但商品没匹配上」
        log.info("接口返回计划数    : {} 个（未按商品过滤）", outcome.rawPlanCount());

        List<PlanInfo> plans = outcome.plans();
        log.info("命中的千川计划    : {} 个", plans.size());
        if (plans.isEmpty()) {
            if (outcome.productMismatchOnly()) {
                log.warn("  —— 接口有返回（{} 个），但商品 ID 全都没匹配上！"
                        + "这不是「无计划」，是商品维度有问题。", outcome.rawPlanCount());
            } else {
                log.info("  —— 暂无千川计划（接口未查到）");
            }
        } else {
            int index = 1;
            for (PlanInfo plan : plans) {
                log.info("  {}. adId={} | name={} | status={} | 投放中={} | 商品={}",
                        index++,
                        plan.adId(),
                        plan.name(),
                        plan.status(),
                        plan.isDelivering() ? "是" : "否",
                        plan.productIds());
            }
        }

        // 接口返回了、但商品没匹配上的计划（排查商品映射/挂载问题用）
        List<PlanInfo> unmatched = outcome.unmatchedPlans();
        if (!unmatched.isEmpty()) {
            log.warn("商品未匹配的计划  : {} 个（接口返回了，但 productInfo 不含目标商品 ID）",
                    unmatched.size());
            int index = 1;
            for (PlanInfo plan : unmatched) {
                log.warn("  {}. adId={} | name={} | status={} | 商品={}",
                        index++, plan.adId(), plan.name(), plan.status(), plan.productIds());
            }
        }

        // 本地台账命中（仅作参考：台账与真实状态可能不一致，计划可能已被人工删除）
        if (outcome.ledgerHit().isPresent()) {
            PlanEntry entry = outcome.ledgerHit().get();
            log.info("本地台账命中      : adId={} | planName={} | advertiserId={} | updatedAt={}",
                    entry.adId(), entry.planName(), entry.advertiserId(), entry.updatedAt());
        } else {
            log.info("本地台账命中      : 无");
        }

        // ⚠️ warnings 必须全部打印且显眼
        List<String> warnings = outcome.warnings();
        if (!warnings.isEmpty()) {
            log.warn("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
            log.warn("[WARN] 有 {} 条账户查询未成功，本次结果可能不完整！", warnings.size());
            log.warn("[WARN] 不要把「没查到计划」当成「该组合没有计划」——否则可能重复建计划（花钱）！");
            for (int i = 0; i < warnings.size(); i++) {
                log.warn("  warning[{}]: {}", i + 1, warnings.get(i));
            }
            log.warn("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
        } else {
            log.info("warnings          : 无（全部账户查询成功）");
        }

        log.info("========== 结论 ==========");
        if (outcome.hasPlan()) {
            log.info("该组合【已有】千川计划，不要重复建。");
        } else if (!warnings.isEmpty()) {
            log.warn("该组合【未查到】千川计划，但有 {} 条账户查询失败，结果不可靠，"
                    + "请先排掉 warnings 再判断。", warnings.size());
        } else if (outcome.productMismatchOnly()) {
            log.warn("该组合【未查到】匹配商品 {} 的计划，但接口返回了 {} 个计划 ——"
                    + "这不是「无计划」，请核对商品 ID 映射与计划挂载的商品。",
                    outcome.productId(), outcome.rawPlanCount());
        } else {
            log.info("该组合【暂无】千川计划，可正常创建。");
        }
        log.info("========== PlanQueryProbe 结束 ==========");
    }

    /** 取命令行参数；缺失或空白时回退到默认值。 */
    private static String pickArg(String[] args, int index, String defaultValue) {
        if (args == null || index >= args.length) {
            return defaultValue;
        }
        String value = args[index];
        return (value == null || value.isBlank()) ? defaultValue : value.trim();
    }
}
