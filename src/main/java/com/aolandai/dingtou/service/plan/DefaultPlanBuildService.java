package com.aolandai.dingtou.service.plan;

import com.aolandai.dingtou.client.ApiErrors;
import com.aolandai.dingtou.client.QianchuanClientFactory;
import com.aolandai.dingtou.config.DingtouProperties;
import com.aolandai.dingtou.config.PlanProperties;
import com.aolandai.dingtou.dto.CountResult;
import com.aolandai.dingtou.dto.kol.SuixintuiInvestResult;
import com.aolandai.dingtou.dto.plan.PlanBuildParam;
import com.aolandai.dingtou.dto.plan.PlanBuildResult;
import com.aolandai.dingtou.dto.qianchuan.MaterialRef;
import com.aolandai.dingtou.dto.qianchuan.OverallVideoCreateParam;
import com.aolandai.dingtou.dto.qianchuan.PlanInfo;
import com.aolandai.dingtou.dto.qianchuan.PlanQueryOutcome;
import com.aolandai.dingtou.dto.qianchuan.PlanWriteOutcome;
import com.aolandai.dingtou.dto.qianchuan.CreatePlanResult;
import com.aolandai.dingtou.dto.sheet.CellWrite;
import com.aolandai.dingtou.dto.sheet.DeliveryRowRef;
import com.aolandai.dingtou.exception.BizException;
import com.aolandai.dingtou.dto.api.ErrorCode;
import com.aolandai.dingtou.exception.RateLimitException;
import com.aolandai.dingtou.service.aggregate.CumulativeCountService;
import com.aolandai.dingtou.service.decision.Decision;
import com.aolandai.dingtou.service.decision.DecisionContext;
import com.aolandai.dingtou.service.decision.DecisionEngine;
import com.aolandai.dingtou.service.decision.Scenario;
import com.aolandai.dingtou.service.decision.PlanNamer;
import com.aolandai.dingtou.service.kol.SuixintuiInvestService;
import com.aolandai.dingtou.service.qianchuan.MaterialPoolService;
import com.aolandai.dingtou.service.qianchuan.PlanMaterialAddService;
import com.aolandai.dingtou.service.qianchuan.PlanQueryService;
import com.aolandai.dingtou.service.qianchuan.PlanRenameService;
import com.aolandai.dingtou.service.qianchuan.QianchuanOverallVideoCreateService;
import com.bytedance.ads.model.QianchuanOverallVideoCreateV10Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link PlanBuildService} 的默认实现。
 *
 * <h2>流程</h2>
 * <pre>
 * 1. 解析上下文：素材表定位行 → 定位账户 → 抖音号转数值UID → 商品名转商品ID
 * 2. 查该【抖音号+商品】是否已有千川计划
 *    2.1 ⚠️ 查询有 warning（有账户没查成功）→ 直接中止，绝不猜
 * 3. 无计划（B / C2）：查素材池（全部素材）→ 建乘方计划（素材随建计划带上）+ 命名
 *    有计划（C1）：加本条素材 → 改名
 * 4. 生成回写指令：该组合「投放类型为空」的行 → K=全域PC + N=已投
 * </pre>
 *
 * <h2>⚠️ 三个刻意的设计</h2>
 * <ol>
 *   <li><b>不区分 B 和 C2</b>：两者动作完全相同（都是「无计划 → 建计划 + 加全部素材 + 命名」），
 *       区分它们对调用方没有任何意义，只会多一个出错点。
 *       所以本服务<b>不需要算 N</b> —— 省掉了「读 4992 行素材表」这个最慢的环节。</li>
 *   <li><b>查计划有 warning 就中止</b>：某个账户查询失败时，「没有计划」这个结论<b>不成立</b>。
 *       照常往下走会<b>重复建计划（花钱）</b>。宁可让人工看一眼。</li>
 *   <li><b>服务不写 Excel</b>，只返回 {@link CellWrite} —— 不碰文件格式、不怕被占用。</li>
 * </ol>
 */
@Component
public class DefaultPlanBuildService implements PlanBuildService {

    private static final Logger log = LoggerFactory.getLogger(DefaultPlanBuildService.class);

    /** {@code 投放类型}列（素材表第 11 列，K）。 */
    private static final String COLUMN_DELIVERY_TYPE = "K";

    /** {@code 投放状态}列（素材表第 14 列，N）。 */
    private static final String COLUMN_DELIVERY_STATUS = "N";

    /**
     * 乘方场景回写 {@code 投放类型} 的值。
     *
     * <p>⚠️ 用 {@code 全域PC} 而<b>不是</b>「乘方」：实测素材表 {@code K} 列历史数据里
     * 只有 {@code 全域随心推}（3680 行）和 {@code 全域PC}（1303 行）两种，
     * <b>没有「乘方」这个词</b>。写新词会让新老数据分裂成两套口径，下游按 K 列统计时会漏。</p>
     */
    private static final String VALUE_PLAN_TYPE = "全域PC";

    /** 投放完成后回写 {@code 投放状态} 的值。 */
    private static final String VALUE_STATUS_DONE = "已投";

    /**
     * {@code 投放状态}列里「该组合还有随心推在投放中」的标记前缀
     * （老板 2026-10-07：「当天检测为投放中应该合理标记记录状态当天…可以合并到投放状态列」）。
     *
     * <p>完整值形如 <b>{@code 投放中 10.7}</b> —— 带<b>检测当天</b>的日期，用途是：</p>
     * <ul>
     *   <li><b>当天不再重复检测</b>：影刀扫表时把「日期 = 今天」的排除掉；</li>
     *   <li><b>第二天自动顺延</b>：第二天影刀会再扫到它 → 服务重测 →
     *       仍是投放中就把日期覆盖成 {@code 投放中 10.8}；不投放中了就正常走 A/B/C1/C2。</li>
     * </ul>
     *
     * <p>⚠️ 日期用 {@code M.D}（与素材表其他日期一致，无前导零）。</p>
     */
    private static final String VALUE_STATUS_BLOCKED_PREFIX = "投放中";

    private final PlanTargetResolver targetResolver;
    private final PlanQueryService planQueryService;
    private final MaterialPoolService materialPoolService;
    private final PlanMaterialAddService materialAddService;
    private final PlanRenameService renameService;
    private final QianchuanClientFactory clientFactory;
    private final PlanProperties planProperties;
    private final DingtouProperties dingtouProperties;
    private final CumulativeCountService countService;
    private final SuixintuiInvestService suixintuiInvestService;
    private final com.aolandai.dingtou.source.AnomalySheetWriter anomalySheetWriter;
    private final com.aolandai.dingtou.source.TargetingRegistryWriter targetingRegistryWriter;

    public DefaultPlanBuildService(PlanTargetResolver targetResolver,
                                   PlanQueryService planQueryService,
                                   MaterialPoolService materialPoolService,
                                   PlanMaterialAddService materialAddService,
                                   PlanRenameService renameService,
                                   QianchuanClientFactory clientFactory,
                                   PlanProperties planProperties,
                                   DingtouProperties dingtouProperties,
                                   CumulativeCountService countService,
                                   SuixintuiInvestService suixintuiInvestService,
                                   com.aolandai.dingtou.source.AnomalySheetWriter anomalySheetWriter,
                                   com.aolandai.dingtou.source.TargetingRegistryWriter targetingRegistryWriter) {
        this.targetResolver = targetResolver;
        this.planQueryService = planQueryService;
        this.materialPoolService = materialPoolService;
        this.materialAddService = materialAddService;
        this.renameService = renameService;
        this.clientFactory = clientFactory;
        this.planProperties = planProperties;
        this.dingtouProperties = dingtouProperties;
        this.countService = countService;
        this.suixintuiInvestService = suixintuiInvestService;
        this.anomalySheetWriter = anomalySheetWriter;
        this.targetingRegistryWriter = targetingRegistryWriter;
    }

    @Override
    /**
     * 一站式投放：算 N → 判场景 → 执行。
     *
     * <p>⚠️ <b>老板 2026-10-08：这里统一兜底记异常表</b> ——
     * 任何一步抛异常（解析上下文 / 查计划 / 算 N / 建单 / 建计划 / 加素材）都记一条，
     * 再<b>照常上抛</b>（上层仍要拿到失败，不能吞）。</p>
     *
     * <p>⚠️ 只有「参数错」（{@code ErrorCode.BAD_REQUEST}）<b>不记</b> ——
     * 那是影刀传参问题/配置问题，记进去会把异常表刷满。</p>
     */
    public PlanBuildResult build(PlanBuildParam param) throws Exception {
        try {
            return doBuild(param);
        } catch (BizException e) {
            if (e.errorCode() == ErrorCode.BAD_REQUEST) {
                throw e;   // 参数错：配置/调用方问题，不记异常表
            }
            recordAnomaly(param, "执行", "业务失败",
                    e.errorCode() + " - " + e.getMessage());
            throw e;
        } catch (Exception e) {
            recordAnomaly(param, "执行", "异常",
                    e.getClass().getSimpleName() + " - " + e.getMessage());
            throw e;
        }
    }

    /** 记一条异常（账户信息此时可能还没解析出来，就用「未解析」占位）。 */
    private void recordAnomaly(PlanBuildParam param, String stage, String type, String detail) {
        try {
            anomalySheetWriter.append(com.aolandai.dingtou.source.AnomalySheetWriter.of(
                    param.awemeShowId(), param.productName(), "（未解析到账户）",
                    stage, type, detail));
        } catch (Exception ignored) {
            // ⚠️ 记异常本身失败不能再抛 —— 会盖掉真正的错误
        }
    }

    private PlanBuildResult doBuild(PlanBuildParam param) throws Exception {
        String aweme = param.awemeShowId();
        String product = param.productName();
        if (aweme.isEmpty()) {
            throw BizException.badRequest("awemeShowId（抖音号）必填");
        }
        if (product.isEmpty()) {
            throw BizException.badRequest("productName（商品名）必填");
        }

        LocalDate date = param.executionDate() == null ? LocalDate.now() : param.executionDate();
        boolean dryRun = dingtouProperties.isDryRun();
        List<String> warnings = new ArrayList<>();

        // 1) 解析上下文
        Optional<PlanTargetResolver.PlanTarget> targetOpt =
                targetResolver.resolve(aweme, product, warnings);
        if (targetOpt.isEmpty()) {
            return emptyResult(aweme, product, dryRun, warnings);
        }
        PlanTargetResolver.PlanTarget target = targetOpt.get();
        String planName = PlanNamer.build(aweme, product, date);

        // 2) 查计划
        // ⚠️ 传 target.advertiserId()（素材表 E 列解析出来的账户）→ 先只查它一个。
        //    本系统的计划只可能建在那个账户下，所以命中就省下其余 7 个账户的调用
        //    （实测查计划是 3.5 分钟耗时的大头）。没命中时服务会回退全量再确认一遍。
        PlanQueryOutcome planOutcome = planQueryService.query(aweme, product, target.advertiserId());
        // ⚠️ 提示信息原样带出（含「登记表没填、回退全量扫描」这类正常提示），便于排障
        warnings.addAll(planOutcome.warnings());
        // ⚠️ 但「能否下结论」只看 failedAccounts —— 拿 warnings 判断会把正常提示误判成失败。
        //    有账户查失败时「没有计划」这个结论不成立，照常往下走会重复建计划（花钱）。
        if (!planOutcome.queryComplete()) {
            throw BizException.businessFail("查计划时有账户没查成功（共扫 " + planOutcome.accountsScanned()
                    + " 个账户，失败 " + planOutcome.failedAccounts().size() + " 个），"
                    + "无法确认是否已有计划。为避免重复建计划（花钱）已中止，请人工核对后再跑。"
                    + "明细：" + String.join("；", planOutcome.failedAccounts()));
        }

        // 2.5) ⚠️⚠️ 老板 2026-10-08：计划存在但【不是投放中】→ 记异常表，不添加素材、也不建新计划。
        //      ⚠️ 两个必须守住的边界：
        //        · 只在 plans() 非空时判 —— hasPlan() 可能来自【台账命中】而接口没返回明细，
        //          那种情况不能判「非投放中」（会误伤）。
        //        · status 为空时【不判】—— 接口没给状态就别猜，按原流程走（宁可放过，不要误伤）。
        if (!planOutcome.plans().isEmpty()) {
            boolean anyUnknown = planOutcome.plans().stream()
                    .anyMatch(plan -> plan.status() == null || plan.status().isBlank());
            boolean anyDelivering = planOutcome.plans().stream()
                    .anyMatch(com.aolandai.dingtou.dto.qianchuan.PlanInfo::isDelivering);
            if (!anyUnknown && !anyDelivering) {
                String statuses = planOutcome.plans().stream()
                        .map(com.aolandai.dingtou.dto.qianchuan.PlanInfo::status)
                        .distinct().reduce((a, b) -> a + "/" + b).orElse("");
                String adIds = planOutcome.plans().stream()
                        .map(com.aolandai.dingtou.dto.qianchuan.PlanInfo::adId)
                        .limit(3).reduce((a, b) -> a + "," + b).orElse("（无明细）");
                String detail = "已存在千川计划（adId=" + adIds
                        + "）但状态不是投放中（" + statuses + "），无法添加素材；"
                        + "也未建新计划（计划已存在，避免重复）";
                warnings.add(detail);
                anomalySheetWriter.append(com.aolandai.dingtou.source.AnomalySheetWriter.of(
                        aweme, product, target.advertiserName() + "(" + target.advertiserId() + ")",
                        "查计划", "计划非投放中", detail));
                log.warn("{} + {}：{}", aweme, product, detail);
                return emptyResultWithCells(aweme, product, target, 0, dryRun, warnings, null,
                        List.of());
            }
        }

        // 3) 算 N（累计随心推笔数）—— ⚠️ 之前漏了这一步，导致「无计划」就建计划，
        //    而按需求 N<3 应该走 A（投随心推），**不该建千川计划**。
        CountResult countResult = countService.count(aweme, product);
        int n = countResult.count();

        // 4) 判定场景（复用 DecisionEngine：有计划一律 C1；否则 N<3→A / N=3→B / N>3→C2）
        Decision decision = DecisionEngine.decide(
                new DecisionContext(aweme, product, n, planOutcome.hasPlan()), date);
        // ⚠️ 判定依据走独立的 reason 字段，【不放进 warnings】——
        //    放进去会让影刀/探针以为「没有全部办成」（踩过这个坑）
        String reason = decision.reason();

        // 5) ⚠️⚠️ 按场景判「要不要等」—— 老板 2026-10-08 明确【两个口径不同】：
        //    · A（建随心推订单）→ 受【5 个互斥状态】限制
        //      （DELIVERY_OK / AUDIT / CREATING / BOOK / FROZEN）
        //    · B/C1/C2（建千川乘方计划）→ 只判【是否投放中】（DELIVERY_OK）
        //      因为「同一抖音号 × 同一商品维度下，无论随心推订单处于什么状态，
        //      基本都可以创建成功」→ 判投放中只是【想让这个随心推跑完再建】
        //    ⚠️ 这【不是失败】，是主动等待 —— 调用方不该算进失败数。
        boolean mustWait = switch (decision.scenario()) {
            case A -> countResult.blocked();
            case B, C1, C2 -> countResult.delivering();
        };
        if (mustWait) {
            // 在素材表「投放状态」列记「投放中 M.D」——当天不再检测，第二天自动顺延重测
            List<CellWrite> skipCells = cellsBlocked(target, date);
            if (decision.scenario() == Scenario.A) {
                warnings.add("该组合还有随心推订单占用【互斥名额】（共 " + n + " 笔，其中 "
                        + countResult.blockingStatuses() + " 仍在存续），"
                        + "【建不了新随心推订单】；本次跳过，已在素材表「投放状态」标记为 "
                        + blockedStatusText(date) + "，等它们结束后（第二天）会自动重测");
            } else {
                warnings.add("该组合还有随心推订单【正在投放中】（共 " + n + " 笔），"
                        + "本次跳过、等它跑完再建千川计划；已在素材表「投放状态」标记为 "
                        + blockedStatusText(date) + "，第二天会自动重测");
            }
            log.info("{} + {}：场景 {} 需要等（{}）→ 跳过（已标记 {}）", aweme, product,
                    decision.scenario(), countResult.blockingStatuses(), blockedStatusText(date));
            return emptyResultWithCells(aweme, product, target, n, dryRun, warnings,
                    PlanBuildResult.Action.SKIP_BLOCKED, skipCells);
        }

        // 6) 按场景执行
        // ⚠️⚠️ 老板 2026-10-08：执行阶段的失败（建随心推 / 建计划 / 加素材）**统一在这里兜底记异常表**
        //    —— 比在每个 service 里各记一次改动小、覆盖全（漏一处就少一条记录）。
        //    ⚠️ 记完【照常上抛】：上层（processPending / 影刀）仍要拿到失败，不能吞。
        try {
            return switch (decision.scenario()) {
                case A -> createSuixintui(param, target, n, reason, dryRun, warnings);
                case C1 -> reuseExistingPlan(param, target, planName, planOutcome, n, reason, dryRun, warnings);
                case B, C2 -> createNewPlan(param, target, planName, n, reason, dryRun, warnings);
            };
        } catch (Exception e) {
            String stage = switch (decision.scenario()) {
                case A -> "建随心推";
                case C1 -> "加素材";
                case B, C2 -> "建乘方计划";
            };
            String detail = "场景 " + decision.scenario() + "（" + stage + "）执行失败："
                    + e.getClass().getSimpleName() + " - " + e.getMessage();
            warnings.add(detail);
            // 这里带【真实账户信息】记一条；外层兜底那次是「未解析到账户」的粗略版，
            // 两条都留着便于对照（外层只在异常逃出本方法时才会触发，正常不会重复）
            anomalySheetWriter.append(com.aolandai.dingtou.source.AnomalySheetWriter.of(
                    aweme, product, target.advertiserName() + "(" + target.advertiserId() + ")",
                    stage, "执行失败", detail));
            log.error("{} + {}：{}", aweme, product, detail, e);
            throw e;
        }
    }

    // ==================== A：累计笔数不足 3 → 投随心推 ====================

    /**
     * A 场景：建<b>随心推</b>（不建千川计划）。
     *
     * <p>⚠️ 这里<b>委托</b>给 {@code SuixintuiInvestService}，而不是自己再实现一遍建单 ——
     * A 场景的完整逻辑（定位行 / 账户唯一 / 抖音号转 UID / 商品映射 / 建单 / 生成 cells）
     * 只有一处实现，以后改 A 场景不用改两个地方。</p>
     *
     * <p>代价是会重新解析一次上下文（多读一次素材表，约 2 秒）—— 换取「逻辑不重复」，值得。</p>
     */
    private PlanBuildResult createSuixintui(PlanBuildParam param,
                                            PlanTargetResolver.PlanTarget target,
                                            int n,
                                            String reason,
                                            boolean dryRun,
                                            List<String> warnings) throws Exception {
        SuixintuiInvestResult r = suixintuiInvestService.invest(
                param.awemeShowId(), param.productName());
        warnings.addAll(r.warnings());
        log.info("planBuild（A 投随心推）：{} + {} → 账户={} N={} {}",
                param.awemeShowId(), param.productName(), r.advertiserId(), n,
                dryRun ? "【dry-run 未真建】" : ("orderId=" + r.orderId()));

        return new PlanBuildResult(r.awemeShowId(), r.productName(), r.advertiserId(),
                r.advertiserName(), r.awemeId(), parseOrNull(r.productId()), "",
                n, reason, PlanBuildResult.Action.CREATE_SUIXINTUI, r.orderId(), 0,
                dryRun, r.requestBody(), r.cells(), warnings);
    }

    // ==================== C1：复用已有计划 ====================

    private PlanBuildResult reuseExistingPlan(PlanBuildParam param,
                                              PlanTargetResolver.PlanTarget target,
                                              String planName,
                                              PlanQueryOutcome planOutcome,
                                              int n,
                                              String reason,
                                              boolean dryRun,
                                              List<String> warnings) throws Exception {
        // hasPlan() 为真但拿不到唯一计划：可能是「匹配到多个」或「只有台账命中、接口没查到」
        Optional<PlanInfo> planOpt = planOutcome.single();
        if (planOpt.isEmpty()) {
            throw BizException.businessFail("判定为「已有计划」但拿不到唯一的计划 ID"
                    + "（接口查到 " + planOutcome.plans().size() + " 个，台账命中="
                    + planOutcome.ledgerHit().isPresent() + "）。"
                    + "为避免重复建计划（花钱）已中止，请人工核对千川后台。"
                    + (planOutcome.ambiguous() ? "匹配到多个计划：" + planOutcome.plans() : ""));
        }
        PlanInfo plan = planOpt.get();
        long adId = parseAdId(plan);
        if (plan.advertiserId() != 0 && plan.advertiserId() != target.advertiserId()) {
            // 账户不一致说明「素材表里写的账户」与「计划实际所在账户」不是同一个
            warnings.add("⚠️ 素材表定位到的账户（" + target.advertiserName() + "，id="
                    + target.advertiserId() + "）与计划实际所属账户（id=" + plan.advertiserId()
                    + "）不一致，将以【计划所在账户】为准");
            log.warn("planBuild 账户不一致：素材表={} 计划={}", target.advertiserId(), plan.advertiserId());
        }
        long planAdvertiserId = plan.advertiserId() != 0
                ? plan.advertiserId() : target.advertiserId();

        // 「本条素材」—— 批量调用时可能有多条（同一组合有多个新视频），一次全加进去
        List<Long> videoIds = resolveVideoIds(param, target, warnings);
        if (videoIds.isEmpty()) {
            return emptyResult(param.awemeShowId(), param.productName(), dryRun, warnings);
        }
        List<MaterialRef> materials = new ArrayList<>(videoIds.size());
        for (Long id : videoIds) {
            materials.add(new MaterialRef(null, id, MaterialRef.IMAGE_MODE_VERTICAL));
        }
        PlanWriteOutcome addOutcome = materialAddService.add(planAdvertiserId, adId, target.productId(),
                target.awemeUid(), materials, dryRun);
        warnings.addAll(addOutcome.warnings());

        PlanWriteOutcome renameOutcome = renameService.rename(planAdvertiserId, adId, planName, dryRun);

        log.info("planBuild（C1 复用计划）：{} + {} → 账户={} 计划={} 加素材 1 条 改名={} {}",
                param.awemeShowId(), param.productName(), planAdvertiserId, adId, planName,
                dryRun ? "【dry-run 未真调】" : "");

        return new PlanBuildResult(param.awemeShowId(), param.productName(),
                planAdvertiserId, target.advertiserName(), target.awemeUid(), target.productId(),
                planName, n, reason, PlanBuildResult.Action.REUSE_PLAN, adId, addOutcome.appliedCount(),
                dryRun, addOutcome.requestBody() + "\n--- 改名 ---\n" + renameOutcome.requestBody(),
                cellsOf(target, VALUE_PLAN_TYPE), warnings);
    }

    /**
     * 定位 C1 要加的素材（可能多条）。
     *
     * <p>两种用法：</p>
     * <ul>
     *   <li><b>调用方明确指定</b>（{@code videoIds} 非空）→ <b>直接用，全部加进去</b>。
     *       批量调用（{@code processPending}）走这条：同一组合有多个新视频时一次全加。</li>
     *   <li><b>没指定</b> → 在该组合「投放类型为空」的行里找：
     *       只有唯一一条才用它；<b>多条时返回空 + 报错要人工确认</b>（绝不瞎挑一条）。</li>
     * </ul>
     */
    private static List<Long> resolveVideoIds(PlanBuildParam param,
                                              PlanTargetResolver.PlanTarget target,
                                              List<String> warnings) {
        if (param.hasExplicitVideos()) {
            List<Long> explicit = param.nonNullVideoIds();
            log.info("C1 使用调用方指定的 {} 条素材", explicit.size());
            return explicit;
        }

        List<DeliveryRowRef> withVideo = new ArrayList<>();
        List<String> badRows = new ArrayList<>();
        for (DeliveryRowRef row : target.pendingRows()) {
            if (!row.hasVideoId()) {
                badRows.add("第 " + row.rowNumber() + " 行（空）");
                continue;
            }
            if (row.videoIdAsLong().isEmpty()) {
                badRows.add("第 " + row.rowNumber() + " 行（非数字：" + row.videoId() + "）");
                continue;
            }
            withVideo.add(row);
        }

        if (!badRows.isEmpty()) {
            warnings.add("以下行的「视频id」为空或不是数字，已跳过：" + String.join("、", badRows)
                    + " —— 请人工核对素材表");
        }

        if (withVideo.isEmpty()) {
            warnings.add("该组合没有可用的「视频id」，无法确定要加哪条素材。"
                    + "请在调用时传 videoIds（= 素材表 H 列的值），或先补全素材表");
            return List.of();
        }
        if (withVideo.size() > 1) {
            // ⚠️ 刻意不自动全加：调用方没指定时，多条意味着「服务猜不准」——
            //    宁可让它报错要人工确认，也不要自作主张把不确定的素材投出去。
            warnings.add("该组合有 " + withVideo.size() + " 行「投放类型为空」且都带视频id（第 "
                    + withVideo.stream().map(r -> String.valueOf(r.rowNumber())).toList()
                    + " 行），无法确定「本条」是哪一条。"
                    + "请在调用时传 videoIds（可多条，一次全加）");
            return List.of();
        }
        return List.of(withVideo.get(0).videoIdAsLong().orElseThrow());
    }

    // ==================== B / C2：建新计划 ====================

    private PlanBuildResult createNewPlan(PlanBuildParam param,
                                          PlanTargetResolver.PlanTarget target,
                                          String planName,
                                          int n,
                                          String reason,
                                          boolean dryRun,
                                          List<String> warnings) throws Exception {
        // 1) 素材池：该达人能投这个商品的「全部」素材
        List<MaterialRef> pool = materialPoolService.list(
                target.advertiserId(), target.awemeUid(), target.productId());
        List<MaterialRef> usable = new ArrayList<>();
        for (MaterialRef m : pool) {
            if (m.awemeItemId() != null) {
                usable.add(m);
            } else {
                warnings.add("跳过一条没有抖音视频ID（aweme_item_id）的素材：" + m.describe()
                        + " —— 用 video_id 会强制要求创意标题，而本服务没有标题数据源");
            }
        }
        if (usable.isEmpty()) {
            warnings.add("素材池里没有可用于建计划的素材（账户=" + target.advertiserName()
                    + "，抖音号=" + target.awemeUid() + "，商品ID=" + target.productId()
                    + "，池内共 " + pool.size() + " 条）。请确认该达人是否已授权、商品是否对应");
            return emptyResult(param, target, planName, dryRun, warnings);
        }

        OverallVideoCreateParam createParam = buildCreateParam(target, planName, usable);
        QianchuanOverallVideoCreateService createService = clientFactory.createService();
        // ⚠️ prepare() = 校验 + 组装，不发请求 —— dry-run 用它打印「即将发出的那一份」
        QianchuanOverallVideoCreateV10Request request = createService.prepare(createParam);
        String requestBody = request.toJson();

        Long adId;
        if (dryRun) {
            log.warn("【dry-run】不会真的建乘方计划。即将发出的请求体：{}", requestBody);
            adId = null;
        } else {
            log.info("建乘方计划：账户={} 名称={} 素材={} 条",
                    target.advertiserId(), planName, usable.size());
            CreatePlanResult result = createService.create(createParam);
            if (!result.isSuccess()) {
                RateLimitException rateLimit = ApiErrors.rateLimitOrNull(
                        result.getCode(), "建乘方计划(账户 " + target.advertiserId() + ")",
                        result.getRequestId());
                if (rateLimit != null) {
                    throw rateLimit;
                }
                throw BizException.businessFail("建乘方计划失败：code=" + result.getCode()
                        + " message=" + result.getMessage()
                        + " —— 无法确认是否建成，请人工核对千川后台后再决定是否重跑");
            }
            if (result.getAdId() == null) {
                throw BizException.businessFail("建计划返回成功但没有 adId，"
                        + "无法确认是否真的建成，请人工核对千川后台");
            }
            adId = result.getAdId();

            // ⚠️⚠️ 老板 2026-10-08：「创建乘方计划后还要更新定向投放登记表的
            //     对应达人的投放账户的投放商品，多个商品用逗号隔开」。
            //     这张表的含义就是「已经建过计划的【达人 × 商品 × 账户】」，
            //     登记进去后，下次同组合就能直接命中（走查计划 → 加素材）。
            //     ⚠️ 回写失败【不能拖垮主流程】—— 计划已经建成了，钱已经花了。
            try {
                targetingRegistryWriter.registerProduct(
                        param.awemeShowId(), target.advertiserName(), param.productName());
            } catch (Exception e) {
                warnings.add("计划已建成，但回写《定向投放登记表》失败（不影响本次投放）："
                        + e.getMessage());
                log.warn("回写登记表失败（计划 {} 已建成）: {}", adId, e.getMessage());
            }
        }

        log.info("planBuild（B/C2 建计划）：{} + {} → 账户={} 计划ID={} 名称={} 素材={} 条 {}",
                target.productId(), planName, target.advertiserId(),
                adId == null ? "（dry-run 未真建）" : adId, planName, usable.size(),
                dryRun ? "【dry-run】" : "");

        return new PlanBuildResult(param.awemeShowId(), param.productName(),
                target.advertiserId(), target.advertiserName(),
                target.awemeUid(), target.productId(), planName,
                n, reason, PlanBuildResult.Action.CREATE_PLAN, adId, usable.size(), dryRun, requestBody,
                cellsOf(target, VALUE_PLAN_TYPE), warnings);
    }

    /** 组装「新建乘方计划」的入参（预算 / ROI 等来自配置）。 */
    private OverallVideoCreateParam buildCreateParam(PlanTargetResolver.PlanTarget target,
                                                     String planName,
                                                     List<MaterialRef> materials) {
        OverallVideoCreateParam param = new OverallVideoCreateParam();
        param.setAdvertiserId(target.advertiserId());
        param.setName(planName);
        param.setProductIds(List.of(target.productId()));
        param.setMarketingGoal("VIDEO_PROM_GOODS");

        OverallVideoCreateParam.DeliverySetting setting = new OverallVideoCreateParam.DeliverySetting();
        setting.setBudget(planProperties.getDefaultBudget());
        setting.setRoi2Goal(planProperties.getDefaultRoi2Goal());
        setting.setVideoScheduleType(planProperties.getDefaultVideoScheduleType());
        setting.setSmartBidType(planProperties.getDefaultSmartBidType());
        // 留空 = 不传（用平台默认）；QianchuanOverallVideoCreateService 会把空串转成 null
        setting.setQcpxMode(planProperties.getDefaultQcpxMode());
        setting.setDeepExternalAction(planProperties.getDefaultDeepExternalAction());
        // ⚠️ 用 boolean 基本类型 → 每次都会显式传 false（老板 2026-10-05 确认要这样，行为确定）
        setting.setEnableAigcCreative(planProperties.isEnableAigcCreative());
        // 乘方-成本项：两个开关都显式传（老板 2026-10-05 定：都关 = 文档里的「基础投放」）。
        // ⚠️ 显式传而不是省略，是为了不依赖平台默认值；将来要开「达人佣金出价」改配置即可。
        OverallVideoCreateParam.OverallCostItems costItems = new OverallVideoCreateParam.OverallCostItems();
        costItems.setStarTaskMaterialSwitch(planProperties.isStarTaskMaterialSwitch());
        // ⚠️ 注意：接口/SDK 里这个字段拼写少一个 s（alliance_commision_switch），不是笔误
        costItems.setAllianceCommisionSwitch(planProperties.isAllianceCommissionSwitch());
        setting.setOverallCostItems(costItems);
        param.setDeliverySetting(setting);

        OverallVideoCreateParam.CreativeItem item = new OverallVideoCreateParam.CreativeItem();
        item.setProductId(target.productId());
        item.setAwemeUid(target.awemeUid());
        item.setCreativeType(planProperties.getDefaultCreativeType());
        // ⚠️ 刻意不设 hideInAweme（老板 2026-10-05 定「不用传」）：
        //    文档明确两种情况都「传了无效」——① 素材全是抖音号主页视频；② 达人号（bind_type 非 OFFICIAL/SELF）。
        //    我们的场景两条都占，所以传了纯属冗余。
        List<OverallVideoCreateParam.VideoMaterial> videos = new ArrayList<>(materials.size());
        for (MaterialRef m : materials) {
            videos.add(new OverallVideoCreateParam.VideoMaterial()
                    .imageMode(m.imageMode())
                    .awemeItemId(m.awemeItemId()));
        }
        item.setVideoMaterial(videos);
        // ⚠️ 刻意不设 titleMaterial：素材全是抖音主页视频时，带标题会被接口拒
        param.setMultiProductCreativeList(List.of(item));
        return param;
    }

    // ==================== 回写指令 ====================

    /**
     * 生成回写指令：该组合「投放类型为空」的行，<b>同时</b>写 {@code K=全域PC} 与 {@code N=已投}。
     *
     * <p>⚠️ 两个都写是刻意的：只写 {@code K} 的话，「投放状态」会一直停在「待投放」，
     * 看表的人分不清哪些真投了；而且<b>只要影刀写 K 失败一次，下次扫表还会选中该行 → 重复投放（花钱）</b>。
     * 写两列等于双重去重。</p>
     */
    /**
     * 「投放中」标记要写的单元格：<b>只写 {@code N 投放状态}</b>，不动 {@code K 投放类型}。
     *
     * <p>⚠️ 不能写 K —— K 非空会被当成「已处理」，而这里其实<b>还没投</b>，只是暂时跳过。</p>
     */
    private static List<CellWrite> cellsBlocked(PlanTargetResolver.PlanTarget target,
                                                LocalDate date) {
        String value = blockedStatusText(date);
        List<CellWrite> cells = new ArrayList<>(target.pendingRows().size());
        for (DeliveryRowRef row : target.pendingRows()) {
            cells.add(new CellWrite(row.rowNumber(), COLUMN_DELIVERY_STATUS, value));
        }
        return cells;
    }

    /** 「投放中 M.D」的文本（M.D 无前导零，与素材表其他日期一致）。 */
    static String blockedStatusText(LocalDate date) {
        return VALUE_STATUS_BLOCKED_PREFIX + " " + date.getMonthValue() + "." + date.getDayOfMonth();
    }

    private static List<CellWrite> cellsOf(PlanTargetResolver.PlanTarget target, String typeValue) {
        List<CellWrite> cells = new ArrayList<>(target.pendingRows().size() * 2);
        for (DeliveryRowRef row : target.pendingRows()) {
            cells.add(new CellWrite(row.rowNumber(), COLUMN_DELIVERY_TYPE, typeValue));
            cells.add(new CellWrite(row.rowNumber(), COLUMN_DELIVERY_STATUS, VALUE_STATUS_DONE));
        }
        return cells;
    }

    // ==================== 工具 ====================

    /** 计划 ID 是字符串，转成接口要的数字。 */
    private static long parseAdId(PlanInfo plan) {
        try {
            return Long.parseLong(plan.adId().trim());
        } catch (NumberFormatException e) {
            throw BizException.businessFail("计划 ID 不是数字：「" + plan.adId()
                    + "」，无法加素材/改名，请人工核对");
        }
    }

    /** 商品 ID 是字符串，转成数字；转不了就返回 null（只用于回显，不影响执行）。 */
    private static Long parseOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 统一构造「没办成」的结果。 */
    private PlanBuildResult emptyResult(String aweme, String product, boolean dryRun,
                                        List<String> warnings) {
        return emptyResult(aweme, product, dryRun, warnings, null);
    }

    /**
     * 带 {@code action} 的版本。
     *
     * <p>用于区分「没办成」和「<b>主动跳过</b>」——
     * {@link PlanBuildResult.Action#SKIP_BLOCKED} 不是失败，调用方不该算进失败数。</p>
     */
    private PlanBuildResult emptyResult(String aweme, String product, boolean dryRun,
                                        List<String> warnings, PlanBuildResult.Action action) {
        return new PlanBuildResult(aweme, product, null, "", null, null, "",
                0, "", action, null, 0, dryRun, "", List.of(), warnings);
    }

    /** 带 cells 的版本（「投放中」跳过时要回写素材表标记）。 */
    private PlanBuildResult emptyResultWithCells(String aweme, String product,
                                                 PlanTargetResolver.PlanTarget target, int n,
                                                 boolean dryRun, List<String> warnings,
                                                 PlanBuildResult.Action action,
                                                 List<CellWrite> cells) {
        return new PlanBuildResult(aweme, product, target.advertiserId(), target.advertiserName(),
                target.awemeUid(), target.productId(), "",
                n, "", action, null, 0, dryRun, "", cells, warnings);
    }

    private PlanBuildResult emptyResult(PlanBuildParam param, PlanTargetResolver.PlanTarget target,
                                        String planName, boolean dryRun, List<String> warnings) {
        return new PlanBuildResult(param.awemeShowId(), param.productName(),
                target.advertiserId(), target.advertiserName(),
                target.awemeUid(), target.productId(), planName,
                0, "", null, null, 0, dryRun, "", List.of(), warnings);
    }
}
