package com.aolandai.dingtou.service.qianchuan;

import com.aolandai.dingtou.dto.qianchuan.MaterialRef;
import com.aolandai.dingtou.dto.qianchuan.PlanWriteOutcome;
import com.bytedance.ads.ApiException;
import com.bytedance.ads.model.QianchuanUniPromotionAdMaterialAddV10Request;
import com.bytedance.ads.model.QianchuanUniPromotionAdMaterialAddV10Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PlanMaterialAddService} 单测：注入假 fetcher，<b>绝不真调接口</b>。
 *
 * <p>重点覆盖<b>标题互斥规则</b> —— 它错了不会报错，只会「素材不生效」，最难发现。</p>
 */
class PlanMaterialAddServiceTest {

    private static final long ACC = 1823379540880396L;
    private static final long AD_ID = 1877746196794116L;
    private static final long PRODUCT = 3593275488396811650L;
    private static final long AWEME_UID = 2071074583877891L;
    private static final long VIDEO_ID = 7687379351705059825L;

    private final List<QianchuanUniPromotionAdMaterialAddV10Request> sent = new ArrayList<>();
    private PlanMaterialAddService service;

    @BeforeEach
    void setUp() {
        service = new PlanMaterialAddService(() -> request -> {
            sent.add(request);
            QianchuanUniPromotionAdMaterialAddV10Response resp =
                    new QianchuanUniPromotionAdMaterialAddV10Response();
            resp.setCode(0L);
            resp.setRequestId("req-1");
            return resp;
        });
    }

    private static MaterialRef homeVideo(long awemeItemId) {
        return new MaterialRef(null, awemeItemId, MaterialRef.IMAGE_MODE_VERTICAL);
    }

    @Test
    @DisplayName("★ dry-run：不调接口，但请求体照常组装（含 aweme_item_id，且【不带标题】）")
    void dryRunDoesNotCallApi() throws Exception {
        PlanWriteOutcome outcome = service.add(ACC, AD_ID, PRODUCT, AWEME_UID,
                List.of(homeVideo(VIDEO_ID)), true);

        assertTrue(outcome.dryRun());
        assertTrue(sent.isEmpty(), "dry-run 绝不能真调接口");
        assertEquals(1, outcome.appliedCount());
        assertTrue(outcome.requestBody().contains(String.valueOf(VIDEO_ID)), outcome.requestBody());
        assertTrue(outcome.requestBody().contains("\"ad_id\""), outcome.requestBody());
        // ⚠️ 素材全是抖音主页视频 → 不能带 title_material
        assertFalse(outcome.requestBody().contains("title_material"), outcome.requestBody());
    }

    @Test
    @DisplayName("真调：请求体字段正确（advertiser_id / ad_id / product_id / aweme_uid / 素材）")
    void realAddBuildsCorrectRequest() throws Exception {
        PlanWriteOutcome outcome = service.add(ACC, AD_ID, PRODUCT, AWEME_UID,
                List.of(homeVideo(VIDEO_ID), homeVideo(VIDEO_ID + 1)), false);

        assertFalse(outcome.dryRun());
        assertEquals("req-1", outcome.responseRequestId());
        assertEquals(1, sent.size());

        QianchuanUniPromotionAdMaterialAddV10Request req = sent.get(0);
        assertEquals(ACC, req.getAdvertiserId());
        assertEquals(AD_ID, req.getAdId());
        assertEquals(1, req.getMultiProductCreativeList().size());
        var creative = req.getMultiProductCreativeList().get(0);
        assertEquals(PRODUCT, creative.getProductId());
        assertEquals(AWEME_UID, creative.getAwemeUid());
        assertEquals(2, creative.getVideoMaterial().size());
        assertEquals(VIDEO_ID, creative.getVideoMaterial().get(0).getAwemeItemId());
        assertEquals("VIDEO_VERTICAL", String.valueOf(creative.getVideoMaterial().get(0).getImageMode()));
        // 素材全是主页视频 → 不能带标题
        assertTrue(creative.getTitleMaterial() == null || creative.getTitleMaterial().isEmpty());
    }

    @Test
    @DisplayName("★ 没有 aweme_item_id 的素材被跳过 + warning（用 video_id 会强制要求标题，我们没有标题数据源）")
    void materialWithoutAwemeItemIdIsSkippedWithWarning() throws Exception {
        PlanWriteOutcome outcome = service.add(ACC, AD_ID, PRODUCT, AWEME_UID,
                List.of(homeVideo(VIDEO_ID),
                        new MaterialRef("v0200fg10000abc", null, MaterialRef.IMAGE_MODE_VERTICAL)),
                false);

        assertEquals(1, outcome.appliedCount());
        assertEquals(1, outcome.warnings().size());
        assertTrue(outcome.warnings().get(0).contains("aweme_item_id"), outcome.warnings().toString());
    }

    @Test
    @DisplayName("★ 一条可用素材都没有 → 抛（绝不发「空素材」请求，那要么被拒要么假成功）")
    void noUsableMaterialIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.add(ACC, AD_ID, PRODUCT, AWEME_UID,
                        List.of(new MaterialRef("v0200fg10000abc", null, null)), false));
        assertTrue(sent.isEmpty());
    }

    @Test
    @DisplayName("SDK 业务错误 → 抛 ApiException（静默会把「素材已加」写进素材表，后续不再补）")
    void businessErrorIsThrown() {
        PlanMaterialAddService failing = new PlanMaterialAddService(() -> request -> {
            QianchuanUniPromotionAdMaterialAddV10Response resp =
                    new QianchuanUniPromotionAdMaterialAddV10Response();
            resp.setCode(40000L);
            resp.setMessage("参数不合法");
            return resp;
        });
        ApiException e = assertThrows(ApiException.class,
                () -> failing.add(ACC, AD_ID, PRODUCT, AWEME_UID, List.of(homeVideo(VIDEO_ID)), false));
        assertTrue(e.getMessage().contains("40000"), e.getMessage());
    }

    @Test
    @DisplayName("40100 频控 → 抛 RateLimitException")
    void rateLimitIsThrown() {
        PlanMaterialAddService failing = new PlanMaterialAddService(() -> request -> {
            QianchuanUniPromotionAdMaterialAddV10Response resp =
                    new QianchuanUniPromotionAdMaterialAddV10Response();
            resp.setCode(40100L);
            return resp;
        });
        assertThrows(com.aolandai.dingtou.exception.RateLimitException.class,
                () -> failing.add(ACC, AD_ID, PRODUCT, AWEME_UID, List.of(homeVideo(VIDEO_ID)), false));
    }
}
