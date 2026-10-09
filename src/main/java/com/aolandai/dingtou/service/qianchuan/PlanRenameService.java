package com.aolandai.dingtou.service.qianchuan;

import com.aolandai.dingtou.client.ApiErrors;
import com.aolandai.dingtou.client.QianchuanClientFactory;
import com.aolandai.dingtou.dto.qianchuan.PlanWriteOutcome;
import com.aolandai.dingtou.exception.RateLimitException;
import com.bytedance.ads.ApiException;
import com.bytedance.ads.model.QianchuanUniPromotionAdNameUpdateV10Request;
import com.bytedance.ads.model.QianchuanUniPromotionAdNameUpdateV10Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 给千川计划<b>改名</b>（C1 用：把名字里的日期改成最新的 {@code M.D}）。
 *
 * <p>SDK 入口：{@code /qianchuan/uni_promotion/ad/name/update/}。</p>
 *
 * <h2>⚠️ 为什么不用 {@code QianchuanAdUpdateV10Api}（qianchuan/ad/update）</h2>
 * <p>实测（{@code javap} 读字节码）该接口的<b>必填字段是
 * {@code ad_id} + {@code advertiser_id} + {@code delivery_setting}</b> ——
 * 也就是说「只想改个名字」也得塞一个投放设置进去，<b>有把计划预算 / ROI 覆盖掉的风险</b>。
 * 而且本项目的计划都在 {@code uni_promotion} 域（{@code ad/get} 对它们一律返回 0），
 * {@code ad/update} 很可能同样不适用。</p>
 *
 * <p>专用接口 {@code uni_promotion/ad/name/update} 的请求体<b>只有
 * {@code ad_id} + {@code advertiser_id} + {@code name}</b> 三个字段，
 * 不碰预算、不碰素材 —— 这才是「改个名字」该用的东西。</p>
 *
 * <p>⚠️ 同理<b>不要</b>用 {@code overall_video/update}：它必填
 * {@code multi_product_creative_list}，是<b>全量</b>素材更新，会抹掉原有素材。</p>
 */
@Component
public class PlanRenameService {

    private static final Logger log = LoggerFactory.getLogger(PlanRenameService.class);

    /** 底层的「改一次」函数抽象 —— 便于单测注入假实现，<b>绝不真调接口</b>。 */
    @FunctionalInterface
    public interface RenameFetcher {
        QianchuanUniPromotionAdNameUpdateV10Response rename(
                QianchuanUniPromotionAdNameUpdateV10Request request) throws ApiException;
    }

    /** 「取一次 fetcher」的抽象（会换 token，所以可能抛异常）。 */
    @FunctionalInterface
    interface FetcherProvider {
        RenameFetcher get() throws Exception;
    }

    private final FetcherProvider fetcherProvider;

    /** ⚠️ 有两个构造器时必须显式标注，否则 Spring 不知道用哪个。 */
    @Autowired
    public PlanRenameService(QianchuanClientFactory clientFactory) {
        this(clientFactory::planRenameFetcher);
    }

    /** 测试用：直接注入 fetcher 来源。 */
    PlanRenameService(FetcherProvider fetcherProvider) {
        this.fetcherProvider = fetcherProvider;
    }

    /**
     * 改名。
     *
     * @param advertiserId 投放账户 ID
     * @param adId         目标计划 ID
     * @param name         新名字（形如 {@code 抖音号-商品-M.D}）
     * @param dryRun       {@code true} = 只组装请求体、不真调
     * @throws ApiException       接口/网络异常
     * @throws RateLimitException 被频控（调用方<b>不要重试</b>）
     */
    public PlanWriteOutcome rename(long advertiserId, long adId, String name, boolean dryRun)
            throws ApiException {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("新计划名不能为空");
        }

        QianchuanUniPromotionAdNameUpdateV10Request request =
                new QianchuanUniPromotionAdNameUpdateV10Request();
        request.setAdvertiserId(advertiserId);
        request.setAdId(adId);
        request.setName(name.trim());
        // ⚠️ 这份 body 就是「即将真实发出的那一份」，dry-run 与真调共用，不重复组装
        String requestBody = request.toJson();

        if (dryRun) {
            log.warn("【dry-run】不会真的改名。即将发出的请求体：{}", requestBody);
            return new PlanWriteOutcome(true, 0, requestBody, null, List.of());
        }

        log.info("改名：账户={} 计划={} 新名={}", advertiserId, adId, name);

        RenameFetcher fetcher;
        try {
            fetcher = fetcherProvider.get();
        } catch (Exception e) {
            throw new ApiException("换取千川 Access-Token 失败，无法改名：" + e.getMessage());
        }

        QianchuanUniPromotionAdNameUpdateV10Response response = fetcher.rename(request);
        checkResponse(response, advertiserId, adId);
        return new PlanWriteOutcome(false, 0, requestBody, response.getRequestId(), List.of());
    }

    /**
     * ⚠️ 把 SDK 的「业务错误」显式抛出来。
     *
     * <p>改名静默失败 → 名字还是旧的（日期没更新）→ 人工按名字判断「计划是不是最新的」时会被误导。</p>
     */
    private static void checkResponse(QianchuanUniPromotionAdNameUpdateV10Response response,
                                      long advertiserId, long adId) throws ApiException {
        if (response == null) {
            throw new ApiException("改名返回 null（账户 " + advertiserId + " 计划 " + adId + "）");
        }
        Long code = response.getCode();
        if (code != null && code != 0L) {
            RateLimitException rateLimit = ApiErrors.rateLimitOrNull(
                    code, "改名(账户 " + advertiserId + " 计划 " + adId + ")", response.getRequestId());
            if (rateLimit != null) {
                throw rateLimit;
            }
            throw new ApiException(code.intValue(), "改名业务错误（账户 " + advertiserId
                    + " 计划 " + adId + "）：code=" + code + " message=" + response.getMessage());
        }
    }
}
