package com.aolandai.dingtou.source;

import com.aolandai.dingtou.dto.sheet.SheetTable;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Excel 读取封装（.xlsx / .xls 通吃）。
 *
 * <p>⚠️ <b>刻意不用 POI 的 {@code DataFormatter}</b>，这是本项目最容易踩的一个坑：</p>
 * <p>{@code DataFormatter} 是「按单元格格式渲染」，而这几张业务表的日期列格式是 {@code 0.00}，
 * 于是 {@code 9.2} 会被渲染成字符串 {@code "9.20"} —— M.D 解析当场废掉
 * （{@code "9.20"} 会被解析成 9 月 20 日，而不是 9 月 2 日）。
 * 所以数值单元格一律走 {@link BigDecimal#stripTrailingZeros()}，
 * 保留 Excel 里存的原值 {@code 9.2}。</p>
 *
 * <p>另外：所有值统一转字符串（见 {@link SheetTable} 的说明）。</p>
 */
public final class ExcelReader {

    /** 数据行上限保护：防止误读一张百万行的表把内存打爆。 */
    private static final int MAX_ROWS = 200_000;

    private ExcelReader() {
    }

    /**
     * 读取一个 sheet。
     *
     * @param file            表文件（.xlsx / .xls）
     * @param sheetNameOrNull sheet 名；为 {@code null} 或空白时取<b>第一个</b> sheet
     *                        （业务表按月改名，如「9月视频交付」→「10月视频交付」，
     *                        写死 sheet 名会在下个月直接崩）
     */
    public static SheetTable read(Path file, String sheetNameOrNull) throws IOException {
        if (file == null) {
            throw new IOException("表路径为空");
        }
        if (!Files.exists(file)) {
            throw new IOException("表不存在: " + file.toAbsolutePath());
        }
        if (!Files.isReadable(file)) {
            throw new IOException("表不可读（可能被 Excel 独占占用）: " + file.toAbsolutePath());
        }

        try (Workbook workbook = WorkbookFactory.create(file.toFile(), null, true)) {
            Sheet sheet = resolveSheet(workbook, sheetNameOrNull, file);
            int headerRowIndex = sheet.getFirstRowNum();
            Row headerRow = sheet.getRow(headerRowIndex);
            if (headerRow == null) {
                throw new IOException("表为空，没有表头行: " + file.toAbsolutePath());
            }

            List<String> headers = new ArrayList<>();
            int lastCell = headerRow.getLastCellNum();
            for (int c = 0; c < lastCell; c++) {
                headers.add(cellText(headerRow.getCell(c)));
            }

            List<List<String>> rows = new ArrayList<>();
            int lastRow = sheet.getLastRowNum();
            for (int r = headerRowIndex + 1; r <= lastRow; r++) {
                if (rows.size() >= MAX_ROWS) {
                    throw new IOException("数据行超过 " + MAX_ROWS + " 行上限，疑似读错文件: "
                            + file.toAbsolutePath());
                }
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                List<String> values = new ArrayList<>(headers.size());
                for (int c = 0; c < headers.size(); c++) {
                    values.add(cellText(row.getCell(c)));
                }
                if (isBlankRow(values)) {
                    continue;
                }
                rows.add(values);
            }
            return new SheetTable(sheet.getSheetName(), headers, rows);
        }
    }

    /** 列出全部 sheet 名，便于报错时给出「你到底有哪些 sheet」。 */
    public static List<String> sheetNames(Path file) throws IOException {
        if (file == null || !Files.exists(file)) {
            return List.of();
        }
        try (Workbook workbook = WorkbookFactory.create(file.toFile(), null, true)) {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                names.add(workbook.getSheetName(i));
            }
            return names;
        }
    }

    private static Sheet resolveSheet(Workbook workbook, String sheetNameOrNull, Path file)
            throws IOException {
        if (sheetNameOrNull == null || sheetNameOrNull.isBlank()) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new IOException("表里没有任何 sheet: " + file.toAbsolutePath());
            }
            return workbook.getSheetAt(0);
        }
        Sheet sheet = workbook.getSheet(sheetNameOrNull.trim());
        if (sheet == null) {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                names.add(workbook.getSheetName(i));
            }
            throw new IOException("找不到 sheet: '" + sheetNameOrNull + "'，实际有: " + names
                    + "（文件: " + file.toAbsolutePath() + "）");
        }
        return sheet;
    }

    /**
     * 单元格 → 字符串。
     *
     * <p>⚠️ 数值走 {@code BigDecimal.stripTrailingZeros()}，<b>不用 DataFormatter</b>（理由见类注释）。</p>
     */
    public static String cellText(Cell cell) {
        if (cell == null) {
            return "";
        }
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            type = cell.getCachedFormulaResultType();
        }
        return switch (type) {
            case STRING -> cell.getStringCellValue().trim();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case NUMERIC -> numericText(cell);
            case BLANK, ERROR, _NONE -> "";
            default -> "";
        };
    }

    private static String numericText(Cell cell) {
        double value = cell.getNumericCellValue();
        // 日期/时间格式的单元格才按日期渲染；业务表的 M.D 是普通小数，不走这里
        if (DateUtil.isCellDateFormatted(cell) && value >= 1.0) {
            return cell.getLocalDateTimeCellValue().toLocalDate().toString();
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static boolean isBlankRow(List<String> values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return false;
            }
        }
        return true;
    }
}
