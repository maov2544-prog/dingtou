package com.aolandai.dingtou.service.qianchuan;

import com.aolandai.dingtou.client.QianchuanClientFactory;
import com.aolandai.dingtou.dto.qianchuan.SuixintuiUniOrderListRequest;
import com.aolandai.dingtou.dto.sheet.AccountRow;
import com.aolandai.dingtou.source.AccountDictReader;
import com.aolandai.dingtou.store.OrderSnapshotEntry;
import com.aolandai.dingtou.store.OrderSnapshotStore;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 随心推订单快照的<b>刷新服务</b> —— 「首次全量 + 之后增量」（老板 2026-10-07 定）。
 *
 * <h2>为什么是这个策略（实测依据）</h2>
 * <p>实测最大账户（官方【母婴商品】）拉一次全量：<b>689 页 / 34,386 条 / 11 分 38 秒</b>。
 * 8 个账户就是 <b>≈90 分钟</b> —— 所以：</p>
 * <ul>
 *   <li><b>全量只做一次</b>（首次），且<b>必须异步</b>跑（不能让影刀干等）；</li>
 *   <li><b>之后每次投放前跑增量</b>：只拉「最近 N 天」，实测日订单量 40~100 条
 *       → <b>5 页 ≈ 5 秒</b>，同步跑完全没问题。</li>
 * </ul>
 *
 * <h2>🔴 增量为什么必须按 order_id 去重</h2>
 * <p>增量拉的是「最近 N 天」，这 N 天里<b>有一部分订单已经在快照里了</b>。
 * 直接累加会重复计数 → N 虚高 → 误判 ≥3 → <b>白建千川计划</b>。</p>
 * <p>所以 {@link OrderSnapshotEntry} 存了已计入的 {@code orderIds}，
 * 合并时做集合并集（幂等）。</p>
 *
 * <h2>⚠️ 口径</h2>
 * <ul>
 *   <li><b>不看订单状态</b>：老板明确「有一个就算一笔」——`FAILED`/`TIMEOUT` 也算；</li>
 *   <li><b>只拉「随心推全域-商品订单」</b>（`VIDEO_PROM_GOODS`），直播全域接口不支持；</li>
 *   <li><b>不传时间窗 = 拉全量</b>（实测能拉到 404 天的历史，不受 180 天限制）。</li>
 * </ul>
 */
@Service
public class OrderSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(OrderSnapshotService.class);

    /**
     * 增量默认回溯天数。
     *
     * <p>⚠️ <b>必须 ≥ 随心推投放时长上限 168 小时（7 天）</b> —— 否则
     * 「7 天前创建、现在还在投」的订单看不到，{@code deliveringCount} 会被错误覆盖成 0
     * → 该跳过的组合没跳过 → 和正在跑的随心推撞车。</p>
     *
     * <p>实测日订单量 40~100 条 → 7 天约 280~700 条 → <b>6~14 页 ≈ 15 秒</b>，可同步跑。</p>
     */
    public static final int DEFAULT_LOOKBACK_DAYS = 7;

    /** 组合键分隔符：抖音号是字母数字、商品 ID 是数字，用 {@code |} 不会冲突。 */
    private static final String KEY_SEP = "|";

    private final QianchuanClientFactory clientFactory;
    private final AccountDictReader accountDictReader;
    private final OrderSnapshotStore snapshotStore;

    /** 防止全量刷新被重复触发（90 分钟的任务，重入会打爆接口）。 */
    private final AtomicBoolean fullRefreshRunning = new AtomicBoolean(false);

    public OrderSnapshotService(QianchuanClientFactory clientFactory,
                                AccountDictReader accountDictReader,
                                OrderSnapshotStore snapshotStore) {
        this.clientFactory = clientFactory;
        this.accountDictReader = accountDictReader;
        this.snapshotStore = snapshotStore;
    }

    /**
     * 刷新结果。
     *
     * @param mode           {@code FULL} / {@code INCREMENTAL}
     * @param accounts       处理的账户数
     * @param fetchedOrders  拉到的订单条数（去重前）
     * @param newOrders      真正新增的笔数（0 = 全是重复）
     * @param snapshotKeys   刷新后快照里的组合数
     * @param costMillis     耗时
     * @param async          是否异步执行（全量 = true，方法立即返回）
     * @param warnings       提示
     */
    public record RefreshResult(
            String mode,
            int accounts,
            int fetchedOrders,
            int newOrders,
            int snapshotKeys,
            long costMillis,
            boolean async,
            List<String> warnings) {

        public RefreshResult {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    /** 8 个账户（账户字典全量）—— 老板定「首次全量 8 个都跑」。 */
    public List<Long> allAdvertiserIds() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (AccountRow row : accountDictReader.readAll()) {
            Long id = row.advertiserIdAsLong();
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    /**
     * <b>全量刷新（异步）</b>：8 个账户各拉一次全量历史。
     *
     * <p>⚠️ 实测 ≈90 分钟，所以<b>立即返回</b>，后台线程跑。
     * 进度看日志与 {@code snapshotStore.keyCount()}（每拉完一个账户就合并一次）。</p>
     *
     * @return 若已有全量在跑，返回 {@code async=true} + warning（不重复触发）
     */
    public RefreshResult refreshFullAsync() throws Exception {
        List<Long> ids = allAdvertiserIds();
        if (!fullRefreshRunning.compareAndSet(false, true)) {
            return new RefreshResult("FULL", ids.size(), 0, 0, snapshotStore.keyCount(), 0L, true,
                    List.of("已有一次全量刷新在跑，本次不重复触发（避免打爆接口）"));
        }
        Thread worker = new Thread(() -> {
            try {
                runFull(ids);
            } catch (Exception e) {
                log.error("全量刷新订单快照失败", e);
            } finally {
                fullRefreshRunning.set(false);
            }
        }, "order-snapshot-full");
        worker.setDaemon(true);
        worker.start();
        log.warn("订单快照【全量刷新】已在后台启动：{} 个账户，预计 90 分钟左右。"
                + "每拉完一个账户会合并一次，可看日志或 snapshotStore.keyCount() 观察进度。", ids.size());
        return new RefreshResult("FULL", ids.size(), 0, 0, snapshotStore.keyCount(), 0L, true,
                List.of("全量刷新已在后台启动（约 90 分钟），不会阻塞本次调用"));
    }

    /** 全量刷新（同步版，供探针/测试用；生产请用 {@link #refreshFullAsync()}）。 */
    public RefreshResult refreshFullSync() throws Exception {
        long startedAt = System.currentTimeMillis();
        List<Long> ids = allAdvertiserIds();
        int[] stats = runFull(ids);
        return new RefreshResult("FULL", ids.size(), stats[0], stats[1],
                snapshotStore.keyCount(), System.currentTimeMillis() - startedAt, false, List.of());
    }

    /**
     * <b>按需刷新（带节流）</b>：投放流程开头调用 —— 快照够新就不重复拉。
     *
     * <p>⚠️ <b>为什么要节流</b>：老板要求「每次投放前跑一次」，
     * 而 {@code planBuild} 是<b>逐条</b>调的 —— 不做节流就会「每条都拉一次全账户订单」，
     * 几十条组合就是几十次 × 44 秒。</p>
     *
     * <p>节流窗口内直接返回，不调接口。</p>
     *
     * @param maxAgeMinutes 快照「多新算够新」；≤0 表示每次都拉
     * @return 本次是否真的刷新了（{@code false} = 命中节流，没调接口）
     */
    public boolean ensureFresh(int maxAgeMinutes) throws Exception {
        if (maxAgeMinutes > 0 && snapshotStore.isFreshWithin(Duration.ofMinutes(maxAgeMinutes))) {
            log.info("订单快照 {} 分钟内已刷新过，跳过本次增量（节流）", maxAgeMinutes);
            return false;
        }
        refreshIncremental();
        return true;
    }

    /**
     * <b>增量刷新（同步）</b>：拉「最近 N 天」，按 order_id 合并。
     *
     * <p>实测 8 账户 ≈44 秒，可以同步跑在投放流程里。</p>
     */
    public RefreshResult refreshIncremental() throws Exception {
        return refreshIncremental(DEFAULT_LOOKBACK_DAYS);
    }

    /** 增量刷新（可指定回溯天数）。 */
    public RefreshResult refreshIncremental(int lookbackDays) throws Exception {
        int days = Math.max(1, lookbackDays);
        String start = java.time.LocalDate.now().minusDays(days).toString();
        String end = java.time.LocalDate.now().toString();
        long startedAt = System.currentTimeMillis();

        List<Long> ids = allAdvertiserIds();
        List<String> warnings = new ArrayList<>();
        int fetched = 0;
        int added = 0;

        for (Long advertiserId : ids) {
            try {
                List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> orders =
                        fetch(advertiserId, start, end);
                fetched += orders.size();
                added += snapshotStore.merge(aggregate(orders), now());
            } catch (Exception e) {
                // ⚠️ 单个账户失败不中断 —— 但必须留痕，否则「漏拉」会静默累积
                warnings.add("账户 " + advertiserId + " 增量拉取失败：" + e.getMessage());
                log.warn("账户 {} 增量拉取失败: {}", advertiserId, e.getMessage());
            }
        }

        long cost = System.currentTimeMillis() - startedAt;
        log.info("订单快照【增量刷新】完成：{} 个账户，拉取 {} 条，新增 {} 笔，耗时 {}ms",
                ids.size(), fetched, added, cost);
        return new RefreshResult("INCREMENTAL", ids.size(), fetched, added,
                snapshotStore.keyCount(), cost, false, warnings);
    }

    // ==================== 内部 ====================

    /** @return {@code [拉取条数, 新增笔数]} */
    private int[] runFull(List<Long> ids) throws Exception {
        long startedAt = System.currentTimeMillis();

        // ⚠️⚠️ 全量前必须【先清空快照】！
        //    merge 是【并集】—— 而旧快照里的 orderIds 可能是【按旧规则】算的
        //    （比如 N 白名单上线前，FAILED/OFFLINE_AUDIT 的订单也被算进去了）。
        //    不清空的话，并集会把那些脏 orderId 永远留着 → N 偏大 → 误判 ≥3 → 白建计划。
        //    ⚠️ 清空后若中途失败，已拉到的部分会留着，重跑会补全（不会更糟）。
        int before = snapshotStore.keyCount();
        if (before > 0) {
            snapshotStore.clear();
            log.warn("全量刷新前已清空旧快照（{} 个组合）—— 避免旧规则的脏数据被并集留下", before);
        }

        int fetched = 0;
        int added = 0;
        int index = 0;
        for (Long advertiserId : ids) {
            index++;
            try {
                // ⚠️ 不传时间窗 = 拉全量（实测能拉到 404 天历史）
                List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> orders =
                        fetch(advertiserId, null, null);
                fetched += orders.size();
                int thisAdded = snapshotStore.merge(aggregate(orders), now());
                added += thisAdded;
                log.info("全量刷新进度 [{}/{}]：账户 {} 拉到 {} 条，新增 {} 笔，"
                                + "累计 {} 个组合 / {} 笔，已耗时 {}s",
                        index, ids.size(), advertiserId, orders.size(), thisAdded,
                        snapshotStore.keyCount(), snapshotStore.totalOrders(),
                        (System.currentTimeMillis() - startedAt) / 1000);
            } catch (Exception e) {
                log.error("账户 {} 全量拉取失败（继续下一个）: {}", advertiserId, e.getMessage());
            }
        }
        log.info("订单快照【全量刷新】完成：{} 个账户，拉取 {} 条，新增 {} 笔，耗时 {}s",
                ids.size(), fetched, added, (System.currentTimeMillis() - startedAt) / 1000);
        return new int[]{fetched, added};
    }

    private List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> fetch(
            long advertiserId, String startDate, String endDate) throws Exception {
        SuixintuiUniOrderListRequest request = new SuixintuiUniOrderListRequest(advertiserId);
        if (startDate != null) {
            request.orderCreateStartDate(startDate);
        }
        if (endDate != null) {
            request.orderCreateEndDate(endDate);
        }
        // ⚠️ 不设 status 过滤 = 所有状态都拉（老板：「不算状态，有一个就算一笔」）
        return clientFactory.suixintuiOrderService().fetchUniPromotionOrders(request);
    }

    /**
     * 把订单按【抖音号 + 商品 ID】分组。
     *
     * <p>⚠️ <b>不按状态过滤</b> —— 老板明确「有一个就算一笔」。</p>
     */
    static List<OrderSnapshotEntry> aggregate(
            List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> orders) {
        Map<String, OrderSnapshotEntry> byKey = new LinkedHashMap<>();
        Map<String, List<String>> idsByKey = new LinkedHashMap<>();
        Map<String, String> nameByKey = new LinkedHashMap<>();
        Map<String, Integer> blockingByKey = new LinkedHashMap<>();
        Map<String, java.util.Set<String>> statusNamesByKey = new LinkedHashMap<>();
        String at = now();

        for (QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner order : orders) {
            if (order == null || order.getAwemeInfo() == null || order.getProductInfo() == null) {
                continue;
            }
            String aweme = order.getAwemeInfo().getAwemeShowId();
            Long productId = order.getProductInfo().getProductId();
            if (aweme == null || aweme.isBlank() || productId == null) {
                continue;
            }
            String key = aweme.trim() + KEY_SEP + productId;
            // ⚠️ 只有【算 N 的状态】才计入 orderIds（老板 2026-10-08 的白名单）
            //    没付钱的/创建失败的（UNPAID/FAILED/TIMEOUT/UNPAIDCANCEL/OFFLINE_AUDIT）不算一笔
            if (countsTowardN(order.getStatus())) {
                idsByKey.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(String.valueOf(order.getOrderId()));
            }
            if (order.getProductInfo().getProductName() != null) {
                nameByKey.putIfAbsent(key, order.getProductInfo().getProductName());
            }
            // 占用互斥名额的订单数：这类组合建不了新【随心推订单】
            if (isBlocking(order.getStatus())) {
                blockingByKey.merge(key, 1, Integer::sum);
                statusNamesByKey.computeIfAbsent(key, k -> new java.util.LinkedHashSet<>())
                        .add(String.valueOf(order.getStatus()));
            }
        }

        for (Map.Entry<String, List<String>> entry : idsByKey.entrySet()) {
            String[] parts = entry.getKey().split(Pattern.quote(KEY_SEP), 2);
            byKey.put(entry.getKey(), new OrderSnapshotEntry(parts[0], parts[1],
                    nameByKey.getOrDefault(entry.getKey(), ""), entry.getValue(),
                    blockingByKey.getOrDefault(entry.getKey(), 0),
                    statusNamesByKey.containsKey(entry.getKey())
                            ? String.join("/", statusNamesByKey.get(entry.getKey())) : "",
                    at));
        }
        return new ArrayList<>(byKey.values());
    }

    /**
     * 订单是否<b>正在投放中</b>（{@code DELIVERY_OK}）。
     *
     * <p>⚠️ 老板 2026-10-08：创建<b>千川乘方计划</b>时，同一抖音号 × 同一商品维度下
     * <b>无论随心推订单处于什么状态，基本都可以创建成功</b> ——
     * 所以判「是否投放中」<b>不是硬性互斥</b>，只是<b>想让这个随心推跑完再建</b>。</p>
     */
    static boolean isDelivering(
            com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10DataOrderListStatus status) {
        return status == com.bytedance.ads.model
                .QianchuanAwemeUniPromotionOrderGetV10DataOrderListStatus.DELIVERY_OK;
    }

    /**
     * 订单<b>是否计入 N（累计随心推笔数）</b> —— 老板 2026-10-08 定的<b>白名单</b>。
     *
     * <h2>算 N（7 个）</h2>
     * <p>{@code DELIVERY_OK}（投放中）/ {@code FROZEN}（投放终止）/
     * {@code FINISHED} / {@code OVER} / {@code AUDIT}（审核中）/
     * {@code BOOK}（未开播）/ {@code CREATING}（创建中）</p>
     *
     * <h2>不算 N（5 个）</h2>
     * <p>{@code OFFLINE_AUDIT}（线下审核）/ {@code FAILED}（计划创建失败）/
     * {@code TIMEOUT}（未支付超 15 分钟关闭）/ {@code UNPAID}（未支付）/
     * {@code UNPAIDCANCEL}（未支付取消）</p>
     *
     * <p>⚠️ <b>为什么用白名单而不是「剔除 5 个」</b>：接口以后<b>新增状态</b>时，
     * 白名单默认<b>不算</b> → 最多晚建计划；黑名单默认<b>算</b> → 会<b>多算 N</b> →
     * 提前建计划 = <b>白花钱</b>。失效方向上前者便宜得多。</p>
     */
    static boolean countsTowardN(
            com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10DataOrderListStatus status) {
        if (status == null) {
            return false;
        }
        return N_COUNTING_STATUSES.contains(status);
    }

    /**
     * 计入 N 的状态白名单（老板 2026-10-08 定）。
     *
     * <p>⚠️ 接口新增状态时<b>默认不算 N</b>（安全方向：最多晚建计划，不会白花钱）。</p>
     */
    private static final java.util.Set<
            com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10DataOrderListStatus>
            N_COUNTING_STATUSES = java.util.EnumSet.of(
                    com.bytedance.ads.model
                            .QianchuanAwemeUniPromotionOrderGetV10DataOrderListStatus.DELIVERY_OK,
                    com.bytedance.ads.model
                            .QianchuanAwemeUniPromotionOrderGetV10DataOrderListStatus.FROZEN,
                    com.bytedance.ads.model
                            .QianchuanAwemeUniPromotionOrderGetV10DataOrderListStatus.FINISHED,
                    com.bytedance.ads.model
                            .QianchuanAwemeUniPromotionOrderGetV10DataOrderListStatus.OVER,
                    com.bytedance.ads.model
                            .QianchuanAwemeUniPromotionOrderGetV10DataOrderListStatus.AUDIT,
                    com.bytedance.ads.model
                            .QianchuanAwemeUniPromotionOrderGetV10DataOrderListStatus.BOOK,
                    com.bytedance.ads.model
                            .QianchuanAwemeUniPromotionOrderGetV10DataOrderListStatus.CREATING);

    /**
     * 订单是否<b>仍存续（占用互斥名额）</b>—— 这类组合<b>建不了新随心推订单</b>。
     *
     * <h2>🔴 老板 2026-10-07 给的口径</h2>
     * <p>「这些状态下订单仍存续，会触发互斥，<b>无法创建同抖音号 + 同商品的新订单</b>」：</p>
     * <table border="1">
     *   <tr><th>状态</th><th>含义</th></tr>
     *   <tr><td>{@code DELIVERY_OK}</td><td>投放中（核心在途状态）</td></tr>
     *   <tr><td>{@code AUDIT}</td><td>审核中（属于待投放）</td></tr>
     *   <tr><td>{@code CREATING}</td><td>创建中（已支付正在生成计划）</td></tr>
     *   <tr><td>{@code BOOK}</td><td>未开播（直播订单待开播，属于待投放）</td></tr>
     *   <tr><td><b>{@code FROZEN}</b></td><td><b>投放终止 —— 仍占用名额！</b>（最易漏）</td></tr>
     * </table>
     *
     * <p>⚠️ <b>不占用</b>的：{@code OVER} / {@code FINISHED} / {@code FAILED} / {@code TIMEOUT}
     * —— 这些是「已结束」，名额已释放，可以正常建计划。</p>
     *
     * <p>⚠️ 用<b>应答</b>里的 {@code DataOrderListStatus}（不是请求过滤用的
     * {@code FilteringStatus}）—— 两者是不同的类，混用编译不过（踩过）。</p>
     */
    static boolean isBlocking(
            com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10DataOrderListStatus status) {
        if (status == null) {
            return false;
        }
        return BLOCKING_STATUSES.contains(status);
    }

    /**
     * 占用互斥名额的状态（老板 2026-10-07 给的口径）。
     *
     * <p>⚠️ <b>只对「创建随心推订单」生效</b> —— 老板 2026-10-08 明确：
     * 创建<b>千川乘方计划</b>时「同一抖音号 × 同一商品维度下，无论随心推什么状态基本都能建成功」，
     * 所以建计划<b>只判 {@code DELIVERY_OK}</b>（见 {@link #isDelivering}）。</p>
     *
     * <p>⚠️ 要加状态就改这里 —— 但<b>必须先跟老板确认</b>。</p>
     */
    private static final java.util.Set<
            com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10DataOrderListStatus>
            BLOCKING_STATUSES = java.util.EnumSet.of(
                    com.bytedance.ads.model
                            .QianchuanAwemeUniPromotionOrderGetV10DataOrderListStatus.DELIVERY_OK,
                    com.bytedance.ads.model
                            .QianchuanAwemeUniPromotionOrderGetV10DataOrderListStatus.AUDIT,
                    com.bytedance.ads.model
                            .QianchuanAwemeUniPromotionOrderGetV10DataOrderListStatus.CREATING,
                    com.bytedance.ads.model
                            .QianchuanAwemeUniPromotionOrderGetV10DataOrderListStatus.BOOK,
                    com.bytedance.ads.model
                            .QianchuanAwemeUniPromotionOrderGetV10DataOrderListStatus.FROZEN);

    /** 全量刷新是否正在跑（供探针/排障观察）。 */
    public boolean isFullRefreshRunning() {
        return fullRefreshRunning.get();
    }

    private static String now() {
        return OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
