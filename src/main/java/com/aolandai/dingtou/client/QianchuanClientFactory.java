package com.aolandai.dingtou.client;

import com.bytedance.ads.ApiClient;
import com.bytedance.ads.ApiException;
import com.bytedance.ads.api.QianchuanAwemeUniPromotionOrderCreateV10Api;
import com.bytedance.ads.api.QianchuanAwemeVideoGetV10Api;
import com.bytedance.ads.model.QianchuanAwemeVideoGetV10MarketingGoal;
import com.bytedance.ads.api.QianchuanUniPromotionProductAwemeGetV10Api;
import com.bytedance.ads.model.QianchuanUniPromotionProductAwemeGetV10Filtering;
import com.bytedance.ads.model.QianchuanUniPromotionProductAwemeGetV10FilteringTab;
import com.bytedance.ads.model.QianchuanUniPromotionProductAwemeGetV10Platform;
import com.bytedance.ads.api.QianchuanAwemeAuthListGetV10Api;
import com.bytedance.ads.api.QianchuanUniAwemeAuthorizedGetV10Api;
import com.bytedance.ads.model.QianchuanAwemeAuthListGetV10Filtering;
import com.bytedance.ads.model.QianchuanAwemeAuthListGetV10PageSize;
import com.bytedance.ads.model.QianchuanUniAwemeAuthorizedGetV10Filtering;
import com.bytedance.ads.api.QianchuanUniPromotionAdDetailV10Api;
import com.bytedance.ads.api.QianchuanUniPromotionAdMaterialAddV10Api;
import com.bytedance.ads.api.QianchuanUniPromotionAdNameUpdateV10Api;
import com.bytedance.ads.api.QianchuanUniPromotionBlockMaterialGetV10Api;
import com.bytedance.ads.model.QianchuanUniPromotionBlockMaterialGetV10MarketingGoal;
import com.bytedance.ads.model.QianchuanUniPromotionBlockMaterialGetV10MediaType;
import com.bytedance.ads.model.QianchuanUniPromotionBlockMaterialGetV10OrderField;
import com.aolandai.dingtou.config.QianchuanProperties;
import com.aolandai.dingtou.service.qianchuan.AwemeShowIdResolver;
import com.aolandai.dingtou.service.qianchuan.AwemeUidResolver;
import com.aolandai.dingtou.service.qianchuan.MaterialPoolService;
import com.aolandai.dingtou.service.qianchuan.PlanAwemeService;
import com.aolandai.dingtou.service.qianchuan.PlanMaterialAddService;
import com.aolandai.dingtou.service.qianchuan.PlanMaterialResolver;
import com.aolandai.dingtou.service.qianchuan.PlanRenameService;
import com.aolandai.dingtou.service.qianchuan.SuixintuiOrderCreateService;
import com.aolandai.dingtou.service.qianchuan.QianchuanOverallVideoCreateService;
import com.aolandai.dingtou.service.qianchuan.QianchuanPlanService;
import com.aolandai.dingtou.service.qianchuan.SuixintuiOrderDetailService;
import com.aolandai.dingtou.service.qianchuan.SuixintuiOrderService;
import com.aolandai.dingtou.service.qianchuan.SuixintuiUniOrderByAwemeService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 千川 / 随心推 客户端工厂：<b>每次按需构造服务对象，token 由 provider 缓存</b>。
 *
 * <p>为什么不把这些服务直接做成 Spring 单例 Bean：它们的构造需要 Access-Token，
 * 而 token 要等真正调用时才换取。做成单例就意味着「启动时换 token」，
 * 对方服务一抖服务就起不来。所以改成「用的时候拿 token 现造」——</p>
 *
 * <p>这些服务的构造器里<b>没有任何 IO</b>（只是 new 一个 SDK Api 对象），
 * 所以每次现造的开销可以忽略。</p>
 */
@Component
public class QianchuanClientFactory {

    /**
     * SDK 业务错误显式抛出来。
     *
     * <p>⚠️ SDK 在 {@code code != 0} 时<b>不抛异常</b>，只返回 code + 空 data；
     * 静默会把「接口报错」翻译成「没有数据」—— 两者处理方式完全相反。</p>
     */
    private static void requireOk(Long code, String message, String requestId, String what)
            throws ApiException {
        if (code == null || code != 0L) {
            throw new ApiException(what + " 业务错误：code=" + code
                    + " message=" + message + " request_id=" + requestId);
        }
    }

    /** 把 SDK 应答转成 JSON 树（避免上层依赖 SDK 类型）。 */
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();


    private final AccessTokenProvider qianchuanToken;
    private final AccessTokenProvider suixintuiToken;
    private final QianchuanProperties properties;
    private final QianchuanThrottleFactory throttleFactory;

    public QianchuanClientFactory(
            @Qualifier("qianchuanAccessTokenProvider") AccessTokenProvider qianchuanToken,
            @Qualifier("suixintuiAccessTokenProvider") AccessTokenProvider suixintuiToken,
            QianchuanProperties properties,
            QianchuanThrottleFactory throttleFactory) {
        this.qianchuanToken = qianchuanToken;
        this.suixintuiToken = suixintuiToken;
        this.properties = properties;
        this.throttleFactory = throttleFactory;
    }

    // ==================== 千川侧 ====================

    /**
     * 计划查询服务（只读）。
     *
     * <p>⚠️ 每次调用都造一个<b>新的服务实例 + 新的限流器</b> —— 也就是说
     * 「限流器的生命周期 = 一次任务」。{@code 40100} 是接口全局总频控，
     * 自适应学到的间隔必须在整个任务内（跨账户、跨场景）保持，不能按账户重置。</p>
     */
    public QianchuanPlanService planService() throws Exception {
        return new QianchuanPlanService(qianchuanToken.token(), properties,
                throttleFactory.newThrottle());
    }

    /** 建乘方计划服务（<b>写操作，花钱</b>）。 */
    public QianchuanOverallVideoCreateService createService() throws Exception {
        return new QianchuanOverallVideoCreateService(qianchuanToken.token());
    }

    /**
     * 「查一次乘方计划详情」的 fetcher（只读）—— 用于取计划的<b>达人信息</b>。
     *
     * <p>返回函数式接口而不是 Api 对象，是为了让上层服务能被单测注入假实现。</p>
     */
    public PlanAwemeService.PlanDetailFetcher planDetailFetcher() throws Exception {
        QianchuanUniPromotionAdDetailV10Api api =
                new QianchuanUniPromotionAdDetailV10Api(apiClient(qianchuanToken.token()));
        return (advertiserId, adId) ->
                api.openApiV10QianchuanUniPromotionAdDetailGet(advertiserId, adId);
    }

    /**
     * 「查一页素材池」的 fetcher（只读）。
     *
     * <p>接口【获取投放计划可排除抖音视频/图文列表】：按【账户 + 抖音号 uid + 商品id】
     * 列出该达人能投这个商品的视频。分页是 {@code cursor} + {@code has_more}。</p>
     *
     * <p>{@code marketing_goal} 固定 {@code VIDEO_PROM_GOODS}、{@code media_type} 固定
     * {@code VIDEO}、排序固定 {@code CREATE_TIME} —— 本服务只做短视频带货，这些不给调用方选，
     * 免得「传错一个枚举就查不到素材」。</p>
     */
    public MaterialPoolService.MaterialPageFetcher materialPoolFetcher() throws Exception {
        QianchuanUniPromotionBlockMaterialGetV10Api api =
                new QianchuanUniPromotionBlockMaterialGetV10Api(apiClient(qianchuanToken.token()));
        return (advertiserId, awemeUid, productIds, cursor) ->
                api.openApiV10QianchuanUniPromotionBlockMaterialGetGet(
                        advertiserId,
                        awemeUid,
                        QianchuanUniPromotionBlockMaterialGetV10MarketingGoal.VIDEO_PROM_GOODS,
                        QianchuanUniPromotionBlockMaterialGetV10MediaType.VIDEO,
                        productIds,
                        null,   // filtering：按 aweme + product 已足够定位，不额外过滤
                        QianchuanUniPromotionBlockMaterialGetV10OrderField.CREATE_TIME,
                        cursor);
    }

    /**
     * 「往计划加一次素材」的 fetcher（<b>写操作</b>，增量）。
     *
     * <p>⚠️ 加素材<b>不花钱</b>，但它改的是线上计划 —— 所以同样受 {@code dry-run} 保护。</p>
     */
    public PlanMaterialAddService.MaterialAddFetcher planMaterialAddFetcher() throws Exception {
        QianchuanUniPromotionAdMaterialAddV10Api api =
                new QianchuanUniPromotionAdMaterialAddV10Api(apiClient(qianchuanToken.token()));
        return api::openApiV10QianchuanUniPromotionAdMaterialAddPost;
    }

    /**
     * 「改一次计划名」的 fetcher（<b>写操作</b>）。
     *
     * <p>⚠️ 用<b>专用改名接口</b> {@code uni_promotion/ad/name/update}（请求体只有
     * {@code ad_id} / {@code advertiser_id} / {@code name}），
     * <b>不要</b>用 {@code qianchuan/ad/update}（必填 {@code delivery_setting}，
     * 有覆盖预算/ROI 的风险）。</p>
     */
    public PlanRenameService.RenameFetcher planRenameFetcher() throws Exception {
        QianchuanUniPromotionAdNameUpdateV10Api api =
                new QianchuanUniPromotionAdNameUpdateV10Api(apiClient(qianchuanToken.token()));
        return api::openApiV10QianchuanUniPromotionAdNameUpdatePost;
    }

    // ==================== 随心推侧 ====================

    /** 按抖音号查随心推订单。 */
    /**
     * 「拉随心推全域订单」的服务（只读）。
     *
     * <p>用途：刷新订单快照（首次全量 + 之后增量）。
     * ⚠️ 实测最大账户全量 = 689 页 / 34,386 条 / <b>11 分 38 秒</b>。</p>
     */
    public SuixintuiOrderService suixintuiOrderService() throws Exception {
        String token = suixintuiToken.token();
        return new SuixintuiOrderService(apiClient(token), token,
                properties.getRequestIntervalMillis());
    }

    public SuixintuiUniOrderByAwemeService suixintuiOrderByAwemeService() throws Exception {
        String token = suixintuiToken.token();
        // 与千川侧共用同一个「请求间隔」开关（见 QianchuanProperties#requestIntervalMillis）
        return new SuixintuiUniOrderByAwemeService(
                apiClient(token), token, properties.getRequestIntervalMillis());
    }

    /**
     * 「建一次随心推订单」的 fetcher（<b>写操作，花钱</b>）。
     *
     * <p>⚠️ 用<b>随心推侧 token</b>（{@code state=shop_suixintui}）—— 与「查随心推订单」
     * 保持一致；随心推域接口拿千川 token 会鉴权失败。</p>
     */
    public SuixintuiOrderCreateService.OrderCreateFetcher suixintuiOrderCreateFetcher()
            throws Exception {
        ApiClient client = apiClient(suixintuiToken.token());
        QianchuanAwemeUniPromotionOrderCreateV10Api api =
                new QianchuanAwemeUniPromotionOrderCreateV10Api(client);
        return api::openApiV10QianchuanAwemeUniPromotionOrderCreatePost;
    }

    /**
     * 「拉一页账户下的授权达人」的 fetcher（只读）。
     *
     * <p>用于把【抖音号】换算成建随心推需要的<b>数值 awemeId</b>。</p>
     */
    public AwemeUidResolver.AuthorizedAwemeFetcher authorizedAwemeFetcher() throws Exception {
        QianchuanUniAwemeAuthorizedGetV10Api api =
                new QianchuanUniAwemeAuthorizedGetV10Api(apiClient(qianchuanToken.token()));
        return (advertiserId, searchKeyword, page, pageSize) -> {
            QianchuanUniAwemeAuthorizedGetV10Filtering filtering = null;
            if (searchKeyword != null && !searchKeyword.isBlank()) {
                filtering = new QianchuanUniAwemeAuthorizedGetV10Filtering();
                filtering.setSearchKeyWords(searchKeyword);
            }
            return api.openApiV10QianchuanUniAwemeAuthorizedGetGet(
                    advertiserId, filtering, page, pageSize);
        };
    }

    /**
     * 「按数值 UID 列表查授权关系」的 fetcher（只读）。
     *
     * <p>用于 <b>UID → 抖音号</b> 的反查（乘方计划详情接口的 {@code unique_id} 有时为空）。</p>
     *
     * <p>⚠️ <b>{@code aweme_id} 必须传数组</b> —— 传标量会报
     * {@code code=40000 filtering: aweme_id: Field must be set to array}（实测踩过）。</p>
     */
    public AwemeShowIdResolver.AwemeAuthListFetcher awemeAuthListFetcher() throws Exception {
        QianchuanAwemeAuthListGetV10Api api =
                new QianchuanAwemeAuthListGetV10Api(apiClient(qianchuanToken.token()));
        return (advertiserId, awemeIds) -> {
            QianchuanAwemeAuthListGetV10Filtering filtering =
                    new QianchuanAwemeAuthListGetV10Filtering();
            filtering.setAwemeId(awemeIds);
            return api.openApiV10QianchuanAwemeAuthListGetGet(
                    advertiserId, filtering, 1L, QianchuanAwemeAuthListGetV10PageSize.NUMBER_100);
        };
    }

    /**
     * 「查某账户 + 某抖音号下的视频列表」的 fetcher（含商品信息）。
     *
     * <p>用于 <b>视频ID → 商品ID</b>。⚠️ 接口的 {@code count} <b>最多 20</b>
     * （写 50 会报 {@code 40000}）。</p>
     */
    public PlanMaterialResolver.VideoListFetcher videoListFetcher() throws Exception {
        // ⚠️ 用【随心推】token —— 老板给的示例代码里这两个接口都用 sxToken
        //    （千川 token 调会报 code=40002「advertiser does no grant you ... permission」，实测）
        QianchuanAwemeVideoGetV10Api api =
                new QianchuanAwemeVideoGetV10Api(apiClient(suixintuiToken.token()));
        return (advertiserId, awemeUid, cursor, count) -> {
            var response = api.openApiV10QianchuanAwemeVideoGetGet(advertiserId, awemeUid,
                    QianchuanAwemeVideoGetV10MarketingGoal.VIDEO_PROM_GOODS, cursor, count);
            // ⚠️ SDK 业务错误【不抛异常】（code!=0 + data==null）→ 必须主动抛，
            //    否则「接口报错」会被静默翻译成「没有视频」
            requireOk(response == null ? null : response.getCode(),
                    response == null ? "响应为空" : response.getMessage(),
                    response == null ? "" : response.getRequestId(),
                    "查视频列表(账户 " + advertiserId + ")");
            // 直接返回 JSON 树，避免上层再依赖 SDK 类型
            try {
                return MAPPER.readTree(response.getData() == null
                        ? "{}" : response.getData().toJson());
            } catch (Exception e) {
                throw new ApiException("解析视频列表响应失败：" + e.getMessage());
            }
        };
    }

    /**
     * 「按品名反查商品」的 fetcher（兜底用）。
     *
     * <p>视频列表没匹配到视频ID时，用商品名搜候选，返回给人工确认。</p>
     */
    public PlanMaterialResolver.ProductSearchFetcher productSearchFetcher() throws Exception {
        // ⚠️ 同 videoListFetcher：用【随心推】token
        QianchuanUniPromotionProductAwemeGetV10Api api =
                new QianchuanUniPromotionProductAwemeGetV10Api(apiClient(suixintuiToken.token()));
        return (advertiserId, awemeUid, productName) -> {
            var filtering = new QianchuanUniPromotionProductAwemeGetV10Filtering()
                    .tab(QianchuanUniPromotionProductAwemeGetV10FilteringTab.ALL);
            if (productName != null && !productName.isBlank()) {
                filtering.productName(productName);
            }
            var response = api.openApiV10QianchuanUniPromotionProductAwemeGetGet(
                    advertiserId, awemeUid, filtering, 1L,
                    QianchuanUniPromotionProductAwemeGetV10Platform.QIANCHUAN);
            requireOk(response == null ? null : response.getCode(),
                    response == null ? "响应为空" : response.getMessage(),
                    response == null ? "" : response.getRequestId(),
                    "品名反查商品(账户 " + advertiserId + ")");
            try {
                return MAPPER.readTree(response.getData() == null
                        ? "{}" : response.getData().toJson());
            } catch (Exception e) {
                throw new ApiException("解析商品列表响应失败：" + e.getMessage());
            }
        };
    }

    /** 随心推订单详情。 */
    public SuixintuiOrderDetailService suixintuiOrderDetailService() throws Exception {
        String token = suixintuiToken.token();
        return new SuixintuiOrderDetailService(apiClient(token), token);
    }

    // ==================== 探活 / 排障 ====================

    /** 千川 token 剩余有效秒数（0 = 未换取或已过期）。 */
    public long qianchuanTokenRemainingSeconds() {
        return qianchuanToken.remainingSeconds();
    }

    /** 随心推 token 剩余有效秒数。 */
    public long suixintuiTokenRemainingSeconds() {
        return suixintuiToken.remainingSeconds();
    }

    /** 主动作废两侧 token（鉴权失败时用）。 */
    public void invalidateTokens() {
        qianchuanToken.invalidate();
        suixintuiToken.invalidate();
    }

    // ==================== 内部 ====================

    /**
     * 造一个带 Access-Token 的 SDK 客户端。
     *
     * <p>⚠️ <b>必须显式设超时</b>：SDK 自带的默认读超时很短，实测调「授权达人列表」
     * 会直接 {@code SocketTimeoutException: timeout}（两次都复现）。
     * 用配置里的 {@code connect-timeout-seconds} / {@code read-timeout-seconds}
     * （{@code setXxxTimeout} 的单位是<b>毫秒</b>）。</p>
     */
    private ApiClient apiClient(String accessToken) {
        ApiClient client = new ApiClient();
        client.setBasePath(properties.getApiBaseUrl());
        client.setConnectTimeout((int) (properties.getConnectTimeoutSeconds() * 1000L));
        client.setReadTimeout((int) (properties.getReadTimeoutSeconds() * 1000L));
        client.addDefaultHeader("Access-Token", accessToken);
        return client;
    }
}
