package com.aolandai.dingtou.service.qianchuan;

import com.aolandai.dingtou.client.QianchuanClientFactory;
import com.aolandai.dingtou.config.QianchuanProperties;
import com.bytedance.ads.ApiException;
import com.bytedance.ads.model.QianchuanAwemeAuthListGetV10Response;
import com.bytedance.ads.model.QianchuanAwemeAuthListGetV10ResponseDataAuthorizationInfosInner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>数值 UID → 抖音号</b> 的反查（乘方计划详情接口「只有抖音名没有抖音号」的兜底）。
 *
 * <h2>为什么要它</h2>
 * <p>【乘方计划详情】接口 {@code uni_promotion/ad/detail/} 的 {@code aweme_infos[].unique_id}
 * （抖音号）<b>有时候是空字符串</b> —— 实测 175 个计划里 22 个（<b>12.6%</b>）。
 * 但同一应答里的 {@code aweme_uid}（数值 UID）<b>总是有值</b>。</p>
 *
 * <p>所以拿 UID 去【抖音号授权列表】接口 {@code aweme_auth_list/get/} 反查
 * {@code aweme_show_id}（抖音号）即可。<b>实测兜底覆盖率 11/12 = 92%</b>
 * （唯一失败的那个连抖音名和 UID 都没有，是计划压根没挂达人）。</p>
 *
 * <h2>🔴 为什么用这个接口而不是 {@code uni_aweme/authorized/get/}</h2>
 * <table border="1">
 *   <tr><th></th><th>{@code uni_aweme/authorized/get/}</th>
 *       <th>{@code aweme_auth_list/get/}（本类用的）</th></tr>
 *   <tr><td>过滤条件</td><td>只有 {@code search_key_words}（<b>只能按抖音号搜</b>）</td>
 *       <td><b>{@code aweme_id}（数值 UID）数组</b>、{@code aweme_show_id}、{@code aweme_name}…</td></tr>
 *   <tr><td>按 UID 反查</td><td>❌ 只能<b>全量扫</b>（实测某账户 <b>9600 个达人 / 96 页</b>，约 4.5 分钟）</td>
 *       <td>✅ <b>一次请求，约 1 秒</b></td></tr>
 *   <tr><td>批量</td><td>—</td><td>✅ {@code aweme_id} 是数组，一次可查多个</td></tr>
 * </table>
 *
 * <h2>⚠️ 两个必须记住的点</h2>
 * <ul>
 *   <li><b>{@code aweme_id} 必须是数组</b>：传标量会报
 *       {@code code=40000 filtering: aweme_id: Field must be set to array}；</li>
 *   <li><b>「查不到」不缓存</b>：查不到可能是刚授权还没生效，缓存住会让它一直拿不到抖音号
 *       （与 {@link AwemeUidResolver} 同一条原则）。</li>
 * </ul>
 */
@Component
public class AwemeShowIdResolver {

    private static final Logger log = LoggerFactory.getLogger(AwemeShowIdResolver.class);

    /**
     * 单次请求最多带多少个 UID。
     *
     * <p>接口没写上限，保守取 50 —— 批量是为了少发请求，不是为了发超长 URL。
     * 实测一次传 5 个正常。</p>
     */
    private static final int BATCH_SIZE = 50;

    /**
     * 底层的「按 UID 列表查授权关系」抽象 —— 便于单测注入假实现，不真调接口。
     */
    @FunctionalInterface
    public interface AwemeAuthListFetcher {
        QianchuanAwemeAuthListGetV10Response fetch(long advertiserId, List<Long> awemeIds)
                throws ApiException;
    }

    private final QianchuanClientFactory clientFactory;
    private final long cacheSeconds;

    /**
     * 命中缓存：{@code "账户|UID"} → 抖音号。只缓存<b>查到</b>的（见类注释）。
     *
     * <p>⚠️ <b>带 TTL</b>：抖音号理论上会变（达人自己改），授权关系也会变。
     * 缓存没有过期时间就等于「一旦查错就永远错」。</p>
     */
    private final Map<String, CachedShowId> cache = new ConcurrentHashMap<>();

    /** 带过期时间的缓存条目。 */
    private record CachedShowId(String showId, long expiresAtMillis) {

        boolean isAlive(long nowMillis) {
            return nowMillis < expiresAtMillis;
        }
    }

    public AwemeShowIdResolver(QianchuanClientFactory clientFactory,
                               QianchuanProperties properties) {
        this.clientFactory = clientFactory;
        this.cacheSeconds = properties.getAwemeShowIdCacheSeconds();
    }

    /**
     * 批量反查：UID 列表 → {@code UID → 抖音号}。
     *
     * <p>返回的 map <b>只含查到的</b> —— 没查到的不放进去（调用方自己决定怎么兜底）。</p>
     *
     * @throws ApiException 换取 token 失败 / 接口报错（限流会抛 {@code RateLimitException}）
     */
    public Map<Long, String> resolveBatch(long advertiserId, Collection<Long> awemeUids)
            throws ApiException {
        return resolveBatch(advertiserId, awemeUids, null);
    }

    /** 可注入 fetcher 的版本（单测用）。 */
    Map<Long, String> resolveBatch(long advertiserId, Collection<Long> awemeUids,
                                   AwemeAuthListFetcher fetcher) throws ApiException {
        Map<Long, String> out = new LinkedHashMap<>();
        if (awemeUids == null || awemeUids.isEmpty()) {
            return out;
        }

        Set<Long> pending = new LinkedHashSet<>();
        for (Long uid : awemeUids) {
            if (uid == null) {
                continue;
            }
            CachedShowId cached = cache.get(cacheKey(advertiserId, uid));
            if (cached != null && cached.isAlive(System.currentTimeMillis())) {
                out.put(uid, cached.showId());
            } else {
                pending.add(uid);
            }
        }
        if (pending.isEmpty()) {
            log.debug("账户 {} 的 {} 个 UID 全部命中缓存", advertiserId, awemeUids.size());
            return out;
        }

        AwemeAuthListFetcher real = fetcherOf(fetcher);
        List<Long> list = new ArrayList<>(pending);
        for (int i = 0; i < list.size(); i += BATCH_SIZE) {
            List<Long> batch = list.subList(i, Math.min(i + BATCH_SIZE, list.size()));
            Map<Long, String> found = fetchOneBatch(real, advertiserId, batch);
            out.putAll(found);
        }

        log.info("UID→抖音号 反查：账户 {} 请求 {} 个（缓存命中 {}），查到 {} 个",
                advertiserId, awemeUids.size(), awemeUids.size() - pending.size(), out.size());
        return out;
    }

    /**
     * 取 fetcher；没注入就现造一个。
     *
     * <p>⚠️ 工厂方法抛的是 {@code Exception}（换取 token 失败等），
     * 这里统一包成 {@link ApiException} —— 与 {@code AwemeUidResolver} 保持一致。</p>
     */
    private AwemeAuthListFetcher fetcherOf(AwemeAuthListFetcher fixedFetcher) throws ApiException {
        if (fixedFetcher != null) {
            return fixedFetcher;
        }
        try {
            return clientFactory.awemeAuthListFetcher();
        } catch (Exception e) {
            throw new ApiException("换取千川 Access-Token 失败，无法反查抖音号：" + e.getMessage());
        }
    }

    private Map<Long, String> fetchOneBatch(AwemeAuthListFetcher fetcher, long advertiserId,
                                            List<Long> batch) throws ApiException {
        QianchuanAwemeAuthListGetV10Response response = fetcher.fetch(advertiserId, batch);
        checkResponse(response, advertiserId);

        Map<Long, String> out = new LinkedHashMap<>();
        List<QianchuanAwemeAuthListGetV10ResponseDataAuthorizationInfosInner> infos =
                response.getData().getAuthorizationInfos();
        if (infos == null || infos.isEmpty()) {
            return out;
        }
        for (var info : infos) {
            if (info == null || info.getAwemeId() == null) {
                continue;
            }
            String showId = info.getAwemeShowId();
            if (showId == null || showId.isBlank()) {
                continue;
            }
            String trimmed = showId.trim();
            out.put(info.getAwemeId(), trimmed);
            cache.put(cacheKey(advertiserId, info.getAwemeId()),
                    new CachedShowId(trimmed,
                            System.currentTimeMillis() + cacheSeconds * 1000L));
        }
        return out;
    }

    /**
     * ⚠️ 把 SDK 的「业务错误」显式抛出来。
     *
     * <p>SDK 在 {@code code != 0} 时<b>不抛异常</b>，只返回 {@code code} + 空 data。
     * 静默会把「接口报错」翻译成「这个达人没有抖音号」—— 两者处理方式完全相反。</p>
     */
    private static void checkResponse(QianchuanAwemeAuthListGetV10Response response,
                                      long advertiserId) throws ApiException {
        if (response == null) {
            throw new ApiException("查授权列表返回 null（账户 " + advertiserId + "）");
        }
        Long code = response.getCode();
        if (code == null || code != 0L) {
            throw new ApiException(code == null ? -1 : code.intValue(),
                    "查授权列表业务错误（账户 " + advertiserId + "）：" + response.getMessage());
        }
        if (response.getData() == null) {
            throw new ApiException("查授权列表 code=0 但 data 为空（账户 " + advertiserId + "）");
        }
    }

    /** 供探针/排障观察缓存规模。 */
    public int cacheSize() {
        return cache.size();
    }

    /** 缓存时长（秒）—— 供启动横幅展示。 */
    public long cacheSeconds() {
        return cacheSeconds;
    }

    private static String cacheKey(long advertiserId, long awemeUid) {
        return advertiserId + "|" + awemeUid;
    }
}
