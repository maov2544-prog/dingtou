package com.aolandai.dingtou.service.aggregate;

import com.aolandai.dingtou.config.AggregateProperties;
import com.aolandai.dingtou.config.SourceProperties;
import com.aolandai.dingtou.config.StoreProperties;
import com.aolandai.dingtou.dto.CountResult;
import com.aolandai.dingtou.exception.BizException;
import com.aolandai.dingtou.source.DeliverySheetReader;
import com.aolandai.dingtou.source.ProductDictReader;
import com.aolandai.dingtou.store.OrderSnapshotEntry;
import com.aolandai.dingtou.store.OrderSnapshotStore;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefaultCumulativeCountService} 单测。
 *
 * <h2>⚠️ 口径已变（老板 2026-10-07）：<b>不预筛，只认随心推订单数</b></h2>
 * <ul>
 *   <li>快照可用 → 用快照数（权威）；</li>
 *   <li>快照<b>不可用</b> → <b>抛 {@link BizException}</b>（宁可报错也不拿近似值定档 ——
 *       猜错会白建千川计划）；</li>
 *   <li>商品名映射不到唯一 ID → 同样抛错。</li>
 * </ul>
 */
class DefaultCumulativeCountServiceTest {

    private static final String[] HEADER = {
            "日期", "对接商务", "抖音名", "抖音号", "全域账号", "视频发布日期", "产品",
            "视频id", "视频链接", "定向情况", "投放类型", "视频无效", "投放群", "投放状态"};

    // ==================== 造数据 ====================

    private static Path writeSheet(Path file, String sheetName, String[][] rows) throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(sheetName);
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    row.createCell(c).setCellValue(rows[r][c]);
                }
            }
            try (OutputStream out = Files.newOutputStream(file)) {
                workbook.write(out);
            }
        }
        return file;
    }

    /** 素材表：益生元面霜 3 行（2 行已投放）、生姜洗护 1 行。 */
    private static Path writeDelivery(Path dir) throws IOException {
        List<String[]> rows = new ArrayList<>();
        rows.add(HEADER);
        rows.add(row("602085838", "益生元面霜", "全域随心推"));
        rows.add(row("602085838", "益生元面霜", "全域随心推"));
        rows.add(row("602085838", "益生元面霜", ""));
        rows.add(row("602085838", "生姜洗护", "全域随心推"));
        return writeSheet(dir.resolve("delivery.xlsx"), "9月视频交付",
                rows.toArray(new String[0][]));
    }

    private static String[] row(String aweme, String product, String deliveryType) {
        return new String[]{"9.2", "晓鹏", "某达人", aweme, "官方【孕产-商品】", "9.2", product,
                "7687", "link", "已定向", deliveryType, "", "", "已投"};
    }

    private static Path writeProductDict(Path dir) throws IOException {
        return writeSheet(dir.resolve("product.xlsx"), "Sheet1", new String[][]{
                {"商品id", "投放商品", "店铺"},
                {"3625005696866738289", "益生元面霜", "官方店"},
                {"3625005696866738290", "生姜洗护", "官方店"},
                {"3625005696866738291", "紫苏套装", "官方店"}});
    }

    /** 造快照记录：{@code count} 笔订单，其中 {@code blocking} 笔<b>占用互斥名额</b>。 */
    private static OrderSnapshotEntry snap(String aweme, String productId, int count,
                                           int blocking) {
        List<String> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add("oid-" + aweme + "-" + i);
        }
        return new OrderSnapshotEntry(aweme, productId, "x", ids, blocking,
                blocking > 0 ? "DELIVERY_OK" : "", OffsetDateTime.now().toString());
    }

    // ==================== 装配 ====================

    private record Fixture(DefaultCumulativeCountService service,
                           OrderSnapshotStore snapshotStore) {
    }

    private static Fixture fixture(Path dir) throws IOException {
        StoreProperties store = new StoreProperties();
        store.setDataDir(dir);

        SourceProperties source = new SourceProperties();
        source.setDeliverySheet(writeDelivery(dir));
        source.setOrderSheet(dir.resolve("order.xlsx"));
        source.setAccountDict(dir.resolve("account.xlsx"));
        source.setProductDict(writeProductDict(dir));

        OrderSnapshotStore snapshot = new OrderSnapshotStore(store);
        DefaultCumulativeCountService service = new DefaultCumulativeCountService(
                snapshot, new ProductDictReader(source), new DeliverySheetReader(source),
                new AggregateProperties(), source);
        return new Fixture(service, snapshot);
    }

    // ==================== 主路径：只认订单数 ====================

    @Test
    @DisplayName("★ 快照新鲜且命中 → 用快照数（权威来源），素材表行数不参与")
    void freshSnapshotHitIsAuthoritative(@TempDir Path dir) throws IOException {
        Fixture f = fixture(dir);
        f.snapshotStore().replaceAll(List.of(snap("602085838", "3625005696866738289", 5, 0)));

        CountResult r = f.service().count("602085838", "益生元面霜");

        assertEquals(5, r.count(), "⚠️ 素材表里是 3 行，但口径已改成只认订单数（5 笔）");
        assertEquals(CountSource.ORDER_SNAPSHOT, r.source());
        assertTrue(r.authoritative());
        assertEquals("3625005696866738289", r.productId());
    }

    @Test
    @DisplayName("★ 快照里没有该组合 → 0 笔（仍是权威来源，不是「查不到」）")
    void snapshotMissIsZero(@TempDir Path dir) throws IOException {
        Fixture f = fixture(dir);
        f.snapshotStore().replaceAll(List.of(snap("999999", "111", 3, 0)));

        CountResult r = f.service().count("602085838", "益生元面霜");

        assertEquals(0, r.count());
        assertEquals(CountSource.ORDER_SNAPSHOT, r.source());
    }

    @Test
    @DisplayName("★★ 快照不新鲜 → 抛错（【不】回退预筛 —— 拿近似值定档会白建计划）")
    void staleSnapshotThrowsInsteadOfPrescreen(@TempDir Path dir) throws IOException {
        Fixture f = fixture(dir);
        OrderSnapshotEntry old = new OrderSnapshotEntry("602085838", "3625005696866738289", "x",
                List.of("a", "b", "c"), 0, "", OffsetDateTime.now().minusDays(3).toString());
        f.snapshotStore().replaceAll(List.of(old));

        BizException e = assertThrows(BizException.class,
                () -> f.service().count("602085838", "益生元面霜"));

        assertTrue(e.getMessage().contains("订单快照不可用"), e.getMessage());
        assertTrue(e.getMessage().contains("refreshOrders"), "要告诉调用方怎么修：" + e.getMessage());
    }

    @Test
    @DisplayName("★★ 空快照 → 也抛错（空快照无法区分「没拉过」和「真 0 笔」）")
    void emptySnapshotThrows(@TempDir Path dir) throws IOException {
        Fixture f = fixture(dir);
        f.snapshotStore().clear();

        BizException e = assertThrows(BizException.class,
                () -> f.service().count("602085838", "益生元面霜"));
        assertTrue(e.getMessage().contains("订单快照不可用"), e.getMessage());
    }

    @Test
    @DisplayName("★ 商品名映射不到唯一 ID → 抛错（不再回退预筛）")
    void unmappedProductThrows(@TempDir Path dir) throws IOException {
        Fixture f = fixture(dir);
        f.snapshotStore().replaceAll(List.of(snap("602085838", "3625005696866738289", 5, 0)));

        BizException e = assertThrows(BizException.class,
                () -> f.service().count("602085838", "压根不存在的商品"));
        assertTrue(e.getMessage().contains("映射不到唯一商品 ID"), e.getMessage());
    }

    // ==================== 「投放中」标记 ====================

    @Test
    @DisplayName("★ 该组合有订单占用互斥名额 → blocked=true（调用方要跳过，等它们结束）")
    void blockedFlagIsExposed(@TempDir Path dir) throws IOException {
        Fixture f = fixture(dir);
        f.snapshotStore().replaceAll(List.of(snap("602085838", "3625005696866738289", 4, 2)));

        CountResult r = f.service().count("602085838", "益生元面霜");

        assertEquals(4, r.count());
        assertTrue(r.blocked(), "有 2 笔占用互斥名额，必须标记出来");
        assertEquals("DELIVERY_OK", r.blockingStatuses(), "要带上具体状态名，便于排查");
        assertTrue(r.detail().contains("仍存续"), r.detail());
    }

    @Test
    @DisplayName("没有占用名额的订单 → blocked=false")
    void notBlocked(@TempDir Path dir) throws IOException {
        Fixture f = fixture(dir);
        f.snapshotStore().replaceAll(List.of(snap("602085838", "3625005696866738289", 4, 0)));

        assertFalse(f.service().count("602085838", "益生元面霜").blocked());
    }

    // ==================== 边界 ====================

    @Test
    @DisplayName("抖音号或商品名为空 → source=NONE，count=0，不抛异常")
    void blankInputReturnsNone(@TempDir Path dir) throws IOException {
        Fixture f = fixture(dir);
        f.snapshotStore().replaceAll(List.of(snap("a", "1", 1, 0)));

        assertEquals(CountSource.NONE, f.service().count("", "益生元面霜").source());
        assertEquals(CountSource.NONE, f.service().count("602085838", "").source());
        assertEquals(CountSource.NONE, f.service().count(null, null).source());
    }

    @Test
    @DisplayName("次链商品名（带「（次链）」后缀）也能映射到商品 ID")
    void secondaryChainNameResolves(@TempDir Path dir) throws IOException {
        StoreProperties store = new StoreProperties();
        store.setDataDir(dir);
        SourceProperties source = new SourceProperties();
        source.setDeliverySheet(writeDelivery(dir));
        source.setOrderSheet(dir.resolve("order.xlsx"));
        source.setAccountDict(dir.resolve("account.xlsx"));
        source.setProductDict(writeSheet(dir.resolve("p2.xlsx"), "Sheet1", new String[][]{
                {"商品id", "投放商品", "店铺"},
                {"111", "山茶花套装（次链）", "官方店"}}));

        OrderSnapshotStore snapshot = new OrderSnapshotStore(store);
        DefaultCumulativeCountService service = new DefaultCumulativeCountService(
                snapshot, new ProductDictReader(source), new DeliverySheetReader(source),
                new AggregateProperties(), source);
        snapshot.replaceAll(List.of(snap("602085838", "111", 7, 0)));

        // 素材表里写的是不带后缀的短名
        assertEquals(7, service.count("602085838", "山茶花套装").count());
    }

    @Test
    @DisplayName("★ 刷新快照后，同一个查询读到新值（端到端：replaceAll → count）")
    void refreshedSnapshotIsReadable(@TempDir Path dir) throws IOException {
        Fixture f = fixture(dir);
        f.snapshotStore().replaceAll(List.of(snap("602085838", "3625005696866738289", 2, 0)));
        assertEquals(2, f.service().count("602085838", "益生元面霜").count());

        f.snapshotStore().replaceAll(List.of(snap("602085838", "3625005696866738289", 9, 0)));
        assertEquals(9, f.service().count("602085838", "益生元面霜").count());
    }
}
