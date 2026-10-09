package com.aolandai.dingtou.service.kol;

import com.aolandai.dingtou.config.KolProperties;
import com.aolandai.dingtou.dto.kol.CycleRange;
import com.aolandai.dingtou.dto.kol.DeliveryStatusResult;
import com.aolandai.dingtou.dto.kol.DeliveryStatusRow;
import com.aolandai.dingtou.dto.qianchuan.PlanAweme;
import com.aolandai.dingtou.dto.qianchuan.PlanAwemeLookupResult;
import com.aolandai.dingtou.dto.qianchuan.PlanRef;
import com.aolandai.dingtou.dto.sheet.AccountRow;
import com.aolandai.dingtou.exception.BizException;
import com.aolandai.dingtou.service.qianchuan.PlanAwemeService;
import com.aolandai.dingtou.source.AccountDictReader;
import com.aolandai.dingtou.source.KolDeliverySheetReader;
import com.aolandai.dingtou.source.KolDeliverySheetWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「交付状态」检查：<b>看上一周期被催更的达人有没有交付新素材</b>。
 *
 * <h2>流程</h2>
 * <pre>
 * 1. 找【上一个周期】sheet（如当前是 2026-10-11到2026-11-01 → 上一个 2026-09-13到2026-10-04）
 * 2. 判定窗口 = [上一周期结束日, 本周期开始日]（闭区间）
 * 3. 逐行拿 C 列「计划 ID」→ 调【乘方计划详情】拿计划名（复用 PlanAwemeService，带批量 + 限流 + 兜底）
 * 4. 从计划名里提取 M.D 日期（如「朗朗呀-儿童彩妆套装-10.4更新」→ 10.4）
 * 5. 日期落在窗口内 → 「已交付」；否则「未交付」
 * 6. 写回「交付状态」列
 * </pre>
 *
 * <h2>⚠️ 计划名的格式很杂（实测）</h2>
 * <pre>
 * 朗朗呀-儿童彩妆套装-10.4更新           → 分隔符 -
 * 小骆爱健身\t橄榄油-7.26更新_转投         → 名字里带制表符
 * 旺崽小奶乐-抚触油-2.12更新（复活）_转投    → 后缀「（复活）」
 * 楠楠爸妈-洗护二合一/9.22更新_转投        → 分隔符 / 而不是 -
 * </pre>
 * <p>所以提取策略是<b>以「更新」为锚点</b>找前面的 {@code M.D}；找不到再退化成「最后一个 M.D」。</p>
 *
 * <h2>⚠️ 表里没有 advertiser_id</h2>
 * <p>只有账户名（如「官方【母婴商品】」）→ 用账户字典换成 ID；换不到的记 warning 跳过。</p>
 */
@Component
public class DeliveryStatusChecker {

    private static final Logger log = LoggerFactory.getLogger(DeliveryStatusChecker.class);

    /** 优先：{@code M.D} 紧跟「更新」二字。 */
    private static final Pattern DATE_BEFORE_UPDATE =
            Pattern.compile("(\\d{1,2})\\.(\\d{1,2})[\\s\\u3000]*(?=更新)");

    /** 兜底：任意 {@code M.D}，取<b>最后一个</b>。 */
    private static final Pattern ANY_MD = Pattern.compile("(\\d{1,2})\\.(\\d{1,2})");

    private final KolDeliverySheetReader reader;
    private final KolDeliverySheetWriter writer;
    private final AccountDictReader accountDictReader;
    private final PlanAwemeService planAwemeService;
    private final KolProperties properties;

    public DeliveryStatusChecker(KolDeliverySheetReader reader,
                                 KolDeliverySheetWriter writer,
                                 AccountDictReader accountDictReader,
                                 PlanAwemeService planAwemeService,
                                 KolProperties properties) {
        this.reader = reader;
        this.writer = writer;
        this.accountDictReader = accountDictReader;
        this.planAwemeService = planAwemeService;
        this.properties = properties;
    }

    /**
     * 执行检查。
     *
     * @param referenceDate 参考日期，用于定位「上一个周期」；{@code null} = 今天
     * @param dryRun        {@code true} = 只算不写（受 {@code dingtou.dry-run} 保护）
     */
    public DeliveryStatusResult check(LocalDate referenceDate, boolean dryRun) throws Exception {
        LocalDate ref = referenceDate == null ? LocalDate.now() : referenceDate;
        List<String> warnings = new ArrayList<>();

        // 1) 上一个周期
        Optional<CycleRange> prevOpt = reader.resolvePreviousCycle(ref);
        if (prevOpt.isEmpty()) {
            throw BizException.businessFail("找不到「上一个周期」：参考日 " + ref
                    + " 之前没有任何周期 sheet。请核对表 " + reader.filePath());
        }
        CycleRange prev = prevOpt.get();
        LocalDate lastSunday = KolDeliverySheetReader.previousSunday(ref);

        // 2) 判定窗口 = [上一周期结束日, 本周期开始日]
        LocalDate windowStart = prev.endDate();
        LocalDate windowEnd = reader.nextCycleOf(prev)
                .map(CycleRange::startDate)
                .orElse(windowStart.plusDays(7));
        log.info("交付状态检查：参考日 {} → 上一个周日 {} → sheet='{}' 里「周期时间={}」的行；"
                        + "判定窗口 [{} ~ {}]",
                ref, lastSunday, prev.sheetName(), lastSunday, windowStart, windowEnd);

        // 3) 读行 —— ⚠️ 只读 J 列「周期时间」== 上一个周日 的行（不是整个 sheet）
        List<DeliveryStatusRow> rows = reader.readRows(prev, lastSunday);
        Map<String, Long> idByName = accountIdByName();
        List<PlanRef> refs = new ArrayList<>();
        Set<PlanRef> seenRefs = new HashSet<>();
        int unknownAccount = 0;
        for (DeliveryStatusRow row : rows) {
            Long advertiserId = idByName.get(row.advertiserName());
            if (advertiserId == null) {
                unknownAccount++;
                warnings.add("第 " + row.rowNumber() + " 行的账户「" + row.advertiserName()
                        + "」在账户字典里找不到，已跳过");
                continue;
            }
            try {
                Long.parseLong(row.adId());
            } catch (NumberFormatException e) {
                warnings.add("第 " + row.rowNumber() + " 行的计划 ID 不是数字：「" + row.adId()
                        + "」，已跳过");
                continue;
            }
            // ⚠️ 查询按【账户+计划】去重（同一个计划在表里可能出现多行，查一次就够），
            //    但【写回要按行】—— 用 Set 去重查询，用行号写回。
            // ⚠️ 变量名别叫 ref —— 方法开头已经有个 LocalDate ref（参考日期），
            //    Java 不允许内层块遮蔽局部变量，会直接编译不过
            PlanRef planRef = new PlanRef(advertiserId, Long.parseLong(row.adId()));
            if (seenRefs.add(planRef)) {
                refs.add(planRef);
            }
        }
        if (unknownAccount > 0) {
            warnings.add("共 " + unknownAccount + " 行因账户名映射不到被跳过，请核对账户字典");
        }
        if (refs.isEmpty()) {
            throw BizException.businessFail("上一周期（" + prev.sheetName()
                    + "）里没有任何可处理的【账户 + 计划】，请核对表内容与账户字典");
        }

        // 4) 批量查计划名（复用 PlanAwemeService：批量 + 限流 + 抖音号兜底）
        PlanAwemeLookupResult lookup = planAwemeService.lookupAll(refs);
        warnings.addAll(lookup.warnings());
        Map<Long, String> planNameByAdId = new HashMap<>();
        for (PlanAweme item : lookup.items()) {
            if (!item.planName().isEmpty()) {
                planNameByAdId.put(item.adId(), item.planName());
            }
        }

        // 5) 判断 + 收集要写的值
        // ⚠️ 按【行】遍历写回：同一计划在表里可能有多行，每行都要写
        Map<Integer, String> cells = new LinkedHashMap<>();
        Set<Long> unresolvedWarned = new HashSet<>();
        int delivered = 0;
        int notDelivered = 0;
        int unresolved = 0;
        int skippedRows = 0;
        for (DeliveryStatusRow row : rows) {
            Long advertiserId = idByName.get(row.advertiserName());
            if (advertiserId == null) {
                skippedRows++;
                continue;
            }
            long adId;
            try {
                adId = Long.parseLong(row.adId());
            } catch (NumberFormatException e) {
                skippedRows++;
                continue;
            }
            String planName = planNameByAdId.get(adId);
            Optional<LocalDate> date = planName == null
                    ? Optional.empty() : extractDate(planName, windowStart, windowEnd);
            boolean ok = date.isPresent()
                    && !date.get().isBefore(windowStart) && !date.get().isAfter(windowEnd);
            if (date.isEmpty()) {
                // ⚠️ 解析不出日期 → 【留空，什么都不写】（老板 2026-10-06：属于异常情况不用管）。
                //    不能写「未交付」—— 那会污染人工的判断，也看不出「这行没检查成功」。
                unresolved++;
                if (unresolvedWarned.add(adId)) {
                    warnings.add("计划 " + adId + "（第 " + row.rowNumber() + " 行）：计划名 "
                            + (planName == null ? "（没查到）" : "「" + planName + "」")
                            + " 里解析不出日期，【交付状态留空】请人工处理");
                }
                continue;
            }
            if (ok) {
                delivered++;
            } else {
                notDelivered++;
            }
            cells.put(row.rowNumber(),
                    ok ? properties.getDeliveredValue() : properties.getNotDeliveredValue());
        }

        // 6) 写回
        int written = writer.writeStatus(prev, cells, dryRun);

        if (skippedRows > 0) {
            warnings.add("共 " + skippedRows + " 行因账户映射不到 / 计划 ID 非数字被跳过");
        }
        log.info("交付状态检查完成：sheet='{}' 周期时间={} 共 {} 行 → 已交付 {} / 未交付 {}"
                        + "，另有 {} 行解析不出日期【留空】{}",
                prev.sheetName(), lastSunday, rows.size(), delivered, notDelivered, unresolved,
                dryRun ? "【dry-run 未真写】" : ("；已写入 " + written + " 行"));

        return new DeliveryStatusResult(prev.sheetName(), windowStart, windowEnd, lastSunday,
                rows.size(), delivered, notDelivered, unresolved, written, dryRun, warnings);
    }

    // ==================== 内部 ====================

    /** 账户名 → 账户 ID。 */
    private Map<String, Long> accountIdByName() throws Exception {
        Map<String, Long> out = new HashMap<>();
        for (AccountRow account : accountDictReader.readAll()) {
            Long id = account.advertiserIdAsLong();
            if (id != null && account.name() != null && !account.name().isBlank()) {
                out.put(account.name().trim(), id);
            }
        }
        return out;
    }

    /**
     * 从计划名里提取 {@code M.D} 日期。
     *
     * <p>⚠️ <b>年份靠推断</b>：计划名里只有 {@code M.D}（如 {@code 10.4}），没有年份。
     * 做法是拿窗口结束日的年份生成 {@code year-1 / year / year+1} 三个候选，
     * <b>排除未来日期后取最近的一次</b> —— 因为「素材更新」永远是已经发生的事。</p>
     *
     * <p>这样跨年周期（如 12 月到次年 1 月）也不会错：{@code 1.3} 会被推成次年 1 月 3 日。</p>
     *
     * <p>包级可见是为了单测能直接验日期解析。</p>
     */
    static Optional<LocalDate> extractDate(String planName, LocalDate windowStart,
                                           LocalDate windowEnd) {
        if (planName == null || planName.isBlank()) {
            return Optional.empty();
        }
        // ⚠️⚠️ 必须用 group(1)/group(2)，【不能】用 group() ——
        //    group() 是「整个匹配」，会把 `\s*` 匹配到的尾随空格也带进来，
        //    于是 `Integer.parseInt("9 ")` 抛 NumberFormatException，
        //    被 catch 成「解析不出日期」→ 实测「7.9 更新」整批漏判（踩过）
        String monthText;
        String dayText;
        Matcher before = DATE_BEFORE_UPDATE.matcher(planName);
        if (before.find()) {
            monthText = before.group(1);
            dayText = before.group(2);
        } else {
            Matcher any = ANY_MD.matcher(planName);
            String lastM = null;
            String lastD = null;
            while (any.find()) {
                lastM = any.group(1);
                lastD = any.group(2);
            }
            if (lastM == null) {
                return Optional.empty();
            }
            monthText = lastM;
            dayText = lastD;
        }
        int month;
        int day;
        try {
            month = Integer.parseInt(monthText);
            day = Integer.parseInt(dayText);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }

        // ⚠️ 规则是「取【不晚于窗口结束日】的最近一次」，不是「离窗口最近」。
        //    实测反例：窗口 10-04~10-11、计划名「2.12更新」——
        //    「离窗口最近」会算出 2027-02-12（未来！），而素材其实是 2026-02-12 更新的。
        //    素材更新永远是【过去】的事，所以先排除未来日期，再取最近的一次。
        LocalDate best = null;
        LocalDate earliest = null;
        for (int yearOffset = -1; yearOffset <= 1; yearOffset++) {
            LocalDate candidate;
            try {
                candidate = LocalDate.of(windowEnd.getYear() + yearOffset, month, day);
            } catch (Exception e) {
                continue;   // 2.30 这种非法日期
            }
            if (earliest == null || candidate.isBefore(earliest)) {
                earliest = candidate;
            }
            if (candidate.isAfter(windowEnd)) {
                continue;   // 未来日期不可能（素材更新是已经发生的事）
            }
            if (best == null || candidate.isAfter(best)) {
                best = candidate;
            }
        }
        // 极端兜底：三个候选全在未来（几乎不可能）→ 取最早的那个，别返回空
        return Optional.ofNullable(best != null ? best : earliest);
    }


}
