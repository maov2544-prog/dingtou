package com.aolandai.dingtou.service.qianchuan;

import com.aolandai.dingtou.dto.qianchuan.PlanWriteOutcome;
import com.bytedance.ads.ApiException;
import com.bytedance.ads.model.QianchuanUniPromotionAdNameUpdateV10Request;
import com.bytedance.ads.model.QianchuanUniPromotionAdNameUpdateV10Response;
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
 * {@link PlanRenameService} 单测：注入假 fetcher，<b>绝不真调接口</b>。
 *
 * <p>重点钉住「请求体<b>只有</b> ad_id / advertiser_id / name」——
 * 一旦有人换成 {@code ad/update}（必填 delivery_setting），就可能把计划的预算 / ROI 覆盖掉。</p>
 */
class PlanRenameServiceTest {

    private static final long ACC = 1823379540880396L;
    private static final long AD_ID = 1877746196794116L;
    private static final String NAME = "610129yiyi-护臀膏-10.5";

    private final List<QianchuanUniPromotionAdNameUpdateV10Request> sent = new ArrayList<>();
    private PlanRenameService service;

    @BeforeEach
    void setUp() {
        service = new PlanRenameService(() -> request -> {
            sent.add(request);
            QianchuanUniPromotionAdNameUpdateV10Response resp =
                    new QianchuanUniPromotionAdNameUpdateV10Response();
            resp.setCode(0L);
            resp.setRequestId("req-rename");
            return resp;
        });
    }

    @Test
    @DisplayName("★ dry-run：不调接口，请求体只含 ad_id / advertiser_id / name（不碰预算与素材）")
    void dryRunDoesNotCallApi() throws Exception {
        PlanWriteOutcome outcome = service.rename(ACC, AD_ID, NAME, true);

        assertTrue(outcome.dryRun());
        assertTrue(sent.isEmpty());
        assertTrue(outcome.requestBody().contains("\"name\""), outcome.requestBody());
        assertTrue(outcome.requestBody().contains(String.valueOf(AD_ID)), outcome.requestBody());
        // ⚠️ 这两个字段一旦出现，就说明用错了接口（ad/update 有覆盖预算/素材的风险）
        assertFalse(outcome.requestBody().contains("delivery_setting"), outcome.requestBody());
        assertFalse(outcome.requestBody().contains("multi_product_creative_list"),
                outcome.requestBody());
    }

    @Test
    @DisplayName("真调：字段正确，并回传平台 requestId")
    void realRenameBuildsCorrectRequest() throws Exception {
        PlanWriteOutcome outcome = service.rename(ACC, AD_ID, NAME, false);

        assertFalse(outcome.dryRun());
        assertEquals("req-rename", outcome.responseRequestId());
        assertEquals(1, sent.size());
        QianchuanUniPromotionAdNameUpdateV10Request req = sent.get(0);
        assertEquals(ACC, req.getAdvertiserId());
        assertEquals(AD_ID, req.getAdId());
        assertEquals(NAME, req.getName());
    }

    @Test
    @DisplayName("新名字首尾空格会被去掉（平台会拒首尾空格）")
    void nameIsTrimmed() throws Exception {
        service.rename(ACC, AD_ID, "  " + NAME + "  ", false);
        assertEquals(NAME, sent.get(0).getName());
    }

    @Test
    @DisplayName("名字为空 → 抛 IllegalArgumentException（本地就能发现）")
    void blankNameIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.rename(ACC, AD_ID, "   ", false));
        assertTrue(sent.isEmpty());
    }

    @Test
    @DisplayName("SDK 业务错误 → 抛 ApiException（静默会让「名字里的日期」永远停在旧值，误导人工判断）")
    void businessErrorIsThrown() {
        PlanRenameService failing = new PlanRenameService(() -> request -> {
            QianchuanUniPromotionAdNameUpdateV10Response resp =
                    new QianchuanUniPromotionAdNameUpdateV10Response();
            resp.setCode(40000L);
            resp.setMessage("参数不合法");
            return resp;
        });
        ApiException e = assertThrows(ApiException.class,
                () -> failing.rename(ACC, AD_ID, NAME, false));
        assertTrue(e.getMessage().contains("40000"), e.getMessage());
    }

    @Test
    @DisplayName("40100 频控 → 抛 RateLimitException")
    void rateLimitIsThrown() {
        PlanRenameService failing = new PlanRenameService(() -> request -> {
            QianchuanUniPromotionAdNameUpdateV10Response resp =
                    new QianchuanUniPromotionAdNameUpdateV10Response();
            resp.setCode(40100L);
            return resp;
        });
        assertThrows(com.aolandai.dingtou.exception.RateLimitException.class,
                () -> failing.rename(ACC, AD_ID, NAME, false));
    }
}
