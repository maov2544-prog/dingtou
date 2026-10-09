package com.aolandai.dingtou.service.task.handler;

import com.aolandai.dingtou.dto.plan.PendingRow;
import com.aolandai.dingtou.dto.plan.ProcessPendingParam;
import com.aolandai.dingtou.exception.BizException;
import com.aolandai.dingtou.service.plan.ProcessPendingService;
import com.aolandai.dingtou.service.task.TaskHandler;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code processPending} —— <b>一次处理整批「待投放」的行</b>（影刀最终形态）。
 *
 * <pre>
 * { "requestId": "r-batch-1",
 *   "params": {
 *     "executionDate": "2026-10-05",        // 选填，默认今天
 *     "rows": [
 *       { "row": 4992, "awemeShowId": "272886488", "productName": "益生元身体乳",
 *         "videoId": 7690503084850281061 },
 *       { "row": 4993, "awemeShowId": "55454664964", "productName": "青少年洗发水" }
 *     ]
 *   } }
 * </pre>
 *
 * <p>⚠️ <b>影刀不需要做任何判断</b>：不用调 {@code decide}、不用算 N、不用选场景。
 * 只要把扫到的「投放状态=待投放」的行原样交上来，服务返回 {@code cells} 让影刀写回。</p>
 *
 * <p>⚠️ 同一【抖音号 + 商品】的多行会被<b>去重</b>（只查一次计划），
 * 它们的 {@code videoId} 会<b>合并</b>后一次加进去 —— 不会漏素材。</p>
 *
 * <p>⚠️ <b>重跑安全</b>：批次失败可以原样重跑，已建过计划的会走 C1 复用，不会重复花钱。</p>
 */
@Component
public class ProcessPendingHandler implements TaskHandler {

    private final ProcessPendingService service;

    public ProcessPendingHandler(ProcessPendingService service) {
        this.service = service;
    }

    @Override
    public String code() {
        return "processPending";
    }

    @Override
    public String description() {
        return "批量：一次提交整批「待投放」行，服务自己算 N + 判场景 + 执行，返回要写回素材表的 cells";
    }

    @Override
    public Object handle(JsonNode params) throws Exception {
        JsonNode safeParams = Params.requireParams(params, "rows");
        List<PendingRow> rows = parseRows(safeParams);
        LocalDate executionDate = Params.optionalDate(safeParams, "executionDate", null);
        return service.process(new ProcessPendingParam(rows, executionDate));
    }

    /** 解析 {@code rows} 数组；<b>结构性错误</b>（不是数组 / 缺字段名）抛 {@code code=2}。 */
    private static List<PendingRow> parseRows(JsonNode params) {
        JsonNode node = params.get("rows");
        if (node == null || node.isNull()) {
            throw BizException.badRequest("缺少必填参数: rows（待投放的行数组）");
        }
        if (!node.isArray()) {
            throw BizException.badRequest("rows 必须是数组，实际: " + node.getNodeType());
        }
        if (node.isEmpty()) {
            throw BizException.badRequest("rows 必须是非空数组");
        }

        List<PendingRow> rows = new ArrayList<>(node.size());
        int index = 0;
        for (JsonNode item : node) {
            index++;
            if (item == null || !item.isObject()) {
                throw BizException.badRequest("rows[" + index + "] 必须是对象，形如 "
                        + "{\"row\":4992,\"awemeShowId\":\"272886488\",\"productName\":\"益生元身体乳\"}");
            }
            rows.add(new PendingRow(
                    Params.optionalInt(item, "row", 0),
                    Params.requiredText(item, "awemeShowId"),
                    Params.requiredText(item, "productName"),
                    Params.optionalLong(item, "videoId")));
        }
        return rows;
    }
}
