package com.aolandai.dingtou.source;

import com.aolandai.dingtou.config.SourceProperties;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 回写《定向投放登记表》—— <b>建乘方计划成功后</b>，把商品名追加到
 * 「该达人所在行 + 该账户对应的商品列」。
 *
 * <h2>老板 2026-10-08 需求</h2>
 * <blockquote>
 * 「创建乘方计划后还要更新定向投放登记表的**对应达人的投放账户的投放商品**，
 * **多个商品用逗号隔开**」
 * </blockquote>
 *
 * <p>这张表的含义 = 「已经在千川建计划的【达人 × 商品 × 账户】」，
 * 所以建完计划要<b>把商品登记进去</b> —— 下次再处理同组合时就能直接命中。</p>
 *
 * <h2>⚠️ 定位规则（3 行复合表头）</h2>
 * <pre>
 * 行：按 R 列「达人抖音信息」里的<b>抖音号</b>匹配（文本包含）
 * 列：第 2 行「账户分组名」== 目标账户名 的那些<b>商品列</b>（第 3 行是「投放商品」）
 * </pre>
 *
 * <h2>⚠️ 三个必须做对的点</h2>
 * <ol>
 *   <li><b>追加不是覆盖</b>：目标格可能已有商品（如 {@code 燕窝气垫，山茶花面膜}）；</li>
 *   <li><b>要去重</b>：同一个商品不能重复加；</li>
 *   <li><b>写前备份</b>（同目录 {@code .backup-yyyyMMdd-HHmmss.xlsx}）——
 *       登记表是人工维护的，写坏了没退路。</li>
 * </ol>
 */
@Component
public class TargetingRegistryWriter {

    private static final Logger log = LoggerFactory.getLogger(TargetingRegistryWriter.class);

    private static final String COL_PRODUCT = "投放商品";
    private static final String COL_AWEME = "达人抖音信息";
    private static final int HEADER_ROW_COUNT = 3;

    /** 商品分隔符（与表里现有格式一致：全角逗号）。 */
    private static final String SEPARATOR = "，";

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final SourceProperties properties;

    public TargetingRegistryWriter(SourceProperties properties) {
        this.properties = properties;
    }

    /**
     * 把商品登记到「该达人 + 该账户」的商品格。
     *
     * @param awemeShowId  抖音号（定位行）
     * @param accountName  账户名（定位列分组，如 {@code 官方【孕产-商品】}）
     * @param productName  要登记的商品名
     * @return 实际写入的格数（0 = 没找到行/列，或该商品已登记）
     */
    public int registerProduct(String awemeShowId, String accountName, String productName)
            throws IOException {
        if (isBlank(awemeShowId) || isBlank(accountName) || isBlank(productName)) {
            log.debug("登记表回写跳过：参数不完整（{} / {} / {}）", awemeShowId, accountName, productName);
            return 0;
        }
        Path file = properties.getTargetingSheet();
        if (file == null || !Files.exists(file)) {
            log.warn("《定向投放登记表》不存在（{}），跳过回写", file);
            return 0;
        }

        int[] written = {0};
        try (Workbook workbook = WorkbookFactory.create(file.toFile())) {
            Sheet sheet = workbook.getSheetAt(0);
            Row accountHeader = sheet.getRow(1);
            Row leafHeader = sheet.getRow(2);
            if (accountHeader == null || leafHeader == null) {
                log.warn("《定向投放登记表》表头不足 3 行，跳过回写");
                return 0;
            }

            List<Integer> targetCols = productColsOfAccount(accountHeader, leafHeader, accountName);
            if (targetCols.isEmpty()) {
                log.warn("《定向投放登记表》里找不到账户「{}」的商品列，跳过回写", accountName);
                return 0;
            }

            String aweme = awemeShowId.trim();
            String product = productName.trim();
            for (int r = HEADER_ROW_COUNT; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                if (!ExcelReader.cellText(row.getCell(findColumn(leafHeader, COL_AWEME)))
                        .contains(aweme)) {
                    continue;
                }
                for (int col : targetCols) {
                    if (appendProduct(row, col, product)) {
                        written[0]++;
                    }
                }
                break;   // 一个抖音号只处理第一行（同号多行的情况少见，且不该重复登记）
            }

            if (written[0] > 0) {
                backup(file);
                try (OutputStream out = Files.newOutputStream(file)) {
                    workbook.write(out);
                }
                log.info("登记表回写：{} + {}（账户 {}）→ 写入 {} 格", aweme, product,
                        accountName, written[0]);
            } else {
                log.info("登记表回写：{} + {}（账户 {}）→ 无需写入（已登记或未找到行）",
                        aweme, product, accountName);
            }
        } catch (IOException e) {
            throw new IOException("回写《定向投放登记表》失败（可能正被 Excel 打开）："
                    + e.getMessage(), e);
        }
        return written[0];
    }

    // ==================== 内部 ====================

    /**
     * 在指定格里追加商品（去重、逗号分隔）。
     *
     * @return 是否真的改了
     */
    private static boolean appendProduct(Row row, int col, String product) {
        Cell cell = row.getCell(col);
        String current = cell == null ? "" : ExcelReader.cellText(cell).trim();

        Set<String> existing = new LinkedHashSet<>();
        if (!current.isEmpty()) {
            for (String part : current.split("[,，、/／|]")) {
                String p = part.trim();
                if (!p.isEmpty()) {
                    existing.add(p);
                }
            }
        }
        if (existing.contains(product)) {
            return false;   // ⚠️ 去重：已登记过就不动
        }
        existing.add(product);

        if (cell == null) {
            cell = row.createCell(col);
        }
        cell.setCellValue(String.join(SEPARATOR, existing));
        return true;
    }

    /** 第 2 行分组名 == 账户名、且第 3 行是「投放商品」的列。 */
    private static List<Integer> productColsOfAccount(Row accountHeader, Row leafHeader,
                                                      String accountName) {
        List<Integer> out = new ArrayList<>();
        String target = accountName.trim();
        for (int c = leafHeader.getFirstCellNum(); c < leafHeader.getLastCellNum(); c++) {
            if (!COL_PRODUCT.equals(ExcelReader.cellText(leafHeader.getCell(c)).trim())) {
                continue;
            }
            if (target.equals(accountNameAt(accountHeader, c))) {
                out.add(c);
            }
        }
        return out;
    }

    /** 第 2 行从 {@code col} 往左找第一个非空（合并单元格只有左上角有值）。 */
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

    private static void backup(Path file) throws IOException {
        Path dir = file.getParent();
        if (dir == null) {
            return;
        }
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : ".xlsx";
        Path backup = dir.resolve(base + ".backup-" + LocalDateTime.now().format(STAMP) + ext);
        Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
        log.info("登记表已备份 → {}", backup.getFileName());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
