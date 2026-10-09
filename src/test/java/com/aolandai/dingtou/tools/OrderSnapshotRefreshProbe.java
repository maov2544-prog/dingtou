package com.aolandai.dingtou.tools;

import com.aolandai.dingtou.DingtouApplication;
import com.aolandai.dingtou.service.qianchuan.OrderSnapshotService;
import com.aolandai.dingtou.store.OrderSnapshotEntry;
import com.aolandai.dingtou.store.OrderSnapshotStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.util.Comparator;
import java.util.Optional;

/**
 * 手工探针：跑一次<b>订单快照增量刷新</b>，并打印快照内容（<b>只读接口，不花钱</b>）。
 *
 * <pre>
 * java -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 \
 *   -cp "target/test-classes;target/classes;&lt;依赖&gt;" \
 *   com.aolandai.dingtou.tools.OrderSnapshotRefreshProbe [--full] [--days=7]
 * </pre>
 *
 * <p>默认<b>增量</b>（拉最近 7 天，实测约 15 秒）。
 * 加 {@code --full} 走全量（<b>约 90 分钟</b>，8 个账户）—— 慎用，建议走服务里的异步接口。</p>
 *
 * <p>⚠️ 手工探针，不进自动化测试（surefire 已排除 {@code **&#47;tools/**}）。</p>
 */
public final class OrderSnapshotRefreshProbe {

    private static final Logger log = LoggerFactory.getLogger(OrderSnapshotRefreshProbe.class);

    private static final String PROBE_AUTH_TOKEN = "probe-only-not-a-real-token";

    private OrderSnapshotRefreshProbe() {
    }

    public static void main(String[] args) throws Exception {
        boolean full = hasFlag(args, "--full");
        int days = (int) longFlag(args, "--days", OrderSnapshotService.DEFAULT_LOOKBACK_DAYS);

        log.info("========== OrderSnapshotRefreshProbe 开始（只读，不花钱）==========");
        log.info("模式 = {}，回溯天数 = {}", full ? "全量" : "增量", days);

        try (var context = new SpringApplicationBuilder(DingtouApplication.class)
                .web(WebApplicationType.NONE)
                .run("--dingtou.auth-token=" + PROBE_AUTH_TOKEN, "--server.port=0")) {

            OrderSnapshotService service = context.getBean(OrderSnapshotService.class);
            OrderSnapshotStore store = context.getBean(OrderSnapshotStore.class);

            if (full) {
                log.warn("⚠️ 全量模式会跑约 90 分钟（8 个账户）。本探针是同步版，请耐心等待。");
                log.info("结果: {}", service.refreshFullSync());
            } else {
                log.info("结果: {}", service.refreshIncremental(days));
            }

            report(store);
        }
        log.info("========== OrderSnapshotRefreshProbe 结束 ==========");
    }

    private static void report(OrderSnapshotStore store) {
        log.info("========== 快照内容 ==========");
        log.info("组合数      : {}", store.keyCount());
        log.info("订单总笔数  : {}", store.totalOrders());
        log.info("刷新时间    : {}", store.lastRefreshedAt().orElse("（空）"));
        log.info("是否新鲜    : {}", store.isFresh());

        log.info("--- N 分布 ---");
        int[] buckets = new int[4];
        int blockingCombos = 0;
        for (OrderSnapshotEntry e : store.all()) {
            int n = e.orderCount();
            if (n <= 1) {
                buckets[0]++;
            } else if (n == 2) {
                buckets[1]++;
            } else if (n == 3) {
                buckets[2]++;
            } else {
                buckets[3]++;
            }
            if (e.hasBlockingOrder()) {
                blockingCombos++;
            }
        }
        log.info("N=1 : {}", buckets[0]);
        log.info("N=2 : {}", buckets[1]);
        log.info("N=3 : {}", buckets[2]);
        log.info("N>3 : {}", buckets[3]);
        log.info("N>=3（会转千川）: {}", buckets[2] + buckets[3]);
        log.info("有订单仍存续（占用互斥名额）的组合: {}（状态：{}）", blockingCombos,
                store.all().stream().filter(OrderSnapshotEntry::hasBlockingOrder)
                        .map(OrderSnapshotEntry::blockingStatuses).distinct().limit(8)
                        .collect(java.util.stream.Collectors.joining(" | ")));

        log.info("--- 前 10 条（按笔数降序）---");
        store.all().stream()
                .sorted(Comparator.comparingInt(OrderSnapshotEntry::orderCount).reversed())
                .limit(10)
                .forEach(e -> log.info("    {} + {} → {} 笔{}{}",
                        e.awemeShowId(), e.productName().isEmpty() ? e.productId() : e.productName(),
                        e.orderCount(),
                        e.hasBlockingOrder() ? ("（占用：" + e.blockingStatuses() + "）") : "",
                        e.orderIds().size() != e.orderCount() ? " ⚠️ orderIds 不一致" : ""));

        Optional<OrderSnapshotEntry> first = store.all().stream().findFirst();
        first.ifPresent(e -> log.info("样例 orderIds: {}", e.orderIds()));
    }

    private static boolean hasFlag(String[] args, String flag) {
        if (args == null) {
            return false;
        }
        for (String a : args) {
            if (flag.equals(a)) {
                return true;
            }
        }
        return false;
    }

    private static long longFlag(String[] args, String flag, long fallback) {
        if (args == null) {
            return fallback;
        }
        for (String a : args) {
            if (a != null && a.startsWith(flag + "=")) {
                try {
                    return Long.parseLong(a.substring(flag.length() + 1).trim());
                } catch (NumberFormatException e) {
                    return fallback;
                }
            }
        }
        return fallback;
    }
}
