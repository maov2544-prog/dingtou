package com.aolandai.dingtou.service.task.handler;

import com.aolandai.dingtou.dto.qianchuan.VideoProductResult;
import com.aolandai.dingtou.service.qianchuan.PlanMaterialResolver;
import com.aolandai.dingtou.service.task.TaskHandler;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * {@code resolveProductByVideo} —— <b>抖音分享链接 → 商品 ID</b>。
 *
 * <pre>
 * {"requestId":"r1","params":{
 *   "link": "0.56 复制打开抖音，看看【尚尚孕期（努力版）的作品】… https://v.douyin.com/0q-qmQYi6hQ/ …",
 *   "advertiserId": 1823379540880396,
 *   "awemeShowId": "173701235",     // 选填：素材表 D 列，给了能提高命中率
 *   "awemeUid": 65628672972         // 选填：已知数值 uid 时直接给，跳过解析
 * }}
 * </pre>
 *
 * <h2>返回</h2>
 * <pre>
 * { "videoId":"7690582056733081211", "awemeUid":65628672972,
 *   "awemeName":"尚尚孕期（努力版）", "awemeShowId":"173701235",
 *   "productId":"3593275488396811650", "productName":"澳兰黛孕产面膜",
 *   "title":"…", "img":"…", "candidates":[], "stage":"", "error":"" }
 * </pre>
 *
 * <h2>⚠️ 链路</h2>
 * <pre>
 * 分享口令 → v.douyin.com 短链（多跳跟随）→ 视频ID + social_author_id
 *          → 视频列表（账户 + uid，count≤20 翻页）→ 匹配视频ID → 商品ID
 *          → 兜底：品名反查（返回 candidates 供人工确认）
 * </pre>
 *
 * <p>⚠️ <b>只读、不花钱</b>（不建计划、不建单）。</p>
 */
@Component
public class ResolveProductByVideoHandler implements TaskHandler {

    private final PlanMaterialResolver resolver;

    public ResolveProductByVideoHandler(PlanMaterialResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public String code() {
        return "resolveProductByVideo";
    }

    @Override
    public String description() {
        return "抖音分享链接 → 视频ID → 商品ID（解析出抖音名/抖音号/商品ID/视频ID；只读不花钱）";
    }

    @Override
    public Object handle(JsonNode params) throws Exception {
        String link = Params.optionalText(params, "link");
        Long advertiserIdOpt = Params.optionalLong(params, "advertiserId");
        String awemeShowId = Params.optionalText(params, "awemeShowId");
        Long awemeUid = Params.optionalLong(params, "awemeUid");

        if (link == null || link.isBlank()) {
            throw com.aolandai.dingtou.exception.BizException.badRequest(
                    "link（抖音分享口令）必填");
        }
        long advertiserId = advertiserIdOpt == null ? 0L : advertiserIdOpt;
        if (advertiserId <= 0) {
            throw com.aolandai.dingtou.exception.BizException.badRequest(
                    "advertiserId（千川账户 ID）必填且必须是正整数");
        }

        VideoProductResult result = resolver.resolve(link, advertiserId, awemeUid,
                awemeShowId == null || awemeShowId.isBlank() ? null : awemeShowId, null);

        // ⚠️ 失败不用抛异常 —— 结构化返回，让影刀自己决定怎么处理
        return result;
    }
}
