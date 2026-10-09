package com.aolandai.dingtou.source;

import com.aolandai.dingtou.config.KolProperties;
import com.aolandai.dingtou.dto.kol.CycleRange;
import com.aolandai.dingtou.dto.kol.DeliveryStatusRow;
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
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 读《高消耗达人 7 天未更新素材总表》—— 用于「交付状态」检查。
 *
 * <h2>表的结构（实测）</h2>
 * <pre>
 * sheet 名 = 周期区间，形如 「2026-09-13到2026-10-04」（21 天 = 3 个周日到周日）
 * 第 1 行 = 表头：
 *   A 投放千川账户 | B 投放类型 | C 计划 ID | D 达人抖音名 | E 达人抖音号 |
 *   F 投放商品 | G 消耗金额 | H 素材更新时间 | I 间隔天数 | J 周期时间 |
 *   K 对接商务 | L 催更状态 | M 拒绝范围 | N 备注 | O 聊天记录截图 | P 交付状态 | ...
 * </pre>
 *
 * <p>⚠️ <b>表里没有 {@code advertiser_id}，只有账户名</b>（如「官方【母婴商品】」）——
 * 所以调用方要拿账户名去账户字典换 ID。</p>
 *
 * <p>⚠️ 表里会<b>预建未来周期的空 sheet</b>（如 2026-10-11 那个），所以不能假设
 * 「最后一个 sheet 就是当前周期」。</p>
 */
@Component
public class KolDeliverySheetReader {

    private static final Logger log = LoggerFactory.getLogger(KolDeliverySheetReader.class);

    /** sheet 名形如 {@code 2026-09-13到2026-10-04}。 */
    private static final Pattern CYCLE_SHEET = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2})\\s*到\\s*(\\d{4}-\\d{2}-\\d{2})$");

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final KolProperties properties;

    public KolDeliverySheetReader(KolProperties properties) {
        this.properties = properties;
    }

    /** 表文件路径（来自配置）。 */
    public Path filePath() {
        return properties.getDeliveryStatusSheet();
    }

    /**
     * 全部周期 sheet（按开始日升序）。非周期命名的 sheet 会被忽略。
     */
    public List<CycleRange> allCycles() throws IOException {
        Path file = requireFile();
        List<CycleRange> out = new ArrayList<>();
        for (String name : ExcelReader.sheetNames(file)) {
            CycleRange range = parseCycle(name);
            if (range != null) {
                out.add(range);
            }
        }
        out.sort(Comparator.comparing(CycleRange::startDate));
        return out;
    }

    /**
     * 找「上一个周期」—— <b>准绳是「上一个周日」</b>（老板 2026-10-06 明确）。
     *
     * <h2>规则</h2>
     * <ol>
     *   <li>先算出<b>上一个周日</b>：从参考日往前找最近的周日；
     *       ⚠️ 参考日<b>本身就是周日</b>时，要<b>再往前 7 天</b>（「上一个」不含今天）；</li>
     *   <li>再取<b>结束日 == 那个周日</b>的周期 sheet。</li>
     * </ol>
     *
     * <p>例（老板给的例子）：参考日 {@code 2026-10-11}（周日）→ 上一个周日 {@code 2026-10-04}
     * → sheet {@code 2026-09-13到2026-10-04} ✅</p>
     *
     * <p>⚠️ 与「结束日 &lt; 参考日」的区别：参考日<b>恰好是周日</b>时两者一致；
     * 但参考日是<b>周一到周六</b>时，「上一个周日」就是<b>上一周的周日</b>——
     * 用「周日」做准绳才不会随「今天星期几」漂移。</p>
     *
     * <p>兜底：万一表的周期结束日不是周日（数据问题），退化为
     * 「结束日 ≤ 上一个周日 的最晚周期」，并记 warning —— 不静默用错周期。</p>
     */
    public Optional<CycleRange> resolvePreviousCycle(LocalDate referenceDate) throws IOException {
        LocalDate lastSunday = previousSunday(referenceDate);
        List<CycleRange> cycles = allCycles();

        Optional<CycleRange> exact = cycles.stream()
                .filter(range -> range.endDate().isEqual(lastSunday))
                .findFirst();
        if (exact.isPresent()) {
            return exact;
        }

        Optional<CycleRange> fallback = cycles.stream()
                .filter(range -> !range.endDate().isAfter(lastSunday))
                .max(Comparator.comparing(CycleRange::endDate));
        fallback.ifPresent(range -> log.warn("表里没有「结束日 = 上一个周日 {}」的周期 sheet，"
                + "退化为结束日 ≤ 它的最晚周期：{}（请核对表头 sheet 命名）",
                lastSunday, range.sheetName()));
        return fallback;
    }

    /**
     * 上一个周日：从参考日往前找最近的周日。
     *
     * <p>⚠️ 参考日<b>本身是周日</b>时返回<b>再往前 7 天</b>那个 —— 「上一个」不含今天。
     * 所以先减一天再往前找。</p>
     *
     * <p>⚠️ 必须 <b>public</b>：调用方 {@code DeliveryStatusChecker} 在<b>另一个包</b>
     * （{@code service.kol}），package-private 会编译不过（踩过）。</p>
     */
    public static LocalDate previousSunday(LocalDate referenceDate) {
        LocalDate d = referenceDate.minusDays(1);
        while (d.getDayOfWeek() != DayOfWeek.SUNDAY) {
            d = d.minusDays(1);
        }
        return d;
    }

    /**
     * 找某个周期的「下一个周期」（用来拿「本周期开始日」）。
     *
     * <p>没有下一个 sheet 时返回空 —— 调用方用「结束日 + 7 天」兜底。</p>
     */
    public Optional<CycleRange> nextCycleOf(CycleRange current) throws IOException {
        return allCycles().stream()
                .filter(range -> range.startDate().isAfter(current.startDate()))
                .min(Comparator.comparing(CycleRange::startDate));
    }

    /**
     * 读一个周期 sheet 里<b>属于指定「周期时间」</b>的行。
     *
     * <h2>🔴 为什么要按 J 列「周期时间」过滤（老板 2026-10-06 明确）</h2>
     * <p>一个 sheet 是<b>好几周合并</b>的（如 {@code 2026-09-13到2026-10-04} 里，
     * J 列实际有 {@code 2026-09-13} / {@code 2026-09-20} / {@code 2026-10-04} 三个值，
     * 分别 50 / 56 / 57 行）。</p>
     *
     * <p>所以「上一个周期」<b>不是整个 sheet</b>，而是 sheet 里
     * <b>J 列「周期时间」== 上一个周日</b>的那些行。</p>
     *
     * <p>📌 顺带的好处：detail 调用从 163 次降到 57 次（少 65%）。</p>
     *
     * @param cycleTime J 列「周期时间」要等于哪一天（= 上一个周日）
     */
    public List<DeliveryStatusRow> readRows(CycleRange cycle, LocalDate cycleTime)
            throws IOException {
        Path file = requireFile();
        try (Workbook workbook = WorkbookFactory.create(file.toFile(), null, true)) {
            Sheet sheet = workbook.getSheet(cycle.sheetName());
            if (sheet == null) {
                throw new IOException("表里找不到 sheet '" + cycle.sheetName() + "'（"
                        + file.toAbsolutePath() + "）");
            }
            Row header = sheet.getRow(sheet.getFirstRowNum());
            if (header == null) {
                throw new IOException("sheet '" + cycle.sheetName() + "' 没有表头行");
            }
            int accountCol = requireColumn(header, cycle.sheetName(), "投放千川账户");
            int adIdCol = requireColumnAny(header, cycle.sheetName(), "计划 ID", "计划ID");
            int cycleTimeCol = requireColumn(header, cycle.sheetName(), "周期时间");

            List<DeliveryStatusRow> rows = new ArrayList<>();
            Set<String> seenCycleTimes = new LinkedHashSet<>();
            int skipped = 0;
            for (int r = header.getRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                String adId = ExcelReader.cellText(row.getCell(adIdCol));
                if (adId.isEmpty()) {
                    continue;
                }
                String cycleTimeText = ExcelReader.cellText(row.getCell(cycleTimeCol));
                seenCycleTimes.add(cycleTimeText);
                if (!isSameDay(cycleTimeText, cycleTime)) {
                    continue;
                }
                String account = ExcelReader.cellText(row.getCell(accountCol));
                DeliveryStatusRow parsed = new DeliveryStatusRow(r + 1, account, adId);
                if (!parsed.isComplete()) {
                    skipped++;
                    continue;
                }
                rows.add(parsed);
            }
            if (skipped > 0) {
                log.warn("sheet '{}' 里「周期时间={}」有 {} 行缺「投放千川账户」，已跳过",
                        cycle.sheetName(), cycleTime, skipped);
            }
            if (rows.isEmpty()) {
                throw new IOException("sheet '" + cycle.sheetName() + "' 里没有「周期时间 = "
                        + cycleTime + "」的行。该 sheet 里出现过的周期时间："
                        + seenCycleTimes + "（请核对该周期的数据是否已生成）");
            }
            log.info("读《高交付状态表》sheet '{}'：「周期时间={}」共 {} 行"
                            + "（该 sheet 全部周期时间 {}）",
                    cycle.sheetName(), cycleTime, rows.size(), seenCycleTimes);
            return rows;
        }
    }

    /** J 列「周期时间」是否等于目标日（宽容：{@code 2026-10-04} / {@code 2026-10-04 00:00:00} / {@code 2026/10/4} 都认）。 */
    static boolean isSameDay(String cellText, LocalDate target) {
        LocalDate parsed = parseDate(cellText);
        return parsed != null && parsed.isEqual(target);
    }

    /** 从单元格文本里抠出日期；抠不出返回 {@code null}。 */
    static LocalDate parseDate(String cellText) {
        if (cellText == null || cellText.isBlank()) {
            return null;
        }
        String text = cellText.trim();
        // 2026-10-04 00:00:00 → 取前 10 位
        if (text.length() >= 10) {
            try {
                return LocalDate.parse(text.substring(0, 10), DATE);
            } catch (DateTimeParseException ignored) {
                // 继续尝试别的格式
            }
        }
        try {
            return LocalDate.parse(text, DATE);
        } catch (DateTimeParseException ignored) {
            // 继续
        }
        Matcher m = Pattern.compile("^(\\d{4})[/.](\\d{1,2})[/.](\\d{1,2})").matcher(text);
        if (m.find()) {
            try {
                return LocalDate.of(Integer.parseInt(m.group(1)),
                        Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    // ==================== 内部 ====================

    private Path requireFile() throws IOException {
        Path file = properties.getDeliveryStatusSheet();
        if (file == null) {
            throw new IOException("未配置 dingtou.kol.delivery-status-sheet（高消耗达人未更新素材总表路径）");
        }
        if (!Files.exists(file)) {
            throw new IOException("表不存在: " + file.toAbsolutePath()
                    + "（⚠️ 共享盘路径必须带 IP，机器名解析不了）");
        }
        if (!Files.isReadable(file)) {
            throw new IOException("表不可读（可能被 Excel 独占占用）: " + file.toAbsolutePath());
        }
        return file;
    }

    /** 解析 sheet 名 → 周期；不是周期命名则返回 {@code null}。 */
    static CycleRange parseCycle(String sheetName) {
        if (sheetName == null) {
            return null;
        }
        Matcher m = CYCLE_SHEET.matcher(sheetName.trim());
        if (!m.matches()) {
            return null;
        }
        try {
            return new CycleRange(sheetName.trim(),
                    LocalDate.parse(m.group(1), DATE), LocalDate.parse(m.group(2), DATE));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static int requireColumn(Row header, String sheetName, String name) throws IOException {
        int idx = findColumn(header, name);
        if (idx < 0) {
            throw new IOException("sheet '" + sheetName + "' 找不到列「" + name + "」"
                    + "（表结构变了？请核对表头）");
        }
        return idx;
    }

    private static int requireColumnAny(Row header, String sheetName, String... names)
            throws IOException {
        for (String name : names) {
            int idx = findColumn(header, name);
            if (idx >= 0) {
                return idx;
            }
        }
        throw new IOException("sheet '" + sheetName + "' 找不到列「" + String.join("/", names)
                + "」（表结构变了？请核对表头）");
    }

    private static int findColumn(Row header, String name) {
        for (int c = header.getFirstCellNum(); c < header.getLastCellNum(); c++) {
            if (name.equals(ExcelReader.cellText(header.getCell(c)).trim())) {
                return c;
            }
        }
        return -1;
    }
}
