package com.aolandai.dingtou.service.aggregate;

import com.aolandai.dingtou.config.AggregateProperties;
import com.aolandai.dingtou.config.SourceProperties;
import com.aolandai.dingtou.dto.CountResult;
import com.aolandai.dingtou.dto.sheet.DeliveryRow;
import com.aolandai.dingtou.source.DeliverySheetReader;
import com.aolandai.dingtou.source.ProductDictReader;
import com.aolandai.dingtou.store.OrderSnapshotEntry;
import com.aolandai.dingtou.exception.BizException;
import com.aolandai.dingtou.store.OrderSnapshotStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link CumulativeCountService} 的默认实现：<b>订单快照优先，素材表预筛兜底</b>。
 *
 * <h2>取数顺序</h2>
 * <ol>
 *   <li><b>订单快照</b>（权威）：{@link OrderSnapshotStore}。需要先把商品名映射到唯一商品 ID；</li>
 *   <li><b>素材表预筛</b>（近似）：数定投素材表里该组合的行数。</li>
 * </ol>
 *
 * <p>⚠️ <b>快照里的「没有」为什么不能直接当 0：</b>只有确认快照是<b>全量</b>拉取的，
 * 「没查到」才等于「真的 0 笔」。所以本实现会在「预筛说有行、快照却没有」时打 WARN
 * 并回退用预筛数 —— 这种不一致通常意味着快照没覆盖全，或者商品名映射错了。</p>
 *
 * <h2>预筛为什么要缓存</h2>
 * <p>定投素材表有 4992 行，全量读一遍约 2 秒；而 {@code decide} 是<b>逐条</b>调的。
 * 所以预筛结果按「文件最后修改时间 + TTL」缓存 —— 表一改就自动失效。</p>
 */
@Component
public class DefaultCumulativeCountService implements CumulativeCountService {

    private static final Logger log = LoggerFactory.getLogger(DefaultCumulativeCountService.class);

    private final OrderSnapshotStore snapshotStore;
    private final ProductDictReader productDictReader;
    private final DeliverySheetReader deliverySheetReader;
    private final AggregateProperties properties;
    private final SourceProperties sourceProperties;

    /** 预筛缓存锁 */
    private final Object cacheLock = new Object();

    /** 缓存的预筛计数：(抖音号, 商品名) → 行数 */
    private Map<MaterialKey, Integer> cachedCounts;
    /** 缓存对应的素材表最后修改时间 */
    private long cachedFileStamp = -1L;
    /** 缓存写入时刻（毫秒） */
    private long cachedAtMillis;

    public DefaultCumulativeCountService(OrderSnapshotStore snapshotStore,
                                         ProductDictReader productDictReader,
                                         DeliverySheetReader deliverySheetReader,
                                         AggregateProperties properties,
                                         SourceProperties sourceProperties) {
        this.snapshotStore = snapshotStore;
        this.productDictReader = productDictReader;
        this.deliverySheetReader = deliverySheetReader;
        this.properties = properties;
        this.sourceProperties = sourceProperties;
    }

    @Override
    public CountResult count(String awemeShowId, String productName) throws IOException {
        if (isBlank(awemeShowId) || isBlank(productName)) {
            return new CountResult(awemeShowId, productName, "", 0, CountSource.NONE,
                    "抖音号或商品名为空，无法统计");
        }

        // 🔴 老板 2026-10-07 定调：**不预筛，只认随心推订单数**。
        //    预筛数的是「素材表行数」，与「订单笔数」不是一回事（阈值 3 直接决定花不花钱）。
        //    → 素材表预筛已【停用】（代码保留在 prescreenCount，仅作排障用）。
        if (!snapshotStore.isFresh()) {
            // ⚠️ 宁可报错也不要拿近似值定档 —— 猜错会白建千川计划（花钱）。
            throw BizException.businessFail(
                    "订单快照不可用（未刷新或已过期），无法确定累计笔数 N。"
                            + "请先调 refreshOrders 刷新（首次传 full=true，之后增量）");
        }

        Optional<String> productId = productDictReader.uniqueProductIdOfName(productName);
        if (productId.isEmpty()) {
            throw BizException.businessFail("商品名「" + productName
                    + "」在【商品 ID 映射表】里映射不到唯一商品 ID，无法查订单数");
        }

        Optional<OrderSnapshotEntry> hit = snapshotStore.find(awemeShowId, productId.get());
        int count = hit.map(OrderSnapshotEntry::orderCount).orElse(0);
        boolean blocked = hit.map(OrderSnapshotEntry::hasBlockingOrder).orElse(false);
        String blockingStatuses = hit.map(OrderSnapshotEntry::blockingStatuses).orElse("");

        String detail = "随心推订单快照（" + snapshotStore.keyCount() + " 个组合 / "
                + snapshotStore.totalOrders() + " 笔；刷新于 "
                + snapshotStore.lastRefreshedAt().orElse("?") + "）"
                + (blocked ? "；⚠️ 该组合有订单仍存续（占用互斥名额：" + blockingStatuses
                        + "），建不了新订单/新计划" : "");
        return new CountResult(awemeShowId, productName, productId.get(), count,
                CountSource.ORDER_SNAPSHOT, blocked, blockingStatuses, detail);
    }

    // ==================== 预筛（带缓存） ====================

    private int prescreenCount(String awemeShowId, String productName) throws IOException {
        return counts().getOrDefault(new MaterialKey(awemeShowId, productName), 0);
    }

    /** 取预筛计数表；缓存失效（文件变了 / 超时）就重建。 */
    private Map<MaterialKey, Integer> counts() throws IOException {
        long stamp = fileStamp();
        long now = System.currentTimeMillis();
        boolean expired = properties.getPrescreenCacheSeconds() <= 0
                || now - cachedAtMillis > properties.getPrescreenCacheSeconds() * 1000L;

        if (cachedCounts != null && cachedFileStamp == stamp && !expired) {
            return cachedCounts;
        }

        synchronized (cacheLock) {
            if (cachedCounts != null && cachedFileStamp == stamp
                    && now - cachedAtMillis <= properties.getPrescreenCacheSeconds() * 1000L) {
                return cachedCounts;
            }
            Map<MaterialKey, Integer> rebuilt = buildCounts();
            cachedCounts = rebuilt;
            cachedFileStamp = stamp;
            cachedAtMillis = System.currentTimeMillis();
            log.info("素材表预筛已重建: {} 个组合（口径={}）",
                    rebuilt.size(), properties.getPrescreenScope().name());
            return rebuilt;
        }
    }

    private Map<MaterialKey, Integer> buildCounts() throws IOException {
        List<DeliveryRow> rows = deliverySheetReader.readAll();
        boolean investedOnly = properties.getPrescreenScope() == PrescreenScope.INVESTED;

        Map<MaterialKey, Integer> counts = new HashMap<>();
        int skipped = 0;
        for (DeliveryRow row : rows) {
            if (!row.aggregatable()) {
                skipped++;
                continue;
            }
            if (investedOnly && !row.alreadyInvested()) {
                continue;
            }
            counts.merge(new MaterialKey(row.awemeShowId(), row.productName()), 1, Integer::sum);
        }
        if (skipped > 0) {
            log.warn("素材表预筛跳过了 {} 行（抖音号或产品为空）", skipped);
        }
        return counts;
    }

    private long fileStamp() {
        try {
            return Files.getLastModifiedTime(sourceProperties.getDeliverySheet()).toMillis();
        } catch (IOException | RuntimeException e) {
            // 文件读不到时返回 -1：缓存判定会因 stamp 变化而重建，行为仍然正确
            return -1L;
        }
    }

    /** 预筛的主键：抖音号 + 商品名。 */
    private record MaterialKey(String awemeShowId, String productName) {
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
