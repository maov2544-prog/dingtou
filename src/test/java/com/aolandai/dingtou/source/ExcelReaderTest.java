package com.aolandai.dingtou.source;

import com.aolandai.dingtou.dto.sheet.SheetTable;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ExcelReader} 单测：重点验证「数值单元格不能按显示格式渲染」这条坑。 */
class ExcelReaderTest {

    private static Path writeWorkbook(Path dir, String fileName, String sheetName, WorkbookBuilder builder)
            throws IOException {
        Path file = dir.resolve(fileName);
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(sheetName);
            builder.build(sheet, workbook);
            try (OutputStream out = Files.newOutputStream(file)) {
                workbook.write(out);
            }
        }
        return file;
    }

    @FunctionalInterface
    private interface WorkbookBuilder {
        void build(Sheet sheet, Workbook workbook);
    }

    @Test
    @DisplayName("⚠️ 数值单元格保留原值：格式为 0.00 的 9.2 必须读出 \"9.2\"，不能是 \"9.20\"")
    void numericCellKeepsRawValueNotDisplayFormat(@TempDir Path dir) throws IOException {
        Path file = writeWorkbook(dir, "md.xlsx", "S", (sheet, workbook) -> {
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("日期");
            Row data = sheet.createRow(1);
            Cell cell = data.createCell(0);
            cell.setCellValue(9.2);
            // 业务表的日期列就是这个格式 —— 用 DataFormatter 会把 9.2 渲染成 "9.20"
            CellStyle style = workbook.createCellStyle();
            style.setDataFormat(workbook.createDataFormat().getFormat("0.00"));
            cell.setCellStyle(style);
        });

        SheetTable table = ExcelReader.read(file, null);
        assertEquals("9.2", table.value(0, 0));
    }

    @Test
    @DisplayName("数值 / 文本混存统一成字符串，且不带科学计数法")
    void mixedTypesBecomePlainStrings(@TempDir Path dir) throws IOException {
        Path file = writeWorkbook(dir, "mixed.xlsx", "S", (sheet, workbook) -> {
            sheet.createRow(0).createCell(0).setCellValue("抖音号");
            sheet.createRow(1).createCell(0).setCellValue(602085838d);   // 数值型
            sheet.createRow(2).createCell(0).setCellValue("789987wss");  // 文本型
            sheet.createRow(3).createCell(0).setCellValue(527796516d);
        });

        SheetTable table = ExcelReader.read(file, null);
        assertEquals("602085838", table.value(0, 0));
        assertEquals("789987wss", table.value(1, 0));
        assertEquals("527796516", table.value(2, 0));
    }

    @Test
    @DisplayName("19 位 ID 以文本存时原样读出，不丢位")
    void longIdAsTextIsIntact(@TempDir Path dir) throws IOException {
        String id = "3691600311144808871";
        Path file = writeWorkbook(dir, "id.xlsx", "S", (sheet, workbook) -> {
            sheet.createRow(0).createCell(0).setCellValue("商品id");
            sheet.createRow(1).createCell(0).setCellValue(id);
        });
        assertEquals(id, ExcelReader.read(file, null).value(0, 0));
    }

    @Test
    @DisplayName("空行被跳过；表头与数据行数对得上")
    void blankRowsAreSkipped(@TempDir Path dir) throws IOException {
        Path file = writeWorkbook(dir, "blank.xlsx", "S", (sheet, workbook) -> {
            sheet.createRow(0).createCell(0).setCellValue("产品");
            sheet.createRow(1).createCell(0).setCellValue("益生元面霜");
            sheet.createRow(2);                                  // 全空行
            sheet.createRow(3).createCell(0).setCellValue("   ");  // 只有空格
            sheet.createRow(4).createCell(0).setCellValue("生姜洗护");
        });
        SheetTable table = ExcelReader.read(file, null);
        assertEquals(2, table.rowCount());
        assertEquals("益生元面霜", table.value(0, 0));
        assertEquals("生姜洗护", table.value(1, 0));
    }

    @Test
    @DisplayName("sheet 名留空时取第一个 sheet（换月改名不会崩）")
    void blankSheetNameFallsBackToFirst(@TempDir Path dir) throws IOException {
        Path file = writeWorkbook(dir, "first.xlsx", "10月视频交付", (sheet, workbook) -> {
            sheet.createRow(0).createCell(0).setCellValue("产品");
            sheet.createRow(1).createCell(0).setCellValue("益生元面霜");
        });
        assertEquals("10月视频交付", ExcelReader.read(file, null).sheetName());
        assertEquals("10月视频交付", ExcelReader.read(file, "  ").sheetName());
    }

    @Test
    @DisplayName("sheet 名找不到 → 报错并列出实际 sheet 名")
    void unknownSheetNameReportsAvailable(@TempDir Path dir) throws IOException {
        Path file = writeWorkbook(dir, "s.xlsx", "实际名", (sheet, workbook) ->
                sheet.createRow(0).createCell(0).setCellValue("产品"));

        IOException e = assertThrows(IOException.class, () -> ExcelReader.read(file, "不存在的名"));
        assertTrue(e.getMessage().contains("实际名"), e.getMessage());
    }

    @Test
    @DisplayName("文件不存在 / 为空路径 → 报错")
    void missingFileFails(@TempDir Path dir) {
        assertThrows(IOException.class, () -> ExcelReader.read(dir.resolve("nope.xlsx"), null));
        assertThrows(IOException.class, () -> ExcelReader.read(null, null));
    }

    @Test
    @DisplayName("sheetNames 能列出全部 sheet；文件不存在时返回空表而不是抛异常")
    void sheetNamesWorks(@TempDir Path dir) throws IOException {
        Path file = writeWorkbook(dir, "n.xlsx", "唯一", (sheet, workbook) ->
                sheet.createRow(0).createCell(0).setCellValue("x"));
        assertEquals(1, ExcelReader.sheetNames(file).size());
        assertTrue(ExcelReader.sheetNames(dir.resolve("nope.xlsx")).isEmpty());
    }

    @Test
    @DisplayName("列定位：精确匹配 / 多候选 / 缺失返回空")
    void columnLookup(@TempDir Path dir) throws IOException {
        Path file = writeWorkbook(dir, "c.xlsx", "S", (sheet, workbook) -> {
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("日期");
            header.createCell(1).setCellValue(" 投放状态 ");
            sheet.createRow(1).createCell(0).setCellValue("9.2");
        });
        SheetTable table = ExcelReader.read(file, null);

        assertEquals(0, table.requireColumn("日期"));
        assertTrue(table.columnIndex("不存在").isEmpty());
        // 表头会被 trim，所以带空格也能匹配
        assertEquals(1, table.requireColumn("投放状态"));
        assertEquals(1, table.columnIndexAny("投放备注", "投放状态").orElse(-1));
    }
}
