package com.aolandai.dingtou.service.task.handler;

import com.aolandai.dingtou.config.SourceProperties;
import com.aolandai.dingtou.source.AccountDictReader;
import com.aolandai.dingtou.source.DeliverySheetReader;
import com.aolandai.dingtou.source.ProductDictReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@link PingHandler} 单测。
 *
 * <p>⚠️ 这个测试的存在理由：{@code ping} 是<b>部署自检</b>用的接口，
 * 而它曾经<b>漏报后加的两张表</b>（定向登记表、达人巡查配置表）——
 * 于是「表路径没配 / 共享盘不通」在自检里完全看不见。这里把「必须报全 5 张表」钉住。</p>
 */
class PingHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private PingHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        SourceProperties props = new SourceProperties();
        props.setDeliverySheet(existingFile("delivery.xlsx"));
        props.setOrderSheet(existingFile("order.xlsx"));
        props.setAccountDict(existingFile("account.xlsx"));
        props.setProductDict(existingFile("product.xlsx"));
        props.setKolPatrolConfig(existingFile("kol-patrol.xlsx"));
        handler = new PingHandler(props,
                mock(DeliverySheetReader.class),
                mock(AccountDictReader.class),
                mock(ProductDictReader.class));
    }

    @Test
    @DisplayName("taskCode = ping")
    void codeIsPing() {
        assertEquals("ping", handler.code());
    }

    @Test
    @DisplayName("★ 必须报全【5 张表】—— 漏报会让部署自检看不见「表缺失/共享盘不通」")
    @SuppressWarnings("unchecked")
    void reportsAllSixTables() throws Exception {
        Map<String, Object> data = (Map<String, Object>) handler.handle(null);

        assertEquals("up", data.get("status"));
        assertTrue(data.containsKey("time"));

        Map<String, Object> tables = (Map<String, Object>) data.get("tables");
        assertEquals(5, tables.size(), "必须报全 5 张表，实际: " + tables.keySet());
        assertTrue(tables.keySet().containsAll(java.util.List.of(
                        "deliverySheet", "orderSheet", "accountDict", "productDict",
                        "kolPatrolConfig")),
                "表 key 少了: " + tables.keySet());
    }

    @Test
    @DisplayName("文件存在 → usable=true，并带上路径")
    @SuppressWarnings("unchecked")
    void existingFileIsUsable() throws Exception {
        Map<String, Object> data = (Map<String, Object>) handler.handle(null);
        Map<String, Object> tables = (Map<String, Object>) data.get("tables");

        Map<String, Object> delivery = (Map<String, Object>) tables.get("deliverySheet");
        assertEquals(Boolean.TRUE, delivery.get("usable"));
        assertTrue(String.valueOf(delivery.get("path")).endsWith("delivery.xlsx"));
    }

    @Test
    @DisplayName("文件缺失 → usable=false（不抛异常，只如实报告）")
    @SuppressWarnings("unchecked")
    void missingFileIsNotUsable() throws Exception {
        SourceProperties props = new SourceProperties();
        props.setDeliverySheet(tempDir.resolve("不存在.xlsx"));
        PingHandler missing = new PingHandler(props,
                mock(DeliverySheetReader.class),
                mock(AccountDictReader.class),
                mock(ProductDictReader.class));

        Map<String, Object> data = (Map<String, Object>) missing.handle(null);
        Map<String, Object> tables = (Map<String, Object>) data.get("tables");
        Map<String, Object> delivery = (Map<String, Object>) tables.get("deliverySheet");

        assertEquals(Boolean.FALSE, delivery.get("usable"), "缺失必须如实报 false");
    }

    @Test
    @DisplayName("默认不 deep → 不返回 counts（毫秒级，不开 POI）")
    void lightModeHasNoCounts() throws Exception {
        Map<?, ?> data = (Map<?, ?>) handler.handle(null);
        assertFalse(data.containsKey("counts"));
    }

    @Test
    @DisplayName("deep=true → 带上 counts（打开工作簿数行数）")
    void deepModeHasCounts() throws Exception {
        JsonNode params = MAPPER.readTree("{\"deep\":true}");
        Map<?, ?> data = (Map<?, ?>) handler.handle(params);
        assertTrue(data.containsKey("counts"), "deep=true 应返回 counts");
    }

    private Path existingFile(String name) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, "x");
        return file;
    }
}
