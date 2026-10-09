package com.aolandai.dingtou.source;

import com.aolandai.dingtou.config.AnomalyProperties;
import com.aolandai.dingtou.dto.anomaly.AnomalyRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
import java.util.List;

/**
 * 写「异常表」（老板 2026-10-08：新建一张 Excel，服务直接写，追加一行，带备份）。
 *
 * <h2>⚠️ 三个必须做对的点</h2>
 * <ol>
 *   <li><b>追加，不覆盖</b>：异常表是<b>流水</b>，每次往后加一行。⚠️ 与「交付状态表」不同 ——
 *       那张是全量重写目标列。</li>
 *   <li><b>文件不存在要自动创建</b>（带表头）—— 老板说「新建一张 Excel」，
 *       所以第一次跑时文件还不存在，不能直接报错。</li>
 *   <li><b>写失败不能拖垮主流程</b>：异常表本身是「记录问题」的，
 *       它自己写失败（比如被 Excel 打开）不该让整批投放崩掉 —— 但必须<b>大声记日志</b>。</li>
 * </ol>
 *
 * <p>⚠️ 备份按<b>天</b>覆盖（{@code xxx.backup-20261008.xlsx}）——
 * 异常表一天可能写很多次，按次备份会把目录写爆。</p>
 */
@Component
public class AnomalySheetWriter {

    private static final Logger log = LoggerFactory.getLogger(AnomalySheetWriter.class);

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final DateTimeFormatter BACKUP_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final String SHEET_NAME = "异常表";

    private final AnomalyProperties properties;

    public AnomalySheetWriter(AnomalyProperties properties) {
        this.properties = properties;
    }

    /**
     * 追加一批异常记录。
     *
     * @return 实际写入行数；未启用 / 路径未配置时返回 {@code 0}
     */
    public int append(List<AnomalyRecord> records) {
        if (records == null || records.isEmpty()) {
            return 0;
        }
        if (!properties.isEnabled()) {
            log.info("异常表已关闭（dingtou.anomaly.enabled=false），{} 条异常只进日志", records.size());
            return 0;
        }
        Path file = properties.getSheet();
        if (file == null) {
            log.warn("未配置 dingtou.anomaly.sheet，{} 条异常只进日志", records.size());
            return 0;
        }

        try {
            boolean exists = Files.exists(file);
            if (exists) {
                backup(file);
            } else {
                createParentDirs(file);
            }

            try (Workbook workbook = exists
                    ? WorkbookFactory.create(file.toFile()) : new XSSFWorkbook()) {
                Sheet sheet = exists ? workbook.getSheetAt(0) : workbook.createSheet(SHEET_NAME);
                int startRow = ensureHeader(sheet, exists);
                writeRows(sheet, records, startRow);
                try (OutputStream out = Files.newOutputStream(file)) {
                    workbook.write(out);
                }
            }
            log.info("异常表已追加 {} 行 → {}", records.size(), file);
            return records.size();
        } catch (IOException e) {
            // ⚠️ 异常表写失败【不能拖垮主流程】—— 但要大声记，别静默
            log.error("写异常表失败（可能正被 Excel 打开）：{}；这 {} 条异常只在日志里：{}",
                    e.getMessage(), records.size(), summarize(records), e);
            return 0;
        }
    }

    /** 单条便捷入口。 */
    public int append(AnomalyRecord record) {
        return append(List.of(record));
    }

    /** 造一条带当前时间的记录。 */
    public static AnomalyRecord of(String aweme, String product, String account,
                                   String stage, String type, String detail) {
        return new AnomalyRecord(LocalDateTime.now().format(STAMP), aweme, product,
                account, stage, type, detail);
    }

    // ==================== 内部 ====================

    /** 表头不存在就写表头；返回「第一条数据行」的 0-based 行号。 */
    private static int ensureHeader(Sheet sheet, boolean existed) {
        Row first = sheet.getRow(0);
        if (first == null) {
            Row header = sheet.createRow(0);
            String[] headers = AnomalyRecord.headers();
            for (int c = 0; c < headers.length; c++) {
                header.createCell(c).setCellValue(headers[c]);
            }
            return 1;
        }
        if (!existed) {
            return 1;
        }
        // 已有文件：追加到最后一行之后
        return sheet.getLastRowNum() + 1;
    }

    private static void writeRows(Sheet sheet, List<AnomalyRecord> records, int startRow) {
        int r = startRow;
        for (AnomalyRecord record : records) {
            Row row = sheet.createRow(r++);
            String[] values = record.values();
            for (int c = 0; c < values.length; c++) {
                Cell cell = row.createCell(c);
                // ⚠️ 全部按【文本】写 —— 抖音号/商品 ID 走数字会丢精度或被 Excel 转成科学计数
                cell.setCellValue(values[c] == null ? "" : values[c]);
            }
        }
    }

    private static void createParentDirs(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    /** 按天备份（同一天只留一份，避免写爆目录）。 */
    private static void backup(Path file) throws IOException {
        Path dir = file.getParent();
        if (dir == null) {
            return;
        }
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : ".xlsx";
        Path backup = dir.resolve(base + ".backup-" + LocalDateTime.now().format(BACKUP_DAY) + ext);
        Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
    }

    /** 只取前 3 条做日志摘要，避免刷屏。 */
    private static String summarize(List<AnomalyRecord> records) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < Math.min(3, records.size()); i++) {
            AnomalyRecord r = records.get(i);
            parts.add(r.aweme() + "+" + r.product() + "[" + r.stage() + "/" + r.type() + "]");
        }
        return String.join("；", parts) + (records.size() > 3 ? " …" : "");
    }
}
