package com.aolandai.dingtou.service.task.handler;

import com.aolandai.dingtou.config.QianchuanProperties;
import com.aolandai.dingtou.dto.api.ErrorCode;
import com.aolandai.dingtou.dto.qianchuan.PlanAweme;
import com.aolandai.dingtou.dto.qianchuan.PlanAwemeLookupResult;
import com.aolandai.dingtou.dto.qianchuan.PlanRef;
import com.aolandai.dingtou.exception.BizException;
import com.aolandai.dingtou.service.qianchuan.PlanAwemeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PlanAwemeLookupHandler} 单测：只验「参数解析 → 调 service」这一段，
 * 业务本身在 {@code DefaultPlanAwemeServiceTest} 里测。
 */
class PlanAwemeLookupHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PlanAwemeService service;
    private PlanAwemeLookupHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        service = mock(PlanAwemeService.class);
        handler = new PlanAwemeLookupHandler(service, new QianchuanProperties());
        when(service.lookupAll(any())).thenReturn(
                new PlanAwemeLookupResult(0, 0, List.of(), List.of()));
    }

    @Test
    @DisplayName("taskCode = planAwemeLookup（不带业务前缀，通用能力）")
    void codeIsPlanAwemeLookup() {
        assertEquals("planAwemeLookup", handler.code());
    }

    @Test
    @DisplayName("正常批量：解析出正确的 {advertiserId, adId} 引用列表")
    void parsesItemsIntoRefs() throws Exception {
        handler.handle(params("{\"items\":["
                + "{\"advertiserId\":1823379540880396,\"adId\":1877813517052328},"
                + "{\"advertiserId\":1776098375039040,\"adId\":1877000000000000}]}"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PlanRef>> captor = ArgumentCaptor.forClass(List.class);
        verify(service).lookupAll(captor.capture());
        assertEquals(List.of(
                        new PlanRef(1823379540880396L, 1877813517052328L),
                        new PlanRef(1776098375039040L, 1877000000000000L)),
                captor.getValue());
    }

    @Test
    @DisplayName("advertiserId 传字符串数字也能解析（影刀常把大整数当字符串）")
    void acceptsNumericStrings() throws Exception {
        handler.handle(params("{\"items\":["
                + "{\"advertiserId\":\"1823379540880396\",\"adId\":\"1877813517052328\"}]}"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PlanRef>> captor = ArgumentCaptor.forClass(List.class);
        verify(service).lookupAll(captor.capture());
        assertEquals(List.of(new PlanRef(1823379540880396L, 1877813517052328L)), captor.getValue());
    }

    @Test
    @DisplayName("items 缺失 / 空数组 → 参数错误")
    void missingItemsIsBadRequest() throws Exception {
        assertBadRequest("{}");
        assertBadRequest("{\"items\":[]}");
        assertBadRequest("{\"items\":\"abc\"}");
    }

    @Test
    @DisplayName("items[i] 缺 advertiserId 或 adId → 参数错误")
    void missingItemFieldIsBadRequest() throws Exception {
        assertBadRequest("{\"items\":[{\"adId\":123}]}");
        assertBadRequest("{\"items\":[{\"advertiserId\":123}]}");
        assertBadRequest("{\"items\":[{\"advertiserId\":null,\"adId\":123}]}");
    }

    @Test
    @DisplayName("items[i] 不是对象 → 参数错误")
    void nonObjectItemIsBadRequest() throws Exception {
        assertBadRequest("{\"items\":[123]}");
    }

    @Test
    @DisplayName("items[i] 字段不是整数 → 参数错误（不是内部错误，影刀不该重试）")
    void nonNumericItemFieldIsBadRequest() throws Exception {
        assertBadRequest("{\"items\":[{\"advertiserId\":\"abc\",\"adId\":123}]}");
    }

    @Test
    @DisplayName("超过单批上限 → 参数错误（详情接口一计划一次请求，不能无限批）")
    void tooManyItemsIsBadRequest() throws Exception {
        StringBuilder sb = new StringBuilder("{\"items\":[");
        for (int i = 0; i < 201; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"advertiserId\":1,\"adId\":").append(i + 1).append('}');
        }
        sb.append("]}");
        assertBadRequest(sb.toString());
    }

    @Test
    @DisplayName("达人信息透传（抖音号 / 抖音名 / adId）")
    void returnsLookupResult() throws Exception {
        when(service.lookupAll(any())).thenReturn(new PlanAwemeLookupResult(
                1, 1, List.of(new PlanAweme(111L, "610129yiyi", "伊伊麻麻")), List.of()));

        Object data = handler.handle(params(
                "{\"items\":[{\"advertiserId\":1,\"adId\":111}]}"));

        PlanAwemeLookupResult result = (PlanAwemeLookupResult) data;
        assertEquals(1, result.resolved());
        assertEquals("610129yiyi", result.items().get(0).awemeShowId());
        assertEquals("伊伊麻麻", result.items().get(0).awemeName());
    }

    private void assertBadRequest(String json) throws Exception {
        JsonNode node = params(json);
        BizException e = assertThrows(BizException.class, () -> handler.handle(node));
        assertEquals(ErrorCode.BAD_REQUEST.code(), e.errorCode().code(),
                "必须是 code=2（影刀改参数，不要重试）");
    }

    private static JsonNode params(String json) throws Exception {
        return MAPPER.readTree(json);
    }
}
