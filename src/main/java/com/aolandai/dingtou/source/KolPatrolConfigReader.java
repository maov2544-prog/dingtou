package com.aolandai.dingtou.source;

import com.aolandai.dingtou.config.SourceProperties;
import com.aolandai.dingtou.dto.kol.KolAccountConfig;
import com.aolandai.dingtou.dto.sheet.SheetTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 读【达人巡查配置表】—— 提供「投放账户 + 消耗金额阈值」。
 *
 * <p>实测表头（逐字）：{@code 账户名称} / {@code 账户 ID} / {@code 消耗金额阈值（元）} /
 * {@code 周期完成状态}（最后一列暂不使用）。</p>
 *
 * <p>用途：查「消耗达标的千川计划」时，<b>用它决定查哪些账户、每个账户的阈值是多少</b>。</p>
 *
 * <h2>两个刻意的设计</h2>
 * <ol>
 *   <li><b>阈值列名用「多候选」匹配</b>：表头里是全角括号 {@code （元）}，
 *       但人手改表时很容易打成半角 {@code (元)} 或干脆删掉。用
 *       {@link SheetTable#columnIndexAny} 兜住这几种写法，避免改个括号服务就崩。</li>
 *   <li><b>阈值解析失败不抛异常，只告警并跳过该账户</b>：这张表是业务同事在维护的，
 *       一格填错不该让整个任务挂掉 —— 但要<b>让调用方看到</b>（进 warnings），
 *       否则「某账户被跳过」会被误当成「该账户没有达标计划」。</li>
 * </ol>
 */
@Component
public class KolPatrolConfigReader {

    private static final Logger log = LoggerFactory.getLogger(KolPatrolConfigReader.class);

    private static final String COL_ACCOUNT_NAME = "账户名称";
    private static final String COL_ADVERTISER_ID = "账户 ID";

    /** 阈值列名候选：全角括号（实际） / 半角括号 / 无括号 / 无「金额」二字。 */
    private static final String[] COL_THRESHOLD_CANDIDATES = {
        "消耗金额阈值（元）",
        "消耗金额阈值(元)",
        "消耗金额阈值",
        "消耗阈值（元）",
        "消耗阈值",
    };

    private final SourceProperties properties;

    public KolPatrolConfigReader(SourceProperties properties) {
        this.properties = properties;
    }

    /** 读原始表格（诊断用）。 */
    public SheetTable readRaw() throws IOException {
        return ExcelReader.read(properties.getKolPatrolConfig(), properties.getKolPatrolSheetName());
    }

    /**
     * 读全部账户配置。
     *
     * <p>⚠️ 与 {@link AccountDictReader#readAll()} 不同，这里<b>保留阈值解析失败的行</b>
     * （{@code thresholdYuan} 为 {@code NaN}），由调用方决定是跳过还是报错 ——
     * 这样「配置错了」在 warnings 里看得见，而不是悄悄消失。</p>
     */
    public List<KolAccountConfig> readAll() throws IOException {
        SheetTable table = readRaw();
        int cName = table.requireColumn(COL_ACCOUNT_NAME);
        int cId = table.requireColumn(COL_ADVERTISER_ID);
        int cThreshold = table.columnIndexAny(COL_THRESHOLD_CANDIDATES).orElse(-1);
        if (cThreshold < 0) {
            log.warn("达人巡查配置表：找不到「消耗金额阈值」列（试过 {}），阈值将全部视为未配置",
                    List.of(COL_THRESHOLD_CANDIDATES));
        }

        List<KolAccountConfig> configs = new ArrayList<>();
        for (int i = 0; i < table.rowCount(); i++) {
            String name = table.value(i, cName);
            String id = table.value(i, cId);
            if (name.isBlank() && id.isBlank()) {
                continue;
            }
            String rawThreshold = cThreshold < 0 ? "" : table.value(i, cThreshold);
            double threshold = parseThreshold(rawThreshold);
            configs.add(new KolAccountConfig(name, id, threshold, i + 2));
        }
        log.info("读取达人巡查配置表: {} 个账户（来自 {}）", configs.size(),
                properties.getKolPatrolConfig());
        return configs;
    }

    /**
     * 解析阈值（元）。
     *
     * @return 阈值；空白或格式不对返回 {@link Double#NaN}，调用方据此跳过该账户
     */
    private static double parseThreshold(String raw) {
        if (raw == null || raw.isBlank()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(raw.replace(",", "").trim());
        } catch (NumberFormatException e) {
            log.warn("达人巡查配置表：阈值 '{}' 不是数字，该账户将被跳过", raw);
            return Double.NaN;
        }
    }

    /** 便捷判断：阈值是否可用（非 NaN）。 */
    public static boolean hasThreshold(KolAccountConfig config) {
        return !Double.isNaN(config.thresholdYuan());
    }
}
