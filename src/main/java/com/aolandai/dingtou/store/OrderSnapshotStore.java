package com.aolandai.dingtou.store;

import com.aolandai.dingtou.config.StoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 随心推订单快照：{@code (抖音号, 商品ID) → 订单笔数}。
 *
 * <p>为什么必须落盘、不能每次现查接口：<b>随心推订单查询接口不支持按达人/商品过滤</b>
 * （只有时间窗 + 状态），只能拉回来本地聚合。所以唯一的可行路径就是
 * 「定时拉取 → 存本地 → 投放前查本地」。</p>
 *
 * <p>⚠️ 刷新要用<b>整表覆盖</b>（{@link #replaceAll}），不要追加：
 * 订单状态会变（取消 / 关停），纯追加会让旧计数永远留在快照里。</p>
 */
@Component
public class OrderSnapshotStore extends JsonlStore<OrderSnapshotEntry> {

    private static final Logger log = LoggerFactory.getLogger(OrderSnapshotStore.class);

    /** (抖音号, 商品ID) → 快照记录 */
    private final Map<OrderKey, OrderSnapshotEntry> byKey = new LinkedHashMap<>();

    private final StoreProperties properties;

    public OrderSnapshotStore(StoreProperties properties) throws IOException {
        super(properties.getOrderSnapshot(), OrderSnapshotEntry.class);
        this.properties = properties;
        buildIndex();
        log.info("随心推订单快照已加载: {} 个组合（{}）", byKey.size(), filePath());
    }

    @Override
    protected void reindex() {
        byKey.clear();
        for (OrderSnapshotEntry entry : records()) {
            byKey.put(entry.key(), entry);
        }
    }

    /** 查某个【抖音号 + 商品 ID】的快照；没有返回空。 */
    public synchronized Optional<OrderSnapshotEntry> find(String awemeShowId, String productId) {
        if (awemeShowId == null || awemeShowId.isBlank() || productId == null || productId.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(byKey.get(new OrderKey(awemeShowId, productId)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * 取累计订单笔数。
     *
     * <p>⚠️ 快照里没有该组合时返回 {@code 0} —— 语义是「<b>快照里没查到</b>」，
     * 不等于「真实世界一定 0 笔」。调用方要先用 {@link #isFresh()} 确认快照本身是不是新鲜。</p>
     */
    public synchronized int countOf(String awemeShowId, String productId) {
        return find(awemeShowId, productId).map(OrderSnapshotEntry::orderCount).orElse(0);
    }

    /** 整表覆盖（刷新订单快照）。 */
    public synchronized void replaceAll(List<OrderSnapshotEntry> entries) throws IOException {
        List<OrderSnapshotEntry> safe = entries == null ? List.of() : List.copyOf(entries);
        mutate(records -> {
            records.clear();
            records.addAll(safe);
            return null;
        });
        log.info("订单快照已刷新: {} 个组合，共 {} 笔订单", byKey.size(), totalOrders());
    }

    /**
     * <b>增量合并</b>：把新拉到的订单并进快照，<b>按 order_id 去重</b>。
     *
     * <p>这是「首次全量 + 之后增量」策略的核心 —— 增量拉的是「最近 N 天」，
     * 这 N 天里有一部分订单<b>已经在快照里了</b>，直接累加会重复计数
     * （N 虚高 → 误判 ≥3 → 白建千川计划）。</p>
     *
     * <p>⚠️ <b>幂等</b>：同一批数据合并两次，结果完全一样。</p>
     *
     * @param incoming    新拉到的（按组合分组的）订单
     * @param refreshedAt 本次刷新的时间戳（写进每条记录）
     * @return 本次<b>真正新增</b>的订单笔数（0 = 全是重复的）
     */
    public synchronized int merge(List<OrderSnapshotEntry> incoming, String refreshedAt)
            throws IOException {
        if (incoming == null || incoming.isEmpty()) {
            return 0;
        }
        int[] added = {0};
        mutate(records -> {
            Map<OrderKey, OrderSnapshotEntry> index = new LinkedHashMap<>();
            for (OrderSnapshotEntry entry : records) {
                index.put(entry.key(), entry);
            }
            for (OrderSnapshotEntry in : incoming) {
                OrderSnapshotEntry old = index.get(in.key());
                if (old == null) {
                    OrderSnapshotEntry fresh = in.mergedWith(in.orderIds(), in.blockingCount(), in.blockingStatuses(),
                            refreshedAt);
                    index.put(fresh.key(), fresh);
                    added[0] += fresh.orderCount();
                } else {
                    OrderSnapshotEntry merged = old.mergedWith(in.orderIds(), in.blockingCount(), in.blockingStatuses(),
                            refreshedAt);
                    added[0] += merged.orderCount() - old.orderCount();
                    index.put(merged.key(), merged);
                }
            }
            records.clear();
            records.addAll(index.values());
            return null;
        });
        log.info("订单快照增量合并: 传入 {} 个组合，新增 {} 笔；合并后 {} 个组合 / {} 笔",
                incoming.size(), added[0], byKey.size(), totalOrders());
        return added[0];
    }

    /** 全部记录（只读快照，供探针 / 排障观察）。 */
    public synchronized List<OrderSnapshotEntry> all() {
        return new ArrayList<>(byKey.values());
    }

    /** 清空快照。 */
    public synchronized void clear() throws IOException {
        replaceAll(List.of());
    }

    /** 快照最后一次刷新时间（取所有记录里最新的那个）。空快照返回空。 */
    public synchronized Optional<String> lastRefreshedAt() {
        String latest = null;
        for (OrderSnapshotEntry entry : byKey.values()) {
            String at = entry.refreshedAt();
            if (at == null) {
                continue;
            }
            if (latest == null || at.compareTo(latest) > 0) {
                latest = at;
            }
        }
        return Optional.ofNullable(latest);
    }

    /**
     * 快照是否在有效期内。
     *
     * <p>⚠️ 空快照一律视为<b>不新鲜</b> —— 因为我们无法区分「还没拉过」和「拉了但确实 0 笔」。
     * 宁可多刷一次，也不要拿一份来路不明的空数据去判 N。</p>
     */
    public synchronized boolean isFresh() {
        if (byKey.isEmpty()) {
            return false;
        }
        return lastRefreshedAt().map(at -> {
            try {
                OffsetDateTime refreshed = OffsetDateTime.parse(at);
                Duration age = Duration.between(refreshed, OffsetDateTime.now());
                return age.compareTo(Duration.ofHours(properties.getOrderSnapshotTtlHours())) < 0;
            } catch (DateTimeParseException e) {
                log.warn("快照时间戳无法解析: {}，按不新鲜处理", at);
                return false;
            }
        }).orElse(false);
    }

    /**
     * 快照是否在 {@code maxAge} 内刷新过（比 {@link #isFresh()} 更严格，用于节流）。
     *
     * <p>⚠️ 空快照返回 {@code false} —— 没数据时必须去拉。</p>
     */
    public synchronized boolean isFreshWithin(Duration maxAge) {
        if (byKey.isEmpty()) {
            return false;
        }
        return lastRefreshedAt().map(at -> {
            try {
                Duration age = Duration.between(OffsetDateTime.parse(at), OffsetDateTime.now());
                return age.compareTo(maxAge) < 0;
            } catch (DateTimeParseException e) {
                return false;
            }
        }).orElse(false);
    }

    /** 快照里的组合数。 */
    public synchronized int keyCount() {
        return byKey.size();
    }

    /** 快照里的订单总笔数。 */
    public synchronized int totalOrders() {
        int total = 0;
        for (OrderSnapshotEntry entry : byKey.values()) {
            total += Math.max(entry.orderCount(), 0);
        }
        return total;
    }
}
