package com.aolandai.dingtou.store;

import com.aolandai.dingtou.config.StoreProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link TaskJobStore} 单测：真落盘到临时目录，<b>不碰工程里的 data/</b>。 */
class TaskJobStoreTest {

    private static StoreProperties props(Path dir) {
        StoreProperties p = new StoreProperties();
        p.setDataDir(dir);
        return p;
    }

    @Test
    @DisplayName("★ 建 → 完成 → 查得到，且应答被落盘（影刀重启后还能取回）")
    void createThenFinishIsPersisted(@TempDir Path dir) throws Exception {
        TaskJobStore store = new TaskJobStore(props(dir));
        store.create("t-1", "r-1", "planBuild", "已受理，后台执行中");
        store.finish("t-1", "{\"code\":0,\"data\":{\"adId\":123}}");

        Optional<TaskJob> found = store.find("t-1");
        assertTrue(found.isPresent());
        assertEquals(TaskJobStatus.DONE, found.get().status());
        assertEquals("{\"code\":0,\"data\":{\"adId\":123}}", found.get().responseJson());
        assertTrue(found.get().isFinished());
        assertFalse(found.get().isRunning());

        // ⚠️ 重启后必须还能查到 —— 否则影刀会一直轮询一个「不存在的任务」，
        //    而任务其实已经执行完（可能已花钱）→ 人工再跑一次 → 重复建计划
        TaskJobStore reopened = new TaskJobStore(props(dir));
        assertTrue(reopened.find("t-1").isPresent(), "重启后应当还能查到");
        assertEquals(TaskJobStatus.DONE, reopened.find("t-1").get().status());
    }

    @Test
    @DisplayName("刚登记时是 RUNNING，且没有应答内容")
    void freshJobIsRunning(@TempDir Path dir) throws Exception {
        TaskJobStore store = new TaskJobStore(props(dir));
        store.create("t-2", "r-2", "kolPlanCostQuery", "已受理");

        TaskJob job = store.find("t-2").orElseThrow();
        assertEquals(TaskJobStatus.RUNNING, job.status());
        assertTrue(job.isRunning());
        assertFalse(job.isFinished());
        assertTrue(job.responseJson() == null || job.responseJson().isBlank());
    }

    @Test
    @DisplayName("★ 失败也要落盘（影刀得知道「跑挂了」而不是一直等）")
    void failIsRecorded(@TempDir Path dir) throws Exception {
        TaskJobStore store = new TaskJobStore(props(dir));
        store.create("t-3", "r-3", "planBuild", "已受理");
        store.fail("t-3", "{\"code\":5,\"message\":\"boom\"}", "SocketTimeoutException: timeout");

        TaskJob job = store.find("t-3").orElseThrow();
        assertEquals(TaskJobStatus.FAILED, job.status());
        assertTrue(job.isFinished());
        assertTrue(job.message().contains("timeout"), job.message());
    }

    @Test
    @DisplayName("更新不存在的任务 → 抛 IOException（不是静默创建一条假记录）")
    void finishingUnknownJobThrows(@TempDir Path dir) throws Exception {
        TaskJobStore store = new TaskJobStore(props(dir));
        assertThrows(java.io.IOException.class, () -> store.finish("nope", "{}"));
    }

    @Test
    @DisplayName("★ 清理过期任务：只清已结束的，RUNNING 的一条都不能删")
    void purgeExpiredKeepsRunningJobs(@TempDir Path dir) throws Exception {
        TaskJobStore store = new TaskJobStore(props(dir));
        store.create("old-done", "r", "ping", "已受理");
        store.finish("old-done", "{\"code\":0}");
        store.create("still-running", "r", "planBuild", "已受理");

        // 手工把「已完成」那条的时间戳改到 25 小时前
        store.purgeExpired(java.time.OffsetDateTime.now().plusHours(1));

        assertTrue(store.find("old-done").isEmpty(), "过期的已完成任务应当被清掉");
        assertTrue(store.find("still-running").isPresent(),
                "⚠️ RUNNING 的任务绝不能删 —— 删了影刀会轮询到「不存在」，而它其实还在跑");
    }

    @Test
    @DisplayName("没到保留期的已完成任务不会被清")
    void freshFinishedJobSurvives(@TempDir Path dir) throws Exception {
        TaskJobStore store = new TaskJobStore(props(dir));
        store.create("fresh", "r", "ping", "已受理");
        store.finish("fresh", "{\"code\":0}");

        assertEquals(0, store.purgeExpired());
        assertTrue(store.find("fresh").isPresent());
    }

    @Test
    @DisplayName("taskId 为空 → 查不到（不抛异常）")
    void blankTaskIdReturnsEmpty(@TempDir Path dir) throws Exception {
        TaskJobStore store = new TaskJobStore(props(dir));
        assertTrue(store.find("").isEmpty());
        assertTrue(store.find(null).isEmpty());
    }

    @Test
    @DisplayName("文件内容坏掉 → 拒绝启动（不能静默丢任务）")
    void corruptedFileRefusesToStart(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("task-jobs.jsonl");
        Files.writeString(file, "这不是 JSON\n");
        assertThrows(java.io.IOException.class, () -> new TaskJobStore(props(dir)));
    }
}
