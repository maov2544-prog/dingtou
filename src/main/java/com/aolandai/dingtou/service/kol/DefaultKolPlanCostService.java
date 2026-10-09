package com.aolandai.dingtou.service.kol;

import com.aolandai.dingtou.client.QianchuanClientFactory;
import com.aolandai.dingtou.config.KolProperties;
import com.aolandai.dingtou.config.QianchuanProperties;
import com.aolandai.dingtou.dto.kol.KolAccountConfig;
import com.aolandai.dingtou.dto.kol.KolPlanCostRequest;
import com.aolandai.dingtou.dto.kol.PlanCostQueryResult;
import com.aolandai.dingtou.dto.kol.PlanCostRow;
import com.aolandai.dingtou.dto.kol.StatCostUnit;
import com.aolandai.dingtou.dto.qianchuan.PlanAweme;
import com.aolandai.dingtou.dto.qianchuan.PlanAwemeLookupResult;
import com.aolandai.dingtou.dto.qianchuan.PlanRef;
import com.aolandai.dingtou.exception.RateLimitException;
import com.aolandai.dingtou.service.qianchuan.PlanAwemeService;
import com.aolandai.dingtou.service.qianchuan.QianchuanPlanService;
import com.aolandai.dingtou.source.KolPatrolConfigReader;
import com.bytedance.ads.model.QianchuanUniPromotionListV10AdlabScene;
import com.bytedance.ads.model.QianchuanUniPromotionListV10DataAdListAdInfoAdlabScene;
import com.bytedance.ads.model.QianchuanUniPromotionListV10FilteringHavingCost;
import com.bytedance.ads.model.QianchuanUniPromotionListV10FilteringStatus;
import com.bytedance.ads.model.QianchuanUniPromotionListV10ResponseDataAdListInner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link KolPlanCostService} 的默认实现。
 *
 * <h2>查询口径</h2>
 * <pre>
 * 账户      ：来自《达人巡查配置表》（账户名称 / 账户 ID / 消耗金额阈值（元））
 * 接口过滤  ：marketing_goal=VIDEO_PROM_GOODS、status=DELIVERY_OK、having_cost=YES
 * 排序      ：按【整体消耗降序】拉取，翻到低于阈值即停 → 26 页变 1~2 页
 * 场景      ：全域（UNI_PROJECT）+ 乘方（OVERALL_PROJECT）都查，跨场景按 adId 去重
 * 时间窗    ：[startDate 00:00:00, endDate 23:59:59]；不给则默认「昨天往前推 N 个月」
 * 本地过滤  ：statsInfo.stat_cost &gt;= 阈值（阈值元 × 100000，见 {@link StatCostUnit}）
 * 达人信息  ：{@code withAweme=true} 时，交给通用能力 {@link PlanAwemeService} 补齐
 * </pre>
 *
 * <h2>四个刻意的设计</h2>
 * <ol>
 *   <li><b>某账户失败不中断整轮</b>，但记进 {@code warnings} —— 否则「漏查了一个账户」
 *       会被当成「该账户没有达标计划」；</li>
 *   <li><b>{@link RateLimitException} 必须上抛</b>：频控是全局惩罚，继续打只会加重
 *       5 分钟惩罚，且拿到的结果不可信；</li>
 *   <li><b>接口没返回 {@code stat_cost} 的计划不判达标</b>：宁可不返回，也不能把
 *       「没拿到消耗」当成「消耗为 0」—— 那会把本该达标的计划漏掉；</li>
 *   <li><b>达人信息走通用能力，不在这里自己调 SDK</b> —— 别的业务也要用同一份逻辑，
 *       复制一份迟早分叉。</li>
 * </ol>
 *
 * <p>⚠️ 本类<b>无状态</b>（不持有请求级中间数据），可以被并发调用。</p>
 */
@Component
public class DefaultKolPlanCostService implements KolPlanCostService {

    private static final Logger log = LoggerFactory.getLogger(DefaultKolPlanCostService.class);

    /**
     * 乘方计划在 {@code smartBidType} 字段上的对外标签（业务约定，见 {@link #smartBidTypeOf}）。
     */
    private static final String SCENE_OVERALL_LABEL = "乘方";

    private final KolPatrolConfigReader configReader;
    private final QianchuanClientFactory clientFactory;
    private final KolProperties properties;

    /**
     * 要查的<b>场景</b>来自这里（{@code dingtou.qianchuan.query-scenes}，默认全域 + 乘方）。
     *
     * <p>本系统建的是<b>乘方</b>计划，只查全域会漏掉自己建的全部计划 ——
     * 所以默认两个都查，且该配置<b>不允许为空</b>。</p>
     */
    private final QianchuanProperties qianchuanProperties;

    private final PlanAwemeService planAwemeService;

    public DefaultKolPlanCostService(KolPatrolConfigReader configReader,
                                     QianchuanClientFactory clientFactory,
                                     KolProperties properties,
                                     QianchuanProperties qianchuanProperties,
                                     PlanAwemeService planAwemeService) {
        this.configReader = configReader;
        this.clientFactory = clientFactory;
        this.properties = properties;
        this.qianchuanProperties = qianchuanProperties;
        this.planAwemeService = planAwemeService;
    }

    @Override
    public PlanCostQueryResult query(KolPlanCostRequest request) throws Exception {
        if (request.hasPartialWindow()) {
            throw new IllegalArgumentException(
                    "startDate 与 endDate 必须同时提供（或都不提供以使用默认窗口）");
        }

        LocalDate today = LocalDate.now();
        LocalDate startDate = request.hasExplicitWindow()
                ? request.startDate() : today.minusMonths(properties.getCostWindowMonths());
        LocalDate endDate = request.hasExplicitWindow()
                ? request.endDate() : today.minusDays(1);
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException(
                    "startDate(" + startDate + ") 不能晚于 endDate(" + endDate + ")");
        }

        String startTime = startDate + " 00:00:00";
        String endTime = endDate + " 23:59:59";

        List<String> warnings = new ArrayList<>();
        ScanOutcome scan = scanAccounts(startTime, endTime, warnings);

        List<Qualified> qualified = scan.qualified();
        if (request.withAweme() && !qualified.isEmpty()) {
            qualified = withAweme(qualified, warnings);
        }

        List<PlanCostRow> rows = new ArrayList<>(qualified.size());
        for (Qualified q : qualified) {
            rows.add(q.toRow());
        }
        // 消耗高的排前面：业务上一眼先看到「花得最多」的计划
        rows.sort(Comparator.comparingDouble(PlanCostRow::statCost).reversed());

        log.info("消耗达标查询 [{} ~ {}]：扫描 {} 个账户，接口返回 {} 个计划，达标 {} 个，"
                        + "带达人信息={}，警告 {} 条",
                startTime, endTime, scan.accountsScanned(), scan.plansScanned(),
                rows.size(), request.withAweme(), warnings.size());
        return new PlanCostQueryResult(startTime, endTime, scan.accountsScanned(),
                scan.plansScanned(), rows, warnings);
    }

    // ==================== 第一步：逐账户查 + 阈值过滤 ====================

    private ScanOutcome scanAccounts(String startTime, String endTime, List<String> warnings)
            throws Exception {
        List<KolAccountConfig> configs = configReader.readAll();
        QianchuanPlanService planService = clientFactory.planService();

        List<Qualified> qualified = new ArrayList<>();
        int accountsScanned = 0;
        int plansScanned = 0;
        int plansWithoutCost = 0;

        for (KolAccountConfig config : configs) {
            if (skip(config, warnings)) {
                continue;
            }
            Long advertiserId = config.advertiserIdAsLong();
            accountsScanned++;
            try {
                long thresholdRaw = config.thresholdRaw();
                // ⚠️ 把阈值传给下层：接口按【消耗降序】返回，翻到低于阈值即可停
                //    （实测某账户单场景 2592 个计划 / 26 页，只翻 1 页就够）
                List<QianchuanUniPromotionListV10ResponseDataAdListInner> plans =
                        planService.queryAllPlans(advertiserId, qianchuanProperties.getQueryScenes(),
                                startTime, endTime,
                                QianchuanUniPromotionListV10FilteringStatus.DELIVERY_OK,
                                QianchuanUniPromotionListV10FilteringHavingCost.YES,
                                thresholdRaw);
                plansScanned += plans.size();

                for (QianchuanUniPromotionListV10ResponseDataAdListInner plan : plans) {
                    Double statCost = statCostOf(plan);
                    if (statCost == null) {
                        plansWithoutCost++;
                        continue;
                    }
                    if (statCost < thresholdRaw) {
                        continue;
                    }
                    qualified.add(new Qualified(
                            advertiserId,
                            config.accountName(),
                            adIdOf(plan),
                            adNameOf(plan),
                            StatCostUnit.rawToYuan(statCost),
                            smartBidTypeOf(plan)));
                }
            } catch (RateLimitException e) {
                // 频控必须上抛：吞掉会让本轮把「被频控拦住的账户」误判成「没有达标计划」
                throw e;
            } catch (Exception e) {
                warnings.add("账户 '" + config.accountName() + "' 查询失败: " + e.getMessage());
                log.warn("账户 {} 查询计划失败: {}", config.accountName(), e.getMessage());
            }
        }

        if (plansWithoutCost > 0) {
            warnings.add("有 " + plansWithoutCost + " 个计划接口没返回 stat_cost（无法判定是否达标，已跳过）");
        }
        return new ScanOutcome(qualified, accountsScanned, plansScanned);
    }

    // ==================== 第二步（可选）：补达人信息 ====================

    /**
     * 用通用能力 {@link PlanAwemeService} 批量补上抖音号 / 抖音名。
     *
     * <p>⚠️ 只补「取到了达人信息」的行；没取到的行保持 {@code null}（字段会被 Jackson 省略），
     * 并在 {@code warnings} 里留痕 —— <b>不会把「查失败」写成空串</b>，
     * 那样下游就分不清「这个计划没有抖音号」和「没查到」。</p>
     *
     * <p>⚠️ 结果<b>按 {@code adId} 匹配回填</b>，不按下标 —— {@code items} 只是请求集合的
     * 子集（有些计划查不到），按下标对齐会错位。</p>
     */
    private List<Qualified> withAweme(List<Qualified> qualified, List<String> warnings)
            throws Exception {
        List<PlanRef> refs = new ArrayList<>(qualified.size());
        for (Qualified q : qualified) {
            refs.add(new PlanRef(q.advertiserId(), q.adId()));
        }

        PlanAwemeLookupResult lookup = planAwemeService.lookupAll(refs);
        warnings.addAll(lookup.warnings());

        Map<Long, PlanAweme> byAdId = new HashMap<>();
        for (PlanAweme aweme : lookup.items()) {
            byAdId.put(aweme.adId(), aweme);
        }

        List<Qualified> filled = new ArrayList<>(qualified.size());
        for (Qualified q : qualified) {
            PlanAweme aweme = byAdId.get(q.adId());
            filled.add(aweme == null ? q : q.withAweme(aweme.awemeShowId(), aweme.awemeName()));
        }
        return filled;
    }

    // ==================== 跳过与取值工具 ====================

    /**
     * 该账户是否应跳过；跳过原因写进 warnings。
     *
     * <p>三种跳过：没有账户 ID、账户 ID 不是数字、阈值没填/填错。
     * <b>都必须留痕</b> —— 否则「配置里漏填」会表现为「该账户没有达标计划」。</p>
     */
    private static boolean skip(KolAccountConfig config, List<String> warnings) {
        if (!config.hasAdvertiserId()) {
            warnings.add("配置表第 " + config.rowNumber() + " 行（账户 '"
                    + config.accountName() + "'）没有账户 ID，已跳过");
            return true;
        }
        if (config.advertiserIdAsLong() == null) {
            warnings.add("配置表第 " + config.rowNumber() + " 行（账户 '"
                    + config.accountName() + "'）的账户 ID '" + config.advertiserId()
                    + "' 不是数字，已跳过");
            return true;
        }
        if (!KolPatrolConfigReader.hasThreshold(config)) {
            warnings.add("配置表第 " + config.rowNumber() + " 行（账户 '"
                    + config.accountName() + "'）没有填消耗金额阈值，已跳过");
            return true;
        }
        return false;
    }

    private static Double statCostOf(QianchuanUniPromotionListV10ResponseDataAdListInner plan) {
        return plan == null || plan.getStatsInfo() == null ? null : plan.getStatsInfo().getStatCost();
    }

    private static long adIdOf(QianchuanUniPromotionListV10ResponseDataAdListInner plan) {
        return plan == null || plan.getAdInfo() == null || plan.getAdInfo().getId() == null
                ? 0L : plan.getAdInfo().getId();
    }

    private static String adNameOf(QianchuanUniPromotionListV10ResponseDataAdListInner plan) {
        return plan == null || plan.getAdInfo() == null || plan.getAdInfo().getName() == null
                ? "" : String.valueOf(plan.getAdInfo().getName());
    }

    /**
     * {@code smartBidType} 字段的<b>对外取值</b>。
     *
     * <p>⚠️⚠️ <b>这是本项目的一条业务约定</b>（老板 2026-10-04 拍板），
     * <b>不是接口的原始语义</b> —— 改动前务必读完：</p>
     * <ul>
     *   <li><b>乘方</b>计划（{@code adlabScene = OVERALL_PROJECT}）→ 直接输出 <b>{@code "乘方"}</b>，
     *       <b>不再给出价方式原值</b>（影刀要用它填素材表的「投放类型」）；</li>
     *   <li><b>全域</b>计划（{@code adlabScene = UNI_PROJECT}）→ 保持接口原值
     *       （如 {@code SMART_BID_CUSTOM} / {@code SMART_BID_CONSERVATIVE}）。</li>
     * </ul>
     *
     * <p>⚠️ <b>知情接受的副作用</b>：乘方计划的<b>真实出价方式在本接口看不到了</b>。
     * 若以后要按出价方式筛选乘方计划，需要另外取（详情接口的
     * {@code deliverySetting.smartBidType}），或再加一个字段。</p>
     *
     * <p>⚠️ 另外：接口的 {@code smart_bid_type} <b>本身区分不了乘方/全域</b> ——
     * 实测全域计划也是 {@code SMART_BID_CUSTOM}。所以这里判断依据是
     * {@code adlabScene}（我们本来就是按场景分别查的，这个值可信），<b>不是</b> {@code smartBidType}。</p>
     *
     * <p>拿不到场景或出价方式时返回 {@code null}（字段被 Jackson 省略），<b>不猜、也不返回空串</b>。</p>
     */
    private static String smartBidTypeOf(QianchuanUniPromotionListV10ResponseDataAdListInner plan) {
        if (plan == null || plan.getAdInfo() == null) {
            return null;
        }
        if (plan.getAdInfo().getAdlabScene()
                == QianchuanUniPromotionListV10DataAdListAdInfoAdlabScene.OVERALL_PROJECT) {
            return SCENE_OVERALL_LABEL;
        }
        return plan.getAdInfo().getSmartBidType() == null
                ? null : String.valueOf(plan.getAdInfo().getSmartBidType());
    }

    /** 逐账户扫描的结果：达标项 + 两个计数（供日志与结果用，不放在实例字段上）。 */
    private record ScanOutcome(List<Qualified> qualified, int accountsScanned, int plansScanned) {
    }

    /** 一条「消耗达标」的中间结果；达人信息在第二步才补上。 */
    private record Qualified(long advertiserId, String advertiserName, long adId,
                             String adName, double statCost, String smartBidType,
                             String awemeShowId, String awemeName) {

        Qualified(long advertiserId, String advertiserName, long adId,
                  String adName, double statCost, String smartBidType) {
            this(advertiserId, advertiserName, adId, adName, statCost, smartBidType, null, null);
        }

        Qualified withAweme(String showId, String name) {
            return new Qualified(advertiserId, advertiserName, adId, adName, statCost,
                    smartBidType, showId, name);
        }

        PlanCostRow toRow() {
            return new PlanCostRow(advertiserId, advertiserName, adId, adName, statCost,
                    smartBidType, awemeShowId, awemeName);
        }
    }
}
