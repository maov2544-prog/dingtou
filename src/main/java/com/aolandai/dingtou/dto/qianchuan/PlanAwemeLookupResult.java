package com.aolandai.dingtou.dto.qianchuan;

import java.util.List;

/**
 * 批量「按计划 ID 查达人信息」的结果 —— <b>数据 + 可诊断信息</b>。
 *
 * <p>与 {@code PlanQueryOutcome} 同样的理由：只返回一个列表，会分不清</p>
 * <ul>
 *   <li>「该计划确实没有达人信息」（接口正常返回但 {@code aweme_infos} 为空）；</li>
 *   <li>「这个计划没查成功」（计划被删 / 权限不对 / 频控），</li>
 * </ul>
 * <p>两者都表现为「结果里没有这一条」，但处理方式完全相反。所以把
 * {@code requested} / {@code resolved} / {@code warnings} 一并带出来。</p>
 *
 * @param requested 请求查询的计划数
 * @param resolved  成功查到达人信息的计划数（= {@code items.size()}）
 * @param items     查到的达人信息
 * @param warnings  过程中的异常情况（查失败的计划、多达人只取第一个等），供排障
 */
public record PlanAwemeLookupResult(
        int requested,
        int resolved,
        List<PlanAweme> items,
        List<String> warnings) {

    public PlanAwemeLookupResult {
        items = items == null ? List.of() : List.copyOf(items);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** 有请求但没查到 —— 说明结果不完整，调用方不该当成「这些计划没有达人」。 */
    public boolean incomplete() {
        return resolved < requested;
    }
}
