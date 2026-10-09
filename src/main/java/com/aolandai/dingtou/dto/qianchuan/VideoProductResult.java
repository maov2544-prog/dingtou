package com.aolandai.dingtou.dto.qianchuan;

import java.util.List;

/**
 * 「抖音分享链接 → 商品 ID」的解析结果。
 *
 * <h2>链路（老板 2026-10-08 给的示例 + 已验证可跑）</h2>
 * <pre>
 * 分享口令
 *   ↓ ① 提取 v.douyin.com 短链，多跳跟随（≤10 跳）
 * https://v.douyin.com/0q-qmQYi6hQ/
 *   ↓ ② 同时扫每一跳的 URL 与正文，拿两样东西：
 *      · /video/(\d{10,25})  → 视频ID
 *      · social_author_id=…  → 作者数值 uid（抖音分享参数自带，不走风控接口）
 *   ↓ ③ 视频列表翻页（按账户 + uid）→ 匹配视频ID → product_info.id
 *   ↓ ④ 兜底：按品名反查商品（返回候选供人工确认）
 * </pre>
 *
 * @param videoId     抖音视频 ID（19 位左右）= 素材表 {@code H 视频id}
 * @param awemeUid    作者数值 UID（千川接口要的那个）
 * @param awemeName   抖音名（昵称）
 * @param awemeShowId 抖音号（如 {@code 173701235}）；取不到为空
 * @param productId   商品 ID；没解析出来为空
 * @param productName 商品名
 * @param title       视频标题
 * @param img         商品主图
 * @param candidates  兜底时返回的候选商品（人工确认用）
 * @param stage       卡在哪一步（失败时）
 * @param error       失败原因；空 = 没出错
 */
public record VideoProductResult(
        String videoId,
        Long awemeUid,
        String awemeName,
        String awemeShowId,
        String productId,
        String productName,
        String title,
        String img,
        List<String> candidates,
        String stage,
        String error) {

    public VideoProductResult {
        videoId = videoId == null ? "" : videoId;
        awemeName = awemeName == null ? "" : awemeName;
        awemeShowId = awemeShowId == null ? "" : awemeShowId;
        productId = productId == null ? "" : productId;
        productName = productName == null ? "" : productName;
        title = title == null ? "" : title;
        img = img == null ? "" : img;
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        stage = stage == null ? "" : stage;
        error = error == null ? "" : error;
    }

    /** 是否拿到了商品 ID。 */
    public boolean found() {
        return !productId.isEmpty();
    }

    /** 执行过程是否无异常。 */
    public boolean ok() {
        return error.isEmpty();
    }
}
