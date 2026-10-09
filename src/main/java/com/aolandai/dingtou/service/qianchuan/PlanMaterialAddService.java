package com.aolandai.dingtou.service.qianchuan;

import com.aolandai.dingtou.client.ApiErrors;
import com.aolandai.dingtou.client.QianchuanClientFactory;
import com.aolandai.dingtou.dto.qianchuan.MaterialRef;
import com.aolandai.dingtou.dto.qianchuan.PlanWriteOutcome;
import com.aolandai.dingtou.exception.RateLimitException;
import com.bytedance.ads.ApiException;
import com.bytedance.ads.model.QianchuanUniPromotionAdMaterialAddV10MultiProductCreativeListVideoMaterialImageMode;
import com.bytedance.ads.model.QianchuanUniPromotionAdMaterialAddV10Request;
import com.bytedance.ads.model.QianchuanUniPromotionAdMaterialAddV10RequestMultiProductCreativeListInner;
import com.bytedance.ads.model.QianchuanUniPromotionAdMaterialAddV10RequestMultiProductCreativeListInnerVideoMaterialInner;
import com.bytedance.ads.model.QianchuanUniPromotionAdMaterialAddV10Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 往<b>已有千川计划</b>里<b>增量</b>加素材（C1 用）。
 *
 * <p>SDK 入口：{@code /qianchuan/uni_promotion/ad/material/add/}。</p>
 *
 * <h2>⚠️ 为什么不用「编辑计划」接口</h2>
 * <p>{@code overall_video/update}（编辑乘方商品投放计划）是<b>全量更新</b> ——
 * 它的必填字段里就有 {@code multi_product_creative_list}，只传新素材会把<b>原有素材全抹掉</b>。
 * 加素材必须走本接口（增量）。</p>
 *
 * <h2>⚠️ 标题（title_material）的互斥规则</h2>
 * <p>官方规则：</p>
 * <ul>
 *   <li>素材<b>全是</b>「抖音主页视频」（用 {@code aweme_item_id} 传）→ <b>不能</b>带标题</li>
 *   <li>存在非主页视频（{@code video_id}）/图片 → <b>必须</b>至少一条标题，否则素材不生效</li>
 * </ul>
 * <p>本项目只用 {@code aweme_item_id}（素材表 {@code H 视频id} 就是抖音视频ID），
 * 所以<b>不传标题</b>。没有 {@code awemeItemId} 的素材会被<b>跳过并记 warning</b> ——
 * 因为改用 {@code video_id} 就落进「必须带标题」那一边，而<b>我们没有标题数据源</b>。</p>
 */
@Component
public class PlanMaterialAddService {

    private static final Logger log = LoggerFactory.getLogger(PlanMaterialAddService.class);

    /** 底层的「加一次」函数抽象 —— 便于单测注入假实现，<b>绝不真调接口</b>。 */
    @FunctionalInterface
    public interface MaterialAddFetcher {
        QianchuanUniPromotionAdMaterialAddV10Response add(
                QianchuanUniPromotionAdMaterialAddV10Request request) throws ApiException;
    }

    /** 「取一次 fetcher」的抽象（会换 token，所以可能抛异常）。 */
    @FunctionalInterface
    interface FetcherProvider {
        MaterialAddFetcher get() throws Exception;
    }

    private final FetcherProvider fetcherProvider;

    /** ⚠️ 有两个构造器时必须显式标注，否则 Spring 不知道用哪个。 */
    @Autowired
    public PlanMaterialAddService(QianchuanClientFactory clientFactory) {
        this(clientFactory::planMaterialAddFetcher);
    }

    /** 测试用：直接注入 fetcher 来源。 */
    PlanMaterialAddService(FetcherProvider fetcherProvider) {
        this.fetcherProvider = fetcherProvider;
    }

    /**
     * 往计划里加素材（增量）。
     *
     * @param advertiserId 投放账户 ID
     * @param adId         目标计划 ID
     * @param productId    商品 ID
     * @param awemeUid     达人的数值 UID（{@code aweme_uid}）；{@code null} = 不传（无号商家）
     * @param materials    要加的素材（只有带 {@code awemeItemId} 的会被真正加上）
     * @param dryRun       {@code true} = 只组装请求体、不真调
     * @throws ApiException       接口/网络异常
     * @throws RateLimitException 被频控（调用方<b>不要重试</b>）
     */
    public PlanWriteOutcome add(long advertiserId, long adId, long productId, Long awemeUid,
                                List<MaterialRef> materials, boolean dryRun) throws ApiException {
        List<String> warnings = new ArrayList<>();
        List<MaterialRef> usable = filterUsable(materials, warnings);
        if (usable.isEmpty()) {
            // ⚠️ 不构造「空素材」的请求 —— 那种请求要么被接口拒，要么什么也没加却报成功
            throw new IllegalArgumentException(
                    "没有任何可用于加素材的抖音主页视频（需要 aweme_item_id）。"
                            + "素材池返回 " + (materials == null ? 0 : materials.size()) + " 条，"
                            + "可用 0 条" + (warnings.isEmpty() ? "" : "；" + String.join("；", warnings)));
        }

        QianchuanUniPromotionAdMaterialAddV10Request request =
                buildRequest(advertiserId, adId, productId, awemeUid, usable);
        // ⚠️ 这份 body 就是「即将真实发出的那一份」，dry-run 与真调共用，不重复组装
        String requestBody = request.toJson();

        if (dryRun) {
            log.warn("【dry-run】不会真的加素材。即将发出的请求体：{}", requestBody);
            return new PlanWriteOutcome(true, usable.size(), requestBody, null, warnings);
        }

        log.info("加素材：账户={} 计划={} 商品={} 素材={} 条", advertiserId, adId, productId, usable.size());

        MaterialAddFetcher fetcher;
        try {
            fetcher = fetcherProvider.get();
        } catch (Exception e) {
            throw new ApiException("换取千川 Access-Token 失败，无法加素材：" + e.getMessage());
        }

        QianchuanUniPromotionAdMaterialAddV10Response response = fetcher.add(request);
        checkResponse(response, advertiserId, adId);
        return new PlanWriteOutcome(false, usable.size(), requestBody,
                response.getRequestId(), warnings);
    }

    // ==================== 组装 ====================

    /**
     * 只留能用的素材：必须有 {@code awemeItemId}（抖音主页视频）。
     *
     * <p>⚠️ 不能用 {@code video_id} 兜底 —— 那会让平台判为「非主页视频」，
     * 进而<b>强制要求标题</b>，而我们没有标题数据源。宁可跳过 + 报警，也不要发出一个
     * 「素材不生效」的请求。</p>
     */
    private static List<MaterialRef> filterUsable(List<MaterialRef> materials, List<String> warnings) {
        List<MaterialRef> usable = new ArrayList<>();
        if (materials == null) {
            return usable;
        }
        for (MaterialRef m : materials) {
            if (m != null && m.awemeItemId() != null) {
                usable.add(m);
            } else if (m != null) {
                warnings.add("跳过一条没有抖音视频ID（aweme_item_id）的素材：" + m.describe()
                        + " —— 用 video_id 会强制要求创意标题，而本服务没有标题数据源");
            }
        }
        return usable;
    }

    private static QianchuanUniPromotionAdMaterialAddV10Request buildRequest(
            long advertiserId, long adId, long productId, Long awemeUid, List<MaterialRef> materials) {

        List<QianchuanUniPromotionAdMaterialAddV10RequestMultiProductCreativeListInnerVideoMaterialInner>
                videos = new ArrayList<>(materials.size());
        for (MaterialRef m : materials) {
            videos.add(new QianchuanUniPromotionAdMaterialAddV10RequestMultiProductCreativeListInnerVideoMaterialInner()
                    .awemeItemId(m.awemeItemId())
                    .imageMode(toImageMode(m.imageMode())));
        }

        QianchuanUniPromotionAdMaterialAddV10RequestMultiProductCreativeListInner creative =
                new QianchuanUniPromotionAdMaterialAddV10RequestMultiProductCreativeListInner();
        creative.setProductId(productId);
        creative.setAwemeUid(awemeUid);
        creative.setVideoMaterial(videos);
        // ⚠️ 刻意不设 titleMaterial：素材全是抖音主页视频时，带标题会被接口拒

        QianchuanUniPromotionAdMaterialAddV10Request request =
                new QianchuanUniPromotionAdMaterialAddV10Request();
        request.setAdvertiserId(advertiserId);
        request.setAdId(adId);
        request.setMultiProductCreativeList(List.of(creative));
        return request;
    }

    /**
     * 素材类型字符串 → SDK 强类型枚举。
     *
     * <p>⚠️ SDK 的 {@code fromValue} 对未知取值会抛 {@code Unexpected value}，
     * 这里包一层把字段名带上，报错时能直接看出是哪个字段填错。</p>
     */
    private static QianchuanUniPromotionAdMaterialAddV10MultiProductCreativeListVideoMaterialImageMode
            toImageMode(String imageMode) {
        try {
            return QianchuanUniPromotionAdMaterialAddV10MultiProductCreativeListVideoMaterialImageMode
                    .fromValue(imageMode);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "video_material.image_mode 取值非法：" + imageMode, e);
        }
    }

    /**
     * ⚠️ 把 SDK 的「业务错误」显式抛出来。
     *
     * <p>SDK 在 {@code code != 0} 时<b>不抛异常</b>。加素材静默失败会被上层当成
     * 「素材已加」→ 回写素材表 → <b>后续不会再补</b>，计划里就永久少了这批素材。</p>
     */
    private static void checkResponse(QianchuanUniPromotionAdMaterialAddV10Response response,
                                      long advertiserId, long adId) throws ApiException {
        if (response == null) {
            throw new ApiException("加素材返回 null（账户 " + advertiserId + " 计划 " + adId + "）");
        }
        Long code = response.getCode();
        if (code != null && code != 0L) {
            RateLimitException rateLimit = ApiErrors.rateLimitOrNull(
                    code, "加素材(账户 " + advertiserId + " 计划 " + adId + ")", response.getRequestId());
            if (rateLimit != null) {
                throw rateLimit;
            }
            throw new ApiException(code.intValue(), "加素材业务错误（账户 " + advertiserId
                    + " 计划 " + adId + "）：code=" + code + " message=" + response.getMessage());
        }
    }
}
