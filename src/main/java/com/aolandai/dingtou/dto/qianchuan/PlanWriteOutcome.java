package com.aolandai.dingtou.dto.qianchuan;

import java.util.List;

/**
 * 千川<b>写操作</b>（加素材 / 改名）的结果。
 *
 * <p>与 {@link SuixintuiCreateOutcome} 同一套思路：dry-run 时也把请求体回显出来，
 * 便于人工核对 —— 因为这些操作<b>要么花钱、要么改线上计划</b>。</p>
 *
 * @param dryRun            是否是演练（true = 没真调接口）
 * @param appliedCount      实际写进去的素材条数（改名时为 0）
 * @param requestBody       组装好的请求体 JSON（dry-run 核对用）
 * @param responseRequestId 平台返回的 requestId（排查问题时报给平台）；dry-run 时为 {@code null}
 * @param warnings          过程中的异常情况（如某些素材被跳过）
 */
public record PlanWriteOutcome(
        boolean dryRun,
        int appliedCount,
        String requestBody,
        String responseRequestId,
        List<String> warnings) {

    public PlanWriteOutcome {
        requestBody = requestBody == null ? "" : requestBody;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** 是否真的执行了（非演练）。 */
    public boolean executed() {
        return !dryRun;
    }
}
