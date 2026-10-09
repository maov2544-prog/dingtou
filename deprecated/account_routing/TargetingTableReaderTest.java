package com.aolandai.dingtou.source;

import com.aolandai.dingtou.config.SourceProperties;
import com.aolandai.dingtou.dto.sheet.TargetingRow;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TargetingTableReader} 单测：在临时目录造一张同结构的表（3 行复合表头 + 合并单元格），
 * 验证解析逻辑正确。<b>绝不读真实业务表</b>。
 */
class TargetingTableReaderTest {

    @TempDir
    Path tempDir;

    private Path sheetFile;
    private TargetingTableReader reader;

    @BeforeEach
    void setUp() throws IOException {
        sheetFile = tempDir.resolve("targeting.xlsx");
        buildSheet(sheetFile);
        SourceProperties props = new SourceProperties();
        props.setTargetingSheet(sheetFile);
        reader = new TargetingTableReader(props);
    }

    @Test
    @DisplayName("readAll：解析出全部记录，合并单元格展开正确")
    void readAllParsesRows() throws IOException {
        List<TargetingRow> rows = reader.readAll();
        // 数据行 2 行 × 每行 2 个投放商品列 = 4 条记录（第 1 行空 B 列不产记录）
        assertEquals(4, rows.size(), "应解析出 4 条记录: " + rows);

        // 第 1 个数据行（aweme_1）
        assertEquals("aweme_1", rows.get(0).awemeShowId());
        assertEquals("官方【孕产-商品】", rows.get(0).accountName());
        assertEquals("燕窝气垫", rows.get(0).productText());

        assertEquals("aweme_1", rows.get(1).awemeShowId());
        assertEquals("官方【青少年-商品】", rows.get(1).accountName());
        assertEquals("橄榄油", rows.get(1).productText());

        // 第 2 个数据行（aweme_2）
        assertEquals("aweme_2", rows.get(2).awemeShowId());
        assertEquals("官方【孕产-商品】", rows.get(2).accountName());
        assertEquals("山茶花面膜", rows.get(2).productText());

        assertEquals("aweme_2", rows.get(3).awemeShowId());
        assertEquals("婴童【儿童-商品】", rows.get(3).accountName());
        assertEquals("猫爪润唇膏", rows.get(3).productText());
    }

    @Test
    @DisplayName("findAccounts：整体匹配命中")
    void findAccountsExactMatch() throws IOException {
        Optional<List<String>> accounts = reader.findAccounts("aweme_1", "燕窝气垫");
        assertTrue(accounts.isPresent());
        assertEquals(List.of("官方【孕产-商品】"), accounts.get());
    }

    @Test
    @DisplayName("findAccounts：多商品串拆分命中")
    void findAccountsSplitMatch() throws IOException {
        Optional<List<String>> accounts = reader.findAccounts("aweme_1", "橄榄油");
        assertTrue(accounts.isPresent());
        assertEquals(List.of("官方【青少年-商品】"), accounts.get());
    }

    @Test
    @DisplayName("findAccounts：同一达人命中多个账户")
    void findAccountsMultipleAccounts() throws IOException {
        Optional<List<String>> accounts = reader.findAccounts("aweme_2", "山茶花面膜");
        assertTrue(accounts.isPresent());
        // 山茶花面膜只在官方【孕产-商品】下
        assertEquals(List.of("官方【孕产-商品】"), accounts.get());

        Optional<List<String>> accounts2 = reader.findAccounts("aweme_2", "猫爪润唇膏");
        assertTrue(accounts2.isPresent());
        assertEquals(List.of("婴童【儿童-商品】"), accounts2.get());
    }

    @Test
    @DisplayName("findAccounts：未命中返回 empty")
    void findAccountsMiss() throws IOException {
        assertFalse(reader.findAccounts("aweme_1", "不存在的商品").isPresent());
        assertFalse(reader.findAccounts("不存在的抖音号", "燕窝气垫").isPresent());
    }

    @Test
    @DisplayName("findAccounts：空参数返回 empty")
    void findAccountsEmptyParams() throws IOException {
        assertFalse(reader.findAccounts("", "燕窝气垫").isPresent());
        assertFalse(reader.findAccounts("aweme_1", "  ").isPresent());
        assertFalse(reader.findAccounts(null, "燕窝气垫").isPresent());
    }

    @Test
    @DisplayName("size / comboCount：诊断计数正确")
    void diagnosticCounts() throws IOException {
        assertEquals(2, reader.size(), "2 个不同抖音号");
        assertEquals(4, reader.comboCount(), "4 个（抖音号,单商品）组合");
    }

    // ==================== 造表工具 ====================

    /**
     * 造一张同结构的 3 行复合表头表：
     * <pre>
     * 第 1 行：店铺分组（忽略）
     * 第 2 行：A2:B2=官方【孕产-商品】 C2:D2=官方【青少年-商品】 E2:F2=婴童【儿童-商品】
     * 第 3 行：A3=投放商品 B3=授权状态 C3=投放商品 D3=授权状态 …
     * 第 4 行：A4=燕窝气垫 C4=橄榄油 R4=抖音名：达人1\n抖音号：aweme_1
     * 第 5 行：A5=山茶花面膜 E5=猫爪润唇膏 R5=抖音名：达人2\n抖音号：aweme_2
     * </pre>
     */
    private static void buildSheet(Path file) throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Sheet1");

            // 第 1 行：店铺分组
            Row r1 = sheet.createRow(0);
            r1.createCell(0).setCellValue("官方店铺");
            r1.createCell(2).setCellValue("婴童店");

            // 第 2 行：账户名（合并单元格 A2:B2, C2:D2, E2:F2）
            Row r2 = sheet.createRow(1);
            r2.createCell(0).setCellValue("官方【孕产-商品】");
            r2.createCell(2).setCellValue("官方【青少年-商品】");
            r2.createCell(4).setCellValue("婴童【儿童-商品】");
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(1, 1, 0, 1));
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(1, 1, 2, 3));
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(1, 1, 4, 5));

            // 第 3 行：字段
            Row r3 = sheet.createRow(2);
            r3.createCell(0).setCellValue("投放商品");
            r3.createCell(1).setCellValue("授权状态");
            r3.createCell(2).setCellValue("投放商品");
            r3.createCell(3).setCellValue("授权状态");
            r3.createCell(4).setCellValue("投放商品");
            r3.createCell(5).setCellValue("授权状态");
            r3.createCell(17).setCellValue("达人抖音信息"); // R 列

            // 第 4 行：数据行 1（aweme_1）
            Row r4 = sheet.createRow(3);
            r4.createCell(0).setCellValue("燕窝气垫");     // A4
            r4.createCell(1).setCellValue("1");            // B4 授权状态
            r4.createCell(2).setCellValue("橄榄油");       // C4
            r4.createCell(17).setCellValue("抖音名：达人1\n抖音号：aweme_1"); // R4

            // 第 5 行：数据行 2（aweme_2）
            Row r5 = sheet.createRow(4);
            r5.createCell(0).setCellValue("山茶花面膜");   // A5
            r5.createCell(4).setCellValue("猫爪润唇膏");   // E5
            r5.createCell(17).setCellValue("抖音名：达人2\n抖音号：aweme_2"); // R5

            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file.toFile())) {
                wb.write(fos);
            }
        }
    }
}
