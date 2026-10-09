package com.aolandai.dingtou.source;

import com.aolandai.dingtou.config.SourceProperties;
import com.aolandai.dingtou.dto.sheet.TargetingRow;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 读【定向投放登记表】—— 用「抖音号 + 商品名」定位到投放账户名。
 *
 * <p>⚠️ <b>这是一张「3 行复合表头」的表，通用 {@link ExcelReader}（取第 1 行做表头）读不了它，
 * 所以这里用 POI 专门解析。结构（行号 1-based）：</b></p>
 * <pre>
 * 第 1 行：店铺分组（官方店铺 / 婴童店 / 专卖店 / 对接商务 / 达人抖音信息 …）→ 忽略
 * 第 2 行：投放账户名，【合并单元格】压在一对列之上：
 *          A2:B2 官方【孕产-商品】  C2:D2 官方【青少年-商品】 … O2:P2 专卖【护臀膏-直播】--紫龙
 * 第 3 行：字段行：奇数列 = 投放商品，偶数列 = 授权状态
 * 第 4 行起：数据行
 * </pre>
 *
 * <h2>五个必须记住的坑</h2>
 * <ol>
 *   <li><b>必须展开合并单元格</b>：POI 里合并区域只有左上角有值（B2 是空），
 *       要拿 {@code sheet.getMergedRegions()} 把 A2 的值填回 B2，否则拿不到该列归属哪个账户；</li>
 *   <li><b>抖音号嵌在 R 列（0-based 17）单元格内</b>，形如
 *       {@code 抖音名：小恩恩家有三宝\n抖音号：173701235} —— 按 {@code \n} 切，
 *       取以 {@code 抖音号}（<b>全角冒号</b>）开头的那行再剥前缀。实测取值既有纯数字
 *       也有字母数字混合（{@code jiayinmamaer} / {@code LSW39818}）；</li>
 *   <li><b>投放商品格可能是「多商品连接」</b>：{@code 燕窝气垫-橄榄油}（半角横线）、
 *       {@code 燕窝气垫，山茶花面膜}（全角逗号）→ 先整体匹配，整体匹配不上再拆分逐段匹配；</li>
 *   <li><b>同一【抖音号 + 商品】可能命中多个账户</b>（一行数据横跨 8 个账户列）
 *       → 返回<b>账户列表</b>而不是单个账户；</li>
 *   <li><b>W/X/Y 列（定向5%产品）不是投放商品列，不读</b>；{@code 授权状态} 列（全是 1）也忽略。</li>
 * </ol>
 *
 * <h2>⚠️ 覆盖率极低 —— 调用方必须带回退</h2>
 * <p>实测：表里只有 5 个抖音号 / 7 个（达人,商品）组合，而定投素材表有 1988 个抖音号 /
 * 2770 个组合，三种对齐方式（R 列抖音号、S 列达人UID、R 列抖音名）<b>交集全是 0</b>。
 * 老板确认这张表还没填完。所以 {@link #findAccounts} 未命中时返回 {@link Optional#empty()}，
 * <b>调用方必须回退到全量扫描</b> —— 「未命中就当没有计划」会导致漏掉真计划
 * → 重复建千川计划 → <b>花钱</b>。</p>
 */
@Component
public class TargetingTableReader {

    private static final Logger log = LoggerFactory.getLogger(TargetingTableReader.class);

    /** 第 1 行（0-based）：店铺分组，忽略。 */
    private static final int ROW_SHOP_GROUP = 0;

    /** 第 2 行（0-based）：投放账户名（合并单元格）。 */
    private static final int ROW_ACCOUNT = 1;

    /** 第 3 行（0-based）：字段名（投放商品 / 授权状态）。 */
    private static final int ROW_FIELD = 2;

    /** 数据行从第 4 行（0-based 3）开始。 */
    private static final int FIRST_DATA_ROW = 3;

    /** R 列（0-based 17）：达人抖音信息，抖音号嵌在格内。 */
    private static final int COL_AWEME_INFO = 17;

    /** 只有字段行是这个值的列才是「投放商品」列（授权状态列会被这一步滤掉）。 */
    private static final String FIELD_PRODUCT = "投放商品";

    /** R 列里抖音号那一行的前缀（后面跟全角冒号）。 */
    private static final String AWEME_LABEL = "抖音号";

    /** R 列里抖音名那一行的前缀，用于「整格只有抖音名」时别误当成抖音号。 */
    private static final String AWEME_NAME_LABEL = "抖音名";

    /**
     * 一个投放商品格里多商品的分隔符：半角横线（{@code 燕窝气垫-橄榄油}）、
     * 全角逗号（{@code 燕窝气垫，山茶花面膜}），顺带兼容全角横线与顿号。
     */
    private static final Pattern PRODUCT_SEPARATOR = Pattern.compile("[-－—―,，、]");

    /** 字段行里混着全角/半角空格与换行（实测 H3 是 {@code 授权 \n状态}），比对前先抹平。 */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final SourceProperties properties;

    public TargetingTableReader(SourceProperties properties) {
        this.properties = properties;
    }

    /**
     * 读整张表，展开成一条条【抖音号 + 账户名 + 投放商品】记录。
     *
     * @return 全部记录（顺序：Excel 行号升序、列号升序）
     * @throws IOException 表不存在 / 不可读 / 打开失败
     */
    public List<TargetingRow> readAll() throws IOException {
        Path file = requireFile();
        try (Workbook workbook = WorkbookFactory.create(file.toFile(), null, true)) {
            Sheet sheet = resolveSheet(workbook);
            List<TargetingRow> rows = parse(sheet);
            log.info("读取定向投放登记表: {} 条记录、{} 个抖音号、{} 个【达人+商品】组合（来自 {}）",
                    rows.size(), size(rows), comboCount(rows), file);
            return rows;
        }
    }

    /**
     * 用【抖音号 + 商品名】定位投放账户名列表。
     *
     * <p>匹配策略：<b>先整体匹配，整体匹配不上再拆分逐段匹配</b>
     * （投放商品格可能是 {@code 燕窝气垫-橄榄油} 这样的多商品串）。</p>
     *
     * <p>⚠️ <b>未命中返回 {@link Optional#empty()}，调用方必须回退全量扫描</b>，
     * 不能当成「该组合没有账户 / 没有计划」。</p>
     *
     * @param awemeShowId 抖音号（如 {@code 173701235}、{@code jiayinmamaer}）
     * @param productName 投放商品名（如 {@code 护臀膏}）
     * @return 命中的投放账户名列表（已去重、保持表内顺序）；未命中返回 {@link Optional#empty()}
     * @throws IOException 表不存在 / 不可读 / 打开失败
     */
    public Optional<List<String>> findAccounts(String awemeShowId, String productName)
            throws IOException {
        if (awemeShowId == null || awemeShowId.isBlank()
                || productName == null || productName.isBlank()) {
            return Optional.empty();
        }
        String aweme = awemeShowId.trim();
        String product = productName.trim();

        Set<String> seen = new LinkedHashSet<>();
        List<String> accounts = new ArrayList<>();
        for (TargetingRow row : readAll()) {
            if (!row.awemeShowId().equalsIgnoreCase(aweme)) {
                continue;
            }
            if (!productMatches(row.productText(), product)) {
                continue;
            }
            if (seen.add(row.accountName())) {
                accounts.add(row.accountName());
            }
        }
        if (accounts.isEmpty()) {
            log.info("定向投放登记表未命中: {} + {}（表还没填全，这是常态，调用方应回退全量扫描）",
                    aweme, product);
            return Optional.empty();
        }
        log.info("定向投放登记表命中: {} + {} → 投放账户 {}", aweme, product, accounts);
        return Optional.of(List.copyOf(accounts));
    }

    /**
     * 诊断用：表里一共解析出几个<b>抖音号</b>（去重）。
     *
     * <p>实测真实表是 5 个 —— 跟素材表的 1988 个一比就知道覆盖率有多低。</p>
     */
    public int size() throws IOException {
        return size(readAll());
    }

    /**
     * 诊断用：表里一共解析出几个<b>（抖音号, 单商品）组合</b>（去重，多商品串已拆分）。
     *
     * <p>实测真实表是 7~8 个 —— 素材表是 2770 个。</p>
     */
    public int comboCount() throws IOException {
        return comboCount(readAll());
    }

    // ==================== 解析 ====================

    /** 解析整张表：先定列（账户 → 投放商品列），再逐行取数据。 */
    private static List<TargetingRow> parse(Sheet sheet) {
        List<CellRangeAddress> merges = sheet.getMergedRegions();
        List<AccountColumn> columns = productColumns(sheet, merges);
        if (columns.isEmpty()) {
            log.warn("定向投放登记表：第 2/3 行没有解析出任何【投放商品】列"
                    + "（表头可能被改版），本次查询将全部未命中");
        }

        List<TargetingRow> rows = new ArrayList<>();
        int lastRow = sheet.getLastRowNum();
        for (int r = FIRST_DATA_ROW; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            String awemeShowId = parseAwemeShowId(mergedText(sheet, merges, r, COL_AWEME_INFO));
            if (awemeShowId.isEmpty()) {
                continue;
            }
            for (AccountColumn column : columns) {
                String productText = mergedText(sheet, merges, r, column.index());
                if (productText.isBlank()) {
                    continue;
                }
                rows.add(new TargetingRow(awemeShowId, column.accountName(), productText, r + 1));
            }
        }
        return rows;
    }

    /**
     * 定列：哪一列是「投放商品」，它归属于哪个投放账户。
     *
     * <p>两道闸：<b>第 2 行有账户名</b>（合并单元格展开后）+ <b>第 3 行字段是「投放商品」</b>。
     * 第二道闸是必需的 —— 只有它才能把 {@code 授权状态} 列（B/D/F…P，值全是 1）滤掉，
     * 否则「1」会被当成商品名。</p>
     */
    private static List<AccountColumn> productColumns(Sheet sheet, List<CellRangeAddress> merges) {
        int width = headerWidth(sheet);
        List<AccountColumn> columns = new ArrayList<>();
        for (int c = 0; c < width; c++) {
            String accountName = mergedText(sheet, merges, ROW_ACCOUNT, c).trim();
            if (accountName.isEmpty()) {
                continue;
            }
            String field = WHITESPACE.matcher(mergedText(sheet, merges, ROW_FIELD, c))
                    .replaceAll("");
            if (!FIELD_PRODUCT.equals(field)) {
                log.debug("定向投放登记表：第 {} 列（账户 '{}'）的字段是 '{}'，不是【投放商品】，已跳过",
                        columnName(c), accountName, field);
                continue;
            }
            columns.add(new AccountColumn(c, accountName));
        }
        return columns;
    }

    /** 表头 3 行里最宽的一行有多少列。 */
    private static int headerWidth(Sheet sheet) {
        int width = 0;
        for (int r = ROW_SHOP_GROUP; r <= ROW_FIELD; r++) {
            Row row = sheet.getRow(r);
            if (row != null) {
                width = Math.max(width, row.getLastCellNum());
            }
        }
        return width;
    }

    /**
     * 从 R 列格子里剥出抖音号。
     *
     * <p>格内形如 {@code 抖音名：小恩恩家有三宝\n抖音号：173701235}。</p>
     *
     * @return 抖音号；解析不到返回空串（该行将被跳过）
     */
    private static String parseAwemeShowId(String cellText) {
        if (cellText == null || cellText.isBlank()) {
            return "";
        }
        String text = cellText.replace("\r", "");
        if (text.indexOf('\n') < 0) {
            // 兜底：整格就是一行。只有「不像抖音名行」时才当它是抖音号（为将来格式变化留口子）。
            String single = text.trim();
            return single.startsWith(AWEME_NAME_LABEL) ? "" : single;
        }
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith(AWEME_LABEL)) {
                continue;
            }
            String value = trimmed.substring(AWEME_LABEL.length());
            // 剥掉全角/半角冒号与多余空格
            int i = 0;
            while (i < value.length()) {
                char ch = value.charAt(i);
                if (ch == '：' || ch == ':' || ch == ' ' || ch == '\u00A0') {
                    i++;
                } else {
                    break;
                }
            }
            return value.substring(i).trim();
        }
        return "";
    }

    /**
     * 投放商品格是否命中目标商品名：<b>先整体匹配，整体匹配不上再拆分逐段匹配</b>。
     *
     * <p>{@code 燕窝气垫-橄榄油} 要能匹配 {@code 橄榄油}；{@code 燕窝气垫，山茶花面膜}
     * 要能匹配 {@code 燕窝气垫}。</p>
     */
    private static boolean productMatches(String cellText, String productName) {
        if (cellText == null || cellText.isBlank()) {
            return false;
        }
        String raw = cellText.replace("\r", "").trim();
        if (raw.equalsIgnoreCase(productName)) {
            return true;
        }
        String[] segments = PRODUCT_SEPARATOR.split(raw);
        if (segments.length <= 1) {
            return false;
        }
        for (String segment : segments) {
            if (segment.trim().equalsIgnoreCase(productName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取单元格文本；<b>合并单元格只有左上角有值</b>，所以空的时候回查合并区域左上角。
     */
    private static String mergedText(Sheet sheet, List<CellRangeAddress> merges, int row, int col) {
        Row target = sheet.getRow(row);
        String own = target == null ? "" : ExcelReader.cellText(target.getCell(col));
        if (!own.isBlank()) {
            return own;
        }
        for (CellRangeAddress region : merges) {
            if (!region.isInRange(row, col)) {
                continue;
            }
            Row first = sheet.getRow(region.getFirstRow());
            return first == null ? "" : ExcelReader.cellText(first.getCell(region.getFirstColumn()));
        }
        return "";
    }

    /**
     * 选 sheet：配了名字就按名字找，没配就取第一个。
     *
     * <p>⚠️ 刻意不写死 sheet 名：业务表可能被改名/重建，写死会在别人改表后直接崩。</p>
     */
    private Sheet resolveSheet(Workbook workbook) throws IOException {
        String sheetName = properties == null ? null : properties.getTargetingSheetName();
        if (sheetName == null || sheetName.isBlank()) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new IOException("【定向投放登记表】里没有任何 sheet");
            }
            return workbook.getSheetAt(0);
        }
        Sheet sheet = workbook.getSheet(sheetName.trim());
        if (sheet == null) {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                names.add(workbook.getSheetName(i));
            }
            throw new IOException("【定向投放登记表】找不到 sheet: '" + sheetName + "'，实际有: " + names);
        }
        return sheet;
    }

    private Path requireFile() throws IOException {
        Path file = properties == null ? null : properties.getTargetingSheet();
        if (file == null) {
            throw new IOException("【定向投放登记表】路径未配置（dingtou.source.targeting-sheet）");
        }
        if (!Files.exists(file)) {
            throw new IOException("【定向投放登记表】不存在: " + file.toAbsolutePath());
        }
        if (!Files.isReadable(file)) {
            throw new IOException("【定向投放登记表】不可读（可能被 Excel 独占占用）: " + file.toAbsolutePath());
        }
        return file;
    }

    private static int size(List<TargetingRow> rows) {
        Set<String> awemes = new LinkedHashSet<>();
        for (TargetingRow row : rows) {
            awemes.add(row.awemeShowId().toLowerCase());
        }
        return awemes.size();
    }

    private static int comboCount(List<TargetingRow> rows) {
        Set<String> combos = new LinkedHashSet<>();
        for (TargetingRow row : rows) {
            String aweme = row.awemeShowId().toLowerCase();
            String raw = row.productText().replace("\r", "").trim();
            for (String segment : PRODUCT_SEPARATOR.split(raw)) {
                String product = segment.trim();
                if (!product.isEmpty()) {
                    combos.add(aweme + "\u0001" + product.toLowerCase());
                }
            }
        }
        return combos.size();
    }

    /** Excel 列名（0 → A），日志里定位用。 */
    private static String columnName(int index) {
        StringBuilder name = new StringBuilder();
        int n = index + 1;
        while (n > 0) {
            int remainder = (n - 1) % 26;
            name.insert(0, (char) ('A' + remainder));
            n = (n - 1) / 26;
        }
        return name.toString();
    }

    /** 一个「投放商品」列 + 它归属的投放账户。 */
    private record AccountColumn(int index, String accountName) {
    }
}
