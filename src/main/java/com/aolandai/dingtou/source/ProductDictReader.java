package com.aolandai.dingtou.source;

import com.aolandai.dingtou.config.SourceProperties;
import com.aolandai.dingtou.dto.sheet.ProductRow;
import com.aolandai.dingtou.dto.sheet.SheetTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 读【商品 ID 映射表】—— 商品 ID ↔ 投放商品名。
 *
 * <p>实测表头（逐字）：{@code 商品id}（<b>小写 id</b>）/ {@code 投放商品} / {@code 店铺}，共 103 行。</p>
 *
 * <p>⚠️ <b>用途仅限「商品 ID → 商品名」这一个方向</b>（步骤 1 校验是否主推链接、订单表回填商品名）。
 * <b>不要拿它做「商品名 → 商品 ID」</b>，原因见 {@link ProductRow} 的类注释
 * （重名、空 ID、「（次链）」后缀错配）。</p>
 */
@Component
public class ProductDictReader {

    private static final Logger log = LoggerFactory.getLogger(ProductDictReader.class);

    private static final String COL_PRODUCT_ID = "商品id";
    private static final String COL_PRODUCT_NAME = "投放商品";
    private static final String COL_SHOP = "店铺";

    private final SourceProperties properties;

    public ProductDictReader(SourceProperties properties) {
        this.properties = properties;
    }

    public SheetTable readRaw() throws IOException {
        return ExcelReader.read(properties.getProductDict(), properties.getProductSheetName());
    }

    /** 读全部商品映射行（含没有商品 ID 的那一行，交由调用方决定怎么处理）。 */
    public List<ProductRow> readAll() throws IOException {
        SheetTable table = readRaw();
        int cId = table.requireColumn(COL_PRODUCT_ID);
        int cName = table.requireColumn(COL_PRODUCT_NAME);
        int cShop = table.requireColumn(COL_SHOP);

        List<ProductRow> rows = new ArrayList<>(table.rowCount());
        for (int i = 0; i < table.rowCount(); i++) {
            rows.add(new ProductRow(
                    table.value(i, cId),
                    table.value(i, cName),
                    table.value(i, cShop),
                    i + 2));
        }
        long missingId = rows.stream().filter(r -> !r.hasProductId()).count();
        if (missingId > 0) {
            log.warn("商品映射表里有 {} 行没有商品 ID（已知：猫爪润唇膏/专卖店）", missingId);
        }
        log.info("读取商品映射表: {} 行（来自 {}）", rows.size(), properties.getProductDict());
        return rows;
    }

    /**
     * 商品 ID → 商品名。
     *
     * <p>跳过没有 ID 的行；ID 重复时保留第一条并告警（正常数据不应重复）。</p>
     */
    public Map<String, String> productIdToName() throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        for (ProductRow row : readAll()) {
            if (!row.hasProductId()) {
                continue;
            }
            String previous = map.putIfAbsent(row.productId(), row.productName());
            if (previous != null) {
                log.warn("商品 ID {} 重复映射: '{}' 与 '{}'，保留前者",
                        row.productId(), previous, row.productName());
            }
        }
        return map;
    }

    /** 按商品 ID 取商品名；找不到返回空（调用方应保留原始 ID 并记异常）。 */
    public Optional<String> nameOfProductId(String productId) throws IOException {
        if (productId == null || productId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(productIdToName().get(productId.trim()));
    }

    /**
     * 归一化商品名 → 商品 ID 列表。
     *
     * <p>⚠️ <b>会返回多个 ID</b>：映射表里 {@code 青少年身体乳}、{@code 猫爪润唇膏} 各有两个 ID
     * （分属不同店铺）。所以这个方法返回 List 而不是单个值 —— 把歧义暴露给调用方，别偷偷取第一个。</p>
     */
    public Map<String, List<String>> productNameToIds() throws IOException {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (ProductRow row : readAll()) {
            if (!row.hasProductId()) {
                continue;
            }
            map.computeIfAbsent(row.normalizedName(), k -> new ArrayList<>()).add(row.productId());
        }
        return map;
    }

    /**
     * 按商品名找<b>唯一</b>的商品 ID。
     *
     * <p>找不到、或匹配到多个（歧义）时返回空 —— 调用方应当回退到别的取数方式，
     * 而不是赌一个。</p>
     */
    public Optional<String> uniqueProductIdOfName(String productName) throws IOException {
        if (productName == null || productName.isBlank()) {
            return Optional.empty();
        }
        List<String> ids = productNameToIds().get(productName.trim());
        if (ids == null || ids.size() != 1) {
            if (ids != null && ids.size() > 1) {
                log.warn("商品名 '{}' 在映射表里对应 {} 个商品 ID {}，无法唯一确定",
                        productName, ids.size(), ids);
            }
            return Optional.empty();
        }
        return Optional.of(ids.get(0));
    }
}
