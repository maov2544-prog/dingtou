package com.aolandai.dingtou.dto.anomaly;

/**
 * 一条异常记录 —— 写进「异常表」（老板 2026-10-08：**新建一张 Excel**）。
 *
 * <h2>什么时候记异常</h2>
 * <p>流程里凡是「<b>没办成，且不是「主动等待」</b>」的情况都要记，例如：</p>
 * <ul>
 *   <li>计划存在但<b>不是投放中</b>（{@code 计划状态}）—— 加不了素材；</li>
 *   <li>账户解析不到（{@code 账户}）—— 查不了计划；</li>
 *   <li>创建随心推订单失败（{@code 建随心推}）；</li>
 *   <li>建乘方计划失败 / 加素材失败。</li>
 * </ul>
 *
 * <p>⚠️ 与「投放中 → 跳过等它跑完」区分开：后者是<b>主动等待</b>，不记异常（记在素材表的投放状态列）。</p>
 *
 * @param time   记录时间（{@code yyyy-MM-dd HH:mm:ss}）
 * @param aweme  抖音号
 * @param product 商品名
 * @param account 账户（名 + ID）；解析不到时写「（未解析到账户）」
 * @param stage  阶段：查计划 / 建随心推 / 加素材 / 建乘方计划 / 解析上下文
 * @param type   异常类型（短标签，便于筛选）
 * @param detail 详情（原始报错 / warning 文本）
 */
public record AnomalyRecord(
        String time,
        String aweme,
        String product,
        String account,
        String stage,
        String type,
        String detail) {

    public AnomalyRecord {
        time = time == null ? "" : time;
        aweme = aweme == null ? "" : aweme;
        product = product == null ? "" : product;
        account = account == null ? "" : account;
        stage = stage == null ? "" : stage;
        type = type == null ? "" : type;
        detail = detail == null ? "" : detail;
    }

    /** 异常表的列顺序（写 Excel 时按这个顺序落列）。 */
    public static String[] headers() {
        return new String[]{"时间", "抖音号", "商品名", "账户", "阶段", "异常类型", "详情"};
    }

    /** 按表头顺序取值。 */
    public String[] values() {
        return new String[]{time, aweme, product, account, stage, type, detail};
    }
}
