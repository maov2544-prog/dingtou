package com.aolandai.dingtou.source;

import com.aolandai.dingtou.config.SourceProperties;
import com.aolandai.dingtou.dto.sheet.AccountRow;
import com.aolandai.dingtou.dto.sheet.DeliveryRowRef;
import com.aolandai.dingtou.dto.sheet.SheetTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 在【定投素材表】里定位某个【抖音号 + 商品名】的行，并给出<b>回写所需的信息</b>。
 *
 * <p>⚠️ 本类<b>只读</b>。服务端<b>不写 Excel</b> —— 写入由影刀做（见 {@link DeliveryRowRef}）。</p>
 *
 * <h2>账户从哪来：{@code 全域账号}列 + <b>严格清洗</b></h2>
 * <p>实测该列（4992 行）有 <b>26 种取值</b>，其中只有 6 种能精确匹配账户字典：</p>
 * <pre>
 * 精确匹配 3838 行（76.9%）  |  空 1036 行（20.8%）
 * 变体「官方【母婴-商品】」54 行（多了个横线）  |  脏值 未定向/上/挂错链接/X/标题… 约 35 行
 * </pre>
 *
 * <p><b>清洗规则刻意极简</b>：<b>只认精确匹配</b>，其余（空 / 脏值 / 变体）一律
 * 判为「无账户」并<b>留给调用方报错</b>。</p>
 * <p>为什么不自动纠错（比如把 {@code 官方【母婴-商品】} 纠成 {@code 官方【母婴商品】}）：
 * 老板 2026-10-05 明确「<b>以前的数据可能不一样，新数据会与投放信息表保持一致</b>」——
 * 也就是说老数据的变体<b>不用管</b>；而自动纠错一旦规则写宽，
 * 就可能把某个达人投到<b>错误的账户</b>上（花钱且投错地方），代价远大于省下几行。</p>
 */
@Component
public class DeliverySheetLocator {

    private static final Logger log = LoggerFactory.getLogger(DeliverySheetLocator.class);

    private final SourceProperties properties;
    private final AccountDictReader accountDictReader;
    private final DeliverySheetReader deliverySheetReader;

    public DeliverySheetLocator(SourceProperties properties, AccountDictReader accountDictReader,
                                DeliverySheetReader deliverySheetReader) {
        this.properties = properties;
        this.accountDictReader = accountDictReader;
        this.deliverySheetReader = deliverySheetReader;
    }

    /**
     * 定位【抖音号 + 商品名】对应的全部行。
     *
     * @return 匹配到的行（Excel 行号升序）；<b>找不到返回空列表</b>（调用方应报出来，不要当成「没事」）
     */
    public List<DeliveryRowRef> locate(String awemeShowId, String productName) throws IOException {
        if (awemeShowId == null || awemeShowId.isBlank()
                || productName == null || productName.isBlank()) {
            return List.of();
        }
        String aweme = awemeShowId.trim();
        String product = productName.trim();

        Set<String> knownAccounts = knownAccountNames();
        // ⚠️ 走 DeliverySheetReader（带缓存）—— 原来直接调 ExcelReader.read() 会绕过缓存，
        //    导致「每个组合真读一遍 Excel」（实测单次 350~570ms）
        SheetTable table = deliverySheetReader.readRaw();

        int cAweme = table.requireColumn(DeliverySheetReader.COL_AWEME_SHOW_ID);
        int cProduct = table.requireColumn(DeliverySheetReader.COL_PRODUCT);
        int cAccount = table.columnIndexAny(DeliverySheetReader.COL_GLOBAL_ACCOUNT).orElse(-1);
        int cType = table.columnIndexAny(DeliverySheetReader.COL_DELIVERY_TYPE).orElse(-1);
        int cVideoId = table.columnIndexAny(DeliverySheetReader.COL_VIDEO_ID).orElse(-1);
        if (cAccount < 0) {
            log.warn("定投素材表里找不到「{}」列，所有行都会被判为「无账户」",
                    DeliverySheetReader.COL_GLOBAL_ACCOUNT);
        }
        if (cType < 0) {
            log.warn("定投素材表里找不到「{}」列，无法判断哪些行还没填",
                    DeliverySheetReader.COL_DELIVERY_TYPE);
        }
        if (cVideoId < 0) {
            log.warn("定投素材表里找不到「{}」列，C1 的「加本条素材」将无法定位",
                    DeliverySheetReader.COL_VIDEO_ID);
        }

        List<DeliveryRowRef> hits = new ArrayList<>();
        for (int i = 0; i < table.rowCount(); i++) {
            if (!aweme.equals(table.value(i, cAweme))) {
                continue;
            }
            if (!product.equals(table.value(i, cProduct))) {
                continue;
            }
            String rawAccount = cAccount < 0 ? "" : table.value(i, cAccount);
            hits.add(new DeliveryRowRef(
                    i + 2,                                   // Excel 行号：表头占第 1 行
                    aweme,
                    product,
                    rawAccount,
                    normalizeAccount(rawAccount, knownAccounts),
                    cType < 0 ? "" : table.value(i, cType),
                    cVideoId < 0 ? "" : table.value(i, cVideoId)));
        }
        log.info("素材表定位 {} + {}：命中 {} 行（其中 {} 行还没填投放类型）",
                aweme, product, hits.size(), hits.stream().filter(DeliveryRowRef::typeIsEmpty).count());
        return hits;
    }

    /**
     * 账户名清洗：<b>只认精确匹配账户字典</b>，其余返回空串。
     *
     * <p>（见类注释：刻意不做模糊纠错 —— 投错账户的代价远大于省几行。）</p>
     */
    public static String normalizeAccount(String rawAccount, Set<String> knownAccounts) {
        if (rawAccount == null) {
            return "";
        }
        String trimmed = rawAccount.trim();
        return knownAccounts.contains(trimmed) ? trimmed : "";
    }

    /** 账户字典里的全部账户名（作为清洗白名单）。 */
    private Set<String> knownAccountNames() throws IOException {
        Set<String> names = new LinkedHashSet<>();
        for (AccountRow row : accountDictReader.readAll()) {
            if (!row.name().isBlank()) {
                names.add(row.name().trim());
            }
        }
        return names;
    }
}
