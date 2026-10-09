package com.aolandai.dingtou.service.task.handler;

import com.aolandai.dingtou.exception.BizException;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.time.format.DateTimeParseException;

/**
 * 任务参数的解析与校验工具。
 *
 * <p>抽出来是为了让「参数错了 → {@code code=2}」这条规则在所有任务里完全一致。
 * 之前每个 handler 各写一份 {@code requiredText}，迟早会出现某个任务把缺失参数报成
 * {@code code=5}（内部错误）—— 那样影刀会去重试一个永远失败的请求。</p>
 */
final class Params {

    private Params() {
    }

    /** 必填文本；缺失或空白抛 {@code code=2}。 */
    static String requiredText(JsonNode params, String field) {
        JsonNode node = params == null ? null : params.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw BizException.badRequest("缺少必填参数: " + field);
        }
        return node.asText().trim();
    }

    /** 选填文本；缺失返回空串。 */
    static String optionalText(JsonNode params, String field) {
        JsonNode node = params == null ? null : params.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            return "";
        }
        return node.asText().trim();
    }

    /** 选填布尔；缺失用 {@code fallback}。 */
    static boolean optionalBool(JsonNode params, String field, boolean fallback) {
        JsonNode node = params == null ? null : params.get(field);
        if (node == null || node.isNull()) {
            return fallback;
        }
        return node.asBoolean(fallback);
    }

    /** 选填整数；缺失用 {@code fallback}，格式不对抛 {@code code=2}。 */
    static int optionalInt(JsonNode params, String field, int fallback) {
        JsonNode node = params == null ? null : params.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            return fallback;
        }
        if (!node.canConvertToInt()) {
            throw BizException.badRequest(field + " 必须是整数，实际: " + node.asText());
        }
        return node.asInt();
    }

    /** 选填日期（{@code yyyy-MM-dd}）；缺失用 {@code fallback}。 */
    static LocalDate optionalDate(JsonNode params, String field, LocalDate fallback) {
        JsonNode node = params == null ? null : params.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(node.asText().trim());
        } catch (DateTimeParseException e) {
            throw BizException.badRequest(field + " 格式必须是 yyyy-MM-dd，实际: " + node.asText());
        }
    }

    /**
     * 选填长整数（如 19 位的抖音视频ID）；缺失返回 {@code null}，格式不对抛 {@code code=2}。
     *
     * <p>⚠️ <b>不能用 {@code node.canConvertToLong()} 判断</b> —— Jackson 的
     * {@code TextNode} 没有覆写它，<b>对数字字符串恒返回 false</b>，
     * 会把影刀传过来的 {@code "7687379351705059825"} 误判成非法。</p>
     *
     * <p>⚠️ 抖音视频ID 是 19 位，<b>超过 int32</b>，必须用 {@code long}。</p>
     */
    static Long optionalLong(JsonNode params, String field) {
        JsonNode node = params == null ? null : params.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asLong();
        }
        String text = node.asText().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw BizException.badRequest(field + " 必须是整数，实际: " + text);
        }
    }

    /**
     * 选填的长整数<b>数组</b>（如 {@code videoIds: [1,2,3]}）；缺失返回空列表。
     *
     * <p>⚠️ 元素可以是数字也可以是数字字符串（影刀常把 19 位 ID 当字符串传）——
     * 复用 {@link #optionalLong} 的兼容逻辑。</p>
     */
    static List<Long> optionalLongList(JsonNode params, String field) {
        JsonNode node = params == null ? null : params.get(field);
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw BizException.badRequest(field + " 必须是数组，实际: " + node.getNodeType());
        }
        List<Long> out = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (item == null || item.isNull()) {
                continue;
            }
            if (item.isNumber()) {
                out.add(item.asLong());
                continue;
            }
            String text = item.asText().trim();
            if (text.isEmpty()) {
                continue;
            }
            try {
                out.add(Long.parseLong(text));
            } catch (NumberFormatException e) {
                throw BizException.badRequest(field + " 的元素必须是整数，实际: " + text);
            }
        }
        return out;
    }

    /** 校验 params 本身非空。 */
    static JsonNode requireParams(JsonNode params, String requiredFieldsHint) {
        if (params == null || params.isNull()) {
            throw BizException.badRequest("params 不能为空，至少需要 " + requiredFieldsHint);
        }
        return params;
    }
}
