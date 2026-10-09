package com.aolandai.dingtou.dto.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 影刀发来的统一请求体。
 *
 * <pre>
 * POST /api/v1/task/{taskCode}
 * Header: X-Auth-Token: &lt;token&gt;
 * Body:   { "requestId": "影刀生成的唯一号", "params": { ... } }
 * </pre>
 *
 * @param requestId 影刀生成的<b>唯一请求号</b>。⚠️ 幂等落盘就靠它 ——
 *                  同一个 requestId 重复到达时，服务必须直接返回上次结果，绝不重复执行。
 *                  影刀的重试策略不可控，而重复建千川计划是要花钱的。
 * @param params    任务参数。允许为空（如 {@code ping}）。
 */
public record ApiRequest(String requestId, JsonNode params) {

    /** 空请求（无 requestId、无参数），供 {@code @RequestBody(required=false)} 的缺省场景使用。 */
    public static ApiRequest empty() {
        return new ApiRequest(null, null);
    }

    /**
     * 是否带了 requestId。
     *
     * <p>⚠️ 必须 {@code @JsonIgnore}：Jackson 会把 {@code hasRequestId()} 解析成名为
     * {@code requestId} 的属性，与 record 组件同名，反序列化时可能打架。</p>
     */
    @JsonIgnore
    public boolean hasRequestId() {
        return requestId != null && !requestId.isBlank();
    }
}
