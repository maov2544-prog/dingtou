package com.aolandai.dingtou.store;

import com.aolandai.dingtou.config.StoreProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 随心推订单快照存储单测。 */
class OrderSnapshotStoreTest {

    private static StoreProperties props(Path dir) {
        StoreProperties props = new StoreProperties();
        props.setDataDir(dir);
        return props;
    }

    /** 造一条有 {@code count} 笔订单的快照记录（order_id 用合成 ID）。 */
    private static OrderSnapshotEntry entry(String aweme, String productId, String name,
                                            int count, String refreshedAt) {
        return new OrderSnapshotEntry(aweme, productId, name, orderIds(count), 0, "", refreshedAt);
    }

    /** 造 {@code count} 个合成 order_id。 */
    static java.util.List<String> orderIds(int count) {
        java.util.List<String> ids = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add("oid-" + i);
        }
        return ids;
    }

    private static String now() {
        return OffsetDateTime.now().toString();
    }

    // ==================== 基本读写 ====================

    @Test
    @DisplayName("整表覆盖后可按【抖音号+商品ID】取笔数")
    void replaceAndRead(@TempDir Path dir) throws IOException {
        OrderSnapshotStore store = new OrderSnapshotStore(props(dir));
        store.replaceAll(List.of(
                entry("602085838", "3625005696866738289", "山茶花套装", 3, now()),
                entry("602085838", "3740964015300542791", "青少年洁面泡泡", 7, now()),
                entry("789987wss", "3625005696866738289", "山茶花套装", 1, now())));

        assertEquals(3, store.keyCount());
        assertEquals(11, store.totalOrders());
        assertEquals(3, store.countOf("602085838", "3625005696866738289"));
        assertEquals(7, store.countOf("602085838", "3740964015300542791"));
        assertEquals(1, store.countOf("789987wss", "3625005696866738289"));
    }

    @Test
    @DisplayName("查不到的组合返回 0 笔（语义：快照里没有，不等于真实世界一定 0 笔）")
    void unknownCombinationReturnsZero(@TempDir Path dir) throws IOException {
        OrderSnapshotStore store = new OrderSnapshotStore(props(dir));
        store.replaceAll(List.of(entry("602085838", "111", "x", 5, now())));

        assertEquals(0, store.countOf("不存在的号", "111"));
        assertEquals(0, store.countOf("602085838", "不存在的商品"));
        assertTrue(store.find(null, "111").isEmpty());
        assertTrue(store.find("602085838", "  ").isEmpty());
    }

    @Test
    @DisplayName("刷新是整表覆盖，不是追加（订单状态会变，旧计数不能留下）")
    void refreshOverwritesInsteadOfAppending(@TempDir Path dir) throws IOException {
        OrderSnapshotStore store = new OrderSnapshotStore(props(dir));
        store.replaceAll(List.of(
                entry("a", "1", "旧商品", 9, now()),
                entry("b", "2", "要被删掉的", 4, now())));
        assertEquals(2, store.keyCount());

        store.replaceAll(List.of(entry("a", "1", "新商品", 2, now())));

        assertEquals(1, store.keyCount(), "旧组合应被清掉");
        assertEquals(2, store.countOf("a", "1"));
        assertEquals(0, store.countOf("b", "2"));
    }

    @Test
    @DisplayName("clear 清空快照")
    void clearWorks(@TempDir Path dir) throws IOException {
        OrderSnapshotStore store = new OrderSnapshotStore(props(dir));
        store.replaceAll(List.of(entry("a", "1", "x", 1, now())));
        store.clear();
        assertEquals(0, store.keyCount());
        assertEquals(0, store.totalOrders());
    }

    // ==================== 新鲜度 ====================

    @Test
    @DisplayName("空快照一律视为不新鲜（无法区分「没拉过」和「拉了确实 0 笔」）")
    void emptySnapshotIsNeverFresh(@TempDir Path dir) throws IOException {
        OrderSnapshotStore store = new OrderSnapshotStore(props(dir));
        assertFalse(store.isFresh());
        assertTrue(store.lastRefreshedAt().isEmpty());
    }

    @Test
    @DisplayName("刚刷过的快照是新鲜的；超过 TTL 就不新鲜")
    void freshnessFollowsTtl(@TempDir Path dir) throws IOException {
        StoreProperties props = props(dir);
        props.setOrderSnapshotTtlHours(24);

        OrderSnapshotStore store = new OrderSnapshotStore(props);
        store.replaceAll(List.of(entry("a", "1", "x", 1, now())));
        assertTrue(store.isFresh(), "刚刷新应新鲜");

        store.replaceAll(List.of(
                entry("a", "1", "x", 1, OffsetDateTime.now().minusHours(25).toString())));
        assertFalse(store.isFresh(), "超过 24 小时应不新鲜");

        store.replaceAll(List.of(
                entry("a", "1", "x", 1, OffsetDateTime.now().minusHours(1).toString())));
        assertTrue(store.isFresh());
    }

    @Test
    @DisplayName("时间戳解析不了时按不新鲜处理，不抛异常")
    void unparsableTimestampIsStale(@TempDir Path dir) throws IOException {
        OrderSnapshotStore store = new OrderSnapshotStore(props(dir));
        store.replaceAll(List.of(entry("a", "1", "x", 1, "不是时间")));
        assertFalse(store.isFresh());
    }

    @Test
    @DisplayName("lastRefreshedAt 取最新的一条")
    void lastRefreshedAtTakesLatest(@TempDir Path dir) throws IOException {
        OrderSnapshotStore store = new OrderSnapshotStore(props(dir));
        String older = OffsetDateTime.now().minusDays(2).toString();
        String newer = OffsetDateTime.now().toString();
        store.replaceAll(List.of(
                entry("a", "1", "x", 1, older),
                entry("b", "2", "y", 1, newer)));

        assertEquals(newer, store.lastRefreshedAt().orElseThrow());
    }

    // ==================== 落盘与恢复 ====================

    @Test
    @DisplayName("快照跨实例恢复（模拟服务重启）")
    void snapshotSurvivesRestart(@TempDir Path dir) throws IOException {
        new OrderSnapshotStore(props(dir))
                .replaceAll(List.of(entry("602085838", "111", "面霜", 4, now())));

        OrderSnapshotStore reloaded = new OrderSnapshotStore(props(dir));
        assertEquals(1, reloaded.keyCount());
        assertEquals(4, reloaded.countOf("602085838", "111"));
        assertEquals("面霜", reloaded.find("602085838", "111").orElseThrow().productName());
    }

    @Test
    @DisplayName("⚠️ 快照文件损坏 → 拒绝启动，不静默跳过坏行")
    void corruptFileRefusesToStart(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("order-snapshot.jsonl");
        Files.writeString(file, "{这不是合法JSON}\n", StandardCharsets.UTF_8);

        IOException e = assertThrows(IOException.class, () -> new OrderSnapshotStore(props(dir)));
        assertTrue(e.getMessage().contains("损坏"), e.getMessage());
        assertTrue(e.getMessage().contains("拒绝启动"), e.getMessage());
    }

    @Test
    @DisplayName("空行被忽略，不影响加载")
    void blankLinesAreIgnored(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("order-snapshot.jsonl");
        Files.writeString(file,
                "\n{\"awemeShowId\":\"a\",\"productId\":\"1\",\"productName\":\"x\","
                        + "\"orderIds\":[\"o1\",\"o2\"],\"blockingCount\":0,\"blockingStatuses\":\"\","
                        + "\"refreshedAt\":\"" + now() + "\"}\n\n",
                StandardCharsets.UTF_8);

        assertEquals(2, new OrderSnapshotStore(props(dir)).countOf("a", "1"));
    }

    // ==================== OrderKey ====================

    @Test
    @DisplayName("OrderKey 拒绝空值，并自动 trim")
    void orderKeyValidates(@TempDir Path dir) {
        assertThrows(IllegalArgumentException.class, () -> new OrderKey(null, "1"));
        assertThrows(IllegalArgumentException.class, () -> new OrderKey("  ", "1"));
        assertThrows(IllegalArgumentException.class, () -> new OrderKey("a", null));
        assertThrows(IllegalArgumentException.class, () -> new OrderKey("a", "  "));

        OrderKey key = new OrderKey(" a ", " 1 ");
        assertEquals("a", key.awemeShowId());
        assertEquals("1", key.productId());
    }
}
