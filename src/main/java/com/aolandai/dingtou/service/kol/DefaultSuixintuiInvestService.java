package com.aolandai.dingtou.service.kol;

import com.aolandai.dingtou.config.DingtouProperties;
import com.aolandai.dingtou.config.SuixintuiProperties;
import com.aolandai.dingtou.dto.sheet.CellWrite;
import com.aolandai.dingtou.dto.kol.SuixintuiInvestResult;
import com.aolandai.dingtou.dto.qianchuan.SuixintuiCreateOutcome;
import com.aolandai.dingtou.dto.qianchuan.SuixintuiOrderParam;
import com.aolandai.dingtou.dto.sheet.AccountRow;
import com.aolandai.dingtou.dto.sheet.DeliveryRowRef;
import com.aolandai.dingtou.exception.BizException;
import com.aolandai.dingtou.service.qianchuan.AwemeUidResolver;
import com.aolandai.dingtou.service.qianchuan.SuixintuiOrderCreateService;
import com.aolandai.dingtou.source.AccountDictReader;
import com.aolandai.dingtou.source.DeliverySheetLocator;
import com.aolandai.dingtou.source.ProductDictReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@link SuixintuiInvestService} 的默认实现。
 *
 * <h2>流程</h2>
 * <pre>
 * 1. 在素材表定位【抖音号 + 商品名】的行
 * 2. 只留【投放类型为空】的行（已填过的历史数据一律不碰）
 * 3. 从这些行的「全域账号」列定位账户 —— 必须唯一
 * 4. 抖音号 → 数值 awemeId（授权达人列表；未授权就建不了）
 * 5. 商品名 → 商品 ID
 * 6. 建随心推（dry-run 时只组装请求体）
 * 7. 返回「请影刀写哪些单元格」
 * </pre>
 *
 * <h2>五个刻意的设计</h2>
 * <ol>
 *   <li><b>服务不写 Excel</b>，只返回 {@link CellWrite} —— 不碰文件格式、不怕被占用
 *       （素材表里有条件格式，POI 改写有丢失风险）；</li>
 *   <li><b>只处理「投放类型为空」的行</b> —— 实测 4992 行里只有 4 行是空的，
 *       所以这条规则天然只碰新行，<b>绝不会误改 3680 行历史数据</b>；</li>
 *   <li><b>账户必须唯一</b>：跨多账户时<b>直接报错要人工确认</b>，绝不瞎挑一个
 *       （投错账户 = 花钱且投错地方）；</li>
 *   <li><b>建不了的行不静默</b>：没账户 / 未授权 / 商品映射不到 → 一律进 {@code warnings}
 *       并明确写出<b>行号与原值</b>，让影刀能拿去推人工补表；</li>
 *   <li><b>dry-run 默认开</b>：{@code amount}/{@code deliveryTime} 的单位文档没写，
 *       第一次必须靠演练核对请求体。</li>
 * </ol>
 */
@Component
public class DefaultSuixintuiInvestService implements SuixintuiInvestService {

    private static final Logger log = LoggerFactory.getLogger(DefaultSuixintuiInvestService.class);

    /** 要写的列：{@code 投放类型}（素材表第 11 列）。 */
    private static final String COLUMN_DELIVERY_TYPE = "K";

    /** 要写的列：{@code 投放状态}（素材表第 14 列）。 */
    private static final String COLUMN_DELIVERY_STATUS = "N";

    /** 投随心推时写入的值。 */
    private static final String VALUE_SUIXINTUI = "全域随心推";

    /** 投放完成后回写 {@code 投放状态} 的值。 */
    private static final String VALUE_STATUS_DONE = "已投";

    private final DeliverySheetLocator locator;
    private final AccountDictReader accountDictReader;
    private final ProductDictReader productDictReader;
    private final AwemeUidResolver awemeUidResolver;
    private final SuixintuiOrderCreateService orderCreateService;
    private final SuixintuiProperties properties;
    private final DingtouProperties dingtouProperties;

    public DefaultSuixintuiInvestService(DeliverySheetLocator locator,
                                         AccountDictReader accountDictReader,
                                         ProductDictReader productDictReader,
                                         AwemeUidResolver awemeUidResolver,
                                         SuixintuiOrderCreateService orderCreateService,
                                         SuixintuiProperties properties,
                                         DingtouProperties dingtouProperties) {
        this.locator = locator;
        this.accountDictReader = accountDictReader;
        this.productDictReader = productDictReader;
        this.awemeUidResolver = awemeUidResolver;
        this.orderCreateService = orderCreateService;
        this.properties = properties;
        this.dingtouProperties = dingtouProperties;
    }

    @Override
    public SuixintuiInvestResult invest(String awemeShowId, String productName) throws Exception {
        long amount = properties.getDefaultAmount();
        double deliveryTime = properties.getDefaultDeliveryTime();
        String bidType = properties.getDefaultBidType();
        Double roiGoal = properties.getDefaultRoiGoal();
        List<String> warnings = new ArrayList<>();

        // 1) 定位素材表行
        List<DeliveryRowRef> rows = locator.locate(awemeShowId, productName);
        if (rows.isEmpty()) {
            throw BizException.businessFail("定投素材表里找不到【" + awemeShowId + " + "
                    + productName + "】的行，请核对抖音号与商品名（或该行还没被写进素材表）");
        }

        // 2) 只处理「投放类型为空」的行
        List<DeliveryRowRef> pending = new ArrayList<>();
        for (DeliveryRowRef row : rows) {
            if (row.typeIsEmpty()) {
                pending.add(row);
            }
        }
        if (pending.isEmpty()) {
            warnings.add("该组合的 " + rows.size() + " 行「投放类型」都已填过（历史数据），本次无需处理");
            log.info("A 场景跳过：{} + {} 的 {} 行都已填过投放类型", awemeShowId, productName, rows.size());
            return emptyResult(awemeShowId, productName, amount, deliveryTime, null, "", warnings);
        }

        // 3) 账户：必须唯一
        Set<String> accounts = new LinkedHashSet<>();
        for (DeliveryRowRef row : pending) {
            if (row.hasAccount()) {
                accounts.add(row.accountName());
            }
        }
        if (accounts.isEmpty()) {
            warnings.add("该组合 " + pending.size() + " 行都没能定位到账户"
                    + "（「全域账号」列为空、或不是账户字典里的账户名），未建随心推。逐行明细：");
            for (DeliveryRowRef row : pending) {
                warnings.add("  第 " + row.rowNumber() + " 行 全域账号="
                        + (row.rawAccount().isEmpty() ? "（空）" : row.rawAccount()));
            }
            return emptyResult(awemeShowId, productName, amount, deliveryTime, null, "", warnings);
        }
        if (accounts.size() > 1) {
            throw BizException.businessFail("该组合的待处理行跨多个账户 " + accounts
                    + "，无法确定随心推建到哪个账户，请人工确认后再跑（不会自动挑一个）");
        }
        String accountName = accounts.iterator().next();
        Optional<AccountRow> accountOpt = accountDictReader.findByName(accountName);
        if (accountOpt.isEmpty() || accountOpt.get().advertiserIdAsLong() == null) {
            warnings.add("账户「" + accountName + "」在【投放账户信息表】里找不到（或没有合法账户 ID），未建随心推");
            return emptyResult(awemeShowId, productName, amount, deliveryTime, null, accountName, warnings);
        }
        long advertiserId = accountOpt.get().advertiserIdAsLong();

        // 4) 抖音号 → 数值 awemeId
        Optional<Long> awemeIdOpt = awemeUidResolver.resolve(advertiserId, awemeShowId);
        if (awemeIdOpt.isEmpty()) {
            warnings.add("账户「" + accountName + "」下没有授权达人「" + awemeShowId
                    + "」，无法建随心推（请先在千川后台完成该达人的授权）");
            return emptyResult(awemeShowId, productName, amount, deliveryTime,
                    advertiserId, accountName, warnings);
        }
        long awemeId = awemeIdOpt.get();

        // 5) 商品名 → 商品 ID
        Optional<String> productIdOpt = productDictReader.uniqueProductIdOfName(productName);
        if (productIdOpt.isEmpty()) {
            warnings.add("商品名「" + productName
                    + "」在【商品 ID 映射表】里匹配不到唯一商品 ID（可能重名或没维护），未建随心推");
            return emptyResult(awemeShowId, productName, amount, deliveryTime,
                    advertiserId, accountName, warnings);
        }
        String productId = productIdOpt.get();

        // 6) 建单（dry-run 时只组装请求体）
        boolean dryRun = dingtouProperties.isDryRun();
        SuixintuiOrderParam param = new SuixintuiOrderParam(
                advertiserId, awemeId, Long.parseLong(productId), amount, deliveryTime,
                bidType, properties.getDefaultQcpxMode(), roiGoal);
        SuixintuiCreateOutcome outcome = orderCreateService.create(param, dryRun);

        // 7) 告诉影刀写哪些单元格
        // ⚠️ 两个列都写（老板 2026-10-05 定）：只写 K 的话「投放状态」会一直停在「待投放」，
        //    看表的人分不清哪些真投了；而且只要影刀写 K 失败一次，下次扫表还会选中该行
        //    → 重复投放（花钱）。写两列等于双重去重。
        List<CellWrite> cells = new ArrayList<>(pending.size() * 2);
        for (DeliveryRowRef row : pending) {
            cells.add(new CellWrite(row.rowNumber(), COLUMN_DELIVERY_TYPE, VALUE_SUIXINTUI));
            cells.add(new CellWrite(row.rowNumber(), COLUMN_DELIVERY_STATUS, VALUE_STATUS_DONE));
        }

        log.info("A 场景完成：{} + {} → 账户 {}，随心推 {}，待写 {} 个单元格，警告 {} 条",
                awemeShowId, productName, accountName,
                dryRun ? "【dry-run 未真建】" : ("orderId=" + outcome.orderId()),
                cells.size(), warnings.size());

        return new SuixintuiInvestResult(awemeShowId, productName, advertiserId, accountName,
                awemeId, productId, amount, deliveryTime, bidType, roiGoal, dryRun,
                outcome.orderId(), outcome.requestBody(), cells, warnings);
    }

    /** 统一构造「没建单」的结果，避免各处重复拼装。 */
    private SuixintuiInvestResult emptyResult(String awemeShowId, String productName,
                                              long amount, double deliveryTime,
                                              Long advertiserId, String advertiserName,
                                              List<String> warnings) {
        return new SuixintuiInvestResult(awemeShowId, productName, advertiserId, advertiserName,
                null, null, amount, deliveryTime, properties.getDefaultBidType(),
                properties.getDefaultRoiGoal(), dingtouProperties.isDryRun(), null, "",
                List.of(), warnings);
    }
}
