package com.aolandai.dingtou.service.qianchuan;

import com.aolandai.dingtou.client.QianchuanClientFactory;
import com.aolandai.dingtou.config.QianchuanProperties;
import com.aolandai.dingtou.dto.qianchuan.PlanQueryOutcome;
import com.aolandai.dingtou.dto.sheet.AccountRow;
import com.aolandai.dingtou.source.AccountDictReader;
import com.aolandai.dingtou.source.ProductDictReader;
import com.aolandai.dingtou.store.PlanLedger;
import com.bytedance.ads.model.QianchuanUniPromotionListV10AdlabScene;
import com.bytedance.ads.model.QianchuanUniPromotionListV10Fields;
import com.bytedance.ads.model.QianchuanUniPromotionListV10Filtering;
import com.bytedance.ads.model.QianchuanUniPromotionListV10MarketingGoal;
import com.bytedance.ads.model.QianchuanUniPromotionListV10OrderField;
import com.bytedance.ads.model.QianchuanUniPromotionListV10OrderType;
import com.bytedance.ads.model.QianchuanUniPromotionListV10PageSize;
import com.bytedance.ads.model.QianchuanUniPromotionListV10Response;
import com.bytedance.ads.model.QianchuanUniPromotionListV10ResponseData;
import com.bytedance.ads.model.QianchuanUniPromotionListV10ResponseDataAdListInner;
import com.bytedance.ads.model.QianchuanUniPromotionListV10DataAdListAdInfoStatus;
import com.bytedance.ads.model.QianchuanUniPromotionListV10ResponseDataAdListInnerAdInfo;
import com.bytedance.ads.model.QianchuanUniPromotionListV10ResponseDataAdListInnerProductInfoInner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultPlanQueryService} 单测：用 Mockito 隔离账户字典/商品字典/台账，
 * 用假 fetcher 隔离千川 SDK，<b>绝不真调千川</b>。
 *
 * <p>⚠️ 2026-10-05 起账户来源<b>只有</b>「调用方指定的那一个账户」（素材表 E 列），
 * 所以原来的「账户路由（AUTO / TABLE_ONLY / SCAN_ALL）」「未命中回退全量」用例已删除 ——
 * 那些机制连同 {@code AccountRoutingMode}、{@code TargetingTableReader} 一起退役了。</p>
 */
class DefaultPlanQueryServiceTest {

    private static final String AWEME = "610129yiyi";
    private static final String PRODUCT_NAME = "护臀膏";
    private static final String PRODUCT_ID = "3593275488396811650";
    private static final long TARGET_AD_ID = 1877813517052328L;

    private static final long ACC_1 = 1823379540880396L; // 官方【母婴商品】
    private static final long ACC_2 = 1771298760893511L; // 官方【孕产-商品】

    /** 假 fetcher：按 advertiserId → 场景 返回预置响应；同时记录被查过的账户。 */
    private static final class FakeFetcher implements QianchuanPlanService.PlanListFetcher {
        final Map<Long, Map<QianchuanUniPromotionListV10AdlabScene, QianchuanUniPromotionListV10Response>>
                byAdvertiser = new HashMap<>();
        /** 被真正查过的账户集合（断言「只查了指定账户」用）。 */
        final Set<Long> queried = new TreeSet<>();

        void put(long advertiserId, QianchuanUniPromotionListV10AdlabScene scene,
                 QianchuanUniPromotionListV10Response response) {
            byAdvertiser.computeIfAbsent(advertiserId,
                    k -> new EnumMap<>(QianchuanUniPromotionListV10AdlabScene.class)).put(scene, response);
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
            queried.add(advertiserId);
            return byAdvertiser.getOrDefault(advertiserId, Map.of())
                    .getOrDefault(scene, emptyResponse());
        }
    }

    private DefaultPlanQueryService newService(FakeFetcher fetcher,
                                               List<AccountRow> accounts,
                                               Optional<String> productId) throws Exception {
        QianchuanClientFactory clientFactory = mock(QianchuanClientFactory.class);
        AccountDictReader accountDictReader = mock(AccountDictReader.class);
        ProductDictReader productDictReader = mock(ProductDictReader.class);
        PlanLedger planLedger = mock(PlanLedger.class);
        QianchuanProperties properties = mock(QianchuanProperties.class);

        QianchuanPlanService planService = new QianchuanPlanService(fetcher, 180);
        when(clientFactory.planService()).thenReturn(planService);
        when(accountDictReader.readAll()).thenReturn(accounts);
        when(productDictReader.uniqueProductIdOfName(PRODUCT_NAME)).thenReturn(productId);
        when(planLedger.find(any(), any())).thenReturn(Optional.empty());
        // ⚠️ 场景现在来自配置（不是硬编码常量），mock 必须桩上，否则返回 null → 参数校验失败
        when(properties.getQueryScenes()).thenReturn(List.of(
                QianchuanUniPromotionListV10AdlabScene.UNI_PROJECT,
                QianchuanUniPromotionListV10AdlabScene.OVERALL_PROJECT));

        return new DefaultPlanQueryService(clientFactory, accountDictReader,
                productDictReader, planLedger, properties);
    }

    private static List<AccountRow> twoAccounts() {
        return List.of(
                new AccountRow("官方【母婴商品】", String.valueOf(ACC_1), "官方店", 1),
                new AccountRow("官方【孕产-商品】", String.valueOf(ACC_2), "官方店", 2));
    }

    @Test
    @DisplayName("★ 根因回归：乘方场景命中 → outcome.hasPlan()=true")
    void overallProjectHit() throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.put(ACC_1, QianchuanUniPromotionListV10AdlabScene.OVERALL_PROJECT,
                responseWith(TARGET_AD_ID, PRODUCT_ID));

        PlanQueryOutcome outcome = newService(fetcher, twoAccounts(), Optional.of(PRODUCT_ID))
                .query(AWEME, PRODUCT_NAME, ACC_1);

        assertTrue(outcome.hasPlan(), "乘方场景必须能查到计划");
        assertEquals(1, outcome.plans().size());
        assertEquals(String.valueOf(TARGET_AD_ID), outcome.plans().get(0).adId());
        assertTrue(outcome.queryComplete(), "没有账户查失败");
    }

    @Test
    @DisplayName("★★ 只查【指定的那一个账户】，绝不碰其它账户（2026-10-05 起不再遍历）")
    void onlyQueriesTheGivenAccount() throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.put(ACC_1, QianchuanUniPromotionListV10AdlabScene.OVERALL_PROJECT,
                responseWith(TARGET_AD_ID, PRODUCT_ID));
        // ACC_2 也放一个计划 —— 它不该被查到
        fetcher.put(ACC_2, QianchuanUniPromotionListV10AdlabScene.OVERALL_PROJECT,
                responseWith(999L, PRODUCT_ID));

        PlanQueryOutcome outcome = newService(fetcher, twoAccounts(), Optional.of(PRODUCT_ID))
                .query(AWEME, PRODUCT_NAME, ACC_1);

        assertEquals(Set.of(ACC_1), fetcher.queried, "只该查指定的账户，实际查了: " + fetcher.queried);
        assertEquals(1, outcome.accountsScanned());
        assertEquals(1, outcome.plans().size());
        assertEquals(String.valueOf(TARGET_AD_ID), outcome.plans().get(0).adId());
    }

    @Test
    @DisplayName("★ 指定账户不在账户字典里 → 记 failedAccounts（不能当成「没有计划」）")
    void unknownAccountIsFailedNotEmpty() throws Exception {
        PlanQueryOutcome outcome = newService(new FakeFetcher(), twoAccounts(), Optional.of(PRODUCT_ID))
                .query(AWEME, PRODUCT_NAME, 1234567890123456L);

        assertFalse(outcome.hasPlan());
        assertFalse(outcome.queryComplete(), "账户都没查成，不能下「没有计划」的结论");
        assertEquals(1, outcome.failedAccounts().size());
        assertTrue(outcome.failedAccounts().get(0).contains("1234567890123456"),
                outcome.failedAccounts().toString());
    }

    @Test
    @DisplayName("可诊断性：接口有返回但商品没匹配上 → warning + productMismatchOnly")
    void productMismatchIsDiagnosable() throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.put(ACC_1, QianchuanUniPromotionListV10AdlabScene.OVERALL_PROJECT,
                responseWith(TARGET_AD_ID, "1111111111111111111"));

        PlanQueryOutcome outcome = newService(fetcher, twoAccounts(), Optional.of(PRODUCT_ID))
                .query(AWEME, PRODUCT_NAME, ACC_1);

        assertFalse(outcome.hasPlan());
        assertTrue(outcome.productMismatchOnly(), "rawPlanCount 应该 > 0");
        assertTrue(outcome.warnings().stream().anyMatch(w -> w.contains("商品 ID")),
                outcome.warnings().toString());
        assertTrue(outcome.queryComplete(), "接口是通的，只是商品没匹配上 —— 不算账户失败");
    }

    @Test
    @DisplayName("账户接口报错 → 记 failedAccounts（不是 warnings），让调用方能中止")
    void accountErrorGoesToFailedAccounts() throws Exception {
        QianchuanClientFactory clientFactory = mock(QianchuanClientFactory.class);
        AccountDictReader accountDictReader = mock(AccountDictReader.class);
        ProductDictReader productDictReader = mock(ProductDictReader.class);
        PlanLedger planLedger = mock(PlanLedger.class);
        QianchuanProperties properties = mock(QianchuanProperties.class);
        QianchuanPlanService failing = mock(QianchuanPlanService.class);

        when(clientFactory.planService()).thenReturn(failing);
        when(accountDictReader.readAll()).thenReturn(twoAccounts());
        when(productDictReader.uniqueProductIdOfName(PRODUCT_NAME)).thenReturn(Optional.of(PRODUCT_ID));
        when(planLedger.find(any(), any())).thenReturn(Optional.empty());
        when(properties.getQueryScenes()).thenReturn(List.of(
                QianchuanUniPromotionListV10AdlabScene.UNI_PROJECT));
        when(failing.queryByAwemeAndProduct(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("接口 40000"));

        PlanQueryOutcome outcome = new DefaultPlanQueryService(clientFactory, accountDictReader,
                productDictReader, planLedger, properties)
                .query(AWEME, PRODUCT_NAME, ACC_1);

        assertFalse(outcome.hasPlan());
        assertFalse(outcome.queryComplete(), "账户查失败时不能下「没有计划」的结论");
        assertEquals(1, outcome.failedAccounts().size());
        assertTrue(outcome.failedAccounts().get(0).contains("40000"),
                outcome.failedAccounts().toString());
    }

    @Test
    @DisplayName("商品名映射不到唯一 ID → 直接跳过并告警，不扫账户")
    void productNotMappedSkips() throws Exception {
        FakeFetcher fetcher = new FakeFetcher();

        PlanQueryOutcome outcome = newService(fetcher, twoAccounts(), Optional.empty())
                .query(AWEME, PRODUCT_NAME, ACC_1);

        assertFalse(outcome.hasPlan());
        assertEquals(0, outcome.accountsScanned());
        assertTrue(fetcher.queried.isEmpty(), "商品没映射上就不该去查千川");
        assertTrue(outcome.warnings().stream().anyMatch(w -> w.contains("商品 ID 映射表")),
                outcome.warnings().toString());
    }

    // ==================== 工具 ====================

    private static QianchuanUniPromotionListV10Response emptyResponse() {
        QianchuanUniPromotionListV10Response response = new QianchuanUniPromotionListV10Response();
        response.setCode(0L);
        QianchuanUniPromotionListV10ResponseData data = new QianchuanUniPromotionListV10ResponseData();
        data.setAdList(List.of());
        data.setPageInfo(null);
        response.setData(data);
        return response;
    }

    private static QianchuanUniPromotionListV10Response responseWith(long adId, String productId) {
        QianchuanUniPromotionListV10ResponseDataAdListInnerAdInfo info =
                new QianchuanUniPromotionListV10ResponseDataAdListInnerAdInfo();
        info.setId(adId);
        info.setName("计划-" + adId);
        info.setStatus(QianchuanUniPromotionListV10DataAdListAdInfoStatus.DELIVERY_OK);

        QianchuanUniPromotionListV10ResponseDataAdListInnerProductInfoInner product =
                new QianchuanUniPromotionListV10ResponseDataAdListInnerProductInfoInner();
        product.setProductId(Long.parseLong(productId));

        QianchuanUniPromotionListV10ResponseDataAdListInner ad =
                new QianchuanUniPromotionListV10ResponseDataAdListInner();
        ad.setAdInfo(info);
        ad.setProductInfo(List.of(product));

        QianchuanUniPromotionListV10ResponseData data = new QianchuanUniPromotionListV10ResponseData();
        data.setAdList(List.of(ad));

        QianchuanUniPromotionListV10Response response = new QianchuanUniPromotionListV10Response();
        response.setCode(0L);
        response.setData(data);
        return response;
    }
}
