package com.aolandai.dingtou.dto.plan;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code planBuild} 任务的入参（A / B / C1 / C2）。
 *
 * <h2>⚠️ 影刀不需要知道场景</h2>
 * <p>调用方只交「哪个【抖音号 + 商品】要投放」，<b>由服务自己判断</b>是投随心推、建计划还是复用计划 ——
 * 所以这里<b>没有</b> scenario 参数。业务规则改了就改服务，影刀不用动。</p>
 *
 * @param awemeShowId   抖音号（必填，如 {@code 610129yiyi}）
 * @param productName   商品名（必填，来自素材表 {@code G 产品}）
 * @param videoIds      C1（已有计划）要加的「<b>本条素材</b>」= 素材表 {@code H 视频id}，<b>可多个</b>。
 *                      <ul>
 *                        <li>单条调用（影刀逐行处理）→ 传 1 个；</li>
 *                        <li>批量调用（同一组合有多个新视频）→ 传多个，<b>一次全加进去</b>；</li>
 *                        <li>不传 → 服务在素材表里找该【抖音号+商品】「投放类型为空」的行，
 *                            <b>只有唯一一条</b>才用它，多条则报错要人工确认（绝不瞎挑一条）。</li>
 *                      </ul>
 * @param executionDate 执行日期，用于生成计划名里的 {@code M.D}；{@code null} = 取今天。
 *                      <b>可注入</b>是为了补跑历史日期，也让单测能固定日期。
 */
public record PlanBuildParam(
        String awemeShowId,
        String productName,
        List<Long> videoIds,
        LocalDate executionDate) {

    public PlanBuildParam {
        awemeShowId = awemeShowId == null ? "" : awemeShowId.trim();
        productName = productName == null ? "" : productName.trim();
        videoIds = videoIds == null ? List.of() : List.copyOf(videoIds);
    }

    /**
     * 只传一条视频的便捷工厂（影刀逐行处理的常见用法）。
     *
     * <p>⚠️ 传 {@code null} 表示「不指定，由服务自己去素材表定位」。</p>
     *
     * <p>⚠️ 刻意做成<b>静态工厂而不是重载构造器</b>：构造器签名
     * {@code (String, String, Long, LocalDate)} 与 {@code (String, String, List<Long>, LocalDate)}
     * 在传 {@code null} 时<b>无法区分</b>，编译器直接报 ambiguous ——
     * 而「不指定素材」恰恰是最常见的用法。</p>
     */
    public static PlanBuildParam of(String awemeShowId, String productName, Long videoId,
                                    LocalDate executionDate) {
        return new PlanBuildParam(awemeShowId, productName,
                videoId == null ? List.<Long>of() : List.of(videoId), executionDate);
    }

    /** 是否由调用方明确指定了要加的素材。 */
    public boolean hasExplicitVideos() {
        return !videoIds.isEmpty();
    }

    /** 去掉 {@code null} 元素后的列表（防御性：JSON 数组里可能有 null）。 */
    public List<Long> nonNullVideoIds() {
        List<Long> out = new ArrayList<>(videoIds.size());
        for (Long id : videoIds) {
            if (id != null) {
                out.add(id);
            }
        }
        return out;
    }
}
