package com.aolandai.dingtou.source;

import com.aolandai.dingtou.config.SourceProperties;
import com.aolandai.dingtou.dto.sheet.AccountRow;
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
import java.util.Set;

/**
 * 读【投放账户信息表】—— 8 个千川投放账户的字典。
 *
 * <p>实测表头（逐字）：{@code 投放账户名称} / {@code 投放账户ID} / {@code 投放账户所属店铺}。</p>
 *
 * <p>用途：B / C2 建千川计划时要指定 {@code advertiserId}；步骤 3 遍历账户也要用它。</p>
 */
@Component
public class AccountDictReader {

    private static final Logger log = LoggerFactory.getLogger(AccountDictReader.class);

    private static final String COL_NAME = "投放账户名称";
    private static final String COL_ADVERTISER_ID = "投放账户ID";
    private static final String COL_SHOP = "投放账户所属店铺";

    private final SourceProperties properties;

    /**
     * 缓存：账户字典只有 8 行，但<b>每个组合都会读一次</b>
     * （{@code PlanTargetResolver} → {@code AccountDictReader.readAll()}）
     * → 50 个组合 = 读 50 次文件。缓存以<b>文件修改时间</b>为准。
     */
    private volatile List<AccountRow> cache;
    private volatile java.nio.file.attribute.FileTime cacheStamp;
    private volatile long cacheAt;

    public AccountDictReader(SourceProperties properties) {
        this.properties = properties;
    }

    /** 主动失效缓存。 */
    public void invalidate() {
        cache = null;
        cacheStamp = null;
        cacheAt = 0L;
    }

    /** 读原始表格（诊断用）。 */
    public SheetTable readRaw() throws IOException {
        return ExcelReader.read(properties.getAccountDict(), properties.getAccountSheetName());
    }

    /** 读全部账户。 */
    public List<AccountRow> readAll() throws IOException {
        java.nio.file.Path file = properties.getAccountDict();
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
        List<AccountRow> cached = cache;
        if (cached != null && stamp != null && stamp.equals(cacheStamp)
                && now - cacheAt < ttlMillis) {
            return cached;
        }
        List<AccountRow> rows = doReadAll();
        cache = rows;
        cacheStamp = stamp;
        cacheAt = now;
        return rows;
    }

    /** 真读（不带缓存）。 */
    private List<AccountRow> doReadAll() throws IOException {
        SheetTable table = readRaw();
        int cName = table.requireColumn(COL_NAME);
        int cId = table.requireColumn(COL_ADVERTISER_ID);
        int cShop = table.requireColumn(COL_SHOP);

        List<AccountRow> accounts = new ArrayList<>(table.rowCount());
        for (int i = 0; i < table.rowCount(); i++) {
            AccountRow row = new AccountRow(
                    table.value(i, cName),
                    table.value(i, cId),
                    table.value(i, cShop),
                    i + 2);
            if (row.name().isBlank() && !row.hasAdvertiserId()) {
                continue;
            }
            if (!row.hasAdvertiserId()) {
                log.warn("账户字典第 {} 行没有账户 ID，已跳过: {}", row.rowNumber(), row.name());
                continue;
            }
            accounts.add(row);
        }
        log.info("读取账户字典: {} 个账户（来自 {}）", accounts.size(), properties.getAccountDict());
        return accounts;
    }

    /** 账户名 → 账户行。 */
    public Map<String, AccountRow> byName() throws IOException {
        Map<String, AccountRow> map = new LinkedHashMap<>();
        for (AccountRow row : readAll()) {
            map.putIfAbsent(row.name(), row);
        }
        return map;
    }

    /** 按账户名查账户。 */
    public Optional<AccountRow> findByName(String accountName) throws IOException {
        if (accountName == null || accountName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byName().get(accountName.trim()));
    }

    /** 全部账户 ID（long 形态，直接喂给千川 SDK）。 */
    public Set<Long> advertiserIds() throws IOException {
        Set<Long> ids = new java.util.LinkedHashSet<>();
        for (AccountRow row : readAll()) {
            Long id = row.advertiserIdAsLong();
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }
}
