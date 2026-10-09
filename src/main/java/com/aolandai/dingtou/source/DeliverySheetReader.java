package com.aolandai.dingtou.source;

import com.aolandai.dingtou.config.SourceProperties;
import com.aolandai.dingtou.dto.sheet.DeliveryRow;
import com.aolandai.dingtou.dto.sheet.SheetTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * 读【定投素材表】（`26年视频交付表.xlsx`）。
 *
 * <p>实测表头 A~N：{@code 日期 / 对接商务 / 抖音名 / 抖音号 / 全域账号 / 视频发布日期 / 产品 /
 * 视频id / 视频链接 / 定向情况 / 投放类型 / 视频无效 / 投放群 / 投放备注}。</p>
 *
 * <p>⚠️ 两个刻意的设计：</p>
 * <ul>
 *   <li><b>sheet 名不写死</b>：业务表按月改名（「9月视频交付」→「10月视频交付」），
 *       配置留空时取<b>第一个</b> sheet，这样换月不会直接崩；</li>
 *   <li><b>「投放状态」列接受旧名</b>：老板要求把「投放备注」改名为「投放状态」，
 *       但改名动作可能滞后于代码上线，所以两个名字都认。</li>
 * </ul>
 */
@Component
public class DeliverySheetReader {

    private static final Logger log = LoggerFactory.getLogger(DeliverySheetReader.class);

    private static final String COL_DATE = "日期";
    private static final String COL_BUSINESS = "对接商务";
    private static final String COL_AWEME_NAME = "抖音名";
    static final String COL_AWEME_SHOW_ID = "抖音号";
    static final String COL_GLOBAL_ACCOUNT = "全域账号";
    private static final String COL_PUBLISH_DATE = "视频发布日期";
    static final String COL_PRODUCT = "产品";
    static final String COL_VIDEO_ID = "视频id";
    private static final String COL_VIDEO_LINK = "视频链接";
    private static final String COL_TARGETING = "定向情况";
    static final String COL_DELIVERY_TYPE = "投放类型";
    private static final String COL_INVALID = "视频无效";
    private static final String COL_GROUP = "投放群";

    /** 投放状态列：新名在前，旧名兜底（老板 2026-09-30 要求把「投放备注」改名）。 */
    private static final String[] COL_STATUS_CANDIDATES = {"投放状态", "投放备注"};

    private final SourceProperties properties;

    /**
     * 缓存：素材表 4992 行，全量读一遍 ~2 秒。
     *
     * <p>⚠️ <b>为什么必须缓存</b>：轮询时是「一次读表 → 逐个组合处理」，
     * 而每个组合的 {@code PlanTargetResolver} 又会 {@code locate()} 一次 →
     * <b>50 个组合 = 读表 50 次</b>（纯浪费 100 秒 IO）。</p>
     *
     * <p>失效条件：① <b>文件修改时间变了</b>（影刀/服务写过后）；
     * ② 超过 {@code sheet-cache-seconds}。</p>
     */
    private volatile List<DeliveryRow> cache;
    private volatile java.nio.file.attribute.FileTime cacheStamp;
    private volatile long cacheAt;

    /** 原始表缓存（{@code locate()} 复用，避免它绕过缓存重复真读）。 */
    private volatile SheetTable rawCache;
    private volatile java.nio.file.attribute.FileTime rawCacheStamp;
    private volatile long rawCacheAt;

    public DeliverySheetReader(SourceProperties properties) {
        this.properties = properties;
    }

    /**
     * 主动失效缓存 —— <b>服务自己写完素材表后必须调</b>，否则会读到写之前的旧数据。
     */
    public void invalidate() {
        cache = null;
        cacheStamp = null;
        cacheAt = 0L;
        rawCache = null;
        rawCacheStamp = null;
        rawCacheAt = 0L;
    }

    /**
     * 读原始表格（诊断用 / 给 {@code DeliverySheetLocator} 复用）。
     *
     * <p>⚠️ <b>必须带缓存</b>：{@code locate()} 原本自己直接调 {@code ExcelReader.read()}，
     * 绕过这里 → 每个组合都真读一遍 Excel（实测单次 350~570ms）。
     * 缓存以<b>文件修改时间</b>为准。</p>
     */
    public SheetTable readRaw() throws IOException {
        java.nio.file.Path file = properties.getDeliverySheet();
        long ttlMillis = Math.max(0, properties.getSheetCacheSeconds()) * 1000L;
        java.nio.file.attribute.FileTime stamp = null;
        if (file != null && java.nio.file.Files.exists(file)) {
            try {
                stamp = java.nio.file.Files.getLastModifiedTime(file);
            } catch (IOException ignored) {
                // 取不到修改时间 → 不走缓存
            }
        }
        long now = System.currentTimeMillis();
        SheetTable cached = rawCache;
        if (cached != null && stamp != null && stamp.equals(rawCacheStamp)
                && now - rawCacheAt < ttlMillis) {
            return cached;
        }
        SheetTable table = ExcelReader.read(file, properties.getDeliverySheetName());
        rawCache = table;
        rawCacheStamp = stamp;
        rawCacheAt = now;
        return table;
    }

    /** 读全部数据行（带缓存：文件没改且未过期就直接返回）。 */
    public List<DeliveryRow> readAll() throws IOException {
        java.nio.file.Path file = properties.getDeliverySheet();
        long ttlMillis = Math.max(0, properties.getSheetCacheSeconds()) * 1000L;
        java.nio.file.attribute.FileTime stamp = null;
        if (file != null && java.nio.file.Files.exists(file)) {
            try {
                stamp = java.nio.file.Files.getLastModifiedTime(file);
            } catch (IOException ignored) {
                // 取不到修改时间 → 不走缓存，直接读
            }
        }
        long now = System.currentTimeMillis();
        List<DeliveryRow> cached = cache;
        if (cached != null && stamp != null && stamp.equals(cacheStamp)
                && now - cacheAt < ttlMillis) {
            log.debug("素材表命中缓存（{} 行）", cached.size());
            return cached;
        }

        List<DeliveryRow> rows = doReadAll();
        cache = rows;
        cacheStamp = stamp;
        cacheAt = now;
        return rows;
    }

    /** 真读（不带缓存）。 */
    private List<DeliveryRow> doReadAll() throws IOException {
        SheetTable table = readRaw();

        // 达人主键与商品维度是聚合的前提，缺了必须当场报错，不能悄悄返回一堆空对象
        int cAwemeShowId = table.requireColumn(COL_AWEME_SHOW_ID);
        int cProduct = table.requireColumn(COL_PRODUCT);

        // 其余列允许缺失（表是人工维护的，缺列时给空串比崩掉更合适）
        int cDate = optional(table, COL_DATE);
        int cBusiness = optional(table, COL_BUSINESS);
        int cAwemeName = optional(table, COL_AWEME_NAME);
        int cGlobalAccount = optional(table, COL_GLOBAL_ACCOUNT);
        int cPublishDate = optional(table, COL_PUBLISH_DATE);
        int cVideoId = optional(table, COL_VIDEO_ID);
        int cVideoLink = optional(table, COL_VIDEO_LINK);
        int cTargeting = optional(table, COL_TARGETING);
        int cDeliveryType = optional(table, COL_DELIVERY_TYPE);
        int cInvalid = optional(table, COL_INVALID);
        int cGroup = optional(table, COL_GROUP);
        int cStatus = optionalAny(table, COL_STATUS_CANDIDATES);

        List<DeliveryRow> rows = new ArrayList<>(table.rowCount());
        for (int i = 0; i < table.rowCount(); i++) {
            rows.add(new DeliveryRow(
                    table.value(i, cDate),
                    table.value(i, cBusiness),
                    table.value(i, cAwemeName),
                    table.value(i, cAwemeShowId),
                    table.value(i, cGlobalAccount),
                    table.value(i, cPublishDate),
                    table.value(i, cProduct),
                    table.value(i, cVideoId),
                    table.value(i, cVideoLink),
                    table.value(i, cTargeting),
                    table.value(i, cDeliveryType),
                    table.value(i, cInvalid),
                    table.value(i, cGroup),
                    table.value(i, cStatus),
                    i + 2));
        }
        log.info("读取定投素材表: {} 行（sheet={}, 文件={}）",
                rows.size(), table.sheetName(), properties.getDeliverySheet());
        return rows;
    }

    /**
     * 只读「需要投放」的行。
     *
     * <p>两个条件同时满足：① {@code 投放类型} 为空（尚未投过）；② {@code 投放状态 = 待投放}。
     * 投放类型非空的都是老数据，不用管。</p>
     */
    public List<DeliveryRow> readAwaitingDelivery() throws IOException {
        List<DeliveryRow> all = readAll();
        List<DeliveryRow> awaiting = all.stream().filter(DeliveryRow::awaitingDelivery).toList();
        log.info("待投放素材: {} 行 / 全表 {} 行", awaiting.size(), all.size());
        return awaiting;
    }

    private static int optional(SheetTable table, String column) {
        OptionalInt index = table.columnIndex(column);
        if (index.isEmpty()) {
            log.warn("定投素材表缺少列 '{}'（sheet={}），该列按空值处理", column, table.sheetName());
        }
        return index.orElse(-1);
    }

    private static int optionalAny(SheetTable table, String... candidates) {
        OptionalInt index = table.columnIndexAny(candidates);
        if (index.isEmpty()) {
            log.warn("定投素材表缺少列 {}（sheet={}），投放状态一律按空值处理",
                    String.join(" / ", candidates), table.sheetName());
        }
        return index.orElse(-1);
    }
}
