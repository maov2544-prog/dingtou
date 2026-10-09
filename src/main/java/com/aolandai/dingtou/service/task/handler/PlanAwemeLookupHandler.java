package com.aolandai.dingtou.service.task.handler;

import com.aolandai.dingtou.config.QianchuanProperties;
import com.aolandai.dingtou.dto.qianchuan.PlanRef;
import com.aolandai.dingtou.exception.BizException;
import com.aolandai.dingtou.service.qianchuan.PlanAwemeService;
import com.aolandai.dingtou.service.task.TaskHandler;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code planAwemeLookup} —— 按【投放账户 + 计划 ID】批量查<b>达人信息</b>（抖音号 / 抖音名）。
 *
 * <pre>
 * { "requestId": "...",
 *   "params": { "items": [
 *       { "advertiserId": 1823379540880396, "adId": 1877813517052328 },
 *       { "advertiserId": 1776098375039040, "adId": 1877000000000000 }
 *   ] } }
 * </pre>
 *
 * <p><b>为什么是通用能力而不是 kol 专用</b>：这是「查计划 → 拿达人」的通用一步，
 * 任何业务线（催更、复盘、素材分析）都可能用到，所以 taskCode 刻意不带业务前缀。</p>
 *
 * <p>本类<b>只做参数解析</b>，业务全在 {@link PlanAwemeService} —— 保持「handler 薄、service 厚」，
 * 这样业务逻辑能被单测直接调用，不用起 HTTP 上下文。</p>
 *
 * <p>只读操作，<b>不花钱</b>，所以没有 dry-run 一说。</p>
 *
 * <p>⚠️ 应答里的 {@code warnings} 必须看：某个计划查失败不会中断整批，
 * 而是记进 warnings —— 否则「漏查了一个计划」会被误当成「该计划没有达人」。</p>
 */
@Component
public class PlanAwemeLookupHandler implements TaskHandler {

    private final PlanAwemeService service;
    private final QianchuanProperties properties;

    public PlanAwemeLookupHandler(PlanAwemeService service, QianchuanProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @Override
    public String code() {
        return "planAwemeLookup";
    }

    @Override
    public String description() {
        return "按【投放账户 + 计划ID】批量查达人信息（抖音号 / 抖音名）";
    }

    @Override
    public Object handle(JsonNode params) throws Exception {
        return service.lookupAll(parseItems(Params.requireParams(params, "items")));
    }

    /** 解析 {@code items} 数组；任何格式问题都抛 {@code code=2}（影刀不该重试）。 */
    private List<PlanRef> parseItems(JsonNode params) {
        JsonNode items = params.get("items");
        if (items == null || items.isNull() || !items.isArray() || items.isEmpty()) {
            throw BizException.badRequest(
                    "items 必须是非空数组，形如 [{\"advertiserId\":123,\"adId\":456}]");
        }
        // 单批上限可配（dingtou.qianchuan.plan-aweme-max-items）：
        // 详情接口是「一计划一次请求」，不限批会让影刀一次传上千条把服务拖很久，且必然触发频控
        int maxItems = properties.getPlanAwemeMaxItems();
        if (items.size() > maxItems) {
            throw BizException.badRequest("items 有 " + items.size()
                    + " 条，超过单批上限 " + maxItems + "，请分批调用");
        }

        List<PlanRef> refs = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            JsonNode item = items.get(i);
            if (item == null || !item.isObject()) {
                throw BizException.badRequest("items[" + i + "] 必须是对象，含 advertiserId 与 adId");
            }
            refs.add(new PlanRef(
                    requiredLong(item, i, "advertiserId"),
                    requiredLong(item, i, "adId")));
        }
        return refs;
    }

    /**
     * 取整数字段，兼容「JSON 数字」与「数字字符串」两种写法。
     *
     * <p>⚠️ <b>不能用 {@code node.canConvertToLong()}</b>：Jackson 的 {@code TextNode}
     * 不覆写它（恒为 {@code false}），于是影刀把 16 位账户 ID 当字符串传时会被误判成
     * 「不是整数」而拒掉 —— 而影刀恰恰经常这么传（大整数在 JS/JSON 里容易被转成字符串）。</p>
     */
    private static long requiredLong(JsonNode item, int index, String field) {
        JsonNode node = item.get(field);
        if (node == null || node.isNull()) {
            throw BizException.badRequest("items[" + index + "]." + field + " 缺失");
        }
        if (node.isNumber()) {
            return node.asLong();
        }
        String text = node.asText().trim();
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw BizException.badRequest(
                    "items[" + index + "]." + field + " 不是整数: '" + text + "'");
        }
    }
}
