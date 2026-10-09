package com.aolandai.dingtou.source;

import com.aolandai.dingtou.config.KolProperties;
import com.aolandai.dingtou.dto.kol.CycleRange;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 写《高消耗达人 7 天未更新素材总表》的「交付状态」列。
 *
 * <h2>⚠️ 这是本工程【唯一】会改业务表的地方</h2>
 * <p>其它地方（{@code planBuild} / {@code processPending}）都只<b>返回 cells 让影刀写</b>，
 * 服务不碰 Excel。这里破例是因为老板要求「服务直接写」。</p>
 *
 * <h2>⚠️ 三个必须做的防护</h2>
 * <ol>
 *   <li><b>写之前先备份</b>：同目录复制一份 {@code .backup-yyyyMMdd-HHmmss.xlsx}。
 *       共享盘上的表是别人在用的，写坏了没有退路。</li>
 *   <li><b>文件被 Excel 打开时写不进去</b>：POI 会抛异常。这时<b>如实报错</b>，
 *       绝不静默 —— 静默会让「没写成」看起来像「写成了」。</li>
 *   <li><b>只改目标列的单元格</b>，不新建行、不删行、不动别的列 ——
 *       表里还有 15 个公式单元格，整体重排会出问题。</li>
 * </ol>
 */
@Component
public class KolDeliverySheetWriter {

    private static final Logger log = LoggerFactory.getLogger(KolDeliverySheetWriter.class);

    private static final DateTimeFormatter BACKUP_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final KolProperties properties;

    public KolDeliverySheetWriter(KolProperties properties) {
        this.properties = properties;
    }

    /**
     * 把「行号 → 值」写进交付状态列。
     *
     * @param valueByRowNumber Excel 行号（1-based）→ 要写的值
     * @param dryRun           {@code true} = 只打印不真写（受 {@code dingtou.dry-run} 保护）
     * @return 实际写入的行数
     * @throws IOException 文件不存在 / 被 Excel 独占占用 / 找不到列
     */
    public int writeStatus(CycleRange cycle, Map<Integer, String> valueByRowNumber, boolean dryRun)
            throws IOException {
        if (valueByRowNumber.isEmpty()) {
            log.info("交付状态：没有要写的行，跳过");
            return 0;
        }
        Path file = properties.getDeliveryStatusSheet();
        if (file == null || !Files.exists(file)) {
            throw new IOException("表不存在: " + file);
        }

        if (dryRun) {
            log.warn("【dry-run】不会真写《高消耗达人未更新素材总表》。"
                    + "将要写入 sheet '{}' 的 {} 行：{}", cycle.sheetName(),
                    valueByRowNumber.size(), preview(valueByRowNumber));
            return valueByRowNumber.size();
        }

        Path backup = backup(file);
        log.info("已备份原表 → {}", backup.getFileName());

        try (Workbook workbook = WorkbookFactory.create(file.toFile())) {
            Sheet sheet = workbook.getSheet(cycle.sheetName());
            if (sheet == null) {
                throw new IOException("表里找不到 sheet '" + cycle.sheetName() + "'");
            }
            Row header = sheet.getRow(sheet.getFirstRowNum());
            int col = findColumn(header, properties.getDeliveryStatusColumn());
            if (col < 0) {
                throw new IOException("找不到列「" + properties.getDeliveryStatusColumn()
                        + "」（表结构变了？）");
            }

            int written = 0;
            for (Map.Entry<Integer, String> entry : valueByRowNumber.entrySet()) {
                // ⚠️ POI 的行号是 0-based，Excel 是 1-based
                Row row = sheet.getRow(entry.getKey() - 1);
                if (row == null) {
                    log.warn("第 {} 行不存在，跳过", entry.getKey());
                    continue;
                }
                Cell cell = row.getCell(col);
                if (cell == null) {
                    cell = row.createCell(col);
                }
                cell.setCellValue(entry.getValue());
                written++;
            }

            try (OutputStream out = Files.newOutputStream(file)) {
                workbook.write(out);
            }
            log.info("交付状态已写回 sheet '{}'：{} 行（列「{}」）",
                    cycle.sheetName(), written, properties.getDeliveryStatusColumn());
            return written;
        } catch (IOException e) {
            // ⚠️ 共享盘 + 被 Excel 打开时最常见：如实报错，并指出备份在哪
            throw new IOException("写《高消耗达人未更新素材总表》失败（可能正被 Excel 打开）："
                    + e.getMessage() + "；原表已备份到 " + backup.getFileName(), e);
        }
    }

    // ==================== 内部 ====================

    /** 同目录复制一份带时间戳的备份。 */
    private static Path backup(Path file) throws IOException {
        Path dir = file.getParent();
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : ".xlsx";
        Path backup = dir.resolve(base + ".backup-" + LocalDateTime.now().format(BACKUP_STAMP) + ext);
        Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
        return backup;
    }

    private static int findColumn(Row header, String name) {
        if (header == null) {
            return -1;
        }
        for (int c = header.getFirstCellNum(); c < header.getLastCellNum(); c++) {
            if (name.equals(ExcelReader.cellText(header.getCell(c)).trim())) {
                return c;
            }
        }
        return -1;
    }

    /** 只打印前几行，避免刷屏。 */
    private static Map<Integer, String> preview(Map<Integer, String> all) {
        Map<Integer, String> out = new LinkedHashMap<>();
        int n = 0;
        for (Map.Entry<Integer, String> e : all.entrySet()) {
            if (++n > 5) {
                out.put(-1, "...（共 " + all.size() + " 行）");
                break;
            }
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }
}
