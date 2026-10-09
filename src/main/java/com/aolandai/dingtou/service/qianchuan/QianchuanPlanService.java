package com.aolandai.dingtou.service.qianchuan;

import com.aolandai.dingtou.client.QianchuanThrottle;
import com.aolandai.dingtou.config.QianchuanProperties;
import com.aolandai.dingtou.dto.qianchuan.QianchuanPlanQueryResult;
import com.aolandai.dingtou.exception.AmbiguousPlanException;

import com.bytedance.ads.ApiClient;
import com.bytedance.ads.ApiException;
import com.bytedance.ads.api.QianchuanUniPromotionListV10Api;
import com.bytedance.ads.model.QianchuanUniPromotionListV10AdlabScene;
import com.bytedance.ads.model.QianchuanUniPromotionListV10Fields;
import com.bytedance.ads.model.QianchuanUniPromotionListV10Filtering;
import com.bytedance.ads.model.QianchuanUniPromotionListV10FilteringHavingCost;
import com.bytedance.ads.model.QianchuanUniPromotionListV10FilteringSearchKeywordType;
import com.bytedance.ads.model.QianchuanUniPromotionListV10FilteringStatus;
import com.bytedance.ads.model.QianchuanUniPromotionListV10MarketingGoal;
import com.bytedance.ads.model.QianchuanUniPromotionListV10OrderField;
import com.bytedance.ads.model.QianchuanUniPromotionListV10OrderType;
import com.bytedance.ads.model.QianchuanUniPromotionListV10PageSize;
import com.bytedance.ads.model.QianchuanUniPromotionListV10Response;
import com.bytedance.ads.model.QianchuanUniPromotionListV10ResponseDataAdListInner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 基于 OceanEngine Java SDK 的千川投放计划查询服务。
 *
 * <h2>⚠️ 血泪教训：这个接口有 4 个「静默假阴性」陷阱</h2>
 * <p>本类历史上返回过「0 个计划、无 warnings」，但计划明明存在（{@code adId=1877813517052328}），
 * 导致 {@code decide} 误判「无计划」→ 重复建计划（花钱）。实测定位到 4 个坑，全部修掉：</p>
 * <ol>
 *   <li><b>场景（adlab_scene）漏了乘方</b>：接口的 {@code adlab_scene} 默认只返回 {@code UNI_PROJECT}
 *       （全域推广），而本系统建的是 <b>{@code OVERALL_PROJECT}（乘方）</b>计划 ——
 *       不显式传 {@code OVERALL_PROJECT} 就永远查不到自己建的计划。这是本次 bug 的<b>根因</b>。</li>
 *   <li><b>时间格式</b>：{@code start_time/end_time} 必须是 <b>带时分秒</b>的
 *       {@code yyyy-MM-dd HH:mm:ss}（Go 布局 {@code 2006-01-02 15:04:05}），只给 {@code yyyy-MM-dd}
 *       会报 {@code 40000 ... datetime=2006-01-02 15:04:05}。</li>
 *   <li><b>时间窗上限 180 天</b>：给 365 天会报 {@code 40000 仅支持查询近180天以内的数据}。</li>
 *   <li><b>{@code fields} 不能乱传</b>：{@code allFields()} 里的
 *       {@code total_prepay_order_count_for_roi2} 商品全域不支持，会让整个查询报
 *       {@code 40000 商品全域不支持...查询指标}；这里只传最小的 {@code stat_cost}。</li>
 * </ol>
 *
 * <p><b>最致命的是：以上 4 种失败，SDK 都<b>不抛异常</b></b> —— 它返回
 * {@code code != 0} 且 {@code data == null}。旧代码把 {@code data == null} 当成「没有下一页」
 * 直接 break，于是「接口报错」被静默翻译成「该组合没有计划」，且 <b>warnings 为空</b>。
 * 本类现在会主动把 {@code code != 0} 抛成 {@link ApiException}，让上层记进 warnings。</p>
 *
 * <h2>另一个坑：{@code search_keyword} 只支持 VIDEO</h2>
 * <p>{@code search_keyword}/{@code search_keyword_type} 仅当 {@code marketing_goal=VIDEO_PROM_GOODS}
 * 时支持；LIVE 传了会报 {@code 40000 search_keyword仅当marketing_goal=VIDEO_PROM_GOODS支持}。
 * 而「按抖音号过滤」正是我们唯一精确的达人维度过滤手段，所以本服务<b>只查 VIDEO_PROM_GOODS</b>。
 * （LIVE 计划无法按达人过滤，只能按商品匹配，而不同达人可能投同一商品 → 会假阳性，故不查。）</p>
 *
 * <h2>扩展：场景参数化 + awemeId 可选</h2>
 * <ul>
 *   <li><b>场景</b>：从硬编码 {@code [UNI_PROJECT, OVERALL_PROJECT]} 改为<b>方法参数传入</b>，
 *       调用方可按需指定只查全域、只查乘方、或两个都查；</li>
 *   <li><b>awemeId（抖音号）</b>：从<b>必传</b>改为<b>可选</b> ——
 *       传 {@code null} 或空串时不设 {@code search_keyword}，返回该账户下<b>全部</b>计划
 *       （再按 {@code productId} 本地匹配），用于「全量盘点」类需求。</li>
 * </ul>
 */
public final class QianchuanPlanService {

    private static final Logger log = LoggerFactory.getLogger(QianchuanPlanService.class);

    private static final String BASE_PATH = "https://api.oceanengine.com";

    /** 单页大小。 */
    private static final int PAGE_SIZE = 100;

    /** 接口硬上限：180 天。 */
    private static final int MAX_LOOKBACK_DAYS = 180;

    /** ⚠️ 必须是「带时分秒」的 datetime，接口用 Go 布局 {@code 2006-01-02 15:04:05}。 */
    private static final DateTimeFormatter DATE_TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 查询时请求的指标字段。
     *
     * <p>⚠️ {@code fields} 是<b>必填</b>，但只能传商品全域支持的指标 ——
     * 传 {@code allFields()}（含 {@code total_prepay_order_count_for_roi2}）会被拒。
     * 计划查询本身不需要任何指标，所以只传最小的 {@code stat_cost}。</p>
     */
    private static final List<QianchuanUniPromotionListV10Fields> QUERY_FIELDS =
            List.of(QianchuanUniPromotionListV10Fields.STAT_COST);

    /** 底层的「拉一页」函数抽象 —— 便于单测注入假实现，不真调千川。 */
    @FunctionalInterface
    public interface PlanListFetcher {
        QianchuanUniPromotionListV10Response fetch(
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
                QianchuanUniPromotionListV10AdlabScene scene) throws ApiException;
    }

    private final PlanListFetcher fetcher;

    /**
     * 查询参数（回溯天数 {@code plan-query-lookback-days}、翻页上限 {@code max-pages}）。
     *
     * <p>⚠️ 翻页上限<b>必须可配</b>：它曾经写死 20，而实测某账户单个场景有 <b>26 页</b>
     * （2592 个计划）→ <b>静默漏掉 23% 的计划</b>，连带漏掉达标计划和达人。</p>
     */
    private final QianchuanProperties properties;

    /**
     * 限流器：<b>整个服务实例共用一个</b>。
     *
     * <p>⚠️ 这是踩过坑的设计：{@code 40100} 是<b>接口全局总频控</b>（所有开发者共享），
     * 与「哪个账户」无关。所以自适应学到的间隔必须<b>跨账户保持</b> ——
     * 曾经按账户各 new 一个，导致账户 1 学到「该降速了」，账户 2 又从起始间隔猛打，
     * 实测直接被频控打到整批失败。</p>
     *
     * <p>而本服务实例是「一次任务造一个」（见 {@code QianchuanClientFactory#planService()}），
     * 所以「实例级」正好等于「任务级」。</p>
     */
    private final QianchuanThrottle throttle;

    /**
     * 生产构造。
     *
     * <p>⚠️ 刻意<b>不提供</b>「不给限流器」的便捷构造：那样会静默绕过频控保护
     * （本类历史上就是「间隔写死、配置改了也不生效」）。要造本对象就必须显式给出限流器。</p>
     *
     * @param accessToken Access-Token
     * @param properties  查询参数（回溯天数、翻页上限）
     * @param throttle    整个任务共用的限流器
     */
    public QianchuanPlanService(String accessToken, QianchuanProperties properties,
                                QianchuanThrottle throttle) {
        this(defaultFetcher(accessToken), properties, throttle);
    }

    /** 测试用：注入自定义 fetcher + 不限流的 throttle（不真等待，不拖慢单测）。 */
    QianchuanPlanService(PlanListFetcher fetcher, int lookbackDays) {
        this(fetcher, testProperties(lookbackDays), QianchuanThrottle.forTest());
    }

    /**
     * 全参数构造。
     *
     * @param fetcher    底层「拉一页」实现（测试可传假实现）
     * @param properties 查询参数（回溯天数、翻页上限）
     * @param throttle   整个任务共用的限流器
     */
    QianchuanPlanService(PlanListFetcher fetcher, QianchuanProperties properties,
                         QianchuanThrottle throttle) {
        if (fetcher == null) {
            throw new IllegalArgumentException("fetcher 不能为空");
        }
        if (properties == null) {
            throw new IllegalArgumentException("properties 不能为空");
        }
        if (properties.getPlanQueryLookbackDays() <= 0) {
            throw new IllegalArgumentException(
                    "planQueryLookbackDays 必须为正: " + properties.getPlanQueryLookbackDays());
        }
        if (properties.getMaxPages() <= 0) {
            throw new IllegalArgumentException("maxPages 必须为正: " + properties.getMaxPages());
        }
        if (throttle == null) {
            throw new IllegalArgumentException("throttle 不能为空");
        }
        this.fetcher = fetcher;
        this.properties = properties;
        this.throttle = throttle;
    }

    /** 测试便捷：只关心回溯天数时用它，其余用默认值。 */
    private static QianchuanProperties testProperties(int lookbackDays) {
        QianchuanProperties p = new QianchuanProperties();
        p.setPlanQueryLookbackDays(lookbackDays);
        return p;
    }

    private static PlanListFetcher defaultFetcher(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("accessToken 不能为空");
        }
        ApiClient client = new ApiClient();
        client.setBasePath(BASE_PATH);
        client.addDefaultHeader("Access-Token", accessToken);
        QianchuanUniPromotionListV10Api api = new QianchuanUniPromotionListV10Api(client);
        return (advertiserId, startTime, endTime, goal, fields, filtering,
                orderType, orderField, page, pageSize, scene) ->
                api.openApiV10QianchuanUniPromotionListGet(
                        advertiserId, startTime, endTime, goal, fields, filtering,
                        null, orderType, orderField, page, pageSize, scene, null);
    }

    // ==================== 查询方法 ====================

    /**
     * 查询一个达人 + 商品组合的全部匹配计划。
     *
     * <p>实现：对指定<b>场景列表</b>中的每一个场景，
     * 用 {@code search_keyword_type=AWEME}（抖音号）过滤（{@code awemeId} 非空时），
     * 再对返回计划做商品 ID 本地精确匹配。</p>
     *
     * <p>返回结果同时带出 {@code rawPlanCount}（接口总共返回几个）与
     * {@code unmatchedPlans}（返回了但商品没匹配上的），用于区分
     * 「接口压根没返回」和「返回了但商品 ID 没匹配上」。</p>
     *
     * @param advertiserId 投放账户 ID（必传）
     * @param awemeId      抖音号；{@code null} 或空白时不设 {@code search_keyword}，
     *                     返回该账户下<b>全部</b>计划（再按 {@code productId} 本地匹配）
     * @param productId    目标商品 ID（必传，用于本地匹配）
     * @param scenes       要查询的场景列表（必传，至少一个）
     */
    public QianchuanPlanQueryResult queryByAwemeAndProduct(
            Long advertiserId, String awemeId, String productId,
            List<QianchuanUniPromotionListV10AdlabScene> scenes) throws ApiException {
        validate(advertiserId, productId, scenes);

        FetchedPlans fetched = fetchAllPlans(advertiserId, scenes,
                defaultStartTime(), defaultEndTime(), buildFiltering(awemeId),
                null, null, null);

        List<QianchuanUniPromotionListV10ResponseDataAdListInner> matched = new ArrayList<>();
        List<QianchuanUniPromotionListV10ResponseDataAdListInner> unmatched = new ArrayList<>();
        for (QianchuanUniPromotionListV10ResponseDataAdListInner plan : fetched.plans()) {
            if (containsProduct(plan, productId)) {
                matched.add(plan);
            } else {
                unmatched.add(plan);
            }
        }

        return new QianchuanPlanQueryResult(awemeId, productId, matched, unmatched,
                fetched.plans().size(), fetched.pageCount());
    }

    /**
     * 查询某账户在指定时间窗内的<b>全部计划</b>（不做达人/商品过滤）。
     *
     * <p>与 {@link #queryByAwemeAndProduct} 的区别：</p>
     * <ul>
     *   <li>不带 {@code search_keyword}（不按抖音号过滤）；</li>
     *   <li>不过滤商品；</li>
     *   <li>过滤条件由调用方给：{@code status}（如 {@code DELIVERY_OK}）+
     *       {@code havingCost}（如 {@code YES} = 有消耗）；</li>
     *   <li>时间窗<b>由调用方显式指定</b>（本方法不用构造时的 {@code lookbackDays}）。</li>
     * </ul>
     *
     * <p>典型用途：「找出某账户下所有『投放中 + 有消耗』的计划」，
     * 再在上层按 {@code statsInfo.statCost} 做消耗阈值过滤。</p>
     *
     * <h2>⚠️ 一律按【整体消耗降序】拉取，并在低于 {@code minStatCostRaw} 时提前停止翻页</h2>
     * <p>这是<b>实测调出来的关键优化</b>：某账户单个场景有 <b>2592 个计划 / 26 页</b>
     * （2026-10-04 实测），而达标的只有几十个。因为接口支持
     * {@code order_field=stat_cost&order_type=DESC}，所以：</p>
     * <ul>
     *   <li>给出 {@code minStatCostRaw} → 翻到<b>第一个低于阈值</b>的计划即可停
     *       （降序，后面只会更低）→ 26 页变 <b>1~2 页</b>；</li>
     *   <li>不给 → 拉全部（此时 {@code MAX_PAGES} 才是真正的上限）。</li>
     * </ul>
     * <p>实测：某账户+场景 2592 个计划，按降序后第 1 页末尾已降到 2160 元
     * （阈值 2000 元），所以 <b>1 页就够</b>。</p>
     *
     * @param advertiserId    投放账户 ID（必传）
     * @param scenes          要查询的场景列表（必传，至少一个）
     * @param startTime       开始时间，格式 {@code yyyy-MM-dd HH:mm:ss}
     * @param endTime         结束时间，格式 {@code yyyy-MM-dd HH:mm:ss}
     * @param status          计划状态过滤；{@code null} = 不按状态过滤
     * @param havingCost      是否有消耗过滤；{@code null} = 不按消耗过滤
     * @param minStatCostRaw  <b>消耗下限</b>（{@code stat_cost} 原始单位 = 千分之一分）；
     *                        给出后可提前停止翻页。{@code null} = 不限，拉全部
     * @return 跨场景、跨页<b>去重后</b>的计划（按消耗降序）
     */
    public List<QianchuanUniPromotionListV10ResponseDataAdListInner> queryAllPlans(
            Long advertiserId,
            List<QianchuanUniPromotionListV10AdlabScene> scenes,
            String startTime, String endTime,
            QianchuanUniPromotionListV10FilteringStatus status,
            QianchuanUniPromotionListV10FilteringHavingCost havingCost,
            Long minStatCostRaw) throws ApiException {
        validate(advertiserId, scenes);

        QianchuanUniPromotionListV10Filtering filtering = new QianchuanUniPromotionListV10Filtering();
        filtering.setStatus(status);
        filtering.setHavingCost(havingCost);

        return fetchAllPlans(advertiserId, scenes, startTime, endTime, filtering,
                QianchuanUniPromotionListV10OrderType.DESC,
                QianchuanUniPromotionListV10OrderField.STAT_COST,
                minStatCostRaw).plans();
    }

    /** 构造时的回溯窗口：结束 = 现在，开始 = 现在 - {@code lookbackDays}（夹到接口上限 180 天）。 */
    private String defaultEndTime() {
        return LocalDateTime.now().format(DATE_TIME_FMT);
    }

    private String defaultStartTime() {
        return LocalDateTime.now()
                .minusDays(Math.min(properties.getPlanQueryLookbackDays(), MAX_LOOKBACK_DAYS))
                .format(DATE_TIME_FMT);
    }

    /**
     * 拉取「某账户 × 多个场景 × 多页」的全部计划，<b>跨场景按 adId 去重</b>。
     *
     * <p>把这段循环抽出来，是因为「查达人+商品」和「查全部计划」只有
     * <b>过滤条件不同</b>，翻页 / 频控间隔 / 错误上抛 / 去重这几件事完全一样 ——
     * 复制一份迟早会漏改（例如只在一处加了间隔，另一处又触发 40100）。</p>
     */
    private FetchedPlans fetchAllPlans(Long advertiserId,
                                       List<QianchuanUniPromotionListV10AdlabScene> scenes,
                                       String startTime, String endTime,
                                       QianchuanUniPromotionListV10Filtering filtering,
                                       QianchuanUniPromotionListV10OrderType orderType,
                                       QianchuanUniPromotionListV10OrderField orderField,
                                       Long minStatCostRaw)
            throws ApiException {
        List<QianchuanUniPromotionListV10ResponseDataAdListInner> plans = new ArrayList<>();
        Set<String> seenAdIds = new LinkedHashSet<>();
        int pageCount = 0;

        for (QianchuanUniPromotionListV10AdlabScene scene : scenes) {
            int totalPages = 1;
            // ⚠️ 提前停止：降序排序下，一旦出现低于阈值的计划，本场景后续只会更低
            boolean reachedBelowThreshold = false;
            for (int page = 1; page <= totalPages && page <= properties.getMaxPages(); page++) {
                int currentPage = page;
                QianchuanUniPromotionListV10Response response = throttle.execute(
                        () -> fetcher.fetch(advertiserId, startTime, endTime,
                                QianchuanUniPromotionListV10MarketingGoal.VIDEO_PROM_GOODS,
                                QUERY_FIELDS, filtering, orderType, orderField, currentPage,
                                QianchuanUniPromotionListV10PageSize.NUMBER_100, scene),
                        QianchuanUniPromotionListV10Response::getCode,
                        "千川计划列表(账户 " + advertiserId + "，场景 " + scene
                                + "，第 " + currentPage + " 页)");

                pageCount++;
                checkResponse(response, advertiserId, scene);

                List<QianchuanUniPromotionListV10ResponseDataAdListInner> adList =
                        response.getData().getAdList();
                if (adList != null) {
                    for (QianchuanUniPromotionListV10ResponseDataAdListInner plan : adList) {
                        if (markSeen(seenAdIds, plan)) {
                            plans.add(plan);
                        }
                        if (isBelowThreshold(plan, minStatCostRaw)) {
                            reachedBelowThreshold = true;
                        }
                    }
                }

                if (reachedBelowThreshold) {
                    log.info("账户 {} 场景 {}：第 {} 页已出现低于阈值的计划，按降序推断后续只会更低，停止翻页",
                            advertiserId, scene, currentPage);
                    break;
                }

                if (response.getData().getPageInfo() != null
                        && response.getData().getPageInfo().getTotalPage() != null) {
                    totalPages = response.getData().getPageInfo().getTotalPage().intValue();
                } else if (adList == null || adList.size() < PAGE_SIZE) {
                    break;
                }
            }
        }
        return new FetchedPlans(plans, pageCount);
    }

    /** 一次拉取的结果：去重后的计划 + 实际翻页次数（{@code rawPlanCount} 就是 {@code plans.size()}）。 */
    private record FetchedPlans(
            List<QianchuanUniPromotionListV10ResponseDataAdListInner> plans, int pageCount) {
    }

    // ==================== queryAndHandle ====================

    /** 查询并处理 0、1、多个三种结果；多个计划会完整打印后抛出异常暂停当前组合。 */
    public QianchuanPlanQueryResult queryAndHandle(
            Long advertiserId, String awemeId, String productId,
            List<QianchuanUniPromotionListV10AdlabScene> scenes)
            throws ApiException, AmbiguousPlanException {
        QianchuanPlanQueryResult result = queryByAwemeAndProduct(advertiserId, awemeId, productId, scenes);
        switch (result.getMatchType()) {
            case NONE:
                System.out.println("[千川] 达人 " + awemeId + " + 商品 " + productId + "：0 个计划"
                        + "（接口返回 " + result.getRawPlanCount() + " 个，均未匹配商品）");
                break;
            case SINGLE:
                System.out.println("[千川] 达人 " + awemeId + " + 商品 " + productId + "：1 个计划");
                printPlan(1, result.getSingle());
                break;
            case MULTIPLE:
                System.out.println("[千川] 达人 " + awemeId + " + 商品 " + productId
                        + "：多个计划（" + result.size() + " 个），完整结果如下");
                printAll(result);
                throw new AmbiguousPlanException(result);
            default:
                throw new IllegalStateException("未知匹配类型");
        }
        return result;
    }

    // ==================== 私有方法 ====================

    /**
     * 构造 filtering；awemeId 为 null/blank 时不设 search_keyword（返回全量）。
     */
    private static QianchuanUniPromotionListV10Filtering buildFiltering(String awemeId) {
        QianchuanUniPromotionListV10Filtering filtering = new QianchuanUniPromotionListV10Filtering();
        if (awemeId != null && !awemeId.isBlank()) {
            filtering.setSearchKeyword(awemeId);
            filtering.setSearchKeywordType(
                    QianchuanUniPromotionListV10FilteringSearchKeywordType.AWEME);
        }
        return filtering;
    }

    /**
     * ⚠️ 把 SDK 的「业务错误」显式抛出来。
     *
     * <p>SDK 在接口返回 {@code code != 0}（参数错、频控、权限不足……）时<b>不抛异常</b>，
     * 只返回 {@code data == null}。旧代码把 {@code data == null} 当成「没有下一页」，
     * 于是「接口报错」被静默翻译成「该组合没有计划」—— 这正是假阴性的根源。
     * 这里主动抛出，让上层记进 warnings，绝不静默。</p>
     */
    private static void checkResponse(QianchuanUniPromotionListV10Response response,
                                      Long advertiserId,
                                      QianchuanUniPromotionListV10AdlabScene scene)
            throws ApiException {
        if (response == null) {
            throw new ApiException("千川返回 null（账户 " + advertiserId + "，场景 " + scene + "）");
        }
        Long code = response.getCode();
        if (code != null && code != 0L) {
            throw new ApiException(code.intValue(),
                    "千川接口业务错误（账户 " + advertiserId + "，场景 " + scene
                            + "）：code=" + code + " message=" + response.getMessage());
        }
        if (response.getData() == null) {
            throw new ApiException("千川返回 code=0 但 data 为空（账户 " + advertiserId
                    + "，场景 " + scene + "）");
        }
    }

    /** 记录 adId，返回 true 表示首次见到（需要处理）。 */
    private static boolean markSeen(Set<String> seenAdIds,
                                    QianchuanUniPromotionListV10ResponseDataAdListInner plan) {
        if (plan == null || plan.getAdInfo() == null || plan.getAdInfo().getId() == null) {
            return true;
        }
        return seenAdIds.add(String.valueOf(plan.getAdInfo().getId()));
    }

    /**
     * 该计划的整体消耗是否已<b>低于</b>阈值（用于「降序下提前停止翻页」）。
     *
     * <p>⚠️ 拿不到 {@code stat_cost} 时返回 {@code false}（= 不算低于）——
     * 宁可多翻一页，也不能因为「字段缺失」就提前停掉、漏掉后面的计划。</p>
     */
    private static boolean isBelowThreshold(
            QianchuanUniPromotionListV10ResponseDataAdListInner plan, Long minStatCostRaw) {
        if (minStatCostRaw == null || plan == null || plan.getStatsInfo() == null) {
            return false;
        }
        Double statCost = plan.getStatsInfo().getStatCost();
        return statCost != null && statCost < minStatCostRaw;
    }

    private static boolean containsProduct(
            QianchuanUniPromotionListV10ResponseDataAdListInner plan, String productId) {
        if (plan == null || plan.getProductInfo() == null) {
            return false;
        }
        return plan.getProductInfo().stream()
            .anyMatch(product -> product != null && product.getProductId() != null
                && productId.equals(String.valueOf(product.getProductId())));
    }

    private static void printAll(QianchuanPlanQueryResult result) {
        System.out.println("[千川] 共 " + result.size() + " 个计划，翻页 "
                + result.getPageCount() + " 页");
        int index = 1;
        for (QianchuanUniPromotionListV10ResponseDataAdListInner plan : result.getPlans()) {
            printPlan(index++, plan);
        }
    }

    private static void printPlan(
            int index, QianchuanUniPromotionListV10ResponseDataAdListInner plan) {
        var info = plan == null ? null : plan.getAdInfo();
        if (info == null) {
            System.out.println("  " + index + ". 计划详情为空");
            return;
        }
        System.out.println("  " + index + ". adId=" + info.getId()
                + " | name=" + info.getName()
                + " | status=" + info.getStatus()
                + " | goal=" + info.getMarketingGoal()
                + " | " + info.getStartTime() + " ~ " + info.getEndTime());
    }

    // ==================== 校验 ====================

    /** 校验「查达人+商品」入参：广告主、商品 ID、场景列表都必填。 */
    private static void validate(Long advertiserId, String productId,
                                 List<QianchuanUniPromotionListV10AdlabScene> scenes) {
        if (productId == null || productId.isBlank()) {
            throw new IllegalArgumentException("productId 不能为空");
        }
        validate(advertiserId, scenes);
    }

    /** 校验「查全部计划」入参：广告主、场景列表必填（不涉及商品）。 */
    private static void validate(Long advertiserId,
                                 List<QianchuanUniPromotionListV10AdlabScene> scenes) {
        if (advertiserId == null) {
            throw new IllegalArgumentException("advertiserId 不能为空");
        }
        if (scenes == null || scenes.isEmpty()) {
            throw new IllegalArgumentException("scenes 不能为空，至少指定一个场景");
        }
    }
}
