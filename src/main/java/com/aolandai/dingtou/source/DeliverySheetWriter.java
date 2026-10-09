package com.aolandai.dingtou.source;

import com.aolandai.dingtou.config.SourceProperties;
import com.aolandai.dingtou.dto.sheet.CellWrite;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>服务直接写《定投素材表》</b>（老板 2026-10-08 确认的方案 A）。
 *
 * <h2>为什么要有它</h2>
 * <p>原来素材表是「服务返回 cells → 影刀照写」。老板要把项目做成
 * <b>轮询式</b>（服务自己定时跑，不用影刀 POST 调度）—— 那就<b>必须服务自己写素材表</b>，
 * 否则定时任务跑完的结果落不到表里。</p>
 *
 * <h2>⚠️⚠️ 三个必须做对的点</h2>
 * <ol>
 *   <li><b>只改指定单元格，不新建/删行、不动别的列</b> —— 素材表是 4992 行的核心表，
 *       整体重排会毁掉人工维护的内容；</li>
 *   <li><b>写前备份</b>（同目录 {@code .backup-yyyyMMdd-HHmmss.xlsx}）；</li>
 *   <li><b>文件被 Excel 打开时如实报错</b>，绝不静默 ——
 *       静默会让「没写成」看起来像「写成了」，下次又跑一遍。</li>
 * </ol>
 *
 * <h2>⚠️ 与影刀的关系</h2>
 * <p><b>不能同时写</b>：服务开了直写（{@code dingtou.source.direct-write=true}）之后，
 * 影刀就<b>不该再写素材表</b>，否则两边互相覆盖。切换时要确认影刀那边已关掉。</p>
 */
@Component
public class DeliverySheetWriter {

    private static final Logger log = LoggerFactory.getLogger(DeliverySheetWriter.class);

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final SourceProperties properties;
    private final DeliverySheetReader deliverySheetReader;

    public DeliverySheetWriter(SourceProperties properties,
                               DeliverySheetReader deliverySheetReader) {
        this.properties = properties;
        this.deliverySheetReader = deliverySheetReader;
    }

    /** 是否启用「服务直接写」（受配置控制）。 */
    public boolean enabled() {
        return properties.isDirectWrite();
    }

    /**
     * 把 cells 写进素材表。
     *
     * <p>⚠️ <b>同一行的多次写入会合并</b>（如同时写 K 和 N），避免重复打开文件。</p>
     *
     * @param cells  要写的单元格（行号 1-based + 列字母 + 值）
     * @param dryRun {@code true} = 只打印不真写
     * @return 实际写入的单元格数
     */
    public int write(List<CellWrite> cells, boolean dryRun) throws IOException {
        if (cells == null || cells.isEmpty()) {
            return 0;
        }
        if (!enabled()) {
            log.debug("服务直写素材表未启用（dingtou.source.direct-write=false），跳过 {} 个 cell",
                    cells.size());
            return 0;
        }
        Path file = properties.getDeliverySheet();
        if (file == null || !Files.exists(file)) {
            throw new IOException("素材表不存在: " + file);
        }

        // 行号 → (列字母 → 值)
        Map<Integer, Map<String, String>> byRow = new LinkedHashMap<>();
        for (CellWrite cell : cells) {
            byRow.computeIfAbsent(cell.row(), k -> new LinkedHashMap<>())
                    .put(cell.column(), cell.value());
        }

        if (dryRun) {
            log.warn("【dry-run】不会真写素材表。将要写入 {} 行：{}", byRow.size(),
                    preview(byRow));
            return cells.size();
        }

        backup(file);
        int written = 0;
        try (Workbook workbook = WorkbookFactory.create(file.toFile())) {
            Sheet sheet = workbook.getSheetAt(0);
            for (Map.Entry<Integer, Map<String, String>> entry : byRow.entrySet()) {
                // ⚠️ POI 行号 0-based，Excel 1-based
                Row row = sheet.getRow(entry.getKey() - 1);
                if (row == null) {
                    log.warn("素材表第 {} 行不存在，跳过", entry.getKey());
                    continue;
                }
                for (Map.Entry<String, String> cellEntry : entry.getValue().entrySet()) {
                    int col = columnIndex(cellEntry.getKey());
                    if (col < 0) {
                        log.warn("列字母「{}」非法，跳过", cellEntry.getKey());
                        continue;
                    }
                    Cell cell = row.getCell(col);
                    if (cell == null) {
                        cell = row.createCell(col);
                    }
                    cell.setCellValue(cellEntry.getValue());
                    written++;
                }
            }
            try (OutputStream out = Files.newOutputStream(file)) {
                workbook.write(out);
            }
        } catch (IOException e) {
            throw new IOException("写素材表失败（可能正被 Excel 打开）：" + e.getMessage(), e);
        }
        // ⚠️ 写完立刻失效读取缓存 —— 虽然缓存以「文件修改时间」为主判据（会自动失效），
        //    但显式失效更稳（防同一时间戳精度问题）。
        deliverySheetReader.invalidate();
        log.info("素材表已直写：{} 行 / {} 个单元格（已失效读取缓存）", byRow.size(), written);
        return written;
    }

    /** 列字母 → 0-based 下标（A=0，Z=25，AA=26…）。 */
    static int columnIndex(String letters) {
        if (letters == null || letters.isBlank()) {
            return -1;
        }
        String s = letters.trim().toUpperCase();
        int idx = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 'A' || c > 'Z') {
                return -1;
            }
            idx = idx * 26 + (c - 'A' + 1);
        }
        return idx - 1;
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
        log.info("素材表已备份 → {}", backup.getFileName());
    }

    private static String preview(Map<Integer, Map<String, String>> byRow) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Map.Entry<Integer, Map<String, String>> e : byRow.entrySet()) {
            if (++n > 3) {
                sb.append(" …共 ").append(byRow.size()).append(" 行");
                break;
            }
            sb.append("第").append(e.getKey()).append("行=").append(e.getValue()).append("；");
        }
        return sb.toString();
    }
}
