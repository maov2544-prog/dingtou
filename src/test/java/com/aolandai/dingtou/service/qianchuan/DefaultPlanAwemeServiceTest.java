package com.aolandai.dingtou.service.qianchuan;

import com.aolandai.dingtou.client.QianchuanClientFactory;
import com.aolandai.dingtou.client.QianchuanThrottle;
import com.aolandai.dingtou.client.QianchuanThrottleFactory;
import com.aolandai.dingtou.config.QianchuanProperties;
import com.aolandai.dingtou.dto.qianchuan.PlanAweme;
import com.aolandai.dingtou.dto.qianchuan.PlanAwemeLookupResult;
import com.aolandai.dingtou.dto.qianchuan.PlanRef;
import com.aolandai.dingtou.exception.RateLimitException;
import com.bytedance.ads.model.QianchuanUniPromotionAdDetailV10Response;
import com.bytedance.ads.model.QianchuanUniPromotionAdDetailV10ResponseData;
import com.bytedance.ads.model.QianchuanUniPromotionAdDetailV10ResponseDataAwemeInfosInner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultPlanAwemeService} 单测：注入假 fetcher，<b>绝不真调千川</b>。
 *
 * <p>覆盖：字段映射（抖音号 = {@code unique_id}）、多达人只取第一个 + warning、
 * 无达人信息留痕、业务错误进 warnings、频控上抛、40100 自动重试。</p>
 */
class DefaultPlanAwemeServiceTest {

    private static final long ACC = 1823379540880396L;
    private static final long AD_ID = 1877813517052328L;

    /** 假 fetcher：按 adId 返回预置响应。 */
    private static final class FakeFetcher implements PlanAwemeService.PlanDetailFetcher {
        private final java.util.Map<Long, QianchuanUniPromotionAdDetailV10Response> byAdId =
                new java.util.HashMap<>();

        void put(long adId, QianchuanUniPromotionAdDetailV10Response response) {
            byAdId.put(adId, response);
        }

        @Override
        public QianchuanUniPromotionAdDetailV10Response fetch(long advertiserId, long adId) {
            return byAdId.getOrDefault(adId, okResponse(adId));
        }
    }

    /** 按调用顺序返回的 fetcher（测重试用）。 */
    private static final class SequencingFetcher implements PlanAwemeService.PlanDetailFetcher {
        private final List<QianchuanUniPromotionAdDetailV10Response> responses;
        private int index = 0;

        SequencingFetcher(QianchuanUniPromotionAdDetailV10Response... responses) {
            this.responses = new ArrayList<>(Arrays.asList(responses));
        }

        @Override
        public QianchuanUniPromotionAdDetailV10Response fetch(long advertiserId, long adId) {
            if (index >= responses.size()) {
                return okResponse(adId);
            }
            return responses.get(index++);
        }
    }

    /** 兜底 resolver 的桩：默认「什么都没查到」—— 本测试类只测字段映射，不测兜底。 */
    private static AwemeShowIdResolver stubResolver() {
        return mock(AwemeShowIdResolver.class);
    }

    private static DefaultPlanAwemeService newService() {
        return new DefaultPlanAwemeService(mock(QianchuanClientFactory.class),
                new QianchuanThrottleFactory(new QianchuanProperties()), stubResolver());
    }

    // ==================== 字段映射 ====================

    @Test
    @DisplayName("★ 字段映射：抖音号取 unique_id、抖音名取 aweme_name（实测口径）")
    void mapsUniqueIdToAwemeShowId() throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.put(AD_ID, okResponse(AD_ID, aweme("伊伊麻麻", "610129yiyi", 2071074583877891L)));

        PlanAwemeLookupResult result = newService().doLookupAll(
                List.of(new PlanRef(ACC, AD_ID)), fetcher, QianchuanThrottle.forTest());

        assertEquals(1, result.items().size());
        PlanAweme got = result.items().get(0);
        assertEquals(AD_ID, got.adId(), "必须带上 adId 才能对回原计划");
        assertEquals("610129yiyi", got.awemeShowId(), "抖音号来自 unique_id");
        assertEquals("伊伊麻麻", got.awemeName(), "抖音名来自 aweme_name");
        assertFalse(result.incomplete());
        assertTrue(result.warnings().isEmpty(), "不应有 warning：" + result.warnings());
    }

    @Test
    @DisplayName("批量：多个计划各自对上自己的达人")
    void batchLookupKeepsEachPlanOwnAweme() throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.put(111L, okResponse(111L, aweme("达人A", "id-A", 1L)));
        fetcher.put(222L, okResponse(222L, aweme("达人B", "id-B", 2L)));

        PlanAwemeLookupResult result = newService().doLookupAll(
                List.of(new PlanRef(ACC, 111L), new PlanRef(ACC, 222L)), fetcher, QianchuanThrottle.forTest());

        assertEquals(2, result.resolved());
        assertEquals("id-A", result.items().get(0).awemeShowId());
        assertEquals("id-B", result.items().get(1).awemeShowId());
    }

    // ==================== 多达人 / 无达人 ====================

    @Test
    @DisplayName("一个计划有多个达人 → 只取第一个 + warning（不静默丢弃）")
    void multipleAwemesTakesFirstWithWarning() throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.put(AD_ID, okResponse(AD_ID,
                aweme("达人甲", "id-1", 1L),
                aweme("达人乙", "id-2", 2L)));

        PlanAwemeLookupResult result = newService().doLookupAll(
                List.of(new PlanRef(ACC, AD_ID)), fetcher, QianchuanThrottle.forTest());

        assertEquals(1, result.items().size());
        assertEquals("id-1", result.items().get(0).awemeShowId(), "只取第一个");
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("2 个达人")),
                "丢弃必须留痕：" + result.warnings());
    }

    @Test
    @DisplayName("接口成功但 aweme_infos 为空 → 不计入结果 + warning（不能当成『没有达人』）")
    void emptyAwemeInfosIsWarned() throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.put(AD_ID, okResponse(AD_ID)); // 无达人

        PlanAwemeLookupResult result = newService().doLookupAll(
                List.of(new PlanRef(ACC, AD_ID)), fetcher, QianchuanThrottle.forTest());

        assertEquals(0, result.items().size());
        assertEquals(1, result.requested());
        assertTrue(result.incomplete(), "请求了 1 个、查到 0 个 → 结果不完整");
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("没有达人信息")),
                "必须留痕：" + result.warnings());
    }

    @Test
    @DisplayName("★ 实测真实数据：抖音名有值但抖音号(unique_id)为空 → 保留抖音名 + warning（不静默）")
    void blankUniqueIdKeepsNameAndWarns() throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.put(AD_ID, okResponse(AD_ID, aweme("苗元（努力版）", null, 1L)));

        PlanAwemeLookupResult result = newService().doLookupAll(
                List.of(new PlanRef(ACC, AD_ID)), fetcher, QianchuanThrottle.forTest());

        assertEquals(1, result.items().size(), "达人信息是取到了的，只是缺抖音号");
        assertEquals("", result.items().get(0).awemeShowId());
        assertEquals("苗元（努力版）", result.items().get(0).awemeName(), "抖音名必须保留");
        assertFalse(result.items().get(0).hasAwemeShowId());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("没有抖音号")),
                "抖音号为空必须留痕（否则下游分不清『没有抖音号』和『没查到』）："
                        + result.warnings());
    }

    // ==================== 错误处理 ====================

    @Test
    @DisplayName("某计划业务错误 → 记 warning，其它计划照常返回")
    void businessErrorGoesToWarningsAndContinues() throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.put(111L, okResponse(111L, aweme("达人A", "id-A", 1L)));
        fetcher.put(222L, errorResponse(40002, "doesn't exist or the role is wrong"));

        PlanAwemeLookupResult result = newService().doLookupAll(
                List.of(new PlanRef(ACC, 111L), new PlanRef(ACC, 222L)), fetcher, QianchuanThrottle.forTest());

        assertEquals(2, result.requested());
        assertEquals(1, result.resolved());
        assertEquals("id-A", result.items().get(0).awemeShowId());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("222")),
                "失败计划必须进 warnings：" + result.warnings());
    }

    @Test
    @DisplayName("⚠️ 频控(40100) 必须上抛 RateLimitException（吞掉会误判成『没有达人』）")
    void rateLimitIsPropagated() {
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.put(AD_ID, errorResponse(40100, "系统请求频率超限"));

        assertThrows(RateLimitException.class, () -> newService().doLookupAll(
                List.of(new PlanRef(ACC, AD_ID)), fetcher, QianchuanThrottle.forTest()));
    }

    @Test
    @DisplayName("频控(40100) 自动重试，重试成功后仍能查到")
    void retriesOnRateLimit() throws Exception {
        SequencingFetcher fetcher = new SequencingFetcher(
                errorResponse(40100, "系统请求频率超限"),
                okResponse(AD_ID, aweme("伊伊麻麻", "610129yiyi", 1L)));

        PlanAwemeLookupResult result = newService().doLookupAll(
                List.of(new PlanRef(ACC, AD_ID)), fetcher, QianchuanThrottle.forTest());

        assertEquals(1, result.items().size());
        assertEquals("610129yiyi", result.items().get(0).awemeShowId());
    }

    @Test
    @DisplayName("code=0 但 data 为空 → 记 warning（不是『没有达人』）")
    void nullDataIsWarned() throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        QianchuanUniPromotionAdDetailV10Response resp =
                new QianchuanUniPromotionAdDetailV10Response();
        resp.setCode(0L);
        resp.setData(null);
        fetcher.put(AD_ID, resp);

        PlanAwemeLookupResult result = newService().doLookupAll(
                List.of(new PlanRef(ACC, AD_ID)), fetcher, QianchuanThrottle.forTest());

        assertEquals(0, result.items().size());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("data 为空")),
                "必须留痕：" + result.warnings());
    }

    // ==================== 边界 ====================

    @Test
    @DisplayName("空入参 → 直接返回空结果，不发请求")
    void emptyRefsReturnsEmpty() throws Exception {
        PlanAwemeLookupResult result = newService().lookupAll(List.of());
        assertEquals(0, result.requested());
        assertEquals(0, result.resolved());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    @DisplayName("lookup(单个)：经 clientFactory 取 fetcher，返回 Optional")
    void singleLookupDelegatesThroughFactory() throws Exception {
        QianchuanClientFactory factory = mock(QianchuanClientFactory.class);
        when(factory.planDetailFetcher()).thenReturn(
                (advertiserId, adId) -> okResponse(adId, aweme("伊伊麻麻", "610129yiyi", 1L)));
        QianchuanProperties props = new QianchuanProperties();
        props.setRequestIntervalMillis(0);

        Optional<PlanAweme> got =
                new DefaultPlanAwemeService(factory, new QianchuanThrottleFactory(props),
                        stubResolver()).lookup(ACC, AD_ID);

        assertTrue(got.isPresent());
        assertEquals("610129yiyi", got.get().awemeShowId());
    }

    // ==================== 构造假响应的工具 ====================

    private static QianchuanUniPromotionAdDetailV10Response okResponse(
            long adId, QianchuanUniPromotionAdDetailV10ResponseDataAwemeInfosInner... infos) {
        QianchuanUniPromotionAdDetailV10ResponseData data =
                new QianchuanUniPromotionAdDetailV10ResponseData();
        data.setAdId(adId);
        data.setAwemeInfos(new ArrayList<>(Arrays.asList(infos)));
        QianchuanUniPromotionAdDetailV10Response response =
                new QianchuanUniPromotionAdDetailV10Response();
        response.setCode(0L);
        response.setData(data);
        return response;
    }

    private static QianchuanUniPromotionAdDetailV10Response errorResponse(int code, String message) {
        QianchuanUniPromotionAdDetailV10Response response =
                new QianchuanUniPromotionAdDetailV10Response();
        response.setCode((long) code);
        response.setMessage(message);
        // data 保持 null —— 复现 SDK 的真实行为
        return response;
    }

    private static QianchuanUniPromotionAdDetailV10ResponseDataAwemeInfosInner aweme(
            String name, String uniqueId, Long uid) {
        QianchuanUniPromotionAdDetailV10ResponseDataAwemeInfosInner info =
                new QianchuanUniPromotionAdDetailV10ResponseDataAwemeInfosInner();
        info.setAwemeName(name);
        info.setUniqueId(uniqueId);
        info.setAwemeUid(uid);
        return info;
    }
}
