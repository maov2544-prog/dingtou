package com.aolandai.dingtou.service.qianchuan;

import com.aolandai.dingtou.config.SuixintuiProperties;
import com.bytedance.ads.model.QianchuanUniAwemeAuthorizedGetV10Response;
import com.bytedance.ads.model.QianchuanUniAwemeAuthorizedGetV10ResponseData;
import com.bytedance.ads.model.QianchuanUniAwemeAuthorizedGetV10ResponseDataAwemeIdListInner;
import com.bytedance.ads.model.QianchuanUniAwemeAuthorizedGetV10ResponseDataPageInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AwemeUidResolver} 单测：注入假 fetcher，<b>不真调接口</b>。
 *
 * <p>重点：<b>先走关键词搜索</b>（1 次请求）、搜不到才回退全量 ——
 * 实测某账户有 9600 个授权达人（96 页），全量扫描要 4 分钟，搜索只要几秒。</p>
 */
class AwemeUidResolverTest {

    private static final long ACC = 1778533513439244L;
    private static final String AWEME = "272886488";
    private static final long UID = 12040301875L;

    /** 记录每次请求（关键词 + 页码），便于断言「没有翻全量」。 */
    private final List<String> calls = new ArrayList<>();

    private AwemeUidResolver newResolver(long cacheSeconds) {
        SuixintuiProperties props = new SuixintuiProperties();
        props.setAwemeUidCacheSeconds(cacheSeconds);
        // clientFactory 传 null：单测一律走注入的 fetcher，不会用到它
        return new AwemeUidResolver(null, props);
    }

    private AwemeUidResolver.AuthorizedAwemeFetcher fetcherWith(List<Aweme> byKeyword,
                                                                List<Aweme> fullScan) {
        return (advertiserId, keyword, page, pageSize) -> {
            calls.add((keyword == null ? "<全量>" : keyword) + "#" + page);
            List<Aweme> src = keyword == null ? fullScan : byKeyword;
            return response(src, page, keyword == null ? 1L : 1L);
        };
    }

    @Test
    @DisplayName("★ 关键词搜索命中 → 只发 1 次请求（不翻全量）")
    void keywordSearchHitsInOneCall() throws Exception {
        AwemeUidResolver resolver = newResolver(600);
        AwemeUidResolver.AuthorizedAwemeFetcher fetcher =
                fetcherWith(List.of(new Aweme(AWEME, UID)), List.of());

        Optional<Long> got = resolver.resolve(ACC, AWEME, fetcher);

        assertEquals(Optional.of(UID), got);
        assertEquals(List.of(AWEME + "#1"), calls, "应该只搜 1 次，不该翻全量");
    }

    @Test
    @DisplayName("★ 关键词是模糊匹配 → 必须按抖音号【精确比对】，不能拿第一条")
    void keywordSearchMatchesExactly() throws Exception {
        AwemeUidResolver resolver = newResolver(600);
        AwemeUidResolver.AuthorizedAwemeFetcher fetcher = fetcherWith(
                List.of(new Aweme("272886488XXX", 111L), new Aweme(AWEME, UID)),
                List.of());

        assertEquals(Optional.of(UID), resolver.resolve(ACC, AWEME, fetcher));
    }

    @Test
    @DisplayName("搜索没命中 → 回退全量扫描")
    void fallsBackToFullScan() throws Exception {
        AwemeUidResolver resolver = newResolver(600);
        AwemeUidResolver.AuthorizedAwemeFetcher fetcher =
                fetcherWith(List.of(), List.of(new Aweme(AWEME, UID)));

        assertEquals(Optional.of(UID), resolver.resolve(ACC, AWEME, fetcher));
        assertTrue(calls.contains(AWEME + "#1"), calls.toString());
        assertTrue(calls.contains("<全量>#1"), "应回退全量：" + calls);
    }

    @Test
    @DisplayName("全量扫描里也没有 → 空（说明该账户下确实没授权）")
    void notFoundReturnsEmpty() throws Exception {
        AwemeUidResolver resolver = newResolver(600);
        AwemeUidResolver.AuthorizedAwemeFetcher fetcher =
                fetcherWith(List.of(), List.of(new Aweme("别人", 999L)));

        assertTrue(resolver.resolve(ACC, AWEME, fetcher).isEmpty());
    }

    @Test
    @DisplayName("命中后有缓存：第二次不再发请求")
    void cachesHits() throws Exception {
        AwemeUidResolver resolver = newResolver(600);
        AwemeUidResolver.AuthorizedAwemeFetcher fetcher =
                fetcherWith(List.of(new Aweme(AWEME, UID)), List.of());

        resolver.resolve(ACC, AWEME, fetcher);
        int afterFirst = calls.size();
        resolver.resolve(ACC, AWEME, fetcher);

        assertEquals(afterFirst, calls.size(), "第二次应命中缓存，不再发请求");
    }

    @Test
    @DisplayName("⚠️ 只缓存【命中】，不缓存【未命中】—— 否则刚授权也建不了单")
    void doesNotCacheMisses() throws Exception {
        AwemeUidResolver resolver = newResolver(600);
        AwemeUidResolver.AuthorizedAwemeFetcher miss =
                fetcherWith(List.of(), List.of(new Aweme("别人", 999L)));
        resolver.resolve(ACC, AWEME, miss);
        int afterMiss = calls.size();

        // 第二次用「已授权」的 fetcher，应能立刻查到（说明未命中没被缓存住）
        AwemeUidResolver.AuthorizedAwemeFetcher hit =
                fetcherWith(List.of(new Aweme(AWEME, UID)), List.of());
        assertEquals(Optional.of(UID), resolver.resolve(ACC, AWEME, hit));
        assertTrue(calls.size() > afterMiss, "未命中不该被缓存：" + calls);
    }

    @Test
    @DisplayName("抖音号为空 → 直接返回空，不发请求")
    void blankAwemeReturnsEmpty() throws Exception {
        AwemeUidResolver resolver = newResolver(600);
        AwemeUidResolver.AuthorizedAwemeFetcher fetcher =
                fetcherWith(List.of(new Aweme(AWEME, UID)), List.of());

        assertTrue(resolver.resolve(ACC, "  ", fetcher).isEmpty());
        assertTrue(calls.isEmpty());
    }

    // ==================== 工具 ====================

    private record Aweme(String showId, long uid) {
    }

    private static QianchuanUniAwemeAuthorizedGetV10Response response(List<Aweme> items,
                                                                      long page,
                                                                      long totalPage) {
        List<QianchuanUniAwemeAuthorizedGetV10ResponseDataAwemeIdListInner> list =
                new ArrayList<>();
        for (Aweme a : items) {
            QianchuanUniAwemeAuthorizedGetV10ResponseDataAwemeIdListInner inner =
                    new QianchuanUniAwemeAuthorizedGetV10ResponseDataAwemeIdListInner();
            inner.setAwemeShowId(a.showId());
            inner.setAwemeId(a.uid());
            inner.setAwemeName("昵称-" + a.showId());
            list.add(inner);
        }
        QianchuanUniAwemeAuthorizedGetV10ResponseDataPageInfo pageInfo =
                new QianchuanUniAwemeAuthorizedGetV10ResponseDataPageInfo();
        pageInfo.setPage(page);
        pageInfo.setTotalPage(totalPage);

        QianchuanUniAwemeAuthorizedGetV10ResponseData data =
                new QianchuanUniAwemeAuthorizedGetV10ResponseData();
        data.setAwemeIdList(list);
        data.setPageInfo(pageInfo);

        QianchuanUniAwemeAuthorizedGetV10Response response =
                new QianchuanUniAwemeAuthorizedGetV10Response();
        response.setCode(0L);
        response.setData(data);
        return response;
    }
}
