package com.aolandai.dingtou.service.qianchuan;

import com.aolandai.dingtou.client.ApiErrors;
import com.aolandai.dingtou.client.QianchuanClientFactory;
import com.aolandai.dingtou.config.PlanProperties;
import com.aolandai.dingtou.dto.qianchuan.MaterialRef;
import com.aolandai.dingtou.exception.RateLimitException;
import com.bytedance.ads.ApiException;
import com.bytedance.ads.model.QianchuanUniPromotionBlockMaterialGetV10Response;
import com.bytedance.ads.model.QianchuanUniPromotionBlockMaterialGetV10ResponseData;
import com.bytedance.ads.model.QianchuanUniPromotionBlockMaterialGetV10ResponseDataVideoListInner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 查<b>素材池</b>：按【投放账户 + 抖音号 uid + 商品id】列出该达人<b>能投这个商品</b>的全部视频。
 *
 * <p>SDK 入口：{@code /qianchuan/uni_promotion/block_material/get/}
 * （官方名【获取投放计划可排除抖音视频/图文列表】）。</p>
 *
 * <p>用途：B / C2 建乘方计划时要「加<b>全部</b>素材」——「全部」就是这里查出来的池子，
 * <b>不是</b>素材表里已登记的那几行。两者可能不一样（素材表只登记了商务挑过的）。</p>
 *
 * <h2>⚠️ 入参是「数值 uid」不是「抖音号」</h2>
 * <p>{@code aweme_id} 要的是授权抖音号接口返回的<b>数字 uid</b>，
 * 不是后台显示的那个「抖音号」字符串（如 {@code 610129yiyi}）。
 * 换算走 {@link AwemeUidResolver}。</p>
 *
 * <h2>⚠️ 分页是 cursor + has_more，不是 page/page_size</h2>
 * <p>和本项目其它列表接口不一样，别照抄翻页逻辑。</p>
 */
@Component
public class MaterialPoolService {

    private static final Logger log = LoggerFactory.getLogger(MaterialPoolService.class);

    /**
     * 底层的「查一页」函数抽象 —— 便于单测注入假实现，<b>绝不真调接口</b>。
     *
     * <p>与 {@code SuixintuiOrderCreateService.OrderCreateFetcher} 同样的设计：
     * 把 SDK 调用收敛到一个函数式接口，测试就能完全不碰网络。</p>
     */
    @FunctionalInterface
    public interface MaterialPageFetcher {
        QianchuanUniPromotionBlockMaterialGetV10Response fetch(
                long advertiserId, long awemeUid, List<Long> productIds, long cursor)
                throws ApiException;
    }

    /** 「取一次 fetcher」的抽象（会换 token，所以可能抛异常）。 */
    @FunctionalInterface
    interface FetcherProvider {
        MaterialPageFetcher get() throws Exception;
    }

    private final FetcherProvider fetcherProvider;
    private final PlanProperties properties;

    /** ⚠️ 有两个构造器时必须显式标注，否则 Spring 不知道用哪个（会报 No default constructor found）。 */
    @Autowired
    public MaterialPoolService(QianchuanClientFactory clientFactory, PlanProperties properties) {
        this(clientFactory::materialPoolFetcher, properties);
    }

    /** 测试用：直接注入 fetcher 来源。 */
    MaterialPoolService(FetcherProvider fetcherProvider, PlanProperties properties) {
        this.fetcherProvider = fetcherProvider;
        this.properties = properties;
    }

    /** 查单个商品的素材池。 */
    public List<MaterialRef> list(long advertiserId, long awemeUid, long productId)
            throws ApiException {
        return list(advertiserId, awemeUid, List.of(productId));
    }

    /**
     * 查素材池（自动游标翻页）。
     *
     * @return 去重后的素材列表；查不到返回空 list（<b>不返回 null</b>）
     * @throws ApiException       接口/网络异常
     * @throws RateLimitException 被频控（调用方<b>不要重试</b>，重试只会加重惩罚）
     */
    public List<MaterialRef> list(long advertiserId, long awemeUid, List<Long> productIds)
            throws ApiException {
        if (productIds == null || productIds.isEmpty()) {
            throw new IllegalArgumentException("productIds 不能为空");
        }

        MaterialPageFetcher fetcher;
        try {
            fetcher = fetcherProvider.get();
        } catch (Exception e) {
            throw new ApiException("换取千川 Access-Token 失败，无法查素材池：" + e.getMessage());
        }

        List<MaterialRef> out = new ArrayList<>();
        // ⚠️ 同一素材可能跨页重复（服务端游标不稳定时会发生）→ 按 id 去重，
        //    否则加素材时会传重复项，平台报错且难排查
        Set<String> seen = new LinkedHashSet<>();
        long cursor = 0L;
        int maxRounds = properties.getMaterialPoolMaxRounds();
        boolean truncated = false;

        for (int round = 1; round <= maxRounds; round++) {
            QianchuanUniPromotionBlockMaterialGetV10Response response =
                    fetcher.fetch(advertiserId, awemeUid, productIds, cursor);
            checkResponse(response, advertiserId, awemeUid);

            QianchuanUniPromotionBlockMaterialGetV10ResponseData data = response.getData();
            List<QianchuanUniPromotionBlockMaterialGetV10ResponseDataVideoListInner> videos =
                    data == null ? null : data.getVideoList();
            if (videos == null || videos.isEmpty()) {
                break;
            }

            for (QianchuanUniPromotionBlockMaterialGetV10ResponseDataVideoListInner v : videos) {
                MaterialRef material = new MaterialRef(
                        v.getVideoId(), v.getAwemeItemId(), imageModeOf(v));
                if (!material.isUsable()) {
                    log.warn("素材池返回一条没有任何 id 的素材，已跳过（账户={} 达人UID={}）",
                            advertiserId, awemeUid);
                    continue;
                }
                if (seen.add(material.describe())) {
                    out.add(material);
                }
            }

            Long next = data.getPageInfo() == null ? null : data.getPageInfo().getCursor();
            Boolean hasMore = data.getPageInfo() == null ? null : data.getPageInfo().getHasMore();
            if (!Boolean.TRUE.equals(hasMore) || next == null) {
                break;
            }
            cursor = next;

            if (round == maxRounds) {
                // ⚠️ 打满上限还有下一页 = 可能被静默截断。这里必须吵，
                //    否则表现为「计划里莫名少了素材」，且没有任何报错（MAX_PAGES 踩过同样的坑）
                truncated = true;
                break;
            }
            sleepQuietly(properties.getMaterialPoolPageIntervalMillis());
        }

        if (truncated) {
            log.warn("⚠️ 素材池翻页打满上限 {} 轮，可能仍有下一页 → 结果可能不完整！"
                            + "请调大 dingtou.plan.material-pool-max-rounds（账户={} 达人UID={} 商品={}）",
                    maxRounds, advertiserId, awemeUid, productIds);
        }

        // ⚠️ 占位符数量必须与参数数量【一致】：多了会把 `{}` 原样打印出来（实测踩过），
        //    少了会把后面的参数顶掉。这里 5 个 `{}` 对 5 个参数。
        log.info("素材池查询完成：账户={} 达人UID={} 商品={} → {} 条素材（翻页 {}）",
                advertiserId, awemeUid, productIds, out.size(),
                truncated ? maxRounds + " 轮后已截断" : "至末页");
        return out;
    }

    /**
     * 素材类型：接口没直接给 {@code image_mode}，只能用宽高推。
     *
     * <p>拿不到宽高时按<b>竖版</b>处理 —— 信息流绝大多数是竖版，猜错的代价最小。</p>
     */
    private static String imageModeOf(
            QianchuanUniPromotionBlockMaterialGetV10ResponseDataVideoListInner v) {
        Integer width = v.getWidth();
        Integer height = v.getHeight();
        if (width == null || height == null || width <= 0 || height <= 0) {
            return MaterialRef.IMAGE_MODE_VERTICAL;
        }
        return width > height ? MaterialRef.IMAGE_MODE_LARGE : MaterialRef.IMAGE_MODE_VERTICAL;
    }

    /**
     * ⚠️ 把 SDK 的「业务错误」显式抛出来。
     *
     * <p>SDK 在 {@code code != 0} 时<b>不抛异常</b>，只返回 {@code data == null} ——
     * 那正是本项目历史上「假阴性」的根源。查素材池尤其不能静默：
     * 静默会变成「素材池是空的」→ 建出一个<b>没有素材的计划</b>。</p>
     */
    private static void checkResponse(QianchuanUniPromotionBlockMaterialGetV10Response response,
                                      long advertiserId, long awemeUid) throws ApiException {
        if (response == null) {
            throw new ApiException("查素材池返回 null（账户 " + advertiserId + "）");
        }
        Long code = response.getCode();
        if (code != null && code != 0L) {
            RateLimitException rateLimit = ApiErrors.rateLimitOrNull(
                    code, "查素材池(账户 " + advertiserId + " 达人UID " + awemeUid + ")",
                    response.getRequestId());
            if (rateLimit != null) {
                throw rateLimit;
            }
            throw new ApiException(code.intValue(), "查素材池业务错误（账户 " + advertiserId
                    + "）：code=" + code + " message=" + response.getMessage());
        }
    }

    private static void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("查素材池翻页被中断", e);
        }
    }
}
