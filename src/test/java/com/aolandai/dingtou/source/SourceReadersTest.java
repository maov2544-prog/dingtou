package com.aolandai.dingtou.source;

import com.aolandai.dingtou.config.SourceProperties;
import com.aolandai.dingtou.dto.sheet.AccountRow;
import com.aolandai.dingtou.dto.sheet.DeliveryRow;
import com.aolandai.dingtou.dto.sheet.ProductRow;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 三个业务表 reader 的单测：用临时 xlsx 造出与真实表一致的结构，验证映射与容错。 */
class SourceReadersTest {

    private static Path writeSheet(Path dir, String fileName, String sheetName, String[][] rows)
            throws IOException {
        Path file = dir.resolve(fileName);
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

    private static SourceProperties props(Path delivery, Path order, Path account, Path product) {
        SourceProperties p = new SourceProperties();
        p.setDeliverySheet(delivery);
        p.setOrderSheet(order);
        p.setAccountDict(account);
        p.setProductDict(product);
        return p;
    }

    // ==================== 定投素材表 ====================

    private static final String[] DELIVERY_HEADER = {
            "日期", "对接商务", "抖音名", "抖音号", "全域账号", "视频发布日期", "产品",
            "视频id", "视频链接", "定向情况", "投放类型", "视频无效", "投放群", "投放状态"};

    private Path writeDelivery(Path dir, String statusHeader) throws IOException {
        return writeSheet(dir, "delivery.xlsx", "9月视频交付", new String[][]{
                statusHeader.equals("投放状态")
                        ? DELIVERY_HEADER
                        : new String[]{"日期", "对接商务", "抖音名", "抖音号", "全域账号", "视频发布日期",
                        "产品", "视频id", "视频链接", "定向情况", "投放类型", "视频无效", "投放群", "投放备注"},
                // 老数据：投放类型非空 → 不用管
                {"9.2", "晓鹏", "大漂亮", "602085838", "官方【孕产-商品】", "9.2", "益生元面霜",
                        "7687", "link-a", "已定向", "全域随心推", "", "", "已投"},
                // 新数据：投放类型空 + 待投放 → 要处理
                {"9.29", "伟熙", "木桐爱分享", "55454664964", "婴童【儿童-商品】", "9.29", "青少年洗发水",
                        "7690", "link-b", "已定向", "", "", "", "待投放"},
                // 待审核 → 还不该投
                {"9.29", "伟熙", "木桐爱分享", "55454664964", "婴童【儿童-商品】", "9.29", "青少年洗发水",
                        "7691", "link-c", "已定向", "", "", "", "待审核"},
                // 投放类型空但状态为空 → 不该投
                {"9.29", "伟熙", "木桐爱分享", "55454664964", "婴童【儿童-商品】", "9.29", "青少年洗发水",
                        "7692", "link-d", "已定向", "", "", "", ""},
        });
    }

    @Test
    @DisplayName("定投素材表：14 列映射正确，M.D 日期原样读出")
    void deliverySheetMapsAllColumns(@TempDir Path dir) throws IOException {
        Path delivery = writeDelivery(dir, "投放状态");
        DeliverySheetReader reader = new DeliverySheetReader(
                props(delivery, dir.resolve("o.xlsx"), dir.resolve("a.xlsx"), dir.resolve("p.xlsx")));

        List<DeliveryRow> rows = reader.readAll();
        assertEquals(4, rows.size());

        DeliveryRow first = rows.get(0);
        assertEquals("9.2", first.deliveryDate());
        assertEquals("晓鹏", first.business());
        assertEquals("602085838", first.awemeShowId());
        assertEquals("益生元面霜", first.productName());
        assertEquals("全域随心推", first.deliveryType());
        assertEquals(2, first.rowNumber(), "Excel 行号应含表头，从 2 开始");
        assertTrue(first.alreadyInvested());
        assertFalse(first.awaitingDelivery());
    }

    @Test
    @DisplayName("定投素材表：只有「投放类型空 + 待投放」的行才算要处理")
    void onlyPendingDeliveryRowsAreSelected(@TempDir Path dir) throws IOException {
        Path delivery = writeDelivery(dir, "投放状态");
        DeliverySheetReader reader = new DeliverySheetReader(
                props(delivery, dir.resolve("o.xlsx"), dir.resolve("a.xlsx"), dir.resolve("p.xlsx")));

        List<DeliveryRow> awaiting = reader.readAwaitingDelivery();
        assertEquals(1, awaiting.size());
        assertEquals("55454664964", awaiting.get(0).awemeShowId());
        assertEquals("青少年洗发水", awaiting.get(0).productName());
    }

    @Test
    @DisplayName("定投素材表：列名还是旧名「投放备注」时也能读（改名滞后不会崩）")
    void deliverySheetToleratesLegacyStatusColumn(@TempDir Path dir) throws IOException {
        Path delivery = writeDelivery(dir, "投放备注");
        DeliverySheetReader reader = new DeliverySheetReader(
                props(delivery, dir.resolve("o.xlsx"), dir.resolve("a.xlsx"), dir.resolve("p.xlsx")));

        List<DeliveryRow> rows = reader.readAll();
        assertEquals("已投", rows.get(0).deliveryStatus());
        assertEquals(1, reader.readAwaitingDelivery().size());
    }

    @Test
    @DisplayName("定投素材表：缺「抖音号」或「产品」列 → 直接报错，不返回一堆空对象")
    void deliverySheetRequiresKeyColumns(@TempDir Path dir) throws IOException {
        Path delivery = writeSheet(dir, "bad.xlsx", "S", new String[][]{
                {"日期", "对接商务"}, {"9.2", "晓鹏"}});
        DeliverySheetReader reader = new DeliverySheetReader(
                props(delivery, dir.resolve("o.xlsx"), dir.resolve("a.xlsx"), dir.resolve("p.xlsx")));

        IllegalStateException e = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, reader::readAll);
        assertTrue(e.getMessage().contains("抖音号"), e.getMessage());
    }

    // ==================== 账户字典 ====================

    @Test
    @DisplayName("账户字典：名称 / 16 位 ID / 店铺，ID 转 long 无损")
    void accountDictReads(@TempDir Path dir) throws IOException {
        Path account = writeSheet(dir, "account.xlsx", "Sheet1", new String[][]{
                {"投放账户名称", "投放账户ID", "投放账户所属店铺"},
                {"官方【孕产-商品】", "1771298760893511", "官方店"},
                {"婴童【儿童-商品】", "1778533513439244", "婴童店"},
                {"没ID的账户", "", "官方店"}});

        AccountDictReader reader = new AccountDictReader(
                props(dir.resolve("d.xlsx"), dir.resolve("o.xlsx"), account, dir.resolve("p.xlsx")));

        List<AccountRow> rows = reader.readAll();
        assertEquals(2, rows.size(), "没有账户 ID 的行应被跳过");
        assertEquals("官方【孕产-商品】", rows.get(0).name());
        assertEquals(1771298760893511L, rows.get(0).advertiserIdAsLong());
        assertEquals("婴童店", rows.get(1).shop());
        assertTrue(reader.advertiserIds().contains(1778533513439244L));
    }

    // ==================== 商品映射表 ====================

    @Test
    @DisplayName("商品映射表：商品 ID → 名称；空 ID 行跳过；次链后缀可归一")
    void productDictReads(@TempDir Path dir) throws IOException {
        Path product = writeSheet(dir, "product.xlsx", "Sheet1", new String[][]{
                {"商品id", "投放商品", "店铺"},
                {"3625005696866738289", "山茶花套装", "婴童店"},
                {"3700527847295811597", "山茶花套装（次链）", "婴童店"},
                {"3700528515221946525", "山茶花洁面（次链)", "婴童店"},
                {"", "猫爪润唇膏", "专卖店"}});

        ProductDictReader reader = new ProductDictReader(
                props(dir.resolve("d.xlsx"), dir.resolve("o.xlsx"), dir.resolve("a.xlsx"), product));

        List<ProductRow> rows = reader.readAll();
        assertEquals(4, rows.size());
        assertFalse(rows.get(3).hasProductId(), "空 ID 行也要读出来，交由调用方决定");

        Map<String, String> map = reader.productIdToName();
        assertEquals(3, map.size(), "空 ID 行不进映射");
        assertEquals("山茶花套装", map.get("3625005696866738289"));
        assertEquals("山茶花套装", reader.readAll().get(1).normalizedName());
        assertEquals("山茶花洁面", reader.readAll().get(2).normalizedName(), "半角括号变体也要能归一");
        assertTrue(reader.nameOfProductId("不存在").isEmpty());
        assertTrue(reader.nameOfProductId(null).isEmpty());
    }

    // ==================== 真实数据（文件在才跑） ====================

    @Test
    @DisplayName("真实数据核对：4 张表都能读，行数与实测一致")
    void realFilesReadIfPresent() throws IOException {
        Path base = Path.of("D:/rpa_workspace/shuixitui1");
        Path delivery = base.resolve("26年视频交付表.xlsx");
        Path account = base.resolve("投放账户信息表.xlsx");
        Path product = base.resolve("商品 ID 映射表(1).xlsx");
        assumeTrue(Files.exists(delivery) && Files.exists(account) && Files.exists(product),
                "真实业务表不在本机，跳过");

        SourceProperties props = props(delivery, base.resolve("历史随心推全量订单总表.xlsx"),
                account, product);

        assertEquals(4992, new DeliverySheetReader(props).readAll().size(),
                "定投素材表应为 4992 行");
        assertEquals(8, new AccountDictReader(props).readAll().size(),
                "账户字典应为 8 个账户");
        assertEquals(103, new ProductDictReader(props).readAll().size(),
                "商品映射表应为 103 行");
    }
}
