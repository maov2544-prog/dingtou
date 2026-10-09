package com.aolandai.dingtou.tools;

import com.aolandai.dingtou.DingtouApplication;
import com.aolandai.dingtou.dto.qianchuan.PlanAweme;
import com.aolandai.dingtou.dto.qianchuan.PlanAwemeLookupResult;
import com.aolandai.dingtou.dto.qianchuan.PlanRef;
import com.aolandai.dingtou.service.qianchuan.PlanAwemeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 手工探针：验证 {@code planAwemeLookup}（按【投放账户 + 计划 ID】查达人信息）。
 *
 * <pre>
 * 参数格式：advertiserId:adId（可传多个）
 *
 * java -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 \
 *   -cp "target/test-classes;target/classes;&lt;依赖&gt;" \
 *   com.aolandai.dingtou.tools.PlanAwemeLookupProbe 1823379540880396:1877813517052328
 * </pre>
 *
 * <p>不传参数时用内置的实测组合（官方【母婴商品】+ 已知计划）。</p>
 *
 * <p>⚠️ 本探针会<b>真调千川接口</b>（只读、不花钱），需要有效的千川 token。</p>
 *
 * <p>⚠️ 手工探针，不进自动化测试（surefire 已排除 {@code **&#47;tools/**}）。</p>
 */
public final class PlanAwemeLookupProbe {

    private static final Logger log = LoggerFactory.getLogger(PlanAwemeLookupProbe.class);

    /** 探针专用假 auth-token（`dingtou.auth-token` 有占位值校验）。 */
    private static final String PROBE_AUTH_TOKEN = "probe-only-not-a-real-token";

    /** 默认组合：已实测跑通过（官方【母婴商品】+ 伊伊麻麻的计划）。 */
    private static final String DEFAULT_REF = "1823379540880396:1877813517052328";

    private PlanAwemeLookupProbe() {
    }

    public static void main(String[] args) {
        List<PlanRef> refs = parse(args);
        log.info("========== PlanAwemeLookupProbe 开始 ==========");
        log.info("待查计划 {} 个：{}", refs.size(), refs);

        ConfigurableApplicationContext context = null;
        try {
            // ⚠️ auth-token 必须用【命令行参数】注入（.properties(...) 优先级最低，会被 yml 覆盖）
            context = new SpringApplicationBuilder(DingtouApplication.class)
                    .web(WebApplicationType.NONE)
                    .run("--dingtou.auth-token=" + PROBE_AUTH_TOKEN, "--server.port=0");

            PlanAwemeService service = context.getBean(PlanAwemeService.class);
            log.info("已取得 PlanAwemeService Bean：{}", service.getClass().getName());
            log.info("开始查询（只读，不花钱）……");

            printResult(service.lookupAll(refs));
        } catch (Exception e) {
            log.error("PlanAwemeLookupProbe 执行失败：{}", e.getMessage(), e);
            System.exit(1);
        } finally {
            if (context != null) {
                context.close();
            }
        }
    }

    private static List<PlanRef> parse(String[] args) {
        List<PlanRef> refs = new ArrayList<>();
        if (args == null || args.length == 0) {
            refs.add(parseOne(DEFAULT_REF));
            return refs;
        }
        for (String arg : args) {
            refs.add(parseOne(arg));
        }
        return refs;
    }

    private static PlanRef parseOne(String raw) {
        String[] parts = raw.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("参数格式必须是 advertiserId:adId，实际: " + raw);
        }
        return new PlanRef(Long.parseLong(parts[0].trim()), Long.parseLong(parts[1].trim()));
    }

    private static void printResult(PlanAwemeLookupResult result) {
        log.info("========== 查询结果 ==========");
        log.info("请求计划数      : {}", result.requested());
        log.info("查到达人信息数  : {}", result.resolved());

        List<PlanAweme> items = result.items();
        if (items.isEmpty()) {
            log.info("  —— 没有查到任何达人信息");
        } else {
            log.info(String.format("  %-4s %-22s %-18s %s", "序", "计划ID", "抖音号", "抖音名"));
            int index = 1;
            for (PlanAweme item : items) {
                log.info(String.format("  %-4d %-22d %-18s %s",
                        index++, item.adId(), item.awemeShowId(), item.awemeName()));
            }
        }

        List<String> warnings = result.warnings();
        if (!warnings.isEmpty()) {
            log.warn("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
            log.warn("[WARN] 有 {} 条异常，本次结果可能不完整！", warnings.size());
            log.warn("[WARN] 不要把「没查到达人」当成「该计划没有达人」！");
            for (int i = 0; i < warnings.size(); i++) {
                log.warn("  warning[{}]: {}", i + 1, warnings.get(i));
            }
            log.warn("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
        } else {
            log.info("warnings        : 无（全部计划查询成功）");
        }
        log.info("========== PlanAwemeLookupProbe 结束 ==========");
    }
}
