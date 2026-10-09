package com.aolandai.dingtou.service.kol;

import com.aolandai.dingtou.dto.kol.KolPlanCostRequest;
import com.aolandai.dingtou.dto.kol.PlanCostQueryResult;

import java.time.LocalDate;

/**
 * 查「消耗达标的千川计划」—— 达人巡查 / 催更 域的第一个能力。
 *
 * <p>流程：读《达人巡查配置表》拿账户 + 阈值 → 逐账户查「投放中 + 有消耗」的计划
 * → 按 {@code statCost >= 阈值} 过滤 →（可选）补上达人信息 → 返回列表。</p>
 *
 * <p>⚠️ 返回的 {@link PlanCostQueryResult} 里<b>必须看 {@code warnings}</b>：
 * 某账户查失败不会中断整轮，但「漏查了某账户」和「该账户没有达标计划」是两回事。</p>
 */
public interface KolPlanCostService {

    /** 用配置的默认窗口（最近 {@code dingtou.kol.cost-window-months} 个月）查询，不附带达人信息。 */
    default PlanCostQueryResult query() throws Exception {
        return query(KolPlanCostRequest.defaults());
    }

    /** 用显式指定的统计窗口查询（含首尾两天），不附带达人信息。 */
    default PlanCostQueryResult query(LocalDate startDate, LocalDate endDate) throws Exception {
        return query(KolPlanCostRequest.of(startDate, endDate));
    }

    /**
     * 按请求参数查询。
     *
     * <p>参数里 {@code withAweme=true} 时，会调用通用能力
     * {@code PlanAwemeService} 给每个达标计划补上抖音号 / 抖音名
     * —— 代价是<b>每个计划多一次详情请求</b>。</p>
     */
    PlanCostQueryResult query(KolPlanCostRequest request) throws Exception;
}
