package com.aolandai.dingtou.service.qianchuan;

import com.aolandai.dingtou.client.ApiErrors;
import com.aolandai.dingtou.client.QianchuanClientFactory;
import com.aolandai.dingtou.config.SuixintuiProperties;
import com.aolandai.dingtou.exception.RateLimitException;
import com.bytedance.ads.ApiException;
import com.bytedance.ads.model.QianchuanUniAwemeAuthorizedGetV10Response;
import com.bytedance.ads.model.QianchuanUniAwemeAuthorizedGetV10ResponseDataAwemeIdListInner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把【抖音号】换算成<b>数值 awemeId</b>。
 *
 * <h2>⚠️ 为什么需要它</h2>
 * <p>建随心推接口的 {@code aweme_id} 是 <b>{@code Long}（数值 UID）</b>，
 * 而素材表里存的是<b>抖音号字符串</b>（如 {@code 610129yiyi}、{@code LSW39818}）——
 * 两者<b>不是一回事，不能直接传</b>。</p>
 *
 * <p>换算方式：调 {@code /qianchuan/uni_aweme/authorized/get/}（<b>账户下的已授权达人列表</b>），
 * 它每条同时返回 {@code awemeId}（数值）+ {@code awemeShowId}（抖音号）+ {@code awemeName}（昵称），
 * 正好能建一张「抖音号 → 数值 UID」的对照表。</p>
 *
 * <h2>缓存</h2>
 * <p>同一账户的授权达人列表短期内不会变，而影刀会<b>连续</b>为多个达人建单 ——
 * 不缓存就会为每个达人都把整张列表重拉一遍。缓存时长见
 * {@code dingtou.suixintui.aweme-uid-cache-seconds}。</p>
 *
 * <p>⚠️ 只返回<b>已授权</b>的达人。查不到说明该达人未授权给这个账户 ——
 * <b>这是「不能建」的信号，不是「跳过就好」</b>，调用方必须报出来。</p>
 */
@Component
public class AwemeUidResolver {

    private static final Logger log = LoggerFactory.getLogger(AwemeUidResolver.class);

    /** 单账户授权达人列表的每页条数。 */
    private static final long PAGE_SIZE = 100L;

    /**
     * 全量扫描的翻页上限（<b>只在关键词搜索没命中时才走到</b>）。
     *
     * <p>⚠️ 实测某账户有 <b>9600 个授权达人（96 页）</b> —— 所以全量扫描又慢又容易截断。
     * 这里是兜底路径，正常应该靠 {@code searchKeyWords} 1 次请求命中。</p>
     */
    private static final long MAX_PAGES = 200L;

    /**
     * 底层的「拉一页授权达人」抽象 —— 便于单测注入假实现，不真调接口。
     *
     * @param searchKeyword 关键词（抖音号）；{@code null} = 不限，拉全量
     */
    @FunctionalInterface
    public interface AuthorizedAwemeFetcher {
        QianchuanUniAwemeAuthorizedGetV10Response fetch(long advertiserId, String searchKeyword,
                                                        long page, long pageSize)
                throws ApiException;
    }

    private final QianchuanClientFactory clientFactory;
    private final long cacheSeconds;

    /**
     * 命中缓存：{@code "账户|抖音号"} → 数值 UID。
     *
     * <p>⚠️ 只缓存<b>搜索命中</b>的结果，<b>不缓存「未命中」</b> ——
     * 「现在搜不到」可能是刚授权还没生效，缓存住会让它一直建不了单。</p>
     */
    private final Map<String, CachedHit> hitCache = new ConcurrentHashMap<>();

    public AwemeUidResolver(QianchuanClientFactory clientFactory, SuixintuiProperties properties) {
        this.clientFactory = clientFactory;
        this.cacheSeconds = properties.getAwemeUidCacheSeconds();
    }

    /**
     * 抖音号 → 数值 awemeId。
     *
     * @return 数值 UID；该账户下<b>未授权</b>该抖音号时为空
     */
    public Optional<Long> resolve(long advertiserId, String awemeShowId) throws ApiException {
        return resolve(advertiserId, awemeShowId, null);
    }

    /**
     * 测试可见：注入假 fetcher（不依赖 clientFactory、不真调接口）。
     *
     * @param fixedFetcher 非空则用它，忽略 clientFactory
     */
    Optional<Long> resolve(long advertiserId, String awemeShowId,
                           AuthorizedAwemeFetcher fixedFetcher) throws ApiException {
        if (awemeShowId == null || awemeShowId.isBlank()) {
            return Optional.empty();
        }
        String aweme = awemeShowId.trim();
        String cacheKey = advertiserId + "|" + aweme;

        // ① 命中缓存
        CachedHit hit = hitCache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (hit != null && now - hit.atMillis() < cacheSeconds * 1000L) {
            return Optional.of(hit.awemeId());
        }

        AuthorizedAwemeFetcher fetcher = fetcherOf(fixedFetcher);

        // ② 按【抖音号】关键词搜（快）—— 只翻 1 页即可
        Long found = searchByKeyword(advertiserId, aweme, fetcher);
        if (found != null) {
            if (cacheSeconds > 0) {
                hitCache.put(cacheKey, new CachedHit(found, now));
            }
            return Optional.of(found);
        }

        // ③ 搜索没命中 → 回退全量扫描（准，但慢：实测某账户有 9600 个达人 = 96 页）
        log.warn("账户 {} 按关键词「{}」没搜到授权达人，回退全量扫描（较慢）", advertiserId, aweme);
        Map<String, Long> all = loadAll(advertiserId, fetcher);
        Long fallback = all.get(aweme);
        if (fallback != null && cacheSeconds > 0) {
            hitCache.put(cacheKey, new CachedHit(fallback, now));
        }
        return Optional.ofNullable(fallback);
    }

    /** 清空缓存（配置变更 / 排障用）。 */
    public void clearCache() {
        hitCache.clear();
    }

    // ==================== 内部 ====================

    private AuthorizedAwemeFetcher fetcherOf(AuthorizedAwemeFetcher fixedFetcher)
            throws ApiException {
        if (fixedFetcher != null) {
            return fixedFetcher;
        }
        try {
            return clientFactory.authorizedAwemeFetcher();
        } catch (Exception e) {
            throw new ApiException("换取千川 Access-Token 失败，无法查授权达人：" + e.getMessage());
        }
    }

    /**
     * 按关键词搜一页，本地<b>精确匹配</b>抖音号。
     *
     * <p>⚠️ 关键词是<b>模糊</b>匹配，可能返回一堆相似达人 —— 所以必须再按
     * {@code awemeShowId} 精确比对，不能拿搜索结果的第一条就用。</p>
     */
    private static Long searchByKeyword(long advertiserId, String aweme,
                                        AuthorizedAwemeFetcher fetcher) throws ApiException {
        QianchuanUniAwemeAuthorizedGetV10Response response =
                fetcher.fetch(advertiserId, aweme, 1L, PAGE_SIZE);
        checkResponse(response, advertiserId);
        List<QianchuanUniAwemeAuthorizedGetV10ResponseDataAwemeIdListInner> list =
                response.getData().getAwemeIdList();
        if (list == null) {
            return null;
        }
        for (QianchuanUniAwemeAuthorizedGetV10ResponseDataAwemeIdListInner item : list) {
            if (item != null && item.getAwemeId() != null
                    && item.getAwemeShowId() != null
                    && aweme.equalsIgnoreCase(item.getAwemeShowId().trim())) {
                log.info("账户 {} 授权达人命中：抖音号 {} → UID {}（关键词搜索，1 次请求）",
                        advertiserId, aweme, item.getAwemeId());
                return item.getAwemeId();
            }
        }
        return null;
    }

    private static Map<String, Long> loadAll(long advertiserId, AuthorizedAwemeFetcher fetcher)
            throws ApiException {
        Map<String, Long> index = new LinkedHashMap<>();
        long page = 1L;
        long totalPages = 1L;

        while (page <= totalPages && page <= MAX_PAGES) {
            QianchuanUniAwemeAuthorizedGetV10Response response =
                    fetcher.fetch(advertiserId, null, page, PAGE_SIZE);
            checkResponse(response, advertiserId);

            List<QianchuanUniAwemeAuthorizedGetV10ResponseDataAwemeIdListInner> list =
                    response.getData().getAwemeIdList();
            if (list != null) {
                for (QianchuanUniAwemeAuthorizedGetV10ResponseDataAwemeIdListInner item : list) {
                    if (item == null || item.getAwemeId() == null
                            || item.getAwemeShowId() == null || item.getAwemeShowId().isBlank()) {
                        continue;
                    }
                    index.putIfAbsent(item.getAwemeShowId().trim(), item.getAwemeId());
                }
            }

            if (response.getData().getPageInfo() != null
                    && response.getData().getPageInfo().getTotalPage() != null) {
                totalPages = response.getData().getPageInfo().getTotalPage();
            } else if (list == null || list.size() < PAGE_SIZE) {
                break;
            }
            page++;
        }
        log.info("账户 {} 授权达人全量扫描完成：{} 页，{} 个抖音号", advertiserId, page, index.size());
        return index;
    }

    /**
     * ⚠️ 把 SDK 的「业务错误」显式抛出来（SDK 在 {@code code != 0} 时不抛异常）。
     * 静默会把「接口报错」变成「这个达人没授权」—— 两者处理方式完全相反。
     */
    private static void checkResponse(QianchuanUniAwemeAuthorizedGetV10Response response,
                                      long advertiserId) throws ApiException {
        if (response == null) {
            throw new ApiException("查授权达人返回 null（账户 " + advertiserId + "）");
        }
        Long code = response.getCode();
        if (code != null && code != 0L) {
            RateLimitException rateLimit = ApiErrors.rateLimitOrNull(
                    code, "查授权达人(账户 " + advertiserId + ")", response.getRequestId());
            if (rateLimit != null) {
                throw rateLimit;
            }
            throw new ApiException(code.intValue(), "查授权达人业务错误（账户 " + advertiserId
                    + "）：code=" + code + " message=" + response.getMessage());
        }
        if (response.getData() == null) {
            throw new ApiException("查授权达人 code=0 但 data 为空（账户 " + advertiserId + "）");
        }
    }

    /** 命中缓存条目：数值 UID + 命中时刻。 */
    private record CachedHit(long awemeId, long atMillis) {
    }
}
