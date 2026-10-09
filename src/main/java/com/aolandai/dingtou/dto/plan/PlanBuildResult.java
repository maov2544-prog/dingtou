package com.aolandai.dingtou.dto.plan;

import com.aolandai.dingtou.dto.sheet.CellWrite;

import java.util.List;

/**
 * {@code planBuild} 的完整结果。
 *
 * <h2>⚠️ 怎么读这个结果（影刀侧）</h2>
 * <table border="1">
 *   <tr><th>{@code action}</th><th>{@code adId}</th><th>{@code cells}</th><th>含义</th></tr>
 *   <tr><td>{@code CREATE_SUIXINTUI}</td><td>null</td><td>有</td>
 *       <td><b>建了随心推</b>（A 场景：N&lt;3）：不建千川计划，按 {@code cells} 写回素材表</td></tr>
 *   <tr><td>{@code CREATE_PLAN}</td><td>有</td><td>有</td>
 *       <td><b>建了新计划</b>（B / C2）：素材已随建计划一起带上，按 {@code cells} 写回素材表</td></tr>
 *   <tr><td>{@code REUSE_PLAN}</td><td>有</td><td>有</td>
 *       <td><b>复用了已有计划</b>（C1）：已加本条素材 + 改名，按 {@code cells} 写回</td></tr>
 *   <tr><td>—</td><td>null</td><td><b>空</b></td>
 *       <td><b>没办成</b>：看 {@code warnings} 找原因（没账户 / 未授权 / 商品映射不到 / 素材池为空）</td></tr>
 * </table>
 *
 * <p>⚠️ {@code warnings} <b>必须看</b>：非空说明这次<b>没有全部办成</b>，不能当成「都投了」。</p>
 *
 * <p>⚠️ <b>服务端不写 Excel</b>：{@code cells} 是给影刀执行的写入指令。</p>
 *
 * @param awemeShowId   抖音号（原样回显）
 * @param productName   商品名（原样回显）
 * @param advertiserId  投放账户 ID；{@code null} = 没定位到账户
 * @param advertiserName 投放账户名（来自素材表 {@code 全域账号} 列，清洗后）
 * @param awemeUid      达人的<b>数值 UID</b>（{@code aweme_uid}）；{@code null} = 没解析到
 * @param productId     商品 ID；{@code null} = 映射不到
 * @param planName      计划名（{@code 抖音号-商品-M.D}）；{@code ""} = 没走到命名那步
 * @param cumulativeCount 累计随心推笔数 N（判定依据，回显便于排查）
 * @param reason        人类可读的判定依据（同 {@code Decision.reason}）——
 *                      ⚠️ 它是<b>提示</b>不是<b>问题</b>，所以<b>不放进 {@code warnings}</b>：
 *                      放进去会让影刀/探针以为「没有全部办成」
 * @param action        本次动作：{@code CREATE_SUIXINTUI} / {@code CREATE_PLAN} / {@code REUSE_PLAN}；{@code null} = 没执行
 * @param adId          建成或复用的计划 ID；{@code null} = 没执行
 * @param materialCount 本次加入计划的素材条数
 * @param dryRun        是否演练（true = 没真调建计划/加素材/改名接口）
 * @param requestBody   实际发出（或将要发出）的请求体 JSON —— 演练核对用；可能为空
 * @param cells         请影刀写入的单元格指令
 * @param warnings      过程中的异常情况
 */
public record PlanBuildResult(
        String awemeShowId,
        String productName,
        Long advertiserId,
        String advertiserName,
        Long awemeUid,
        Long productId,
        String planName,
        int cumulativeCount,
        String reason,
        Action action,
        Long adId,
        int materialCount,
        boolean dryRun,
        String requestBody,
        List<CellWrite> cells,
        List<String> warnings) {

    /** 本次动作。刻意不用 B / C2 这种场景名 —— 两者动作完全相同，区分它们对调用方没有意义。 */
    public enum Action {
        /** A：累计笔数不足 3 → 建<b>随心推</b>（不建千川计划）。 */
        CREATE_SUIXINTUI,
        /** B / C2：新建乘方计划（素材随建计划一起带上）。 */
        CREATE_PLAN,
        /** C1：复用已有计划（加本条素材 + 改名）。 */
        REUSE_PLAN,
        /**
         * <b>跳过</b>：该组合还有订单<b>占用互斥名额</b>（仍存续）→ 等它们结束再建计划。
         *
         * <p>老板 2026-10-07：「这些状态下订单仍存续，会触发互斥，
         * <b>无法创建同抖音号 + 同商品的新订单</b>」——
         * {@code DELIVERY_OK} / {@code AUDIT} / {@code CREATING} / {@code BOOK} /
         * <b>{@code FROZEN}（投放终止也占名额）</b>。</p>
         *
         * <p>⚠️ 这<b>不是失败</b>，也不是「没办成」—— 是<b>主动等待</b>，
         * 所以调用方不该把它算进失败数（否则每次都会报一堆假失败）。</p>
         */
        SKIP_BLOCKED
    }

    public PlanBuildResult {
        awemeShowId = awemeShowId == null ? "" : awemeShowId;
        productName = productName == null ? "" : productName;
        advertiserName = advertiserName == null ? "" : advertiserName;
        planName = planName == null ? "" : planName;
        reason = reason == null ? "" : reason;
        requestBody = requestBody == null ? "" : requestBody;
        cells = cells == null ? List.of() : List.copyOf(cells);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** 是否真的办成了（拿到了计划 ID 且非演练）。 */
    public boolean succeeded() {
        return !dryRun && adId != null;
    }
}
