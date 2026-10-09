package com.aolandai.dingtou.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;

/**
 * 本地存储配置（前缀 {@code dingtou.store}）。
 *
 * <p>对应 application.yml：</p>
 * <pre>
 * dingtou:
 *   store:
 *     data-dir: ${DINGTOU_DATA_DIR:D:/program/aolandai/dingtou/data}
 *     order-snapshot-ttl-hours: 24
 * </pre>
 *
 * <p>⚠️ <b>{@code data-dir} 必须是绝对路径。</b>老工程踩过这个坑：
 * 用相对路径时，工作目录一变（WinSW 启动 vs 命令行启动）就读不到已落盘的流水，
 * 结果「防重复」形同虚设 —— 重启后把已经做过的事又做了一遍。</p>
 */
@Validated
@ConfigurationProperties(prefix = "dingtou.store")
public class StoreProperties {

    /** 数据目录（绝对路径）。幂等流水与订单快照都放这里。 */
    @NotNull
    private Path dataDir = Path.of("D:/program/aolandai/dingtou/data");

    /** 幂等流水文件名。 */
    private String requestLedgerFileName = "request-ledger.jsonl";

    /** 随心推订单快照文件名。 */
    private String orderSnapshotFileName = "order-snapshot.jsonl";

    /** 计划台账文件名。 */
    private String planLedgerFileName = "plan-ledger.jsonl";

    /** 异步任务文件名（供 {@code GET /api/v1/task/{taskId}} 轮询）。 */
    private String taskJobsFileName = "task-jobs.jsonl";

    /** 订单快照有效期（小时）。超过就认为过期，需要重新拉取。 */
    private int orderSnapshotTtlHours = 24;

    /** 幂等流水的完整路径。 */
    public Path getRequestLedger() {
        return dataDir.resolve(requestLedgerFileName);
    }

    /** 订单快照的完整路径。 */
    public Path getOrderSnapshot() {
        return dataDir.resolve(orderSnapshotFileName);
    }

    /** 计划台账的完整路径。 */
    public Path getPlanLedger() {
        return dataDir.resolve(planLedgerFileName);
    }

    /** 异步任务文件的完整路径。 */
    public Path getTaskJobs() {
        return dataDir.resolve(taskJobsFileName);
    }

    @AssertTrue(message = "dingtou.store.data-dir 必须是绝对路径"
            + "（相对路径会随工作目录漂移，重启后读不到幂等流水 → 防重复失效）")
    public boolean isDataDirAbsolute() {
        return dataDir != null && dataDir.isAbsolute();
    }

    @AssertTrue(message = "dingtou.store.order-snapshot-ttl-hours 必须为正")
    public boolean isTtlPositive() {
        return orderSnapshotTtlHours > 0;
    }

    /** 启动横幅用。 */
    public String describe() {
        return "数据目录=" + dataDir + "，订单快照有效期=" + orderSnapshotTtlHours + " 小时";
    }

    // ==================== getter / setter ====================

    public Path getDataDir() {
        return dataDir;
    }

    public void setDataDir(Path dataDir) {
        this.dataDir = dataDir;
    }

    public String getRequestLedgerFileName() {
        return requestLedgerFileName;
    }

    public void setRequestLedgerFileName(String requestLedgerFileName) {
        this.requestLedgerFileName = requestLedgerFileName;
    }

    public String getOrderSnapshotFileName() {
        return orderSnapshotFileName;
    }

    public void setOrderSnapshotFileName(String orderSnapshotFileName) {
        this.orderSnapshotFileName = orderSnapshotFileName;
    }

    public String getPlanLedgerFileName() {
        return planLedgerFileName;
    }

    public void setPlanLedgerFileName(String planLedgerFileName) {
        this.planLedgerFileName = planLedgerFileName;
    }

    public int getOrderSnapshotTtlHours() {
        return orderSnapshotTtlHours;
    }

    public void setOrderSnapshotTtlHours(int orderSnapshotTtlHours) {
        this.orderSnapshotTtlHours = orderSnapshotTtlHours;
    }
}
