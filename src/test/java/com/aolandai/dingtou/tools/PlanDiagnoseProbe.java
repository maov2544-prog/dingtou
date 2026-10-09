package com.aolandai.dingtou.tools;

import com.aolandai.dingtou.DingtouApplication;

import com.aolandai.dingtou.dto.sheet.AccountRow;
import com.aolandai.dingtou.source.AccountDictReader;
import com.bytedance.ads.ApiClient;
import com.bytedance.ads.api.QianchuanAdDetailGetV10Api;
import com.bytedance.ads.model.QianchuanAdDetailGetV10Response;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.List;

/**
 * 诊断探针：<b>按计划 ID 直查计划详情</b>，遍历 8 个账户，把原始返回完整打印。
 *
 * <p><b>为什么有它：</b>{@code planQuery} 出假阴性 / 疑似假阳性时，用它做<b>确定性核对</b> ——
 * {@code QianchuanAdDetailGetV10}（{@code /open_api/v1.0/qianchuan/ad/detail/}）只需
 * {@code advertiserId + adId}，是<b>实时</b>的（列表接口 {@code uni_promotion/list} 实测有约 2 天延迟），
 * 且返回该计划的 {@code aweme_info.aweme_show_id}（抖音号）与 {@code product_info.id}，
 * 一眼就能看出这个计划到底属于哪个达人、挂的哪个商品。</p>
 *
 * <h2>本次诊断的关键结论（写在这里以免后人重踩）</h2>
 * <ol>
 *   <li>计划 {@code 1877813517052328} 在账户 <b>官方【母婴商品】</b>(advertiserId=1823379540880396)；</li>
 *   <li>它是 <b>乘方（{@code OVERALL_PROJECT}）</b>计划 —— 而 {@code uni_promotion/list} 的
 *       {@code adlab_scene} <b>默认只返回 {@code UNI_PROJECT}</b>（全域推广），
 *       所以不显式传 {@code OVERALL_PROJECT} 就<b>永远查不到乘方计划</b>；</li>
 *   <li>它的 {@code marketing_goal=VIDEO_PROM_GOODS}、{@code campaign_scene=DAILY_SALE}、
 *       {@code marketing_scene=FEED}、{@code status=DELIVERY_OK}；</li>
 *   <li>{@code aweme_show_id=610129yiyi}、{@code product_info.id=3593275488396811650}
 *       —— 抖音号形态（带字母）与商品 ID <b>都是对的</b>，不存在「keyword 形态不对」的问题；</li>
 *   <li>通用接口 {@code QianchuanAdGetV10}（{@code /qianchuan/ad/get/}）对这些账户<b>一条都查不到</b>
 *       —— 它是另一个域，这些账户的计划都在 {@code uni_promotion} 域。</li>
 * </ol>
 *
 * <h2>怎么跑</h2>
 * <pre>
 *   mvn -B -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt -Dmdep.includeScope=test
 *   mvn -B -q test-compile
 *   java -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 \
 *     -cp "target/test-classes;target/classes;$(cat target/cp.txt)" \
 *     com.aolandai.dingtou.tools.PlanDiagnoseProbe 1877813517052328 610129yiyi
 * </pre>
 *
 * <p>可选参数：{@code args[0]}=计划 ID、{@code args[1]}=期望的抖音号（可选，用于自动判定是否相等）。</p>
 *
 * <p>⚠️ 只读查询，不花钱、不建计划。需要 {@code QIANCHUAN_SERVICE_BEARER_TOKEN}。
 * 本探针有 {@code main()}、无 {@code @Test}，surefire 已排除 {@code **}{@code /tools/**}。</p>
 */
public final class PlanDiagnoseProbe {

    private static final String BASE_PATH = "https://api.oceanengine.com";
    private static final String PROBE_AUTH_TOKEN = "probe-only-not-a-real-token";

    /** 请求间隔（毫秒），规避频控 40100。 */
    private static final long SLEEP_MILLIS = 1500L;
    private static final int RATE_LIMIT_RETRIES = 4;

    private PlanDiagnoseProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args == null || args.length == 0 || args[0].isBlank()) {
            System.out.println("用法: PlanDiagnoseProbe <adId> [期望的抖音号]");
            return;
        }
        String adId = args[0].trim();
        String expectAweme = (args.length > 1 && !args[1].isBlank()) ? args[1].trim() : null;

        System.out.println("========== PlanDiagnoseProbe（按 adId 直查详情）开始 ==========");
        System.out.println("计划 ID   : " + adId);
        if (expectAweme != null) {
            System.out.println("期望抖音号: " + expectAweme);
        }

        ConfigurableApplicationContext context = null;
        try {
            context = new SpringApplicationBuilder(DingtouApplication.class)
                    .web(WebApplicationType.NONE)
                    .run("--dingtou.auth-token=" + PROBE_AUTH_TOKEN, "--server.port=0",
                        "--logging.file.name=target/probe-logs/probe.log");

            AccountDictReader accountReader = context.getBean(AccountDictReader.class);
            List<AccountRow> accounts = accountReader.readAll();

            String accessToken = ProbeTokens.qianchuan();
            ApiClient client = new ApiClient();
            client.setBasePath(BASE_PATH);
            client.addDefaultHeader("Access-Token", accessToken);
            QianchuanAdDetailGetV10Api detailApi = new QianchuanAdDetailGetV10Api(client);

            String foundAccount = null;
            for (AccountRow account : accounts) {
                Long advertiserId = account.advertiserIdAsLong();
                if (advertiserId == null) {
                    continue;
                }
                QianchuanAdDetailGetV10Response resp = null;
                for (int attempt = 0; ; attempt++) {
                    try {
                        resp = detailApi.openApiV10QianchuanAdDetailGetGet(
                                advertiserId, Long.parseLong(adId), Boolean.TRUE, null);
                    } catch (Exception e) {
                        System.out.println("  账户 " + account.name() + " 调用异常: " + e.getMessage());
                        break;
                    }
                    if (resp != null && resp.getCode() != null && resp.getCode() == 40100L
                            && attempt < RATE_LIMIT_RETRIES) {
                        sleep(SLEEP_MILLIS * (attempt + 2));
                        continue;
                    }
                    break;
                }
                if (resp != null && resp.getData() != null) {
                    foundAccount = account.name();
                    System.out.println();
                    System.out.println("命中账户: " + account.name()
                            + " (advertiserId=" + advertiserId + ", 店铺=" + account.shop() + ")");
                    System.out.println("RAW_JSON = " + resp.getData().toJson());
                    if (expectAweme != null) {
                        String json = resp.getData().toJson();
                        boolean contains = json.contains(expectAweme);
                        System.out.println("抖音号 '" + expectAweme + "' 出现在返回里 ? " + contains
                                + (contains ? "  ← 该计划【属于】这个达人"
                                            : "  ← 该计划【不属于】这个达人（疑似假阳性！）"));
                    }
                    break;
                }
                sleep(SLEEP_MILLIS);
            }

            System.out.println();
            System.out.println("========== 诊断汇总 ==========");
            System.out.println("计划 " + adId + " 命中的账户: "
                    + (foundAccount == null ? "无（8 个账户都查不到）" : foundAccount));
            System.out.println("========== PlanDiagnoseProbe 结束 ==========");
        } finally {
            if (context != null) {
                context.close();
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
