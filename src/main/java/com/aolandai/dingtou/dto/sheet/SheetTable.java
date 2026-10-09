package com.aolandai.dingtou.dto.sheet;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * 一张已读进内存的工作表：表头 + 全部数据行（值一律是<b>字符串</b>）。
 *
 * <p>为什么全部转成字符串：这几张业务表里，{@code 抖音号} / {@code 订单ID} / {@code 商品ID}
 * 都存在「数值 + 文本混存」，而且 19 位数字按数值读会丢精度或被转成科学计数法。
 * <b>在读取层就统一成字符串</b>，后续所有比较都是字符串比较，不会再出现类型漂移。</p>
 *
 * @param sheetName sheet 名
 * @param headers   表头行（第 1 行）的逐列原始列名
 * @param rows      数据行（自第 2 行起），每行长度与 {@code headers} 对齐
 */
public record SheetTable(String sheetName, List<String> headers, List<List<String>> rows) {

    public SheetTable {
        headers = headers == null ? List.of() : List.copyOf(headers);
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    public int rowCount() {
        return rows.size();
    }

    public int columnCount() {
        return headers.size();
    }

    /** 按列名找列下标（精确匹配，已 trim）。找不到返回空。 */
    public OptionalInt columnIndex(String header) {
        if (header == null) {
            return OptionalInt.empty();
        }
        String target = header.trim();
        for (int i = 0; i < headers.size(); i++) {
            if (headers.get(i).equals(target)) {
                return OptionalInt.of(i);
            }
        }
        return OptionalInt.empty();
    }

    /**
     * 按列名找列下标，找不到就抛异常。
     *
     * <p>⚠️ 业务表会被人手改列名（例如「投放备注」要改名成「投放状态」），
     * 所以要找多个候选名时请用 {@link #columnIndexAny} —— 那样改列名不会让服务直接崩。</p>
     */
    public int requireColumn(String header) {
        return columnIndex(header).orElseThrow(() -> new IllegalStateException(
                "sheet[" + sheetName + "] 缺少列: '" + header + "'；实际表头=" + headers));
    }

    /** 按多个候选列名找列下标，返回第一个命中的。 */
    public OptionalInt columnIndexAny(String... candidates) {
        for (String candidate : candidates) {
            OptionalInt index = columnIndex(candidate);
            if (index.isPresent()) {
                return index;
            }
        }
        return OptionalInt.empty();
    }

    /** 取第 {@code rowIndex} 行（0-based，不含表头）某列的值；越界返回空串。 */
    public String value(int rowIndex, int columnIndex) {
        if (rowIndex < 0 || rowIndex >= rows.size()) {
            return "";
        }
        List<String> row = rows.get(rowIndex);
        if (columnIndex < 0 || columnIndex >= row.size()) {
            return "";
        }
        String value = row.get(columnIndex);
        return value == null ? "" : value.trim();
    }

    /** 取第 {@code rowIndex} 行的列名 → 值映射，跳过空列名。 */
    public Map<String, String> rowAsMap(int rowIndex) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            String header = headers.get(i);
            if (!header.isBlank()) {
                map.put(header, value(rowIndex, i));
            }
        }
        return map;
    }
}
