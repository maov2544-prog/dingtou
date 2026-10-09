package com.aolandai.dingtou.tools;

import com.bytedance.ads.ApiClient;
import com.bytedance.ads.api.QianchuanUniPromotionListV10Api;
import com.bytedance.ads.model.QianchuanUniPromotionListV10AdlabScene;
import com.bytedance.ads.model.QianchuanUniPromotionListV10Fields;
import com.bytedance.ads.model.QianchuanUniPromotionListV10Filtering;
import com.bytedance.ads.model.QianchuanUniPromotionListV10FilteringHavingCost;
import com.bytedance.ads.model.QianchuanUniPromotionListV10FilteringStatus;
import com.bytedance.ads.model.QianchuanUniPromotionListV10MarketingGoal;
import com.bytedance.ads.model.QianchuanUniPromotionListV10OrderField;
import com.bytedance.ads.model.QianchuanUniPromotionListV10OrderType;
import com.bytedance.ads.model.QianchuanUniPromotionListV10PageSize;
import com.bytedance.ads.model.QianchuanUniPromotionListV10Response;
import com.bytedance.ads.model.QianchuanUniPromotionListV10ResponseDataAdListInner;
import com.aolandai.dingtou.client.QianchuanTokenClient;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 一次性诊断探针：<b>只看第 1 页</b>，回答三个问题 ——
 *
 * <ol>
 *   <li>接口报的 {@code total_page} 到底是多少？（我们一直翻到 20 页，想确认是不是真的）</li>
 *   <li>{@code having_cost=YES} 是否真的生效？（若返回的计划里 {@code stat_cost} 为 0/空，
 *       说明过滤没生效，我们在白翻大量无关计划）</li>
 *   <li>{@code fields=STAT_COST} 是否真的把消耗带回来了？</li>
 * </ol>
 *
 * <pre>
 * java -cp "..." com.aolandai.dingtou.tools.PlanListPageProbe &lt;advertiserId&gt; [UNI_PROJECT|OVERALL_PROJECT]
 * </pre>
 *
 * <p>⚠️ 只读、只发 1 次请求。有 {@code main()}、无 {@code @Test}，surefire 已排除 {@code **&#47;tools/**}。</p>
 */
public final class PlanListPageProbe {

    private static final String BASE_PATH = "https://api.oceanengine.com";
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private PlanListPageProbe() {
    }

    public static void main(String[] args) throws Exception {
        long advertiserId = Long.parseLong(args[0]);
        QianchuanUniPromotionListV10AdlabScene scene = args.length > 1
                ? QianchuanUniPromotionListV10AdlabScene.valueOf(args[1])
                : QianchuanUniPromotionListV10AdlabScene.OVERALL_PROJECT;

        String endTime = LocalDateTime.now().format(FMT);
        String startTime = LocalDateTime.now().minusDays(30).format(FMT);

        ApiClient client = new ApiClient();
        client.setBasePath(BASE_PATH);
        client.addDefaultHeader("Access-Token", QianchuanTokenClient.getAccessToken());
        QianchuanUniPromotionListV10Api api = new QianchuanUniPromotionListV10Api(client);

        QianchuanUniPromotionListV10Filtering filtering = new QianchuanUniPromotionListV10Filtering();
        filtering.setStatus(QianchuanUniPromotionListV10FilteringStatus.DELIVERY_OK);
        filtering.setHavingCost(QianchuanUniPromotionListV10FilteringHavingCost.YES);

        QianchuanUniPromotionListV10Response resp = api.openApiV10QianchuanUniPromotionListGet(
                advertiserId, startTime, endTime,
                QianchuanUniPromotionListV10MarketingGoal.VIDEO_PROM_GOODS,
                List.of(QianchuanUniPromotionListV10Fields.STAT_COST),
                filtering,
                null,
                QianchuanUniPromotionListV10OrderType.DESC,       // 排序方向
                QianchuanUniPromotionListV10OrderField.STAT_COST, // 排序字段：整体消耗
                1,
                QianchuanUniPromotionListV10PageSize.NUMBER_100, scene, null);

        System.out.println("code       = " + resp.getCode() + " / " + resp.getMessage());
        if (resp.getData() == null) {
            System.out.println("data = null");
            return;
        }
        var pageInfo = resp.getData().getPageInfo();
        System.out.println("pageInfo   = " + (pageInfo == null ? "null" : pageInfo.toJson()));
        List<QianchuanUniPromotionListV10ResponseDataAdListInner> adList = resp.getData().getAdList();
        int size = adList == null ? 0 : adList.size();
        System.out.println("本页条数   = " + size);

        int zeroCost = 0;
        int nullCost = 0;
        for (QianchuanUniPromotionListV10ResponseDataAdListInner p : adList) {
            Double c = p.getStatsInfo() == null ? null : p.getStatsInfo().getStatCost();
            if (c == null) {
                nullCost++;
            } else if (c == 0.0) {
                zeroCost++;
            }
        }
        System.out.println("stat_cost 为空 = " + nullCost + "，为 0 = " + zeroCost
                + "（若 >0 说明 having_cost=YES 可能没生效，我们在白翻）");

        System.out.println("--- 前 3 条：所有「类型」相关字段 ---");
        for (int i = 0; i < Math.min(3, size); i++) {
            var ai = adList.get(i).getAdInfo();
            System.out.println("  [" + i + "] " + ai.getName());
            System.out.println("      adlabScene(场景)          = " + ai.getAdlabScene());
            System.out.println("      marketingGoal(营销目标)    = " + ai.getMarketingGoal());
            System.out.println("      marketingServicesMode    = " + ai.getMarketingServicesMode());
            System.out.println("      smartBidType(出价方式)     = " + ai.getSmartBidType());
            System.out.println("      deepExternalAction       = " + ai.getDeepExternalAction());
            System.out.println("      optStatus(操作状态)        = " + ai.getOptStatus());
            System.out.println("      status(投放状态)           = " + ai.getStatus());
            System.out.println("      budgetMode               = " + ai.getBudgetMode());
        }
        long withSmartBid = 0;
        for (var p : adList) {
            if (p.getAdInfo() != null && p.getAdInfo().getSmartBidType() != null) {
                withSmartBid++;
            }
        }
        System.out.println("本页 smartBidType 非空条数 = " + withSmartBid + " / " + size);
        System.out.println("--- 后 5 条 statCost ---");
        for (int i = Math.max(0, size - 5); i < size; i++) {
            var p = adList.get(i);
            System.out.println("  " + i + ". adId=" + p.getAdInfo().getId()
                    + " statCost=" + (p.getStatsInfo() == null ? "-" : p.getStatsInfo().getStatCost()));
        }
    }
}
