package com.aolandai.dingtou.service.qianchuan;

import com.aolandai.dingtou.client.QianchuanThrottle;
import com.aolandai.dingtou.config.QianchuanProperties;
import com.bytedance.ads.ApiException;
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
import com.bytedance.ads.model.QianchuanUniPromotionListV10ResponseData;
import com.bytedance.ads.model.QianchuanUniPromotionListV10ResponseDataAdListInner;
import com.bytedance.ads.model.QianchuanUniPromotionListV10ResponseDataAdListInnerAdInfo;
import com.bytedance.ads.model.QianchuanUniPromotionListV10ResponseDataAdListInnerProductInfoInner;
import com.bytedance.ads.model.QianchuanUniPromotionListV10ResponseDataAdListInnerStatsInfo;
import com.bytedance.ads.model.QianchuanUniPromotionListV10ResponseDataPageInfo;
import com.aolandai.dingtou.dto.qianchuan.QianchuanPlanQueryResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link QianchuanPlanService} 单测：用假 fetcher 注入，<b>绝不真调千川</b>。
 *
 * <p>覆盖本次 bug 修复的核心点：</p>
 * <ul>
 *   <li><b>乘方（OVERALL_PROJECT）场景必须被查到</b> —— 这是本次假阴性的根因；</li>
 *   <li>两个场景都要查、且都用 AWEME（抖音号）关键字；</li>
 *   <li>「接口返回了但商品没匹配上」要能被观察（rawPlanCount / unmatchedPlans）；</li>
 *   <li>SDK 的 {@code code != 0} 业务错误必须抛 {@link ApiException}，不能静默成 0 个计划；</li>
 *   <li>时间参数必须是 {@code yyyy-MM-dd HH:mm:ss} 且窗口 ≤ 180 天。</li>
 * </ul>
 */
class QianchuanPlanServiceTest {

    private static final Long ADVERTISER_ID = 1823379540880396L;
    private static final String AWEME = "610129yiyi";
    private static final String PRODUCT = "3593275488396811650";
    private static final long TARGET_AD_ID = 1877813517052328L;

    /** 默认查询场景：全域推广 + 乘方。 */
    private static final List<QianchuanUniPromotionListV10AdlabScene> DEFAULT_SCENES = List.of(
            QianchuanUniPromotionListV10AdlabScene.UNI_PROJECT,
            QianchuanUniPromotionListV10AdlabScene.OVERALL_PROJECT
    );

    /** 假 fetcher：按场景返回预置响应，并记录调用参数。 */
    private static final class FakeFetcher implements QianchuanPlanService.PlanListFetcher {
        final Map<QianchuanUniPromotionListV10AdlabScene, QianchuanUniPromotionListV10Response>
                byScene = new EnumMap<>(QianchuanUniPromotionListV10AdlabScene.class);
        final List<QianchuanUniPromotionListV10AdlabScene> scenes = new ArrayList<>();
        final List<QianchuanUniPromotionListV10MarketingGoal> goals = new ArrayList<>();
        final List<String> keywords = new ArrayList<>();
        final List<String> startTimes = new ArrayList<>();
        final List<String> endTimes = new ArrayList<>();
        final List<QianchuanUniPromotionListV10Filtering> filterings = new ArrayList<>();

        @Override
        public QianchuanUniPromotionListV10Response fetch(
                Long advertiserId,
                String startTime,
                String endTime,
                QianchuanUniPromotionListV10MarketingGoal goal,
                List<QianchuanUniPromotionListV10Fields> fields,
                QianchuanUniPromotionListV10Filtering filtering,
                QianchuanUniPromotionListV10OrderType orderType,
                QianchuanUniPromotionListV10OrderField orderField,
                int page,
                QianchuanUniPromotionListV10PageSize pageSize,
                QianchuanUniPromotionListV10AdlabScene scene) {
            scenes.add(scene);
            goals.add(goal);
            keywords.add(filtering == null ? null : filtering.getSearchKeyword());
            filterings.add(filtering);
            startTimes.add(startTime);
            endTimes.add(endTime);
            return byScene.getOrDefault(scene, emptyResponse());
        }
    }

    /** 按调用顺序返回预置响应的 fetcher（用于测试频控重试等「首次失败」场景）。 */
    private static final class SequencingFetcher implements QianchuanPlanService.PlanListFetcher {
        private final List<QianchuanUniPromotionListV10Response> responses;
        private int index = 0;

        SequencingFetcher(QianchuanUniPromotionListV10Response... responses) {
            this.responses = new ArrayList<>(Arrays.asList(responses));
        }

        @Override
        public QianchuanUniPromotionListV10Response fetch(
                Long advertiserId, String startTime, String endTime,
                QianchuanUniPromotionListV10MarketingGoal goal,
                List<QianchuanUniPromotionListV10Fields> fields,
                QianchuanUniPromotionListV10Filtering filtering,
                QianchuanUniPromotionListV10OrderType orderType,
                QianchuanUniPromotionListV10OrderField orderField, int page,
                QianchuanUniPromotionListV10PageSize pageSize,
                QianchuanUniPromotionListV10AdlabScene scene) {
            if (index >= responses.size()) {
                return emptyResponse();
            }
            return responses.get(index++);
        }
    }

    // ==================== 测试 ====================

    @Test
    @DisplayName("★ 根因回归：乘方(OVERALL_PROJECT)场景里的计划必须被查到")
    void findsPlanInOverallProjectScene() throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.byScene.put(QianchuanUniPromotionListV10AdlabScene.UNI_PROJECT, emptyResponse());
        fetcher.byScene.put(QianchuanUniPromotionListV10AdlabScene.OVERALL_PROJECT,
                okResponse(plan(TARGET_AD_ID, Long.parseLong(PRODUCT))));

        QianchuanPlanService service = new QianchuanPlanService(fetcher, 180);
        QianchuanPlanQueryResult result =
                service.queryByAwemeAndProduct(ADVERTISER_ID, AWEME, PRODUCT, DEFAULT_SCENES);

        assertEquals(1, result.size(), "应命中 1 个乘方计划");
        assertEquals(String.valueOf(TARGET_AD_ID),
                String.valueOf(result.getSingle().getAdInfo().getId()));
        assertEquals(1, result.getRawPlanCount());
        assertFalse(result.productMismatchOnly());
    }

    @Test
    @DisplayName("两个场景都要查，且都用 AWEME(抖音号) 关键字 + VIDEO_PROM_GOODS")
    void queriesBothScenesWithAwemeKeyword() throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        QianchuanPlanService service = new QianchuanPlanService(fetcher, 180);
        service.queryByAwemeAndProduct(ADVERTISER_ID, AWEME, PRODUCT, DEFAULT_SCENES);

        assertTrue(fetcher.scenes.contains(QianchuanUniPromotionListV10AdlabScene.UNI_PROJECT),
                "必须查全域推广场景");
        assertTrue(fetcher.scenes.contains(QianchuanUniPromotionListV10AdlabScene.OVERALL_PROJECT),
                "必须查乘方场景");
        assertTrue(fetcher.keywords.stream().allMatch(AWEME::equals),
                "所有查询都应按抖音号过滤");
        assertTrue(fetcher.goals.stream()
                        .allMatch(g -> g == QianchuanUniPromotionListV10MarketingGoal.VIDEO_PROM_GOODS),
                "search_keyword 仅 VIDEO 支持，故只查 VIDEO");
    }

    @Test
    @DisplayName("可诊断性：接口返回了但商品没匹配上 → rawPlanCount>0 且进 unmatchedPlans")
    void productMismatchIsObservable() throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.byScene.put(QianchuanUniPromotionListV10AdlabScene.OVERALL_PROJECT,
                okResponse(plan(TARGET_AD_ID, 999999999999L)));

        QianchuanPlanService service = new QianchuanPlanService(fetcher, 180);
        QianchuanPlanQueryResult result =
                service.queryByAwemeAndProduct(ADVERTISER_ID, AWEME, PRODUCT, DEFAULT_SCENES);

        assertEquals(0, result.size(), "商品不匹配 → 0 个命中");
        assertEquals(1, result.getRawPlanCount(), "但接口确实返回了 1 个");
        assertEquals(1, result.getUnmatchedPlans().size());
        assertTrue(result.productMismatchOnly(), "应识别为「仅商品未匹配」");
    }

    @Test
    @DisplayName("SDK 业务错误(code!=0, data=null) 必须抛 ApiException，不能静默成 0 个计划")
    void sdkBusinessErrorIsThrown() {
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.byScene.put(QianchuanUniPromotionListV10AdlabScene.UNI_PROJECT,
                errorResponse(40000, "start_time: Not a string of format: datetime=2006-01-02 15:04:05"));

        QianchuanPlanService service = new QianchuanPlanService(fetcher, 180);
        ApiException e = assertThrows(ApiException.class,
                () -> service.queryByAwemeAndProduct(ADVERTISER_ID, AWEME, PRODUCT, DEFAULT_SCENES));
        assertTrue(e.getMessage().contains("40000"), e.getMessage());
    }

    @Test
    @DisplayName("同一 adId 被两个场景都返回时按 adId 去重，rawPlanCount 不重复计")
    void dedupAcrossScenes() throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        QianchuanUniPromotionListV10ResponseDataAdListInner plan =
                plan(TARGET_AD_ID, Long.parseLong(PRODUCT));
        fetcher.byScene.put(QianchuanUniPromotionListV10AdlabScene.UNI_PROJECT, okResponse(plan));
        fetcher.byScene.put(QianchuanUniPromotionListV10AdlabScene.OVERALL_PROJECT, okResponse(plan));

        QianchuanPlanService service = new QianchuanPlanService(fetcher, 180);
        QianchuanPlanQueryResult result =
                service.queryByAwemeAndProduct(ADVERTISER_ID, AWEME, PRODUCT, DEFAULT_SCENES);

        assertEquals(1, result.getRawPlanCount(), "跨场景去重后应只算 1 个");
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("频控(40100)时自动重试，重试成功后仍能查到计划")
    void retriesOnRateLimit() throws Exception {
        // 第 1 次被频控拦住，第 2 次成功 —— 必须能自愈，不能因为偶发频控就报「无计划」
        SequencingFetcher fetcher = new SequencingFetcher(
                errorResponse(40100, "系统请求频率超限，请稍后重试。"),
                okResponse(plan(TARGET_AD_ID, Long.parseLong(PRODUCT))));

        QianchuanPlanService service = new QianchuanPlanService(fetcher, 180);
        QianchuanPlanQueryResult result =
                service.queryByAwemeAndProduct(ADVERTISER_ID, AWEME, PRODUCT, DEFAULT_SCENES);

        assertEquals(1, result.size(), "频控重试成功后应命中计划");
        assertEquals(String.valueOf(TARGET_AD_ID),
                String.valueOf(result.getSingle().getAdInfo().getId()));
    }

    @Test
    @DisplayName("时间参数：带时分秒格式，且窗口 ≤ 180 天")
    void usesDatetimeFormatAndCappedWindow() throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        // 故意传 365，验证会被夹到 180
        QianchuanPlanService service = new QianchuanPlanService(fetcher, 365);
        service.queryByAwemeAndProduct(ADVERTISER_ID, AWEME, PRODUCT, DEFAULT_SCENES);

        assertFalse(fetcher.startTimes.isEmpty());
        String start = fetcher.startTimes.get(0);
        String end = fetcher.endTimes.get(0);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        LocalDateTime startDt = LocalDateTime.parse(start, fmt);
        LocalDateTime endDt = LocalDateTime.parse(end, fmt);
        long days = ChronoUnit.DAYS.between(startDt, endDt);
        assertTrue(days <= 180, "窗口必须 ≤180 天，实际 " + days);
        assertTrue(days >= 179, "窗口应尽量覆盖（约 180 天），实际 " + days);
    }

    @Test
    @DisplayName("queryAllPlans：不设 search_keyword，按 status + having_cost 过滤，时间窗由调用方给")
    void queryAllPlansUsesStatusAndHavingCost() throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.byScene.put(QianchuanUniPromotionListV10AdlabScene.OVERALL_PROJECT,
                okResponse(plan(TARGET_AD_ID, Long.parseLong(PRODUCT))));

        QianchuanPlanService service = new QianchuanPlanService(fetcher, 180);
        List<QianchuanUniPromotionListV10ResponseDataAdListInner> plans = service.queryAllPlans(
                ADVERTISER_ID, DEFAULT_SCENES,
                "2026-09-04 00:00:00", "2026-10-03 23:59:59",
                QianchuanUniPromotionListV10FilteringStatus.DELIVERY_OK,
                QianchuanUniPromotionListV10FilteringHavingCost.YES, null);

        assertEquals(1, plans.size());
        assertEquals(String.valueOf(TARGET_AD_ID),
                String.valueOf(plans.get(0).getAdInfo().getId()));

        assertTrue(fetcher.keywords.stream().allMatch(java.util.Objects::isNull),
                "查全部计划不应设置 search_keyword（否则就变成按达人过滤了）");
        assertTrue(fetcher.filterings.stream().allMatch(f ->
                        f.getStatus() == QianchuanUniPromotionListV10FilteringStatus.DELIVERY_OK
                                && f.getHavingCost()
                                == QianchuanUniPromotionListV10FilteringHavingCost.YES),
                "每次请求都应带 status=DELIVERY_OK + having_cost=YES");
        assertEquals("2026-09-04 00:00:00", fetcher.startTimes.get(0),
                "时间窗必须用调用方显式给的值，不能用构造时的 lookbackDays");
        assertEquals("2026-10-03 23:59:59", fetcher.endTimes.get(0));
    }

    @Test
    @DisplayName("queryAllPlans：跨场景按 adId 去重（同一计划被两个场景都返回时只算一次）")
    void queryAllPlansDedupsAcrossScenes() throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        QianchuanUniPromotionListV10ResponseDataAdListInner same =
                plan(TARGET_AD_ID, Long.parseLong(PRODUCT));
        fetcher.byScene.put(QianchuanUniPromotionListV10AdlabScene.UNI_PROJECT, okResponse(same));
        fetcher.byScene.put(QianchuanUniPromotionListV10AdlabScene.OVERALL_PROJECT, okResponse(same));

        QianchuanPlanService service = new QianchuanPlanService(fetcher, 180);
        List<QianchuanUniPromotionListV10ResponseDataAdListInner> plans = service.queryAllPlans(
                ADVERTISER_ID, DEFAULT_SCENES, "2026-09-04 00:00:00", "2026-10-03 23:59:59",
                QianchuanUniPromotionListV10FilteringStatus.DELIVERY_OK,
                QianchuanUniPromotionListV10FilteringHavingCost.YES, null);

        assertEquals(1, plans.size(), "同一 adId 跨场景应去重");
    }

    @Test
    @DisplayName("queryAllPlans：scenes 为空 → 参数错误（不能静默返回空列表）")
    void queryAllPlansRejectsEmptyScenes() {
        QianchuanPlanService service = new QianchuanPlanService(new FakeFetcher(), 180);
        assertThrows(IllegalArgumentException.class, () -> service.queryAllPlans(
                ADVERTISER_ID, List.of(), "2026-09-04 00:00:00", "2026-10-03 23:59:59",
                QianchuanUniPromotionListV10FilteringStatus.DELIVERY_OK,
                QianchuanUniPromotionListV10FilteringHavingCost.YES, null));
    }

    @Test
    @DisplayName("★ 回归：限流器【跨查询共享】—— 被频控学到的间隔不能被下一次查询重置")
    void throttleIsSharedAcrossQueries() throws Exception {
        // 用非零基准，才能看出「翻倍」与「重置」的区别
        QianchuanThrottle shared = QianchuanThrottle.forTest(500, 3000, 3, 600_000);
        assertEquals(500, shared.currentIntervalMillis());

        // 第 1 次调用被频控、重试后成功 → 间隔 500 → 1000
        SequencingFetcher fetcher = new SequencingFetcher(
                errorResponse(40100, "系统请求频率超限"),
                emptyResponse());

        QianchuanProperties props = new QianchuanProperties();
        props.setPlanQueryLookbackDays(180);
        QianchuanPlanService service = new QianchuanPlanService(fetcher, props, shared);
        List<QianchuanUniPromotionListV10AdlabScene> scenes =
                List.of(QianchuanUniPromotionListV10AdlabScene.UNI_PROJECT);

        service.queryAllPlans(ADVERTISER_ID, scenes, "2026-09-04 00:00:00", "2026-10-03 23:59:59",
                QianchuanUniPromotionListV10FilteringStatus.DELIVERY_OK,
                QianchuanUniPromotionListV10FilteringHavingCost.YES, null);

        assertEquals(1000, shared.currentIntervalMillis(), "被频控后间隔应翻倍");

        // 第二次查询（模拟下一个账户）必须沿用同一个限流器，而不是从 500 重新开始
        service.queryAllPlans(ADVERTISER_ID, scenes, "2026-09-04 00:00:00", "2026-10-03 23:59:59",
                QianchuanUniPromotionListV10FilteringStatus.DELIVERY_OK,
                QianchuanUniPromotionListV10FilteringHavingCost.YES, null);

        assertTrue(shared.currentIntervalMillis() >= 1000,
                "下一个账户不能把间隔重置回 500ms —— 40100 是全局频控，与账户无关；"
                        + "实际 " + shared.currentIntervalMillis());
    }

    @Test
    @DisplayName("★ 传阈值时按【消耗降序】拉取，并在低于阈值的那一页停止翻页（实测 26 页 → 1~2 页）")
    void stopsPagingWhenBelowThreshold() throws Exception {
        long thresholdRaw = 200_000_000L; // 2000 元

        PagedFetcher fetcher = new PagedFetcher(page -> switch (page) {
            case 1 -> pagedOkResponse(1, 26, descendingPlans(1000, 100, 5_000_000_000L)); // 远高于阈值
            case 2 -> pagedOkResponse(2, 26, descendingPlans(2000, 100, 210_000_000L));   // 跨过阈值
            default -> pagedOkResponse(page, 26, descendingPlans(3000, 100, 1_000_000L)); // 不该被请求
        });

        List<QianchuanUniPromotionListV10ResponseDataAdListInner> plans =
                new QianchuanPlanService(fetcher, 180).queryAllPlans(
                        ADVERTISER_ID, List.of(QianchuanUniPromotionListV10AdlabScene.OVERALL_PROJECT),
                        "2026-09-04 00:00:00", "2026-10-03 23:59:59",
                        QianchuanUniPromotionListV10FilteringStatus.DELIVERY_OK,
                        QianchuanUniPromotionListV10FilteringHavingCost.YES,
                        thresholdRaw);

        assertEquals(List.of(1, 2), fetcher.requestedPages,
                "第 2 页已出现低于阈值的计划（降序 → 后面只会更低）→ 不应再翻第 3 页");
        assertEquals(200, plans.size());
    }

    @Test
    @DisplayName("查询必定带 order_field=stat_cost + order_type=DESC（否则提前停止不成立）")
    void queryAllPlansAlwaysSortsByCostDesc() throws Exception {
        PagedFetcher fetcher = new PagedFetcher(page -> emptyResponse());

        new QianchuanPlanService(fetcher, 180).queryAllPlans(
                ADVERTISER_ID, List.of(QianchuanUniPromotionListV10AdlabScene.OVERALL_PROJECT),
                "2026-09-04 00:00:00", "2026-10-03 23:59:59",
                QianchuanUniPromotionListV10FilteringStatus.DELIVERY_OK,
                QianchuanUniPromotionListV10FilteringHavingCost.YES, null);

        assertEquals(QianchuanUniPromotionListV10OrderType.DESC, fetcher.orderTypes.get(0));
        assertEquals(QianchuanUniPromotionListV10OrderField.STAT_COST, fetcher.orderFields.get(0));
    }

    @Test
    @DisplayName("不传阈值 → 不提前停止，一直翻到没有数据")
    void pagesThroughAllWhenNoThreshold() throws Exception {
        PagedFetcher fetcher = new PagedFetcher(page -> {
            if (page <= 3) {
                return pagedOkResponse(page, 3, descendingPlans(1000L * page, 100, 5_000_000_000L));
            }
            return emptyResponse();
        });

        new QianchuanPlanService(fetcher, 180).queryAllPlans(
                ADVERTISER_ID, List.of(QianchuanUniPromotionListV10AdlabScene.OVERALL_PROJECT),
                "2026-09-04 00:00:00", "2026-10-03 23:59:59",
                QianchuanUniPromotionListV10FilteringStatus.DELIVERY_OK,
                QianchuanUniPromotionListV10FilteringHavingCost.YES, null);

        assertEquals(List.of(1, 2, 3), fetcher.requestedPages,
                "不传阈值时不该提前停止（这里靠「不满一页」自然结束）");
    }

    @Test
    @DisplayName("★ 回归：MAX_PAGES 不再截断 —— 实测某账户单场景 26 页，旧上限 20 会漏 23%")
    void doesNotTruncateAtTwentyPages() throws Exception {
        PagedFetcher fetcher = new PagedFetcher(page -> {
            if (page <= 26) {
                return pagedOkResponse(page, 26, descendingPlans(1000L * page, 100, 5_000_000_000L));
            }
            return emptyResponse();
        });

        new QianchuanPlanService(fetcher, 180).queryAllPlans(
                ADVERTISER_ID, List.of(QianchuanUniPromotionListV10AdlabScene.OVERALL_PROJECT),
                "2026-09-04 00:00:00", "2026-10-03 23:59:59",
                QianchuanUniPromotionListV10FilteringStatus.DELIVERY_OK,
                QianchuanUniPromotionListV10FilteringHavingCost.YES, null);

        assertTrue(fetcher.requestedPages.contains(26),
                "必须能翻到第 26 页（旧上限 20 会静默漏掉 592 个计划）：" + fetcher.requestedPages);
    }

    // ==================== 构造假响应的工具 ====================

    /** 按页返回预置响应的 fetcher，并记录请求过的页码与排序参数。 */
    private static final class PagedFetcher implements QianchuanPlanService.PlanListFetcher {
        private final java.util.function.IntFunction<QianchuanUniPromotionListV10Response> pageSupplier;
        final List<Integer> requestedPages = new ArrayList<>();
        final List<QianchuanUniPromotionListV10OrderType> orderTypes = new ArrayList<>();
        final List<QianchuanUniPromotionListV10OrderField> orderFields = new ArrayList<>();

        PagedFetcher(
                java.util.function.IntFunction<QianchuanUniPromotionListV10Response> pageSupplier) {
            this.pageSupplier = pageSupplier;
        }

        @Override
        public QianchuanUniPromotionListV10Response fetch(
                Long advertiserId, String startTime, String endTime,
                QianchuanUniPromotionListV10MarketingGoal goal,
                List<QianchuanUniPromotionListV10Fields> fields,
                QianchuanUniPromotionListV10Filtering filtering,
                QianchuanUniPromotionListV10OrderType orderType,
                QianchuanUniPromotionListV10OrderField orderField, int page,
                QianchuanUniPromotionListV10PageSize pageSize,
                QianchuanUniPromotionListV10AdlabScene scene) {
            requestedPages.add(page);
            orderTypes.add(orderType);
            orderFields.add(orderField);
            return pageSupplier.apply(page);
        }
    }

    /**
     * 带分页信息的成功响应。
     *
     * <p>⚠️ <b>必须给 pageInfo</b>：翻页循环用 {@code totalPage} 决定翻到第几页，
     * 没有 pageInfo 时只会翻第 1 页（这也是为什么「不传阈值」的用例必须显式给总页数）。</p>
     */
    private static QianchuanUniPromotionListV10Response pagedOkResponse(
            int page, int totalPages, QianchuanUniPromotionListV10ResponseDataAdListInner... plans) {
        QianchuanUniPromotionListV10ResponseData data = new QianchuanUniPromotionListV10ResponseData();
        data.setAdList(new ArrayList<>(Arrays.asList(plans)));
        QianchuanUniPromotionListV10ResponseDataPageInfo pageInfo =
                new QianchuanUniPromotionListV10ResponseDataPageInfo();
        pageInfo.setPage((long) page);
        pageInfo.setTotalPage((long) totalPages);
        data.setPageInfo(pageInfo);
        QianchuanUniPromotionListV10Response response = new QianchuanUniPromotionListV10Response();
        response.setCode(0L);
        response.setData(data);
        return response;
    }

    /** 造一页计划：adId 从 {@code baseAdId} 递增，statCost 从 {@code topCost} 递减。 */
    private static QianchuanUniPromotionListV10ResponseDataAdListInner[] descendingPlans(
            long baseAdId, int count, long topCost) {
        QianchuanUniPromotionListV10ResponseDataAdListInner[] arr =
                new QianchuanUniPromotionListV10ResponseDataAdListInner[count];
        for (int i = 0; i < count; i++) {
            QianchuanUniPromotionListV10ResponseDataAdListInnerAdInfo adInfo =
                    new QianchuanUniPromotionListV10ResponseDataAdListInnerAdInfo();
            adInfo.setId(baseAdId + i);
            adInfo.setName("计划-" + (baseAdId + i));
            QianchuanUniPromotionListV10ResponseDataAdListInner inner =
                    new QianchuanUniPromotionListV10ResponseDataAdListInner();
            inner.setAdInfo(adInfo);
            QianchuanUniPromotionListV10ResponseDataAdListInnerStatsInfo stats =
                    new QianchuanUniPromotionListV10ResponseDataAdListInnerStatsInfo();
            stats.setStatCost((double) (topCost - i * 1_000_000L));
            inner.setStatsInfo(stats);
            arr[i] = inner;
        }
        return arr;
    }

    private static QianchuanUniPromotionListV10ResponseDataAdListInner plan(long adId, long... productIds) {
        QianchuanUniPromotionListV10ResponseDataAdListInnerAdInfo adInfo =
                new QianchuanUniPromotionListV10ResponseDataAdListInnerAdInfo();
        adInfo.setId(adId);
        adInfo.setName("伊伊麻麻-护臀膏");
        QianchuanUniPromotionListV10ResponseDataAdListInner inner =
                new QianchuanUniPromotionListV10ResponseDataAdListInner();
        inner.setAdInfo(adInfo);
        if (productIds.length > 0) {
            List<QianchuanUniPromotionListV10ResponseDataAdListInnerProductInfoInner> products =
                    new ArrayList<>();
            for (long pid : productIds) {
                QianchuanUniPromotionListV10ResponseDataAdListInnerProductInfoInner p =
                        new QianchuanUniPromotionListV10ResponseDataAdListInnerProductInfoInner();
                p.setProductId(pid);
                products.add(p);
            }
            inner.setProductInfo(products);
        }
        return inner;
    }

    private static QianchuanUniPromotionListV10Response okResponse(
            QianchuanUniPromotionListV10ResponseDataAdListInner... plans) {
        QianchuanUniPromotionListV10ResponseData data = new QianchuanUniPromotionListV10ResponseData();
        data.setAdList(new ArrayList<>(Arrays.asList(plans)));
        QianchuanUniPromotionListV10Response response = new QianchuanUniPromotionListV10Response();
        response.setCode(0L);
        response.setData(data);
        return response;
    }

    private static QianchuanUniPromotionListV10Response emptyResponse() {
        QianchuanUniPromotionListV10ResponseData data = new QianchuanUniPromotionListV10ResponseData();
        data.setAdList(new ArrayList<>());
        QianchuanUniPromotionListV10Response response = new QianchuanUniPromotionListV10Response();
        response.setCode(0L);
        response.setData(data);
        return response;
    }

    private static QianchuanUniPromotionListV10Response errorResponse(int code, String message) {
        QianchuanUniPromotionListV10Response response = new QianchuanUniPromotionListV10Response();
        response.setCode((long) code);
        response.setMessage(message);
        // data 保持 null —— 复现 SDK 的真实行为
        return response;
    }
}
