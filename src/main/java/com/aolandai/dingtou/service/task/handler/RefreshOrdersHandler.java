package com.aolandai.dingtou.service.task.handler;

import com.aolandai.dingtou.service.qianchuan.OrderSnapshotService;
import com.aolandai.dingtou.service.task.TaskHandler;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * {@code refreshOrders} —— 刷新随心推订单快照（N 的数据源）。
 *
 * <pre>
 * // 增量（默认）：拉最近 7 天，按 order_id 去重合并。实测 8 账户约 44 秒，同步返回
 * {"requestId":"r1","params":{}}
 *
 * // 指定回溯天数
 * {"requestId":"r2","params":{"lookbackDays":7}}
 *
 * // 首次全量：8 个账户各拉全部历史（实测 ≈90 分钟）→ 【异步】，立即返回
 * {"requestId":"r3","params":{"full":true}}
 * </pre>
 *
 * <h2>⚠️ 为什么要这个任务</h2>
 * <p>老板 2026-10-07 定：<b>N 不预筛，只认随心推订单数</b>。而订单接口
 * <b>没有达人/商品过滤</b>（只有时间/状态/账户）→ 只能拉回来本地分组计数。</p>
 *
 * <p>实测：最大账户全量 = 689 页 / 34,386 条 / <b>11 分 38 秒</b>，
 * 8 个账户 ≈90 分钟 → 所以「<b>首次全量 + 之后每次投放前增量</b>」。</p>
 *
 * <h2>⚠️ 增量是幂等的</h2>
 * <p>重复拉同一天不会多算 —— 快照存了已计入的 {@code orderId}，合并时做集合并集。
 * 所以<b>可以放心地每次投放前都跑一次</b>。</p>
 */
@Component
public class RefreshOrdersHandler implements TaskHandler {

    private final OrderSnapshotService service;

    public RefreshOrdersHandler(OrderSnapshotService service) {
        this.service = service;
    }

    @Override
    public String code() {
        return "refreshOrders";
    }

    @Override
    public String description() {
        return "刷新随心推订单快照（N 的数据源）：默认增量拉最近 7 天（覆盖投放时长上限 168h）；传 full=true 首次全量（异步，约 90 分钟）";
    }

    @Override
    public Object handle(JsonNode params) throws Exception {
        JsonNode safeParams = params == null ? null : params;
        boolean full = Params.optionalBool(safeParams, "full", false);
        if (full) {
            return service.refreshFullAsync();
        }
        int lookbackDays = Params.optionalInt(safeParams, "lookbackDays",
                OrderSnapshotService.DEFAULT_LOOKBACK_DAYS);
        return service.refreshIncremental(lookbackDays);
    }
}
