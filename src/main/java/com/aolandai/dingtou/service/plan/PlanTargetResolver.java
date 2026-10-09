package com.aolandai.dingtou.service.plan;

import com.aolandai.dingtou.dto.sheet.AccountRow;
import com.aolandai.dingtou.dto.sheet.DeliveryRowRef;
import com.aolandai.dingtou.exception.BizException;
import com.aolandai.dingtou.service.qianchuan.AwemeUidResolver;
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
 * 把【抖音号 + 商品名】解析成投放所需的<b>全部上下文</b>。
 *
 * <p>一条链路四步：素材表定位行 → 定位账户 → 抖音号转数值 UID → 商品名转商品 ID。</p>
 *
 * <h2>⚠️ 失败语义刻意分两类</h2>
 * <ul>
 *   <li><b>可以继续等人工补数据</b>（没定位到行 / 没账户 / 未授权 / 商品映射不到）
 *       → 记 {@code warnings} 并返回 {@link Optional#empty()}，
 *       让上层回一个「没办成 + 原因」的正常应答，而不是抛异常刷错误日志；</li>
 *   <li><b>继续下去会做错事</b>（待处理行<b>跨多个账户</b>）→ 直接抛
 *       {@link BizException}。因为「随便挑一个账户」意味着<b>把钱投到错误的地方</b>，
 *       代价远大于让人工看一眼。</li>
 * </ul>
 *
 * <h2>⚠️ 为什么账户只认精确匹配</h2>
 * <p>素材表 {@code 全域账号} 列 4992 行里有 26 种取值，只有 6 种能精确匹配账户字典
 * （精确 76.9% / 空 20.8% / 变体与脏值约 2.3%）。老板 2026-10-05 明确「老数据的变体不用管」，
 * 所以这里<b>不做模糊纠错</b> —— 把 {@code 官方【母婴-商品】} 自动纠成 {@code 官方【母婴商品】}
 * 一旦规则写宽，就可能把达人投到错误账户上。</p>
 *
 * <h2>⚠️ 与 A 场景（{@code DefaultSuixintuiInvestService}）的关系</h2>
 * <p>A 场景里有一段<b>同构</b>的解析逻辑（它是先写好的，本类是从它提炼出来的形态）。
 * 目前只有 {@code planBuild} 用本类；A 场景的收敛是<b>待办</b> ——
 * 改之前要先确认它的 warning 文案与早返回路径完全一致，别为了「看起来统一」把线上行为改掉。</p>
 */
@Component
public class PlanTargetResolver {

    private static final Logger log = LoggerFactory.getLogger(PlanTargetResolver.class);

    /**
     * 解析成功后的投放上下文。
     *
     * @param allRows        该【抖音号+商品】的<b>全部</b>行（回写范围用）
     * @param pendingRows    其中「投放类型为空」的行（<b>待处理</b>的行）
     * @param advertiserId   投放账户 ID
     * @param advertiserName 投放账户名（已清洗）
     * @param awemeUid       达人的数值 UID（{@code aweme_uid}）
     * @param productId      商品 ID
     */
    public record PlanTarget(
            List<DeliveryRowRef> allRows,
            List<DeliveryRowRef> pendingRows,
            long advertiserId,
            String advertiserName,
            long awemeUid,
            long productId) {
    }

    private final DeliverySheetLocator locator;
    private final AccountDictReader accountDictReader;
    private final com.aolandai.dingtou.source.TargetingRegistryReader targetingRegistryReader;
    private final ProductDictReader productDictReader;
    private final AwemeUidResolver awemeUidResolver;

    public PlanTargetResolver(DeliverySheetLocator locator,
                              AccountDictReader accountDictReader,
                              com.aolandai.dingtou.source.TargetingRegistryReader targetingRegistryReader,
                              ProductDictReader productDictReader,
                              AwemeUidResolver awemeUidResolver) {
        this.locator = locator;
        this.accountDictReader = accountDictReader;
        this.targetingRegistryReader = targetingRegistryReader;
        this.productDictReader = productDictReader;
        this.awemeUidResolver = awemeUidResolver;
    }

    /**
     * 解析投放上下文。
     *
     * @param warnings 收集「没办成」的原因（会被调用方原样回给影刀）
     * @return 解析成功返回上下文；需要人工补数据时返回 {@link Optional#empty()}（原因已进 warnings）
     * @throws BizException 待处理行跨多个账户 —— <b>必须人工确认</b>，绝不自动挑一个
     */
    public Optional<PlanTarget> resolve(String awemeShowId, String productName,
                                        List<String> warnings) throws Exception {
        // 1) 素材表定位行
        List<DeliveryRowRef> rows = locator.locate(awemeShowId, productName);
        if (rows.isEmpty()) {
            warnings.add("定投素材表里找不到【" + awemeShowId + " + " + productName
                    + "】的行，请核对抖音号与商品名（或该行还没被写进素材表）");
            return Optional.empty();
        }

        // 2) 只处理「投放类型为空」的行
        List<DeliveryRowRef> pending = new ArrayList<>();
        for (DeliveryRowRef row : rows) {
            if (row.typeIsEmpty()) {
                pending.add(row);
            }
        }
        if (pending.isEmpty()) {
            warnings.add("该组合的 " + rows.size()
                    + " 行「投放类型」都已填过（历史数据），本次无需处理");
            log.info("planBuild 跳过：{} + {} 的 {} 行都已填过投放类型",
                    awemeShowId, productName, rows.size());
            return Optional.empty();
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
                    + "（「全域账号」列为空、或不是账户字典里的账户名）。逐行明细：");
            for (DeliveryRowRef row : pending) {
                warnings.add("  第 " + row.rowNumber() + " 行 全域账号="
                        + (row.rawAccount().isEmpty() ? "（空）" : row.rawAccount()));
            }
            return Optional.empty();
        }
        if (accounts.size() > 1) {
            throw BizException.businessFail("该组合的待处理行跨多个账户 " + accounts
                    + "，无法确定该投到哪个账户，请人工确认后再跑（不会自动挑一个）");
        }
        String accountName = accounts.iterator().next();

        Optional<AccountRow> accountOpt = accountDictReader.findByName(accountName);
        if (accountOpt.isEmpty() || accountOpt.get().advertiserIdAsLong() == null) {
            warnings.add("账户「" + accountName
                    + "」在【投放账户信息表】里找不到（或没有合法账户 ID）");
            return Optional.empty();
        }
        // ⚠️⚠️ 老板 2026-10-08：《定向投放登记表》的含义是「已经在千川建计划的
        //     该达人的商品和对应的千川账户」→ **命中就优先用登记表给的账户**
        //     （查计划范围收窄到 1 个账户，快很多）。
        //     没命中 → 沿用素材表解析出来的账户（原逻辑）。
        try {
            Optional<com.aolandai.dingtou.source.TargetingRegistryReader.RegistryHit> hit =
                    targetingRegistryReader.find(awemeShowId, productName);
            if (hit.isPresent()
                    && hit.get().accountName() != null && !hit.get().accountName().isBlank()) {
                String registryAccount = hit.get().accountName().trim();
                if (!registryAccount.equals(accountName)) {
                    warnings.add("《定向投放登记表》命中（第 " + hit.get().rowNumber()
                            + " 行）：账户按登记表取「" + registryAccount + "」"
                            + "（素材表「全域账号」是「" + accountName + "」）");
                    accountName = registryAccount;
                    accountOpt = accountDictReader.findByName(accountName);
                    if (accountOpt.isEmpty() || accountOpt.get().advertiserIdAsLong() == null) {
                        warnings.add("登记表里的账户「" + accountName
                                + "」在【投放账户信息表】里找不到，回退用素材表的账户");
                        accountName = accounts.iterator().next();
                        accountOpt = accountDictReader.findByName(accountName);
                    }
                } else {
                    warnings.add("《定向投放登记表》命中（第 " + hit.get().rowNumber()
                            + " 行）：账户与素材表一致，用「" + accountName + "」");
                }
            }
        } catch (Exception e) {
            // ⚠️ 登记表读失败不能拖垮主流程 —— 它只是「收窄查询范围」的优化
            warnings.add("读《定向投放登记表》失败（已忽略，继续用素材表账户）：" + e.getMessage());
        }

        long advertiserId = accountOpt.get().advertiserIdAsLong();

        // 4) 抖音号 → 数值 awemeId
        Optional<Long> awemeUidOpt = awemeUidResolver.resolve(advertiserId, awemeShowId);
        if (awemeUidOpt.isEmpty()) {
            warnings.add("账户「" + accountName + "」下没有授权达人「" + awemeShowId
                    + "」，请先在千川后台完成该达人的授权");
            return Optional.empty();
        }
        long awemeUid = awemeUidOpt.get();

        // 5) 商品名 → 商品 ID
        Optional<String> productIdOpt = productDictReader.uniqueProductIdOfName(productName);
        if (productIdOpt.isEmpty()) {
            warnings.add("商品名「" + productName
                    + "」在【商品 ID 映射表】里匹配不到唯一商品 ID（可能重名或没维护）");
            return Optional.empty();
        }
        long productId = Long.parseLong(productIdOpt.get());

        return Optional.of(new PlanTarget(rows, pending, advertiserId, accountName,
                awemeUid, productId));
    }
}
