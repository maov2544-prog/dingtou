package com.aolandai.dingtou.service.qianchuan;

import com.aolandai.dingtou.client.QianchuanClientFactory;
import com.aolandai.dingtou.config.QianchuanProperties;
import com.bytedance.ads.ApiException;
import com.bytedance.ads.model.QianchuanAwemeAuthListGetV10Response;
import com.bytedance.ads.model.QianchuanAwemeAuthListGetV10ResponseData;
import com.bytedance.ads.model.QianchuanAwemeAuthListGetV10ResponseDataAuthorizationInfosInner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@link AwemeShowIdResolver} 单测：注入假 fetcher，<b>不真调千川</b>。
 *
 * <p>背景：乘方计划详情接口的 {@code unique_id}（抖音号）有时为空（实测 12.6%），
 * 用 {@code aweme_uid} 去【抖音号授权列表】反查兜底（实测覆盖率 92%）。</p>
 */
class AwemeShowIdResolverTest {

    private static final long ACC = 1823379540880396L;

    private static AwemeShowIdResolver newResolver(int cacheSeconds) {
        QianchuanProperties props = new QianchuanProperties();
        props.setAwemeShowIdCacheSeconds(cacheSeconds);
        return new AwemeShowIdResolver(mock(QianchuanClientFactory.class), props);
    }

    private static QianchuanAwemeAuthListGetV10Response ok(
            QianchuanAwemeAuthListGetV10ResponseDataAuthorizationInfosInner... infos) {
        QianchuanAwemeAuthListGetV10ResponseData data =
                new QianchuanAwemeAuthListGetV10ResponseData();
        data.setAuthorizationInfos(List.of(infos));
        QianchuanAwemeAuthListGetV10Response response = new QianchuanAwemeAuthListGetV10Response();
        response.setCode(0L);
        response.setMessage("OK");
        response.setData(data);
        return response;
    }

    private static QianchuanAwemeAuthListGetV10ResponseDataAuthorizationInfosInner info(
            long uid, String showId) {
        QianchuanAwemeAuthListGetV10ResponseDataAuthorizationInfosInner inner =
                new QianchuanAwemeAuthListGetV10ResponseDataAuthorizationInfosInner();
        inner.setAwemeId(uid);
        inner.setAwemeShowId(showId);
        return inner;
    }

    @Test
    @DisplayName("★ 批量反查：一次请求查多个 UID，返回 UID → 抖音号")
    void resolvesBatchInOneRequest() throws Exception {
        AwemeShowIdResolver resolver = newResolver(3600);
        AtomicInteger calls = new AtomicInteger();
        List<List<Long>> requested = new ArrayList<>();

        Map<Long, String> got = resolver.resolveBatch(ACC, List.of(111L, 222L, 333L),
                (advertiserId, uids) -> {
                    calls.incrementAndGet();
                    requested.add(uids);
                    return ok(info(111L, "1003948160"), info(222L, "610129yiyi"),
                            info(333L, "965409474"));
                });

        assertEquals(1, calls.get(), "⚠️ 一次请求就该查完，不是每个 UID 一次");
        assertEquals(List.of(111L, 222L, 333L), requested.get(0));
        assertEquals(3, got.size());
        assertEquals("1003948160", got.get(111L));
        assertEquals("610129yiyi", got.get(222L));
    }

    @Test
    @DisplayName("★ 命中缓存的 UID 不再请求；只请求没缓存的")
    void cachedUidsAreNotRequestedAgain() throws Exception {
        AwemeShowIdResolver resolver = newResolver(3600);
        List<List<Long>> requested = new ArrayList<>();

        resolver.resolveBatch(ACC, List.of(111L, 222L), (a, uids) -> {
            requested.add(uids);
            return ok(info(111L, "id-111"), info(222L, "id-222"));
        });
        Map<Long, String> second = resolver.resolveBatch(ACC, List.of(111L, 222L, 333L),
                (a, uids) -> {
                    requested.add(uids);
                    return ok(info(333L, "id-333"));
                });

        assertEquals(2, requested.size(), "第二次只该为 333 发一次请求");
        assertEquals(List.of(333L), requested.get(1), "已缓存的 111/222 不该再请求");
        assertEquals(3, second.size(), "缓存 + 新查的要合起来返回");
        assertEquals("id-111", second.get(111L));
    }

    @Test
    @DisplayName("★「查不到」不缓存 —— 刚授权还没生效时，缓存住会让它永远拿不到")
    void missesAreNotCached() throws Exception {
        AwemeShowIdResolver resolver = newResolver(3600);
        AtomicInteger calls = new AtomicInteger();

        resolver.resolveBatch(ACC, List.of(999L), (a, uids) -> {
            calls.incrementAndGet();
            return ok();
        });
        resolver.resolveBatch(ACC, List.of(999L), (a, uids) -> {
            calls.incrementAndGet();
            return ok(info(999L, "终于有了"));
        });

        assertEquals(2, calls.get(), "没查到的必须重试，不能被缓存挡住");
    }

    @Test
    @DisplayName("接口 code≠0 → 抛 ApiException（不能静默翻译成「没有抖音号」）")
    void nonZeroCodeThrows() {
        AwemeShowIdResolver resolver = newResolver(3600);
        QianchuanAwemeAuthListGetV10Response bad = new QianchuanAwemeAuthListGetV10Response();
        bad.setCode(40000L);
        bad.setMessage("filtering: aweme_id: Field must be set to array");

        ApiException e = assertThrows(ApiException.class,
                () -> resolver.resolveBatch(ACC, List.of(111L), (a, uids) -> bad));
        assertTrue(e.getMessage().contains("40000"), e.getMessage());
    }

    @Test
    @DisplayName("超过批量上限 → 拆成多次请求（不把 URL 撑爆）")
    void splitsIntoBatches() throws Exception {
        AwemeShowIdResolver resolver = newResolver(3600);
        AtomicInteger calls = new AtomicInteger();
        List<Long> all = new ArrayList<>();
        for (long i = 1; i <= 120; i++) {
            all.add(i);
        }

        resolver.resolveBatch(ACC, all, (a, uids) -> {
            calls.incrementAndGet();
            assertTrue(uids.size() <= 50, "单次最多 50 个，实际 " + uids.size());
            return ok();
        });

        assertEquals(3, calls.get(), "120 个应当拆成 3 批（50+50+20）");
    }

    @Test
    @DisplayName("空 UID 列表 → 不发请求，返回空 map")
    void emptyInputDoesNotCallApi() throws Exception {
        AwemeShowIdResolver resolver = newResolver(3600);
        AtomicInteger calls = new AtomicInteger();

        Map<Long, String> got = resolver.resolveBatch(ACC, List.of(), (a, uids) -> {
            calls.incrementAndGet();
            return ok();
        });

        assertTrue(got.isEmpty());
        assertEquals(0, calls.get());
    }

    @Test
    @DisplayName("抖音号为空串的条目被跳过（不把空串当结果写进缓存）")
    void blankShowIdIsSkipped() throws Exception {
        AwemeShowIdResolver resolver = newResolver(3600);

        Map<Long, String> got = resolver.resolveBatch(ACC, List.of(111L, 222L),
                (a, uids) -> ok(info(111L, ""), info(222L, "id-222")));

        assertEquals(1, got.size());
        assertEquals("id-222", got.get(222L));
    }
}
