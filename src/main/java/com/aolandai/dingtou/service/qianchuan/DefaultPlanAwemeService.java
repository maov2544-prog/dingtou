package com.aolandai.dingtou.service.qianchuan;

import com.aolandai.dingtou.client.ApiErrors;
import com.aolandai.dingtou.client.QianchuanClientFactory;
import com.aolandai.dingtou.client.QianchuanThrottle;
import com.aolandai.dingtou.client.QianchuanThrottleFactory;
import com.aolandai.dingtou.dto.qianchuan.PlanAweme;
import com.aolandai.dingtou.dto.qianchuan.PlanAwemeLookupResult;
import com.aolandai.dingtou.dto.qianchuan.PlanRef;
import com.aolandai.dingtou.exception.RateLimitException;
import com.bytedance.ads.ApiException;
import com.bytedance.ads.model.QianchuanUniPromotionAdDetailV10Response;
import com.bytedance.ads.model.QianchuanUniPromotionAdDetailV10ResponseData;
import com.bytedance.ads.model.QianchuanUniPromotionAdDetailV10ResponseDataAwemeInfosInner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link PlanAwemeService} 的默认实现：调【乘方计划详情】
 * {@code GET /qianchuan/uni_promotion/ad/detail/} 取 {@code data.aweme_infos[]}。
 *
 * <h2>四个刻意的设计</h2>
 * <ol>
 *   <li><b>某个计划失败不中断整批</b>，但记进 {@code warnings} —— 否则「漏查了一个计划」
 *       会被当成「该计划没有达人」；</li>
 *   <li><b>频控必须上抛</b>（{@link RateLimitException}）：40100/40110 是全局惩罚，
 *       继续打只会加重，且拿到的结果不可信；</li>
 *   <li><b>多个达人只取第一个，但要记 warning</b> —— 静默丢弃会让人以为「这个计划只有一个达人」。
 *       （实测本项目计划都是 1 个达人，但不能假定永远如此。）</li>
 *   <li><b>限流/重试交给 {@link QianchuanThrottle}</b>，不在本类自己写退避循环 ——
 *       计划列表查询用的是同一套策略，两处各写一份迟早分叉。</li>
 * </ol>
 *
 * <p>⚠️ 字段映射来自实测（2026-10-04）：</p>
 * <pre>
 * aweme_infos[].aweme_name  → 抖音名（伊伊麻麻）
 * aweme_infos[].unique_id   → 抖音号（610129yiyi）   ← 抖音号在 unique_id，不是 aweme_show_id
 * aweme_infos[].aweme_uid   → 抖音 UID（2071074583877891）
 * </pre>
 */
@Component
public class DefaultPlanAwemeService implements PlanAwemeService {

    private static final Logger log = LoggerFactory.getLogger(DefaultPlanAwemeService.class);

    private final QianchuanClientFactory clientFactory;
    private final QianchuanThrottleFactory throttleFactory;
    private final AwemeShowIdResolver awemeShowIdResolver;

    public DefaultPlanAwemeService(QianchuanClientFactory clientFactory,
                                   QianchuanThrottleFactory throttleFactory,
                                   AwemeShowIdResolver awemeShowIdResolver) {
        this.clientFactory = clientFactory;
        this.throttleFactory = throttleFactory;
        this.awemeShowIdResolver = awemeShowIdResolver;
    }

    @Override
    public Optional<PlanAweme> lookup(long advertiserId, long adId) throws Exception {
        PlanAwemeLookupResult result = lookupAll(List.of(new PlanRef(advertiserId, adId)));
        return result.items().stream().findFirst();
    }

    @Override
    public PlanAwemeLookupResult lookupAll(List<PlanRef> refs) throws Exception {
        if (refs == null || refs.isEmpty()) {
            return new PlanAwemeLookupResult(0, 0, List.of(), List.of());
        }
        // 一次查询只造一个 fetcher（token 由 provider 缓存，现造开销可忽略）
        PlanDetailFetcher fetcher = clientFactory.planDetailFetcher();
        return doLookupAll(refs, fetcher, throttleFactory.newThrottle());
    }

    /**
     * 真正的批量查询逻辑。
     *
     * <p>包级可见是为了单测能注入假 fetcher / 不限流的 throttle —— <b>测试绝不真调千川</b>。</p>
     */
    PlanAwemeLookupResult doLookupAll(List<PlanRef> refs, PlanDetailFetcher fetcher,
                                      QianchuanThrottle throttle) throws ApiException {
        List<PlanAweme> items = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (PlanRef ref : refs) {
            try {
                QianchuanUniPromotionAdDetailV10Response response = throttle.execute(
                        () -> fetcher.fetch(ref.advertiserId(), ref.adId()),
                        QianchuanUniPromotionAdDetailV10Response::getCode,
                        "乘方计划详情(" + ref + ")");
                checkResponse(response, ref);

                List<QianchuanUniPromotionAdDetailV10ResponseDataAwemeInfosInner> infos =
                        response.getData().getAwemeInfos();
                if (infos == null || infos.isEmpty()) {
                    warnings.add("计划 " + ref + " 接口返回成功，但没有达人信息"
                            + "（可能未挂达人视频，或该计划类型不支持）");
                    continue;
                }
                if (infos.size() > 1) {
                    warnings.add("计划 " + ref + " 返回了 " + infos.size()
                            + " 个达人，只取第一个，其余已丢弃");
                }
                QianchuanUniPromotionAdDetailV10ResponseDataAwemeInfosInner first = infos.get(0);
                String showId = first.getUniqueId();
                String name = first.getAwemeName();
                Long uid = first.getAwemeUid();
                if (showId == null || showId.isBlank()) {
                    // ⚠️ 实测真实存在（12.6%）：抖音名有值、但 unique_id（抖音号）为空。
                    //    有 UID 就能反查兜底；连 UID 都没有才是真的取不到。
                    warnings.add("计划 " + ref + " 的达人 '" + (name == null ? "" : name)
                            + "' 没有抖音号（unique_id 为空）"
                            + (uid == null ? "，且接口没给 aweme_uid，无法反查"
                                           : "，稍后用 UID " + uid + " 反查"));
                }
                items.add(new PlanAweme(ref.adId(), planNameOf(response), showId, name, uid));
            } catch (RateLimitException e) {
                // 频控必须上抛：吞掉会让本轮把「被频控拦住的计划」误判成「该计划没有达人」
                throw e;
            } catch (ApiException e) {
                warnings.add("计划 " + ref + " 查询失败: " + e.getMessage());
                log.warn("计划 {} 查达人信息失败: {}", ref, e.getMessage());
            }
        }

        // ⚠️ 兜底：抖音号为空但 UID 有值的，用 UID 去【抖音号授权列表】反查
        List<PlanAweme> filled = fillMissingShowIds(refs, items, fetcher, warnings);

        log.info("查达人信息：请求 {} 个计划，查到 {} 个，警告 {} 条，本次调用累计等待 {}ms（末段间隔 {}ms）",
                refs.size(), filled.size(), warnings.size(),
                throttle.lastCallWaitMillis(), throttle.currentIntervalMillis());
        return new PlanAwemeLookupResult(refs.size(), filled.size(), filled, warnings);
    }

    /** 从详情应答里取计划名（{@code data.name}）；取不到返回空串。 */
    private static String planNameOf(
            com.bytedance.ads.model.QianchuanUniPromotionAdDetailV10Response response) {
        if (response == null || response.getData() == null || response.getData().getName() == null) {
            return "";
        }
        return String.valueOf(response.getData().getName());
    }

    /**
     * 兜底：把「抖音号为空但 UID 有值」的补上。
     *
     * <p>背景：详情接口的 {@code unique_id} 有时是空字符串（实测 12.6%），
     * 但 {@code aweme_uid} 有值 → 拿它去【抖音号授权列表】反查（实测覆盖率 <b>92%</b>）。</p>
     *
     * <p>⚠️ <b>按账户分组、批量查</b>：接口的 {@code aweme_id} 是数组，一次能查多个，
     * 所以 N 个计划最多只多 N 个账户数次调用（通常 1~2 次）。</p>
     *
     * <p>⚠️ 兜底失败<b>不影响主流程</b>：只是抖音号仍为空 + 多一条 warning，
     * 下游（影刀）照样能拿抖音名做事。</p>
     */
    private List<PlanAweme> fillMissingShowIds(List<PlanRef> refs, List<PlanAweme> items,
                                               PlanDetailFetcher fetcher,
                                               List<String> warnings) throws ApiException {
        List<PlanAweme> missing = items.stream().filter(PlanAweme::canReverseLookup).toList();
        if (missing.isEmpty()) {
            return items;
        }

        // PlanAweme 只有 adId，反查需要 advertiserId → 从 refs 建映射
        Map<Long, Long> advertiserByAdId = new HashMap<>();
        for (PlanRef ref : refs) {
            advertiserByAdId.put(ref.adId(), ref.advertiserId());
        }

        Map<Long, Map<Long, String>> resolvedByAccount = new HashMap<>();
        Map<Long, Set<Long>> uidsByAccount = new LinkedHashMap<>();
        for (PlanAweme item : missing) {
            Long advertiserId = advertiserByAdId.get(item.adId());
            if (advertiserId == null) {
                continue;
            }
            uidsByAccount.computeIfAbsent(advertiserId, k -> new LinkedHashSet<>())
                    .add(item.awemeUid());
        }

        for (Map.Entry<Long, Set<Long>> entry : uidsByAccount.entrySet()) {
            try {
                Map<Long, String> found = awemeShowIdResolver.resolveBatch(
                        entry.getKey(), entry.getValue());
                resolvedByAccount.put(entry.getKey(), found);
            } catch (RateLimitException e) {
                // 频控必须上抛：吞掉会让本轮把「被拦住的」误判成「反查不到」
                throw e;
            } catch (ApiException e) {
                warnings.add("抖音号兜底反查失败（账户 " + entry.getKey() + "）：" + e.getMessage());
                log.warn("抖音号兜底反查失败（账户 {}）: {}", entry.getKey(), e.getMessage());
            }
        }

        List<PlanAweme> out = new ArrayList<>(items.size());
        int filledCount = 0;
        for (PlanAweme item : items) {
            if (!item.canReverseLookup()) {
                out.add(item);
                continue;
            }
            Long advertiserId = advertiserByAdId.get(item.adId());
            String showId = advertiserId == null ? null
                    : resolvedByAccount.getOrDefault(advertiserId, Map.of()).get(item.awemeUid());
            if (showId == null) {
                out.add(item);
                continue;
            }
            filledCount++;
            out.add(item.withAwemeShowId(showId));
        }

        if (filledCount > 0) {
            warnings.add("抖音号兜底：用 UID 反查补回 " + filledCount + " 个"
                    + "（详情接口的 unique_id 为空，靠【抖音号授权列表】反查）");
            log.info("抖音号兜底：{} 个缺抖音号的计划，补回 {} 个", missing.size(), filledCount);
        }
        return out;
    }

    /**
     * ⚠️ 把 SDK 的「业务错误」显式抛出来。
     *
     * <p>SDK 在 {@code code != 0} 时<b>不抛异常</b>，只返回 {@code data == null} ——
     * 这正是本项目历史上「假阴性」的根源（接口报错被静默翻译成「没有数据」）。
     * 这里主动抛出，让上层记进 warnings，绝不静默。</p>
     *
     * <p>频控码已被 {@link QianchuanThrottle} 消化掉，走到这里的基本是业务错误；
     * 但仍保留频控识别作为兜底。</p>
     */
    private static void checkResponse(QianchuanUniPromotionAdDetailV10Response response, PlanRef ref)
            throws ApiException {
        if (response == null) {
            throw new ApiException("乘方计划详情返回 null（" + ref + "）");
        }
        Long code = response.getCode();
        if (code != null && code != 0L) {
            RateLimitException rateLimit = ApiErrors.rateLimitOrNull(
                    code, "乘方计划详情(" + ref + ")", response.getRequestId());
            if (rateLimit != null) {
                throw rateLimit;
            }
            throw new ApiException(code.intValue(), "乘方计划详情业务错误（" + ref
                    + "）：code=" + code + " message=" + response.getMessage());
        }
        QianchuanUniPromotionAdDetailV10ResponseData data = response.getData();
        if (data == null) {
            throw new ApiException("乘方计划详情 code=0 但 data 为空（" + ref + "）");
        }
    }
}
