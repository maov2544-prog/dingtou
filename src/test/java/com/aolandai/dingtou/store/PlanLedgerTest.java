package com.aolandai.dingtou.store;

import com.aolandai.dingtou.config.StoreProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 计划台账单测：登记 / 查询 / 覆盖 / 跨实例恢复 / 坏文件拒绝启动。 */
class PlanLedgerTest {

    private static StoreProperties props(Path dir) {
        StoreProperties props = new StoreProperties();
        props.setDataDir(dir);
        return props;
    }

    @Test
    @DisplayName("登记后能按【抖音号 + 商品名】查到计划 ID")
    void registerThenFind(@TempDir Path dir) throws IOException {
        PlanLedger ledger = new PlanLedger(props(dir));
        ledger.register("602085838", "益生元面霜", "3625005696866738289",
                1771298760893511L, "1799999999999999", "602085838-益生元面霜-9.30");

        PlanEntry entry = ledger.find("602085838", "益生元面霜").orElseThrow();
        assertEquals("1799999999999999", entry.adId());
        assertEquals(1771298760893511L, entry.advertiserId());
        assertEquals("602085838-益生元面霜-9.30", entry.planName());
        assertEquals(1, ledger.entryCount());
    }

    @Test
    @DisplayName("同组合重复登记是覆盖，不产生重复行；createdAt 保留首次值")
    void registerIsUpsert(@TempDir Path dir) throws IOException {
        PlanLedger ledger = new PlanLedger(props(dir));
        PlanEntry first = ledger.register("602085838", "益生元面霜", "111",
                1000L, "ad-1", "旧名");
        PlanEntry second = ledger.register("602085838", "益生元面霜", "111",
                2000L, "ad-2", "新名");

        assertEquals(1, ledger.entryCount(), "同组合不该出现两行");
        assertEquals("ad-2", second.adId());
        assertEquals(2000L, second.advertiserId());
        assertEquals("新名", second.planName());
        assertEquals(first.createdAt(), second.createdAt(), "首次登记时间应保留");
    }

    @Test
    @DisplayName("查不到返回空；空参数也不抛异常")
    void findMissingReturnsEmpty(@TempDir Path dir) throws IOException {
        PlanLedger ledger = new PlanLedger(props(dir));
        assertTrue(ledger.find("不存在", "不存在").isEmpty());
        assertTrue(ledger.find(null, "益生元面霜").isEmpty());
        assertTrue(ledger.find("602085838", "  ").isEmpty());
    }

    @Test
    @DisplayName("⚠️ adId 为空时拒绝登记 —— 台账的核心就是计划 ID")
    void rejectsBlankAdId(@TempDir Path dir) throws IOException {
        PlanLedger ledger = new PlanLedger(props(dir));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.register("602085838", "益生元面霜", "111", 1L, null, "n"));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.register("602085838", "益生元面霜", "111", 1L, "  ", "n"));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.register("  ", "益生元面霜", "111", 1L, "ad", "n"));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.register("602085838", null, "111", 1L, "ad", "n"));
    }

    @Test
    @DisplayName("移除：命中返回 true，未命中返回 false")
    void removeWorks(@TempDir Path dir) throws IOException {
        PlanLedger ledger = new PlanLedger(props(dir));
        ledger.register("602085838", "益生元面霜", "111", 1L, "ad-1", "n");

        assertTrue(ledger.remove("602085838", "益生元面霜"));
        assertEquals(0, ledger.entryCount());
        assertFalse(ledger.remove("602085838", "益生元面霜"));
        assertFalse(ledger.remove(null, null));
    }

    @Test
    @DisplayName("台账跨实例恢复（模拟服务重启）")
    void ledgerSurvivesRestart(@TempDir Path dir) throws IOException {
        new PlanLedger(props(dir)).register("602085838", "益生元面霜", "111",
                1000L, "ad-1", "n");

        PlanLedger reloaded = new PlanLedger(props(dir));
        assertEquals(1, reloaded.entryCount());
        assertEquals("ad-1", reloaded.find("602085838", "益生元面霜").orElseThrow().adId());
    }

    @Test
    @DisplayName("⚠️ 台账文件损坏 → 拒绝启动，不静默跳过坏行")
    void corruptFileRefusesToStart(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("plan-ledger.jsonl"), "{坏行}\n", StandardCharsets.UTF_8);
        IOException e = assertThrows(IOException.class, () -> new PlanLedger(props(dir)));
        assertTrue(e.getMessage().contains("拒绝启动"), e.getMessage());
        assertTrue(e.getMessage().contains("坏行"), "报错要带出错内容: " + e.getMessage());
    }

    @Test
    @DisplayName("落盘格式里不该出现派生 getter（hasAdId）—— 否则下次启动读不回来")
    void persistedFormatHasNoDerivedGetters(@TempDir Path dir) throws IOException {
        new PlanLedger(props(dir)).register("602085838", "益生元面霜", "111",
                1000L, "ad-1", "n");

        String content = Files.readString(dir.resolve("plan-ledger.jsonl"), StandardCharsets.UTF_8);
        assertTrue(content.contains("\"adId\""), "adId 是真实字段，必须落盘: " + content);
        assertFalse(content.contains("hasAdId"), "hasAdId 是派生 getter，不该落盘: " + content);
        // 关键：真的能重新加载
        assertEquals(1, new PlanLedger(props(dir)).entryCount());
    }

    @Test
    @DisplayName("PlanKey 拒绝空值并自动 trim")
    void planKeyValidates() {
        assertThrows(IllegalArgumentException.class, () -> new PlanKey(null, "商品"));
        assertThrows(IllegalArgumentException.class, () -> new PlanKey("  ", "商品"));
        assertThrows(IllegalArgumentException.class, () -> new PlanKey("602085838", null));
        assertEquals("a", new PlanKey(" a ", " b ").awemeShowId());
        assertEquals("b", new PlanKey(" a ", " b ").productName());
    }
}
