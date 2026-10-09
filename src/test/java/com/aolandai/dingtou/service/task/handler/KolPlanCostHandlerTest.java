package com.aolandai.dingtou.service.task.handler;

import com.aolandai.dingtou.dto.api.ErrorCode;
import com.aolandai.dingtou.dto.kol.KolPlanCostRequest;
import com.aolandai.dingtou.dto.kol.PlanCostQueryResult;
import com.aolandai.dingtou.exception.BizException;
import com.aolandai.dingtou.config.DingtouProperties;
import com.aolandai.dingtou.service.kol.DeliveryStatusChecker;
import com.aolandai.dingtou.service.kol.KolPlanCostService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link KolPlanCostHandler} 单测：只验「参数解析 → 调 service」这一段，
 * 业务本身在 {@code DefaultKolPlanCostServiceTest} 里测。
 */
class KolPlanCostHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private KolPlanCostService service;
    private KolPlanCostHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        service = mock(KolPlanCostService.class);
        handler = new KolPlanCostHandler(service, mock(DeliveryStatusChecker.class), new DingtouProperties());
        when(service.query(any(KolPlanCostRequest.class))).thenReturn(emptyResult());
    }

    @Test
    @DisplayName("taskCode = kolPlanCostQuery")
    void codeIsKolPlanCostQuery() {
        assertEquals("kolPlanCostQuery", handler.code());
    }

    @Test
    @DisplayName("不传参数 → 默认窗口 + 不带达人信息")
    void noParamsUsesDefaults() throws Exception {
        handler.handle(params("{}"));
        assertEquals(KolPlanCostRequest.defaults(), captured());
    }

    @Test
    @DisplayName("同时传 startDate + endDate → 显式窗口，仍不带达人信息")
    void bothDatesUseExplicitWindow() throws Exception {
        handler.handle(params("{\"startDate\":\"2026-09-04\",\"endDate\":\"2026-10-03\"}"));
        assertEquals(new KolPlanCostRequest(
                LocalDate.of(2026, 9, 4), LocalDate.of(2026, 10, 3), false), captured());
    }

    @Test
    @DisplayName("withAweme=true → 请求里带上开关（默认 false）")
    void withAwemeFlagIsPassedThrough() throws Exception {
        handler.handle(params("{\"withAweme\":true}"));
        assertEquals(new KolPlanCostRequest(null, null, true), captured());
    }

    @Test
    @DisplayName("三个参数组合")
    void allThreeParams() throws Exception {
        handler.handle(params(
                "{\"startDate\":\"2026-09-04\",\"endDate\":\"2026-10-03\",\"withAweme\":true}"));
        assertEquals(new KolPlanCostRequest(
                LocalDate.of(2026, 9, 4), LocalDate.of(2026, 10, 3), true), captured());
    }

    @Test
    @DisplayName("只传一个日期 → 参数错误（不能靠猜另一半）")
    void singleDateIsBadRequest() throws Exception {
        assertBadRequest("{\"startDate\":\"2026-09-04\"}");
        assertBadRequest("{\"endDate\":\"2026-10-03\"}");
    }

    @Test
    @DisplayName("startDate 晚于 endDate → 参数错误")
    void startAfterEndIsBadRequest() throws Exception {
        assertBadRequest("{\"startDate\":\"2026-10-03\",\"endDate\":\"2026-09-04\"}");
    }

    @Test
    @DisplayName("日期格式不对 → 参数错误（不是内部错误，影刀不该重试）")
    void badDateFormatIsBadRequest() throws Exception {
        assertBadRequest("{\"startDate\":\"2026/09/04\",\"endDate\":\"2026-10-03\"}");
    }

    @Test
    @DisplayName("withAweme 不是布尔 → 用默认 false（不报错）")
    void nonBooleanWithAwemeFallsBack() throws Exception {
        handler.handle(params("{\"withAweme\":\"yes\"}"));
        KolPlanCostRequest request = captured();
        assertFalse(request.withAweme(), "非布尔值应回退到默认 false");
        assertTrue(request.startDate() == null && request.endDate() == null);
    }

    // ==================== 工具 ====================

    private KolPlanCostRequest captured() throws Exception {
        ArgumentCaptor<KolPlanCostRequest> captor = ArgumentCaptor.forClass(KolPlanCostRequest.class);
        verify(service).query(captor.capture());
        return captor.getValue();
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

    private static PlanCostQueryResult emptyResult() {
        return new PlanCostQueryResult("2026-09-04 00:00:00", "2026-10-03 23:59:59",
                0, 0, List.of(), List.of());
    }
}
