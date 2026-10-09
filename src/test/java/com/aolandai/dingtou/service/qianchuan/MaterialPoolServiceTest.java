package com.aolandai.dingtou.service.qianchuan;

import com.aolandai.dingtou.config.PlanProperties;
import com.aolandai.dingtou.dto.qianchuan.MaterialRef;
import com.bytedance.ads.ApiException;
import com.bytedance.ads.model.QianchuanUniPromotionBlockMaterialGetV10Response;
import com.bytedance.ads.model.QianchuanUniPromotionBlockMaterialGetV10ResponseData;
import com.bytedance.ads.model.QianchuanUniPromotionBlockMaterialGetV10ResponseDataPageInfo;
import com.bytedance.ads.model.QianchuanUniPromotionBlockMaterialGetV10ResponseDataVideoListInner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MaterialPoolService} 单测：注入假 fetcher，<b>绝不真调接口</b>。
 */
class MaterialPoolServiceTest {

    private static final long ACC = 1823379540880396L;
    private static final long AWEME_UID = 2071074583877891L;
    private static final long PRODUCT = 3593275488396811650L;

    private static PlanProperties props() {
        PlanProperties p = new PlanProperties();
        p.setMaterialPoolPageIntervalMillis(0);   // 单测不真睡
        p.setMaterialPoolMaxRounds(5);
        return p;
    }

    private static QianchuanUniPromotionBlockMaterialGetV10Response page(
            List<QianchuanUniPromotionBlockMaterialGetV10ResponseDataVideoListInner> videos,
            Long cursor, boolean hasMore) {
        QianchuanUniPromotionBlockMaterialGetV10ResponseData data =
                new QianchuanUniPromotionBlockMaterialGetV10ResponseData();
        data.setVideoList(videos);
        if (cursor != null || hasMore) {
            QianchuanUniPromotionBlockMaterialGetV10ResponseDataPageInfo info =
                    new QianchuanUniPromotionBlockMaterialGetV10ResponseDataPageInfo();
            info.setCursor(cursor);
            info.setHasMore(hasMore);
            data.setPageInfo(info);
        }
        QianchuanUniPromotionBlockMaterialGetV10Response resp =
                new QianchuanUniPromotionBlockMaterialGetV10Response();
        resp.setCode(0L);
        resp.setData(data);
        return resp;
    }

    private static QianchuanUniPromotionBlockMaterialGetV10ResponseDataVideoListInner video(
            Long awemeItemId, String videoId, Integer width, Integer height) {
        QianchuanUniPromotionBlockMaterialGetV10ResponseDataVideoListInner v =
                new QianchuanUniPromotionBlockMaterialGetV10ResponseDataVideoListInner();
        v.setAwemeItemId(awemeItemId);
        v.setVideoId(videoId);
        v.setWidth(width);
        v.setHeight(height);
        return v;
    }

    @Test
    @DisplayName("★ 游标翻页：has_more=true 时继续拉，直到 false")
    void followsCursorPaging() throws Exception {
        List<Long> cursors = new ArrayList<>();
        MaterialPoolService service = new MaterialPoolService(() -> (adv, uid, products, cursor) -> {
            cursors.add(cursor);
            if (cursor == 0L) {
                return page(List.of(video(111L, "v1", 720, 1280)), 100L, true);
            }
            return page(List.of(video(222L, "v2", 1280, 720)), 200L, false);
        }, props());

        List<MaterialRef> list = service.list(ACC, AWEME_UID, PRODUCT);

        assertEquals(List.of(0L, 100L), cursors, "第一页从 cursor=0 开始，第二页用返回的 cursor");
        assertEquals(2, list.size());
        assertEquals(111L, list.get(0).awemeItemId());
        assertEquals(MaterialRef.IMAGE_MODE_VERTICAL, list.get(0).imageMode());
        assertEquals(MaterialRef.IMAGE_MODE_LARGE, list.get(1).imageMode(), "宽>高 → 横版");
    }

    @Test
    @DisplayName("★ 跨页重复的素材按 id 去重（否则加素材时会传重复项，平台报错且难查）")
    void deduplicatesAcrossPages() throws Exception {
        MaterialPoolService service = new MaterialPoolService(() -> (adv, uid, products, cursor) ->
                cursor == 0L
                        ? page(List.of(video(111L, "v1", 720, 1280)), 1L, true)
                        : page(List.of(video(111L, "v1", 720, 1280),
                        video(222L, "v2", 720, 1280)), 2L, false), props());

        List<MaterialRef> list = service.list(ACC, AWEME_UID, PRODUCT);

        assertEquals(2, list.size(), "重复的那条要被去掉");
    }

    @Test
    @DisplayName("★ code!=0 必须抛（静默会变成「素材池是空的」→ 建出没有素材的计划）")
    void businessErrorIsThrown() {
        MaterialPoolService service = new MaterialPoolService(() -> (adv, uid, products, cursor) -> {
            QianchuanUniPromotionBlockMaterialGetV10Response resp =
                    new QianchuanUniPromotionBlockMaterialGetV10Response();
            resp.setCode(40000L);
            resp.setMessage("参数不合法");
            return resp;
        }, props());

        ApiException e = assertThrows(ApiException.class,
                () -> service.list(ACC, AWEME_UID, PRODUCT));
        assertTrue(e.getMessage().contains("40000"), e.getMessage());
    }

    @Test
    @DisplayName("★ 40100 频控 → 抛 RateLimitException（让上层别重试，重试只会加重惩罚）")
    void rateLimitIsThrown() {
        MaterialPoolService service = new MaterialPoolService(() -> (adv, uid, products, cursor) -> {
            QianchuanUniPromotionBlockMaterialGetV10Response resp =
                    new QianchuanUniPromotionBlockMaterialGetV10Response();
            resp.setCode(40100L);
            resp.setMessage("系统请求频率超限");
            return resp;
        }, props());

        assertThrows(com.aolandai.dingtou.exception.RateLimitException.class,
                () -> service.list(ACC, AWEME_UID, PRODUCT));
    }

    @Test
    @DisplayName("翻页打满上限仍有下一页 → 返回已有结果（并打 WARN，不静默）")
    void truncationReturnsWhatItHas() throws Exception {
        MaterialPoolService service = new MaterialPoolService(() -> (adv, uid, products, cursor) ->
                page(List.of(video(1000L + cursor, "v", 720, 1280)), cursor + 1, true), props());

        List<MaterialRef> list = service.list(ACC, AWEME_UID, PRODUCT);

        assertEquals(5, list.size(), "上限 5 轮，返回 5 条而不是无限循环");
    }

    @Test
    @DisplayName("两个 id 都为空的素材被跳过（不返回 null 字段的垃圾）")
    void unusableMaterialIsSkipped() throws Exception {
        MaterialPoolService service = new MaterialPoolService(() -> (adv, uid, products, cursor) ->
                page(List.of(video(null, null, null, null), video(222L, "v2", 720, 1280)), null, false),
                props());

        List<MaterialRef> list = service.list(ACC, AWEME_UID, PRODUCT);

        assertEquals(1, list.size());
        assertEquals(222L, list.get(0).awemeItemId());
    }

    @Test
    @DisplayName("productIds 为空 → IllegalArgumentException（本地就能发现，别打到线上）")
    void emptyProductIdsIsRejected() {
        MaterialPoolService service = new MaterialPoolService(
                () -> (adv, uid, products, cursor) -> page(List.of(), null, false), props());
        assertThrows(IllegalArgumentException.class, () -> service.list(ACC, AWEME_UID, List.of()));
    }
}
