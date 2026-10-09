package com.aolandai.dingtou.service.qianchuan;

import com.aolandai.dingtou.client.QianchuanClientFactory;
import com.aolandai.dingtou.config.QianchuanProperties;
import com.aolandai.dingtou.dto.qianchuan.PlanInfo;
import com.aolandai.dingtou.dto.qianchuan.PlanQueryOutcome;
import com.aolandai.dingtou.dto.qianchuan.QianchuanPlanQueryResult;
import com.aolandai.dingtou.dto.sheet.AccountRow;
import com.aolandai.dingtou.exception.RateLimitException;
import com.aolandai.dingtou.source.AccountDictReader;
import com.aolandai.dingtou.source.ProductDictReader;
import com.aolandai.dingtou.store.PlanEntry;
import com.aolandai.dingtou.store.PlanLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link PlanQueryService} 的默认实现：<b>只在指定账户里查</b>。
 *
 * <h2>🔴 为什么不再遍历账户（2026-10-05 老板确认的业务口径）</h2>
 * <p>老板核实后确认：<b>一个商品只能在它授权的那个店铺下建计划</b> ——
 * 所以【达人 + 商品】的千川计划<b>只可能</b>存在于【定投素材表 {@code E 全域账号} 列】
 * 对应的那一个账户下，其余账户不可能有。</p>
 *
 * <p>在此之前这里有过<b>三套</b>「定位账户」的机制，全部已删除：</p>
 * <ul>
 *   <li>《定向投放登记表》路由（命中就用、未命中回退全量 8 账户）；</li>
 *   <li>「指定账户未命中 → 遍历其余 7 个账户再确认」的回退；</li>
 *   <li>{@code AccountRoutingMode.SCAN_ALL}（永远全量扫描）。</li>
 * </ul>
 * <p>它们都是「不确定计划在哪个账户」时代的产物。现在账户来源<b>只有一条</b>：
 * 调用方（{@code PlanTargetResolver}）从素材表 {@code E 列}解析出唯一账户后传进来。</p>
 *
 * <h2>⚠️ 为什么值得为此删掉一大套代码</h2>
 * <p>实测：查计划要遍历 8 个账户时，一次调用耗时 <b>2 分 31 秒</b>（还频繁撞 40100 全局频控）；
 * 只查 1 个账户后调用量降到 1/8。这是整个流程里最贵的一步。</p>
 *
 * <h2>⚠️ 两个必须记住的点</h2>
 * <ul>
 *   <li><b>必须按商品 ID 精确匹配</b>：SDK 的 {@code search_keyword_type=AWEME} 只能按抖音号过滤，
 *       商品维度是本地比对 {@code productInfo.productId}；</li>
 *   <li><b>账户查失败 ≠ 没有计划</b>：失败要记进 {@code failedAccounts}（<b>不是</b> {@code warnings}），
 *       让调用方能看到并中止 —— 否则「漏查了」会被当成「该组合没有计划」→ <b>重复建计划（花钱）</b>。</li>
 * </ul>
 */
@Component
public class DefaultPlanQueryService implements PlanQueryService {

    private static final Logger log = LoggerFactory.getLogger(DefaultPlanQueryService.class);

    private final QianchuanClientFactory clientFactory;
    private final AccountDictReader accountDictReader;
    private final ProductDictReader productDictReader;
    private final PlanLedger planLedger;
    private final QianchuanProperties properties;

    public DefaultPlanQueryService(QianchuanClientFactory clientFactory,
                                   AccountDictReader accountDictReader,
                                   ProductDictReader productDictReader,
                                   PlanLedger planLedger,
                                   QianchuanProperties properties) {
        this.clientFactory = clientFactory;
        this.accountDictReader = accountDictReader;
        this.productDictReader = productDictReader;
        this.planLedger = planLedger;
        this.properties = properties;
    }

    @Override
    public PlanQueryOutcome query(String awemeShowId, String productName, long advertiserId)
            throws Exception {
        List<String> warnings = new ArrayList<>();
        // ⚠️ 与 warnings 分开收集：warnings 里可能有「商品没匹配上」这类提示，
        //    拿它判断「查询是否完整」会把正常情况误判成失败（探针实测踩过）。
        List<String> failedAccounts = new ArrayList<>();
        Optional<PlanEntry> ledgerHit = planLedger.find(awemeShowId, productName);

        Optional<String> productId = productDictReader.uniqueProductIdOfName(productName);
        if (productId.isEmpty()) {
            warnings.add("商品名 '" + productName
                    + "' 在【商品 ID 映射表】里匹配不到唯一商品 ID，无法按商品维度查计划");
            log.warn("计划查询跳过：{}", warnings.get(warnings.size() - 1));
            return new PlanQueryOutcome(awemeShowId, productName, "", List.of(),
                    ledgerHit, 0, 0, List.of(), warnings, failedAccounts);
        }

        Optional<AccountRow> account = findById(accountDictReader.readAll(), advertiserId);
        if (account.isEmpty()) {
            // ⚠️ 指定的账户不在字典里 → 不能静默当成「没有计划」（那会导致重复建计划花钱）
            String detail = "指定的投放账户 id=" + advertiserId
                    + " 在【投放账户信息表】里找不到，无法确认该组合有没有计划";
            warnings.add(detail);
            failedAccounts.add(detail);
            log.warn("指定账户 {} 不在账户字典里：{} + {}", advertiserId, awemeShowId, productName);
            return new PlanQueryOutcome(awemeShowId, productName, productId.get(), List.of(),
                    ledgerHit, 0, 0, List.of(), warnings, failedAccounts);
        }

        return doQuery(awemeShowId, productName, productId.get(), List.of(account.get()),
                ledgerHit, warnings, failedAccounts);
    }

    /** 在账户字典里按账户 ID 找。 */
    private static Optional<AccountRow> findById(List<AccountRow> accounts, long advertiserId) {
        for (AccountRow account : accounts) {
            if (Objects.equals(account.advertiserIdAsLong(), advertiserId)) {
                return Optional.of(account);
            }
        }
        return Optional.empty();
    }

    /**
     * 真正去查（当前只会传一个账户，保留 {@code List} 是为了「将来可能扩多账户」时不改签名）。
     *
     * <p>要查的场景来自配置 {@code dingtou.qianchuan.query-scenes}
     * （默认全域 + 乘方）—— 本系统建的是<b>乘方</b>计划，只查全域会漏掉自己建的全部计划。</p>
     */
    private PlanQueryOutcome doQuery(String awemeShowId, String productName, String productId,
                                     List<AccountRow> accounts, Optional<PlanEntry> ledgerHit,
                                     List<String> warnings, List<String> failedAccounts)
            throws Exception {
        QianchuanPlanService planService = clientFactory.planService();
        List<PlanInfo> matched = new ArrayList<>();
        List<PlanInfo> unmatched = new ArrayList<>();
        int scanned = 0;
        int rawPlanCount = 0;

        for (AccountRow account : accounts) {
            Long advertiserId = account.advertiserIdAsLong();
            if (advertiserId == null) {
                warnings.add("账户 '" + account.name() + "' 没有合法的账户 ID，已跳过");
                failedAccounts.add("账户 '" + account.name() + "' 没有合法的账户 ID");
                continue;
            }
            scanned++;
            try {
                QianchuanPlanQueryResult result = planService.queryByAwemeAndProduct(
                        advertiserId, awemeShowId, productId, properties.getQueryScenes());
                if (result == null) {
                    continue;
                }
                rawPlanCount += result.getRawPlanCount();
                for (var plan : result.getPlans()) {
                    matched.add(toPlanInfo(advertiserId, plan));
                }
                for (var plan : result.getUnmatchedPlans()) {
                    unmatched.add(toPlanInfo(advertiserId, plan));
                }
                if (result.productMismatchOnly()) {
                    warnings.add("账户 '" + account.name() + "' 接口返回了 "
                            + result.getRawPlanCount() + " 个计划，但没有一个商品 ID 匹配 '"
                            + productId + "'（可能是商品挂载/映射问题，非『无计划』）");
                    log.warn("账户 {} 商品匹配失败：接口返回 {} 个计划，商品 ID 均不匹配 {}",
                            account.name(), result.getRawPlanCount(), productId);
                }
            } catch (RateLimitException e) {
                throw e;
            } catch (Exception e) {
                warnings.add("账户 '" + account.name() + "' 查询失败: " + e.getMessage());
                failedAccounts.add("账户 '" + account.name() + "' 查询失败: " + e.getMessage());
                log.warn("账户 {} 查询计划失败: {}", account.name(), e.getMessage());
            }
        }

        if (scanned == 0 && !accounts.isEmpty()) {
            warnings.add("没有任何可用的投放账户，未执行查询");
            failedAccounts.add("没有任何可用的投放账户，未执行查询");
        }

        // ⚠️ 老板 2026-10-08：排除「已删除（DELETE）/ 审核不通过（OFFLINE_AUDIT）」的计划 ——
        //    不排除会把它们当成「已有计划」→ 误走「添加素材」→ 必然失败。
        int beforeExclude = matched.size();
        List<PlanInfo> effective = dropExcluded(matched);
        if (effective.size() < beforeExclude) {
            warnings.add("有 " + (beforeExclude - effective.size())
                    + " 个计划状态是「已删除 / 审核不通过」，已排除（当作没有计划）");
        }

        log.info("计划查询 {} + {}：扫描 {} 个账户，接口返回 {} 个计划，命中 {} 个"
                        + "（排除已删除/审核不通过后剩 {} 个），商品未匹配 {} 个，警告 {} 条",
                awemeShowId, productName, scanned, rawPlanCount, beforeExclude,
                effective.size(), unmatched.size(), warnings.size());
        return new PlanQueryOutcome(awemeShowId, productName, productId,
                effective, ledgerHit, scanned, rawPlanCount, unmatched, warnings, failedAccounts);
    }

    /** SDK 计划对象 → 本工程的 {@link PlanInfo}，只留业务要用的字段。 */
    private static PlanInfo toPlanInfo(long advertiserId,
                                       com.bytedance.ads.model
                                               .QianchuanUniPromotionListV10ResponseDataAdListInner plan) {
        var info = plan.getAdInfo();
        List<String> productIds = new ArrayList<>();
        if (plan.getProductInfo() != null) {
            for (var product : plan.getProductInfo()) {
                if (product != null && product.getProductId() != null) {
                    productIds.add(String.valueOf(product.getProductId()));
                }
            }
        }
        return new PlanInfo(
                advertiserId,
                info == null || info.getId() == null ? "" : String.valueOf(info.getId()),
                info == null ? "" : String.valueOf(info.getName()),
                info == null || info.getStatus() == null ? "" : String.valueOf(info.getStatus()),
                productIds);
    }

    /**
     * 剔掉「已删除 / 审核不通过」的计划 —— 老板 2026-10-08：
     * 「排除状态 = {@code DELETE}（已删除）和 {@code OFFLINE_AUDIT}（审核不通过）」。
     *
     * <p>⚠️ 不剔掉的话会把它们当成「已有计划」→ 误走「添加素材」→ <b>必然失败</b>。</p>
     */
    private static List<PlanInfo> dropExcluded(List<PlanInfo> plans) {
        if (plans == null || plans.isEmpty()) {
            return List.of();
        }
        List<PlanInfo> kept = new ArrayList<>(plans.size());
        for (PlanInfo plan : plans) {
            if (plan.isExcluded()) {
                log.info("计划 {} 状态={}（已删除/审核不通过）→ 排除，当作没有计划",
                        plan.adId(), plan.status());
                continue;
            }
            kept.add(plan);
        }
        return kept;
    }
}
