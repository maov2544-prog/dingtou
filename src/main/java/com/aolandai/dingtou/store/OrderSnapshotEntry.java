package com.aolandai.dingtou.store;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 随心推订单快照的一条聚合记录：某个【抖音号 + 商品 ID】一共有多少笔随心推订单。
 *
 * <p>数据来源：{@code QianchuanAwemeUniPromotionOrderGetV10Api}（拉订单 → 本地按
 * {@code awemeInfo.awemeShowId + productInfo.productId} 分组计数）。</p>
 *
 * <h2>🔴 为什么存 {@code orderIds} 而不是直接存计数</h2>
 * <p>老板定的拉取策略是「<b>首次全量 + 之后每次增量</b>」，而增量拉的是
 * 「<b>最近 N 天</b>」—— 这 N 天的订单<b>有一部分已经在全量里了</b>。</p>
 *
 * <p>如果直接累加就会<b>重复计数</b>（N 会虚高 → 误判成 ≥3 → 白建千川计划）。</p>
 *
 * <p>所以存下<b>已计入的订单 ID</b>，增量合并时<b>按 order_id 去重</b>：</p>
 * <pre>
 * 合并 = 旧集合 ∪ 新拉到的集合        ← 幂等，重复拉同一天也不会多算
 * 计数 = 集合大小
 * </pre>
 *
 * <p>⚠️ 体积：实测最大账户 34,386 条订单 / 16,587 个组合 → 文件约 2MB。
 * 比存<b>完整明细</b>（每条订单十几个字段）小得多，加载也快。</p>
 *
 * <h2>⚠️ 口径：不看订单状态</h2>
 * <p>老板明确：「该达人抖音ID + 商品ID 有一个就算一笔，<b>不算状态</b>」——
 * 所以 {@code FAILED} / {@code TIMEOUT} 的订单也算。</p>
 *
 * @param awemeShowId 抖音号（接口返回的 {@code awemeInfo.awemeShowId}，与定投素材表的「抖音号」同源）
 * @param productId   商品 ID（接口返回的 {@code productInfo.productId}）
 * @param productName 商品名（冗余存一份，方便人工核对，不参与计算）
 * <h2>🔴 {@code blockingCount} 是「快照值」，不是「累积值」</h2>
 * <p>老板 2026-10-07：这些状态下的订单<b>仍存续、占用互斥名额</b>，
 * 「<b>无法创建同抖音号 + 同商品的新订单</b>」→ 这类组合必须跳过：</p>
 * <p>{@code DELIVERY_OK}（投放中）/ {@code AUDIT}（审核中）/ {@code CREATING}（创建中）/
 * {@code BOOK}（未开播）/ <b>{@code FROZEN}（投放终止 —— 仍占名额，最易漏）</b></p>
 *
 * <p>但订单状态会变（`DELIVERY_OK` → `OVER`），所以这个字段<b>每次刷新都要覆盖</b>，
 * 不能像 {@code orderIds} 那样累积 —— 累积会让它永远不减。</p>
 *
 * <p>⚠️ 因此增量回溯天数必须 <b>≥ 随心推投放时长上限</b>（`delivery_time` 白名单最大
 * <b>168 小时 = 7 天</b>）—— 否则「7 天前创建、现在还在投」的订单会看不到，
 * {@code blockingCount} 被错误覆盖成 0 → 该跳过的没跳过 → 建计划被千川拒。</p>
 *
 * @param orderIds         已计入的随心推订单 ID（<b>增量去重靠它</b>）
 * @param blockingCount    最近一次刷新时，该组合<b>占用互斥名额</b>的订单数
 * @param blockingStatuses 这些订单的具体状态名（如 {@code FROZEN/DELIVERY_OK}），
 *                         只用于排查 —— <b>不写进素材表</b>
 * @param refreshedAt      这条记录最后一次更新的时间
 */
public record OrderSnapshotEntry(
        String awemeShowId,
        String productId,
        String productName,
        List<String> orderIds,
        int blockingCount,
        String blockingStatuses,
        String refreshedAt) {

    public OrderSnapshotEntry {
        orderIds = orderIds == null ? List.of() : List.copyOf(orderIds);
        productName = productName == null ? "" : productName;
        blockingStatuses = blockingStatuses == null ? "" : blockingStatuses;
    }

    /**
     * 订单笔数（= 已计入的订单 ID 个数）。
     *
     * <p>⚠️ 必须 {@code @JsonIgnore}：这是<b>派生值</b>，写进 JSONL 后回读时
     * record 只能靠构造器参数绑定，{@code orderCount} 会变成「未知字段」→
     * 默认 {@code FAIL_ON_UNKNOWN_PROPERTIES=true} → <b>整个文件读不回来，服务起不来</b>。</p>
     */
    @JsonIgnore
    public int orderCount() {
        return orderIds.size();
    }

    /**
     * 该组合<b>有订单仍存续（占用互斥名额）</b> → <b>建不了新随心推订单</b>。
     *
     * <p>⚠️ 不只「投放中」—— 含 {@code AUDIT}/{@code CREATING}/{@code BOOK}/{@code FROZEN}。
     * 这个口径<b>只对「创建随心推订单」生效</b>。</p>
     */
    @JsonIgnore
    public boolean hasBlockingOrder() {
        return blockingCount > 0;
    }

    /**
     * 该组合<b>有订单正在投放中</b>（{@code DELIVERY_OK}）。
     *
     * <p>⚠️ 老板 2026-10-08：创建<b>千川乘方计划</b>时，同一抖音号 × 同一商品维度下
     * 「无论随心推订单处于什么状态，基本都可以创建成功」→ 所以这个判断<b>不是硬性互斥</b>，
     * 只是<b>想让这个随心推跑完再建</b>。</p>
     *
     * <p>📌 从 {@link #blockingStatuses} <b>派生</b>，不额外存字段 ——
     * 少一个字段就少一次 JSONL 格式迁移（改字段名会让旧快照文件读不了、服务起不来，踩过）。</p>
     */
    @JsonIgnore
    public boolean isDelivering() {
        return blockingStatuses.contains("DELIVERY_OK");
    }

    public OrderKey key() {
        return new OrderKey(awemeShowId, productId);
    }

    /**
     * 合并新拉到的订单 ID（<b>按 order_id 去重</b>、保持原有顺序）。
     *
     * <p>⚠️ 这是增量的核心：重复拉同一段也不会多算 —— 幂等。</p>
     */
    public OrderSnapshotEntry mergedWith(List<String> newOrderIds, int newBlockingCount,
                                         String newBlockingStatuses, String newRefreshedAt) {
        Set<String> all = new LinkedHashSet<>(orderIds);
        if (newOrderIds != null) {
            all.addAll(newOrderIds);
        }
        // ⚠️ blockingCount 是【覆盖】不是累加 —— 订单状态会变，累积会让它永远不减
        return new OrderSnapshotEntry(awemeShowId, productId, productName,
                new ArrayList<>(all), newBlockingCount, newBlockingStatuses, newRefreshedAt);
    }
}
