package com.aolandai.dingtou.dto.plan;

import com.aolandai.dingtou.dto.sheet.CellWrite;

import java.util.List;

/**
 * {@code processPending} 的批量结果。
 *
 * <h2>⚠️ 怎么读（影刀侧）</h2>
 * <ol>
 *   <li>按 {@code cells} <b>写回素材表</b>（所有成功组合的回写指令已汇总在一起）；</li>
 *   <li>看 {@code failed} / {@code skipped} 是不是 0 —— 不是 0 就要看 {@code results} 里的原因；</li>
 *   <li>⚠️ {@code warnings} 必须看：非空说明有些行<b>没有全部办成</b>。</li>
 * </ol>
 *
 * <h2>⚠️ 重跑是安全的（这是刻意保证的）</h2>
 * <p>如果批次跑到一半失败，影刀可以<b>原样重跑整批</b>：
 * 已经建过计划的组合会被「查计划」查到 → 走 C1 复用 → <b>不会重复建计划（不重复花钱）</b>。
 * 所以这里不需要「断点续跑」那么复杂的东西。</p>
 *
 * @param submittedRows 影刀提交的行数
 * @param combinations  去重后的【达人+商品】组合数（同一组合多行只处理一次）
 * @param succeeded     处理成功的组合数
 * @param failed        处理失败的组合数（业务失败，如没账户 / 未授权）
 * @param skipped       被跳过的组合数（如撞上频控，本批剩余未处理）
 * @param cells         汇总的回写指令（<b>影刀按这个写表</b>）
 * @param results       每个组合的明细（便于排查「哪一条没办成、为什么」）
 * @param warnings      汇总的提示
 */
public record ProcessPendingResult(
        int submittedRows,
        int combinations,
        int succeeded,
        int failed,
        int skipped,
        List<CellWrite> cells,
        List<CombinationOutcome> results,
        List<String> warnings) {

    public ProcessPendingResult {
        cells = cells == null ? List.of() : List.copyOf(cells);
        results = results == null ? List.of() : List.copyOf(results);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** 单个【达人 + 商品】组合的处理结果。 */
    public record CombinationOutcome(
            String awemeShowId,
            String productName,
            Status status,
            PlanBuildResult.Action action,
            Long adId,
            int cumulativeCount,
            int materialCount,
            String reason,
            List<String> warnings) {

        public CombinationOutcome {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        /** 组合级状态。 */
        public enum Status {
            /** 办成了（可能建了计划 / 建了随心推 / 复用了计划）。 */
            OK,
            /** 没办成（没账户 / 未授权 / 商品映射不到 / 素材池为空等）—— 看 warnings。 */
            FAILED,
            /** 本批没轮到它（通常是前面撞了频控，整批中止）。 */
            SKIPPED
        }
    }
}
