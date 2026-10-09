package com.aolandai.dingtou.source;

import com.aolandai.dingtou.config.SourceProperties;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 读《定向投放登记表》—— 判断【抖音号 + 商品】是否**已经有千川计划**。
 *
 * <h2>老板 2026-10-08 对这张表的重新定义</h2>
 * <p>「含义是<b>已经在千川建计划的该达人的商品和对应的千川账户</b>，
 * 这一步检测提前，如果要处理的【商品 + 抖音号】命中<b>直接走添加素材的路径</b>」</p>
 *
 * <h2>🔴 表结构：3 行复合表头（实测）</h2>
 * <pre>
 * 第 1 行（账户层）  A1:J1 官方店铺投放账户 | K1:N1 婴童店 | O1:P1 专卖店 | Q 对接商务 | R 达人抖音信息
 * 第 2 行（分组层）  每 2 列一组 = 一个千川账户，如 A2:B2 官方【孕产-商品】
 * 第 3 行（叶子）    A/C/E/G/I/K/M/O = 【投放商品】；B/D/F/H/J/L/N/P = 【授权状态】
 * 第 4 行起          数据
 * </pre>
 *
 * <p>⚠️ <b>P 列本身是「授权状态」不是商品</b> —— 商品在<b>奇数列</b>（A/C/E/G/I/K/M/O）。
 * 老板说的「P 列之前」指的就是这些商品列。</p>
 *
 * <p>⚠️ 第 1 行的账户号（如 {@code 7742425}）是<b>店铺号</b>，不是千川账户 ID ——
 * 真正的账户名在第 2 行的分组名（如 {@code 官方【孕产-商品】}），与账户字典对得上。</p>
 *
 * <p>⚠️ 复合表头大量用<b>合并单元格</b>，POI 读出来只有左上角有值 →
 * 取「账户名」时要<b>往左找第一个非空</b>。</p>
 */
@Component
public class TargetingRegistryReader {

    private static final Logger log = LoggerFactory.getLogger(TargetingRegistryReader.class);

    /** 叶子表头：商品列。 */
    private static final String COL_PRODUCT = "投放商品";

    /** 抖音号所在的列名（第 3 行）。 */
    private static final String COL_AWEME = "达人抖音信息";

    private static final int HEADER_ROW_COUNT = 3;

    /** 命中结果。 */
    public record RegistryHit(String awemeShowId, String productText, String accountName,
                              int rowNumber) {
    }

    private final SourceProperties properties;

    public TargetingRegistryReader(SourceProperties properties) {
        this.properties = properties;
    }

    /**
     * 按【抖音号 + 商品名】查登记表。
     *
     * <p>⚠️ 一个达人可能有多行、一行里多个账户都有该商品 —— 返回<b>第一个</b>命中，
     * 并把「命中了几个账户」记日志（多账户时调用方要自己决定用哪个）。</p>
     */
    public Optional<RegistryHit> find(String awemeShowId, String productName) throws IOException {
        if (awemeShowId == null || awemeShowId.isBlank()
                || productName == null || productName.isBlank()) {
            return Optional.empty();
        }
        Path file = properties.getTargetingSheet();
        if (file == null || !Files.exists(file)) {
            log.debug("未配置/不存在《定向投放登记表》，跳过命中检查");
            return Optional.empty();
        }

        try (Workbook workbook = WorkbookFactory.create(file.toFile(), null, true)) {
            Sheet sheet = workbook.getSheetAt(0);
            Row productHeader = sheet.getRow(2);
            Row accountHeader = sheet.getRow(1);
            if (productHeader == null || accountHeader == null) {
                log.warn("《定向投放登记表》表头不足 3 行，跳过");
                return Optional.empty();
            }

            // 商品列 = 第 3 行是「投放商品」的那些列
            List<Integer> productCols = new ArrayList<>();
            for (int c = productHeader.getFirstCellNum(); c < productHeader.getLastCellNum(); c++) {
                if (COL_PRODUCT.equals(ExcelReader.cellText(productHeader.getCell(c)).trim())) {
                    productCols.add(c);
                }
            }
            int awemeCol = findColumn(productHeader, COL_AWEME);
            if (productCols.isEmpty() || awemeCol < 0) {
                log.warn("《定向投放登记表》找不到商品列或「{}」列，跳过", COL_AWEME);
                return Optional.empty();
            }

            String target = productName.trim();
            for (int r = HEADER_ROW_COUNT; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                String awemeText = ExcelReader.cellText(row.getCell(awemeCol));
                if (!awemeText.contains(awemeShowId)) {
                    continue;
                }
                for (int col : productCols) {
                    String cell = ExcelReader.cellText(row.getCell(col)).trim();
                    if (cell.isEmpty() || !containsProduct(cell, target)) {
                        continue;
                    }
                    // 账户名在第 2 行、往左找第一个非空（合并单元格只有左上角有值）
                    String accountName = accountNameAt(accountHeader, col);
                    RegistryHit hit = new RegistryHit(awemeShowId, cell, accountName, r + 1);
                    log.info("登记表命中：{} + {} → 账户 '{}'（第 {} 行）",
                            awemeShowId, cell, accountName, r + 1);
                    return Optional.of(hit);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * 单元格里的商品是否匹配目标商品。
     *
     * <p>⚠️ 实测登记表里一格可能塞多个商品（如 {@code 燕窝气垫，山茶花面膜}），
     * 所以按<b>逗号/顿号/斜杠</b>拆开逐个精确比对，不做模糊匹配。</p>
     */
    static boolean containsProduct(String cellText, String target) {
        if (cellText.equals(target)) {
            return true;
        }
        for (String part : cellText.split("[,，、/／|]")) {
            if (part.trim().equals(target)) {
                return true;
            }
        }
        return false;
    }

    /** 第 2 行从 {@code col} 往左找第一个非空（合并单元格的值只在左上角）。 */
    private static String accountNameAt(Row accountHeader, int col) {
        for (int c = col; c >= accountHeader.getFirstCellNum(); c--) {
            String text = ExcelReader.cellText(accountHeader.getCell(c)).trim();
            if (!text.isEmpty()) {
                return text;
            }
        }
        return "";
    }

    private static int findColumn(Row header, String name) {
        for (int c = header.getFirstCellNum(); c < header.getLastCellNum(); c++) {
            if (name.equals(ExcelReader.cellText(header.getCell(c)).trim())) {
                return c;
            }
        }
        return -1;
    }

    /** 供探针/排障：把整张登记表读成「账户名 → 商品列表」的索引。 */
    public Map<String, List<String>> dumpIndex() throws IOException {
        Path file = properties.getTargetingSheet();
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (file == null || !Files.exists(file)) {
            return out;
        }
        try (Workbook workbook = WorkbookFactory.create(file.toFile(), null, true)) {
            Sheet sheet = workbook.getSheetAt(0);
            Row productHeader = sheet.getRow(2);
            Row accountHeader = sheet.getRow(1);
            if (productHeader == null || accountHeader == null) {
                return out;
            }
            for (int r = HEADER_ROW_COUNT; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                for (int c = productHeader.getFirstCellNum(); c < productHeader.getLastCellNum(); c++) {
                    if (!COL_PRODUCT.equals(ExcelReader.cellText(productHeader.getCell(c)).trim())) {
                        continue;
                    }
                    String product = ExcelReader.cellText(row.getCell(c)).trim();
                    if (product.isEmpty()) {
                        continue;
                    }
                    String account = accountNameAt(accountHeader, c);
                    out.computeIfAbsent(account, k -> new ArrayList<>()).add(product);
                }
            }
        }
        return out;
    }
}
