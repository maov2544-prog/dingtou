package com.aolandai.dingtou.service.task.handler;

import com.aolandai.dingtou.dto.kol.KolPlanCostRequest;
import com.aolandai.dingtou.exception.BizException;
import com.aolandai.dingtou.config.DingtouProperties;
import com.aolandai.dingtou.dto.kol.DeliveryStatusResult;
import com.aolandai.dingtou.dto.kol.PlanCostQueryResult;
import com.aolandai.dingtou.service.kol.DeliveryStatusChecker;
import com.aolandai.dingtou.service.kol.KolPlanCostService;
import com.aolandai.dingtou.service.task.TaskHandler;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * {@code kolPlanCostQuery} —— 查「消耗达标的千川计划」。
 *
 * <pre>
 * 不传参数（默认窗口 = 最近 1 个月，不带达人信息）：
 *   { "requestId": "...", "params": {} }
 *
 * 显式窗口（两个必须同时给）：
 *   { "params": { "startDate": "2026-09-04", "endDate": "2026-10-03" } }
 *
 * 附带达人信息（抖音号 / 抖音名）：
 *   { "params": { "withAweme": true } }
 *
 * 组合：
 *   { "params": { "startDate": "2026-09-04", "endDate": "2026-10-03", "withAweme": true } }
 * </pre>
 *
 * <p>⚠️ {@code withAweme=true} 会为<b>每个达标计划</b>额外调一次「乘方计划详情」
 * （见 {@code PlanAwemeService}）—— 达标 15 个就多 15 次请求、约 +18 秒。
 * 只想要列表时请保持默认 {@code false}。</p>
 *
 * <p>本类<b>只做参数解析</b>，业务全在 {@link KolPlanCostService} ——
 * 保持「handler 薄、service 厚」，这样业务逻辑能被单测直接调用，不用起 HTTP 上下文。</p>
 *
 * <p>只读操作，<b>不花钱</b>，所以没有 dry-run 一说。</p>
 *
 * <p>⚠️ 应答里的 {@code warnings} 必须看：某账户查询失败时不会中断整轮，
 * 而是记进 warnings —— 否则「漏查了一个账户」会被误当成「该账户没有达标计划」。</p>
 */
@Component
public class KolPlanCostHandler implements TaskHandler {

    private final KolPlanCostService service;
    private final DeliveryStatusChecker deliveryStatusChecker;
    private final DingtouProperties dingtouProperties;

    public KolPlanCostHandler(KolPlanCostService service,
                              DeliveryStatusChecker deliveryStatusChecker,
                              DingtouProperties dingtouProperties) {
        this.service = service;
        this.deliveryStatusChecker = deliveryStatusChecker;
        this.dingtouProperties = dingtouProperties;
    }

    @Override
    public String code() {
        return "kolPlanCostQuery";
    }

    @Override
    public String description() {
        return "查消耗达标的千川计划（投放中 + 有消耗 + 消耗≥阈值），可选附带抖音号/抖音名"
                + "；传 checkDelivery=true 时顺带做「交付状态」检查并写回共享盘汇总表";
    }

    @Override
    public Object handle(JsonNode params) throws Exception {
        LocalDate start = Params.optionalDate(params, "startDate", null);
        LocalDate end = Params.optionalDate(params, "endDate", null);
        boolean withAweme = Params.optionalBool(params, "withAweme", false);

        // 只给一半会造成「窗口边界靠猜」，宁可报错让影刀把参数补全
        if ((start == null) != (end == null)) {
            throw BizException.badRequest(
                    "startDate 与 endDate 必须同时提供（或都不提供以使用默认窗口）");
        }
        if (start != null && start.isAfter(end)) {
            throw BizException.badRequest("startDate(" + start + ") 不能晚于 endDate(" + end + ")");
        }

        PlanCostQueryResult cost = service.query(new KolPlanCostRequest(start, end, withAweme));

        // ⚠️ 交付状态检查是【选填】的额外逻辑：不传 checkDelivery 时行为与以前完全一致。
        //    它会读共享盘的另一张表 + 调 160+ 次详情接口，比较重。
        if (!Params.optionalBool(params, "checkDelivery", false)) {
            return cost;
        }
        LocalDate referenceDate = Params.optionalDate(params, "referenceDate", null);
        return cost.withDelivery(deliveryStatusChecker.check(referenceDate,
                dingtouProperties.isDryRun()));
    }
}
