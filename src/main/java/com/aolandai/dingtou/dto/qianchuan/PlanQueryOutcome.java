package com.aolandai.dingtou.dto.qianchuan;

import com.aolandai.dingtou.store.PlanEntry;

import java.util.List;
import java.util.Optional;

/**
 * 计划查询结果：<b>接口查到的</b> + <b>本地台账记着的</b>。
 *
 * <p>两者都返回，是因为它们用途不同：</p>
 * <ul>
 *   <li>{@code plans}（接口）—— <b>真实状态</b>，判断「该组合到底有没有千川计划」以它为准；</li>
 *   <li>{@code ledgerHit}（台账）—— <b>上次我们建过的计划 ID</b>。台账与真实状态可能不一致
 *       （计划被人工删了），所以它只是「大概有」的信号，<b>不能单独作为判定依据</b>。</li>
 * </ul>
 *
 * <h2>可诊断性：为什么还要 rawPlanCount / unmatchedPlans</h2>
 * <p>曾经有过一次惨痛假阴性：接口返回 0 个计划、warnings 也为空，看起来一切正常，
 * 但计划其实存在 → {@code decide} 误判「无计划」→ 重复建计划（花钱）。
 * 事后只能靠猜。根因是「已按商品过滤后的 plans」<b>无法区分</b>：</p>
 * <ul>
 *   <li>接口<b>压根没返回</b>（{@code rawPlanCount == 0}）→ 参数/场景不对；</li>
 *   <li>接口<b>返回了但商品没匹配上</b>（{@code rawPlanCount > 0} 且 {@code plans} 为空）
 *       → 商品维度有问题。</li>
 * </ul>
 * <p>所以这里把 {@code rawPlanCount}（接口总共返回几个）与 {@code unmatchedPlans}
 * （返回了但商品没匹配上的）也暴露出来。配合 warnings（接口报错也会进 warnings），
 * 下次再出假阴性，一眼就能看出卡在哪一层。</p>
 *
 * @param awemeShowId     抖音号
 * @param productName     商品名
 * @param productId       商品 ID；映射不到唯一 ID 时为空
 * @param plans           接口查到的、<b>商品 ID 匹配上</b>的计划
 * @param ledgerHit       本地台账命中
 * @param accountsScanned 实际扫描的投放账户数
 * @param rawPlanCount    接口<b>总共</b>返回的计划数（跨账户、跨场景，未按商品过滤）
 * @param unmatchedPlans  接口返回了、但商品 ID <b>没匹配上</b>的计划
 * @param warnings        过程中的提示（含「登记表没填、回退全量扫描」这类<b>正常</b>情况），供排障
 * @param failedAccounts  <b>账户级查询失败</b>的明细（账户名 + 原因）。⚠️ 与 {@code warnings} 严格区分：
 *                        {@code warnings} 里有「回退全量扫描」这种<b>正常提示</b>，
 *                        拿它当「查询不完整」会让正常流程被误判成失败。见 {@link #queryComplete()}
 */
public record PlanQueryOutcome(
        String awemeShowId,
        String productName,
        String productId,
        List<PlanInfo> plans,
        Optional<PlanEntry> ledgerHit,
        int accountsScanned,
        int rawPlanCount,
        List<PlanInfo> unmatchedPlans,
        List<String> warnings,
        List<String> failedAccounts) {

    public PlanQueryOutcome {
        productId = productId == null ? "" : productId;
        plans = plans == null ? List.of() : List.copyOf(plans);
        ledgerHit = ledgerHit == null ? Optional.empty() : ledgerHit;
        unmatchedPlans = unmatchedPlans == null ? List.of() : List.copyOf(unmatchedPlans);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        failedAccounts = failedAccounts == null ? List.of() : List.copyOf(failedAccounts);
    }

    /**
     * 「没有账户级查询失败」的构造。
     *
     * <p>给「一切正常」的场景与单测用。⚠️ <b>真实查询路径不要用它</b> ——
     * 那会把「某账户查失败」这个关键信号丢掉，进而让上层误判「没有计划」→ 重复建计划（花钱）。</p>
     */
    public PlanQueryOutcome(String awemeShowId, String productName, String productId,
                            List<PlanInfo> plans, Optional<PlanEntry> ledgerHit,
                            int accountsScanned, int rawPlanCount,
                            List<PlanInfo> unmatchedPlans, List<String> warnings) {
        this(awemeShowId, productName, productId, plans, ledgerHit, accountsScanned,
                rawPlanCount, unmatchedPlans, warnings, List.of());
    }

    /**
     * 本次查询是否<b>完整</b>（没有任何账户查失败）。
     *
     * <p>⚠️ <b>「没有计划」这个结论只有在 {@code queryComplete()} 为真时才成立。</b>
     * 调用方在决定「建新计划」之前必须检查它 —— 否则某账户查失败时会重复建计划（花钱）。</p>
     */
    public boolean queryComplete() {
        return failedAccounts.isEmpty();
    }

    /** 该组合是否已有千川计划（以接口为准，台账只做补充）。 */
    public boolean hasPlan() {
        return !plans.isEmpty() || ledgerHit.isPresent();
    }

    /** 唯一计划；0 个或多个时为空。 */
    public Optional<PlanInfo> single() {
        return plans.size() == 1 ? Optional.of(plans.get(0)) : Optional.empty();
    }

    /** ⚠️ 匹配到多个计划 —— 需要人工确认，不能瞎挑一个。 */
    public boolean ambiguous() {
        return plans.size() > 1;
    }

    /**
     * 是否「接口返回了计划，但全部因商品 ID 不匹配被滤掉」。
     *
     * <p>典型假阴性信号：接口有返回（说明参数/场景对），问题出在商品维度。</p>
     */
    public boolean productMismatchOnly() {
        return plans.isEmpty() && rawPlanCount > 0;
    }
}
