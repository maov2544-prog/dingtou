package com.aolandai.dingtou.dto.plan;

import java.time.LocalDate;
import java.util.List;

/**
 * {@code processPending} 任务的入参：<b>一批</b>待投放的行。
 *
 * <pre>
 * { "requestId": "r-batch-1",
 *   "params": {
 *     "executionDate": "2026-10-05",     // 选填，默认今天
 *     "rows": [
 *       { "row": 4992, "awemeShowId": "272886488", "productName": "益生元身体乳",
 *         "videoId": 7690503084850281061 },
 *       { "row": 4993, "awemeShowId": "55454664964", "productName": "青少年洗发水" }
 *     ]
 *   } }
 * </pre>
 *
 * <h2>⚠️ 影刀只需要「把待投放的行原样交上来」</h2>
 * <p>不用调 {@code decide}、不用算 N、不用判断该走哪个场景 —— 全部由服务完成。
 * 影刀只负责：<b>读表 → 提交 → 拿 cells 写回</b>。</p>
 *
 * @param rows          待投放的行（必填、非空）
 * @param executionDate 执行日期（计划名里的 M.D）；{@code null} = 今天
 */
public record ProcessPendingParam(List<PendingRow> rows, LocalDate executionDate) {

    public ProcessPendingParam {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }
}
