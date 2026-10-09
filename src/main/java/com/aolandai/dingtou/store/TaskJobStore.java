package com.aolandai.dingtou.store;

import com.aolandai.dingtou.config.StoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 异步任务存储：{@code taskId → TaskJob}，供 {@code GET /api/v1/task/{taskId}} 轮询。
 *
 * <p>复用 {@link JsonlStore} 的原子落盘能力（临时文件 → {@code force(true)} → {@code ATOMIC_MOVE}），
 * 所以进程重启后仍能回答「上次那个任务跑完没有」—— 这对「影刀提交后自己崩了、重启再来问」是必要的。</p>
 *
 * <h2>⚠️ 为什么必须落盘，不能只放内存</h2>
 * <p>内存方案在服务重启后就查不到任务了，影刀会一直轮询一个「不存在的任务」，
 * 最终超时放弃 —— 而那个任务<b>其实已经执行完了</b>（可能已经花了钱）。
 * 结果就是影刀以为失败、人工再跑一次 → <b>重复建计划</b>。</p>
 */
@Component
public class TaskJobStore extends JsonlStore<TaskJob> {

    private static final Logger log = LoggerFactory.getLogger(TaskJobStore.class);

    /** 异步任务记录的保留时长；超过就在下次写入时清掉。 */
    private static final Duration RETENTION = Duration.ofHours(24);

    private final Map<String, TaskJob> byTaskId = new LinkedHashMap<>();

    public TaskJobStore(StoreProperties properties) throws IOException {
        super(properties.getTaskJobs(), TaskJob.class);
        // ⚠️ 必须在最后一行：基类构造器里调 reindex() 时子类字段还是 null（会 NPE）
        buildIndex();
    }

    @Override
    protected void reindex() {
        byTaskId.clear();
        for (TaskJob job : records()) {
            byTaskId.put(job.taskId(), job);
        }
    }

    /** 按 taskId 查。 */
    public synchronized Optional<TaskJob> find(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byTaskId.get(taskId));
    }

    /** 登记一个「正在执行」的异步任务。 */
    public TaskJob create(String taskId, String requestId, String taskCode, String message)
            throws IOException {
        String now = now();
        TaskJob job = new TaskJob(taskId, requestId, taskCode, TaskJobStatus.RUNNING,
                null, message, now, now);
        log.info("登记异步任务 taskId={} requestId={} code={}", taskId, requestId, taskCode);
        return save(job);
    }

    /** 标记完成，并落盘完整应答（影刀轮询时原样取回）。 */
    public TaskJob finish(String taskId, String responseJson) throws IOException {
        return transitionTo(taskId, TaskJobStatus.DONE, responseJson, "已完成");
    }

    /** 标记失败（服务内部异常 / 超时放弃）。 */
    public TaskJob fail(String taskId, String responseJson, String message) throws IOException {
        return transitionTo(taskId, TaskJobStatus.FAILED, responseJson, message);
    }

    /** 当前保留的任务数（含已完成的）。 */
    public synchronized int jobCount() {
        return size();
    }

    /**
     * 清理超过保留期（24h）的已完成任务。
     *
     * <p>只清 {@code DONE} / {@code FAILED} —— {@code RUNNING} 的<b>绝不能删</b>：
     * 那会让影刀轮询到一个「不存在的任务」，而任务其实还在跑。</p>
     *
     * @return 清掉的条数
     */
    public synchronized int purgeExpired() throws IOException {
        return purgeExpired(OffsetDateTime.now().minus(RETENTION));
    }

    /**
     * 按指定截止时间清理（包级可见，<b>只给单测用</b>）。
     *
     * <p>抽出来是因为「等 24 小时」在单测里没法验证；注入截止时间就能验证
     * 「过期的清掉、没过期的留下、RUNNING 的不动」这三条规则。</p>
     */
    synchronized int purgeExpired(OffsetDateTime deadline) throws IOException {
        int[] removed = {0};
        mutate(list -> {
            int before = list.size();
            list.removeIf(job -> job.isFinished() && isOlderThan(job.updatedAt(), deadline));
            removed[0] = before - list.size();
            return null;
        });
        if (removed[0] > 0) {
            log.info("清理过期异步任务 {} 条（保留 {} 小时）", removed[0], RETENTION.toHours());
        }
        return removed[0];
    }

    // ==================== 内部 ====================

    private TaskJob save(TaskJob job) throws IOException {
        return mutate(list -> {
            list.removeIf(existing -> existing.taskId().equals(job.taskId()));
            list.add(job);
            return job;
        });
    }

    private TaskJob transitionTo(String taskId, TaskJobStatus status, String responseJson,
                                 String message) throws IOException {
        TaskJob existing = find(taskId).orElseThrow(
                () -> new IOException("异步任务不存在，无法更新状态：taskId=" + taskId));
        TaskJob updated = existing.transition(status, responseJson, message, now());
        log.info("异步任务 {} → {}（taskId={}）", existing.taskCode(), status.label(), taskId);
        return save(updated);
    }

    /** 时间戳解析失败时<b>当作「旧」</b>清掉 —— 留着一条读不懂的记录没有价值。 */
    private static boolean isOlderThan(String timestamp, OffsetDateTime deadline) {
        if (timestamp == null || timestamp.isBlank()) {
            return true;
        }
        try {
            return OffsetDateTime.parse(timestamp).isBefore(deadline);
        } catch (DateTimeParseException e) {
            log.warn("异步任务的时间戳无法解析（{}），按过期处理", timestamp);
            return true;
        }
    }

    /** 与 {@code RequestLedger} 保持一致的时间戳格式（ISO-8601 带时区偏移）。 */
    private static String now() {
        return OffsetDateTime.now().toString();
    }
}
