package com.aolandai.dingtou.service.task;

import com.aolandai.dingtou.dto.api.ApiResponse;
import com.aolandai.dingtou.dto.api.ErrorCode;
import com.aolandai.dingtou.store.TaskJob;
import com.aolandai.dingtou.store.TaskJobStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * 异步任务的<b>轮询</b>查询（对应 {@code GET /api/v1/task/{taskId}}）。
 *
 * <p>逻辑放在这里而不是 controller：controller 只负责「收请求 → 交出去 → 发应答」，
 * 而这里要做「查存储 + 按状态分派 + 反序列化旧应答」，是实打实的逻辑，需要能单测。</p>
 *
 * <h2>三种结果</h2>
 * <table border="1">
 *   <tr><th>任务状态</th><th>返回</th><th>影刀该怎么做</th></tr>
 *   <tr><td>不存在 / 已过期</td><td>{@code code=1}</td><td>别重试，上报人工（可能任务从没提交成功）</td></tr>
 *   <tr><td>{@code RUNNING}</td><td>{@code code=4 + taskId}</td><td><b>继续轮询</b>，别重发原请求</td></tr>
 *   <tr><td>{@code DONE} / {@code FAILED}</td><td><b>原样回放</b>当初那份应答</td><td>按那份应答的 {@code code} 走</td></tr>
 * </table>
 *
 * <p>⚠️ 回放的是「当初那份完整应答」而不是「重新算一遍」——
 * 重算可能给出不同结果（数据变了），也会重复花钱。</p>
 */
@Service
public class TaskJobQueryService {

    private static final Logger log = LoggerFactory.getLogger(TaskJobQueryService.class);

    private final TaskJobStore jobStore;
    private final ObjectMapper objectMapper;

    public TaskJobQueryService(TaskJobStore jobStore, ObjectMapper objectMapper) {
        this.jobStore = jobStore;
        this.objectMapper = objectMapper;
    }

    /** 轮询一次。 */
    public ApiResponse poll(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return ApiResponse.error(ErrorCode.BAD_REQUEST, null, "taskId 不能为空");
        }
        Optional<TaskJob> found = jobStore.find(taskId);
        if (found.isEmpty()) {
            // ⚠️ 别把这句写成「任务已完成」—— 影刀会以为成功了。如实说「查不到」。
            return ApiResponse.error(ErrorCode.BUSINESS_FAIL, null,
                    "任务不存在或已过期（taskId=" + taskId + "）；已完成的任务只保留 24 小时。"
                            + "若你刚提交过，请核对 taskId；否则请重新提交任务");
        }

        TaskJob job = found.get();
        if (job.isRunning()) {
            return ApiResponse.running(job.requestId(), taskId, elapsedMillis(job));
        }
        return replay(job);
    }

    /** 把当初落盘的完整应答原样还原。 */
    private ApiResponse replay(TaskJob job) {
        if (job.responseJson() == null || job.responseJson().isBlank()) {
            log.error("异步任务已结束但没有存应答 taskId={} status={}", job.taskId(), job.status());
            return ApiResponse.error(ErrorCode.INTERNAL_ERROR, job.requestId(),
                    "任务已结束但没有存应答内容（taskId=" + job.taskId()
                            + "），请人工检查 " + jobStore.filePath());
        }
        try {
            return objectMapper.readValue(job.responseJson(), ApiResponse.class);
        } catch (IOException e) {
            log.error("异步任务的应答 JSON 解析失败 taskId={}", job.taskId(), e);
            return ApiResponse.error(ErrorCode.INTERNAL_ERROR, job.requestId(),
                    "任务应答无法解析（taskId=" + job.taskId() + "）: " + e.getMessage());
        }
    }

    /** 从受理到现在过了多久（毫秒）；时间戳解析不了就返回 0（只是给影刀看的提示）。 */
    private static long elapsedMillis(TaskJob job) {
        try {
            return Math.max(0L, Duration.between(
                    OffsetDateTime.parse(job.createdAt()), OffsetDateTime.now()).toMillis());
        } catch (DateTimeParseException e) {
            return 0L;
        }
    }
}
