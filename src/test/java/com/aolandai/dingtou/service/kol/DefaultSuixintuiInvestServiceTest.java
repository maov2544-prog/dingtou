package com.aolandai.dingtou.service.kol;

import com.aolandai.dingtou.config.DingtouProperties;
import com.aolandai.dingtou.config.SuixintuiProperties;
import com.aolandai.dingtou.dto.kol.SuixintuiInvestResult;
import com.aolandai.dingtou.dto.qianchuan.SuixintuiCreateOutcome;
import com.aolandai.dingtou.dto.sheet.AccountRow;
import com.aolandai.dingtou.dto.sheet.DeliveryRowRef;
import com.aolandai.dingtou.exception.BizException;
import com.aolandai.dingtou.service.qianchuan.AwemeUidResolver;
import com.aolandai.dingtou.service.qianchuan.SuixintuiOrderCreateService;
import com.aolandai.dingtou.source.AccountDictReader;
import com.aolandai.dingtou.source.DeliverySheetLocator;
import com.aolandai.dingtou.source.ProductDictReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultSuixintuiInvestService} 单测：全部依赖都 mock，<b>不读真表、不调接口、不花钱</b>。
 *
 * <p>覆盖：定位不到行、没有待处理行、无账户、跨多账户、未授权、商品映射不到、
 * 正常建单、以及<b>只处理「投放类型为空」的行</b>这条关键规则。</p>
 */
class DefaultSuixintuiInvestServiceTest {

    private static final String AWEME = "610129yiyi";
    private static final String PRODUCT = "护臀膏";
    private static final String ACC_NAME = "官方【母婴商品】";
    private static final long ACC_ID = 1823379540880396L;
    private static final long AWEME_UID = 2071074583877891L;
    private static final String PRODUCT_ID = "3593275488396811650";

    private DeliverySheetLocator locator;
    private AccountDictReader accountDictReader;
    private ProductDictReader productDictReader;
    private AwemeUidResolver awemeUidResolver;
    private SuixintuiOrderCreateService orderCreateService;
    private SuixintuiProperties props;
    private DefaultSuixintuiInvestService service;

    @BeforeEach
    void setUp() throws Exception {
        locator = mock(DeliverySheetLocator.class);
        accountDictReader = mock(AccountDictReader.class);
        productDictReader = mock(ProductDictReader.class);
        awemeUidResolver = mock(AwemeUidResolver.class);
        orderCreateService = mock(SuixintuiOrderCreateService.class);

        props = new SuixintuiProperties();
        props.setDefaultAmount(30000L);
        props.setDefaultDeliveryTime(6.0);
        props.setDefaultBidType("AUTO_BID");
        props.setDefaultQcpxMode("");

        DingtouProperties dingtou = new DingtouProperties();
        dingtou.setDryRun(true);                       // 默认演练
        dingtou.setAuthToken("test-token-for-unit-test");

        service = new DefaultSuixintuiInvestService(locator, accountDictReader, productDictReader,
                awemeUidResolver, orderCreateService, props, dingtou);

        // 默认：一切顺利
        when(accountDictReader.findByName(ACC_NAME))
                .thenReturn(Optional.of(new AccountRow(ACC_NAME, String.valueOf(ACC_ID), "官方店", 2)));
        when(awemeUidResolver.resolve(ACC_ID, AWEME)).thenReturn(Optional.of(AWEME_UID));
        when(productDictReader.uniqueProductIdOfName(PRODUCT)).thenReturn(Optional.of(PRODUCT_ID));
        when(orderCreateService.create(any(), anyBoolean()))
                .thenReturn(new SuixintuiCreateOutcome(true, null, "{\"fake\":true}", List.of()));
    }

    /** 一行素材：K 为空 = 待处理。 */
    private static DeliveryRowRef pendingRow(int row) {
        return new DeliveryRowRef(row, AWEME, PRODUCT, ACC_NAME, ACC_NAME, "");
    }

    /** 一行素材：K 已填 = 历史数据，不该碰。 */
    private static DeliveryRowRef doneRow(int row) {
        return new DeliveryRowRef(row, AWEME, PRODUCT, ACC_NAME, ACC_NAME, "全域随心推");
    }

    // ==================== 定位 ====================

    @Test
    @DisplayName("素材表里找不到该组合 → 抛业务失败（要人工核对，不能当没事）")
    void noRowsIsBusinessFail() throws Exception {
        when(locator.locate(AWEME, PRODUCT)).thenReturn(List.of());

        BizException e = assertThrows(BizException.class, () -> service.invest(AWEME, PRODUCT));
        assertTrue(e.getMessage().contains(AWEME) && e.getMessage().contains(PRODUCT),
                e.getMessage());
    }

    @Test
    @DisplayName("★ 只处理【投放类型为空】的行 —— 已填过的历史数据一律不碰")
    void onlyHandlesRowsWithEmptyType() throws Exception {
        when(locator.locate(AWEME, PRODUCT)).thenReturn(List.of(
                doneRow(10), pendingRow(11), doneRow(12), pendingRow(13)));

        SuixintuiInvestResult result = service.invest(AWEME, PRODUCT);

        // 每行写 2 格：K 投放类型 + N 投放状态（老板 2026-10-05 定「两个都写」）
        assertEquals(4, result.cells().size(), "只有第 11、13 行该被写，每行 2 格");
        assertEquals(11, result.cells().get(0).row());
        assertEquals("K", result.cells().get(0).column());
        assertEquals("全域随心推", result.cells().get(0).value());
        assertEquals(11, result.cells().get(1).row());
        assertEquals("N", result.cells().get(1).column());
        assertEquals("已投", result.cells().get(1).value());
        assertEquals(13, result.cells().get(2).row());
        assertEquals(13, result.cells().get(3).row());
    }

    @Test
    @DisplayName("该组合的投放类型都已填过 → 不建单，返回空 cells + warning")
    void allRowsAlreadyFilled() throws Exception {
        when(locator.locate(AWEME, PRODUCT)).thenReturn(List.of(doneRow(10), doneRow(11)));

        SuixintuiInvestResult result = service.invest(AWEME, PRODUCT);

        assertTrue(result.cells().isEmpty());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("都已填过")),
                result.warnings().toString());
        verify(orderCreateService, never()).create(any(), anyBoolean());
    }

    // ==================== 账户 ====================

    @Test
    @DisplayName("待处理行都没有账户 → 不建单，warning 里带【行号 + 原值】便于人工补表")
    void noAccountIsWarnedWithRowDetail() throws Exception {
        when(locator.locate(AWEME, PRODUCT)).thenReturn(List.of(
                new DeliveryRowRef(20, AWEME, PRODUCT, "", "", ""),
                new DeliveryRowRef(21, AWEME, PRODUCT, "未定向", "", "")));

        SuixintuiInvestResult result = service.invest(AWEME, PRODUCT);

        assertTrue(result.cells().isEmpty());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("第 20 行")),
                "要写出具体行号：" + result.warnings());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("未定向")),
                "要回显原值，人工才知道怎么补：" + result.warnings());
        verify(orderCreateService, never()).create(any(), anyBoolean());
    }

    @Test
    @DisplayName("★ 待处理行跨多个账户 → 直接报错要人工确认（绝不瞎挑一个）")
    void multipleAccountsIsBusinessFail() throws Exception {
        when(locator.locate(AWEME, PRODUCT)).thenReturn(List.of(
                pendingRow(30),
                new DeliveryRowRef(31, AWEME, PRODUCT, "婴童【儿童-商品】", "婴童【儿童-商品】", "")));

        BizException e = assertThrows(BizException.class, () -> service.invest(AWEME, PRODUCT));
        assertTrue(e.getMessage().contains("跨多个账户"), e.getMessage());
        verify(orderCreateService, never()).create(any(), anyBoolean());
    }

    // ==================== 达人 / 商品 ====================

    @Test
    @DisplayName("该账户下达人未授权 → 不建单 + warning（提示去千川后台授权）")
    void unauthorizedAwemeIsWarned() throws Exception {
        when(locator.locate(AWEME, PRODUCT)).thenReturn(List.of(pendingRow(40)));
        when(awemeUidResolver.resolve(ACC_ID, AWEME)).thenReturn(Optional.empty());

        SuixintuiInvestResult result = service.invest(AWEME, PRODUCT);

        assertTrue(result.cells().isEmpty());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("没有授权达人")),
                result.warnings().toString());
        verify(orderCreateService, never()).create(any(), anyBoolean());
    }

    @Test
    @DisplayName("商品名映射不到唯一商品 ID → 不建单 + warning")
    void unknownProductIsWarned() throws Exception {
        when(locator.locate(AWEME, PRODUCT)).thenReturn(List.of(pendingRow(50)));
        when(productDictReader.uniqueProductIdOfName(PRODUCT)).thenReturn(Optional.empty());

        SuixintuiInvestResult result = service.invest(AWEME, PRODUCT);

        assertTrue(result.cells().isEmpty());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("商品 ID")),
                result.warnings().toString());
        verify(orderCreateService, never()).create(any(), anyBoolean());
    }

    // ==================== 正常路径 ====================

    @Test
    @DisplayName("★ 正常（dry-run）：返回 cells + 请求体，orderId 为空")
    void happyPathDryRun() throws Exception {
        when(locator.locate(AWEME, PRODUCT)).thenReturn(List.of(pendingRow(60), pendingRow(61)));

        SuixintuiInvestResult result = service.invest(AWEME, PRODUCT);

        assertTrue(result.dryRun(), "默认 dry-run");
        assertNull(result.orderId());
        assertEquals(ACC_ID, result.advertiserId());
        assertEquals(ACC_NAME, result.advertiserName());
        assertEquals(AWEME_UID, result.awemeId());
        assertEquals(PRODUCT_ID, result.productId());
        assertEquals(4, result.cells().size(), "2 行 × 2 格（K 投放类型 + N 投放状态）");
        assertTrue(result.warnings().isEmpty(), result.warnings().toString());
        verify(orderCreateService).create(any(), eq(true));
    }

    @Test
    @DisplayName("正常（非演练）：返回 orderId，cells 照常给影刀")
    void happyPathRealCreate() throws Exception {
        DingtouProperties real = new DingtouProperties();
        real.setDryRun(false);
        real.setAuthToken("test-token-for-unit-test");
        SuixintuiProperties props = new SuixintuiProperties();
        DefaultSuixintuiInvestService svc = new DefaultSuixintuiInvestService(
                locator, accountDictReader, productDictReader, awemeUidResolver,
                orderCreateService, props, real);

        when(locator.locate(AWEME, PRODUCT)).thenReturn(List.of(pendingRow(70)));
        when(orderCreateService.create(any(), anyBoolean()))
                .thenReturn(new SuixintuiCreateOutcome(false, 555L, "{}", List.of()));

        SuixintuiInvestResult result = svc.invest(AWEME, PRODUCT);

        assertTrue(result.created());
        assertEquals(555L, result.orderId());
        assertEquals(2, result.cells().size(), "1 行 × 2 格（K 投放类型 + N 投放状态）");
        verify(orderCreateService).create(any(), eq(false));
    }

    @Test
    @DisplayName("预算/时长来自配置，并在结果里回显（便于核对单位）")
    void amountAndDeliveryTimeComeFromConfig() throws Exception {
        when(locator.locate(AWEME, PRODUCT)).thenReturn(List.of(pendingRow(80)));

        SuixintuiInvestResult result = service.invest(AWEME, PRODUCT);

        assertEquals(30000L, result.amount());
        assertEquals(6.0, result.deliveryTime());
    }

    @Test
    @DisplayName("★ 出价方式 / ROI 目标也来自配置，并透传到建单参数 + 结果回显")
    void bidTypeAndRoiGoalComeFromConfig() throws Exception {
        // 老板 2026-10-05 定：MANUAL_BID + ROI 1.7（MANUAL_BID 使 roi_goal 变成条件必填）
        props.setDefaultBidType("MANUAL_BID");
        props.setDefaultRoiGoal(1.7);
        when(locator.locate(AWEME, PRODUCT)).thenReturn(List.of(pendingRow(85)));

        SuixintuiInvestResult result = service.invest(AWEME, PRODUCT);

        org.mockito.ArgumentCaptor<com.aolandai.dingtou.dto.qianchuan.SuixintuiOrderParam> captor =
                org.mockito.ArgumentCaptor.forClass(
                        com.aolandai.dingtou.dto.qianchuan.SuixintuiOrderParam.class);
        verify(orderCreateService).create(captor.capture(), anyBoolean());
        assertEquals("MANUAL_BID", captor.getValue().bidType());
        assertEquals(1.7, captor.getValue().roiGoal());

        assertEquals("MANUAL_BID", result.bidType(), "结果里要回显，dry-run 核对时不用去翻请求体");
        assertEquals(1.7, result.roiGoal());
    }

    @Test
    @DisplayName("传给建单服务的参数正确（账户/达人UID/商品ID/预算/时长）")
    void passesCorrectParamToCreate() throws Exception {
        when(locator.locate(AWEME, PRODUCT)).thenReturn(List.of(pendingRow(90)));

        service.invest(AWEME, PRODUCT);

        org.mockito.ArgumentCaptor<com.aolandai.dingtou.dto.qianchuan.SuixintuiOrderParam> captor =
                org.mockito.ArgumentCaptor.forClass(
                        com.aolandai.dingtou.dto.qianchuan.SuixintuiOrderParam.class);
        verify(orderCreateService).create(captor.capture(), anyBoolean());
        var p = captor.getValue();
        assertEquals(ACC_ID, p.advertiserId());
        assertEquals(AWEME_UID, p.awemeId(), "必须传【数值 UID】，不是抖音号字符串");
        assertEquals(Long.parseLong(PRODUCT_ID), p.productId());
        assertEquals(30000L, p.amount());
        assertEquals(6.0, p.deliveryTime());
        assertEquals("AUTO_BID", p.bidType());
        assertTrue(p.qcpxMode() == null || p.qcpxMode().isBlank(),
                "A 场景是随心推，默认不传乘方模式（空白 = 建单时跳过该字段）");
    }

    @Test
    @DisplayName("账户不在账户字典里 → 不建单 + warning")
    void accountNotInDictIsWarned() throws Exception {
        when(locator.locate(AWEME, PRODUCT)).thenReturn(List.of(pendingRow(95)));
        when(accountDictReader.findByName(ACC_NAME)).thenReturn(Optional.empty());

        SuixintuiInvestResult result = service.invest(AWEME, PRODUCT);

        assertTrue(result.cells().isEmpty());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("投放账户信息表")),
                result.warnings().toString());
        verify(orderCreateService, never()).create(any(), anyBoolean());
        verify(awemeUidResolver, never()).resolve(anyLong(), any());
    }
}
