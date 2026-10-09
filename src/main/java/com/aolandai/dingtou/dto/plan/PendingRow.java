package com.aolandai.dingtou.dto.plan;

/**
 * 批量任务里的一行「待投放」记录（影刀扫素材表得到）。
 *
 * <p>⚠️ 影刀<b>不需要</b>做任何判断，只要把扫到的行原样交上来 ——
 * 该走 A / B / C1 / C2 由服务自己决定。</p>
 *
 * @param row          Excel 行号（1-based，含表头）—— 用于把结果对回原表；缺失/0 时只做处理不回写
 * @param awemeShowId  抖音号（必填）
 * @param productName  商品名（必填）
 * @param videoId      {@code H 视频id}（选填）；C1 场景用它作「本条素材」。
 *                     不传时服务会在素材表里找该组合「投放类型为空」的行，唯一才用。
 */
public record PendingRow(int row, String awemeShowId, String productName, Long videoId) {

    public PendingRow {
        awemeShowId = awemeShowId == null ? "" : awemeShowId.trim();
        productName = productName == null ? "" : productName.trim();
    }

    /** 该组合的业务键（用于去重）。 */
    public String combinationKey() {
        return awemeShowId + "\u0000" + productName;
    }

    public boolean isComplete() {
        return !awemeShowId.isEmpty() && !productName.isEmpty();
    }
}
