package com.aolandai.dingtou.dto.qianchuan;

/**
 * 素材池里的一条可投素材（来自【获取投放计划可排除抖音视频/图文列表】）。
 *
 * <p>接口一次返回两列 id，用途不同：</p>
 * <ul>
 *   <li>{@code awemeItemId}（抖音视频ID，19 位数字）→ 平台判为<b>抖音主页视频</b>；
 *       用 {@code aweme_item_id} 传素材时<b>不能</b>带创意标题。</li>
 *   <li>{@code videoId}（千川素材库视频id，形如 {@code v0200fg10000...}）→ 判为<b>非主页视频</b>；
 *       用它时<b>必须</b>至少带一条创意标题，否则素材不生效。</li>
 * </ul>
 *
 * <p>⚠️ 本项目只用 {@code awemeItemId}（素材表 {@code H 视频id} 就是抖音视频ID），
 * 所以正好走「不带标题」这条，<b>不需要标题数据源</b>。
 * 保留 {@code videoId} 字段是为了「素材表只有 video_id 的老数据」这种意外情况能报出清晰原因，
 * 而不是无声地少加素材。</p>
 *
 * @param videoId     千川素材库视频id；可能为 {@code null}
 * @param awemeItemId 抖音视频ID；可能为 {@code null}
 * @param imageMode   素材类型：{@code VIDEO_VERTICAL} 竖版（默认）/ {@code VIDEO_LARGE} 横版
 */
public record MaterialRef(String videoId, Long awemeItemId, String imageMode) {

    /** 默认素材类型：竖版（信息流绝大多数是竖版）。 */
    public static final String IMAGE_MODE_VERTICAL = "VIDEO_VERTICAL";

    /** 横版。 */
    public static final String IMAGE_MODE_LARGE = "VIDEO_LARGE";

    public MaterialRef {
        imageMode = (imageMode == null || imageMode.isBlank())
                ? IMAGE_MODE_VERTICAL : imageMode.trim();
    }

    /** 是否是抖音主页视频（有 {@code awemeItemId}）。 */
    public boolean isAwemeHomeVideo() {
        return awemeItemId != null;
    }

    /** 两个 id 都为空 = 这条素材没法用（接口理论上不会这样，但别假设）。 */
    public boolean isUsable() {
        return awemeItemId != null || (videoId != null && !videoId.isBlank());
    }

    /** 一行式描述，便于日志与 warning。 */
    public String describe() {
        return awemeItemId != null ? "aweme_item_id=" + awemeItemId : "video_id=" + videoId;
    }
}
