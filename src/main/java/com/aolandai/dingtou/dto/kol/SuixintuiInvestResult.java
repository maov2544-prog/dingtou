package com.aolandai.dingtou.dto.kol;

import com.aolandai.dingtou.dto.sheet.CellWrite;

import java.util.List;

/**
 * A 场景（建随心推 + 告诉影刀写什么）的完整结果。
 *
 * <h2>⚠️ 怎么读这个结果（影刀侧）</h2>
 * <table border="1">
 *   <tr><th>{@code dryRun}</th><th>{@code orderId}</th><th>{@code cells}</th><th>含义</th></tr>
 *   <tr><td>true</td><td>null</td><td>有</td><td><b>演练</b>：没真建单；请核对 {@code requestBody} 里的
 *       {@code amount}/{@code deliveryTime} 单位，确认后关掉 dry-run</td></tr>
 *   <tr><td>false</td><td>有</td><td>有</td><td><b>真建成</b>：按 {@code cells} 写回素材表</td></tr>
 *   <tr><td>—</td><td>null</td><td><b>空</b></td><td><b>没建</b>：看 {@code warnings} 找原因
 *       （没账户 / 未授权 / 商品映射不到 / 该组合的投放类型都已填过）</td></tr>
 * </table>
 *
 * <p>⚠️ {@code warnings} <b>必须看</b>：非空说明这次<b>没有全部办成</b>，
 * 不能当成「都投了」。</p>
 *
 * @param awemeShowId   抖音号（原样回显）
 * @param productName   商品名（原样回显）
 * @param advertiserId  投放账户 ID；{@code null} = 没定位到账户
 * @param advertiserName 投放账户名（来自素材表 {@code 全域账号}列，清洗后）
 * @param awemeId       达人的数值 UID；{@code null} = 没解析到（多半是未授权）
 * @param productId     商品 ID；{@code null} = 映射不到
 * @param amount        实际使用的预算（回显，便于核对单位）
 * @param deliveryTime  实际使用的投放时长（回显，便于核对单位）
 * @param bidType       实际使用的出价方式（{@code AUTO_BID}/{@code MANUAL_BID}）；{@code ""} = 不传
 * @param roiGoal       实际使用的支付 ROI 目标（回显）；{@code null} = 不传
 * @param dryRun        是否演练
 * @param orderId       建成的随心推订单 ID；演练或失败时为 {@code null}
 * @param requestBody   实际发出（或将要发出）的请求体 JSON —— 演练核对用
 * @param cells         请影刀写入的单元格指令
 * @param warnings      过程中的异常情况
 */
public record SuixintuiInvestResult(
        String awemeShowId,
        String productName,
        Long advertiserId,
        String advertiserName,
        Long awemeId,
        String productId,
        long amount,
        double deliveryTime,
        String bidType,
        Double roiGoal,
        boolean dryRun,
        Long orderId,
        String requestBody,
        List<CellWrite> cells,
        List<String> warnings) {

    public SuixintuiInvestResult {
        awemeShowId = awemeShowId == null ? "" : awemeShowId;
        productName = productName == null ? "" : productName;
        advertiserName = advertiserName == null ? "" : advertiserName;
        bidType = bidType == null ? "" : bidType;
        requestBody = requestBody == null ? "" : requestBody;
        cells = cells == null ? List.of() : List.copyOf(cells);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** 是否真的建成了（非演练且拿到订单 ID）。 */
    public boolean created() {
        return !dryRun && orderId != null;
    }
}
