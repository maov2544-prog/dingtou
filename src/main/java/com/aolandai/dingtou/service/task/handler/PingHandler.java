package com.aolandai.dingtou.service.task.handler;

import com.aolandai.dingtou.config.SourceProperties;
import com.aolandai.dingtou.service.task.TaskHandler;
import com.aolandai.dingtou.source.AccountDictReader;
import com.aolandai.dingtou.source.DeliverySheetReader;
import com.aolandai.dingtou.source.ExcelReader;
import com.aolandai.dingtou.source.ProductDictReader;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code ping} —— 探活。
 *
 * <p>影刀在正式调用前先打这个，确认服务活着、<b>而且 4 张业务表真的能读</b>。</p>
 *
 * <p>两档深度，默认只做便宜的检查：</p>
 * <ul>
 *   <li>默认（light）：只检查 <b>6 个文件</b>是否存在 / 可读，<b>不开 POI</b>，毫秒级；</li>
 *   <li>{@code {"params":{"deep":true}}}：额外打开工作簿，报出 sheet 名与实际行数 ——
 *       慢一点（定投素材表 4992 行），但能确认「表内容真的对得上」。</li>
 * </ul>
 */
@Component
public class PingHandler implements TaskHandler {

    private static final Logger log = LoggerFactory.getLogger(PingHandler.class);

    private final SourceProperties sourceProperties;
    private final DeliverySheetReader deliverySheetReader;
    private final AccountDictReader accountDictReader;
    private final ProductDictReader productDictReader;

    public PingHandler(SourceProperties sourceProperties,
                       DeliverySheetReader deliverySheetReader,
                       AccountDictReader accountDictReader,
                       ProductDictReader productDictReader) {
        this.sourceProperties = sourceProperties;
        this.deliverySheetReader = deliverySheetReader;
        this.accountDictReader = accountDictReader;
        this.productDictReader = productDictReader;
    }

    @Override
    public String code() {
        return "ping";
    }

    @Override
    public String description() {
        return "探活：返回服务状态、时间与 5 张业务表的可读性";
    }

    @Override
    public Object handle(JsonNode params) {
        boolean deep = params != null && params.path("deep").asBoolean(false);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "up");
        data.put("time", OffsetDateTime.now().toString());

        Map<String, Object> tables = new LinkedHashMap<>();
        tables.put("deliverySheet", describeFile("定投素材表", sourceProperties.getDeliverySheet(), deep));
        tables.put("orderSheet", describeFile("订单总表", sourceProperties.getOrderSheet(), deep));
        tables.put("accountDict", describeFile("账户字典", sourceProperties.getAccountDict(), deep));
        tables.put("productDict", describeFile("商品映射表", sourceProperties.getProductDict(), deep));
        // ⚠️ 达人巡查配置表是后加的，曾经漏报 —— 而 ping 正是部署自检用的接口，
        //    漏报会让「表路径没配 / 共享盘不通」在自检里看不见
        tables.put("kolPatrolConfig",
                describeFile("达人巡查配置表", sourceProperties.getKolPatrolConfig(), deep));
        data.put("tables", tables);

        if (deep) {
            data.put("counts", readCounts());
        }
        return data;
    }

    private static Map<String, Object> describeFile(String label, Path path, boolean deep) {
        Map<String, Object> info = new LinkedHashMap<>();
        if (path == null) {
            info.put("usable", false);
            info.put("message", label + " 未配置路径");
            return info;
        }
        boolean exists = Files.exists(path);
        boolean readable = exists && Files.isReadable(path);
        info.put("path", path.toString());
        info.put("exists", exists);
        info.put("readable", readable);
        if (!exists) {
            info.put("usable", false);
            info.put("message", label + " 文件不存在");
            return info;
        }
        if (deep) {
            try {
                info.put("sheets", ExcelReader.sheetNames(path));
            } catch (IOException e) {
                info.put("usable", false);
                info.put("message", label + " 打不开: " + e.getMessage());
                return info;
            }
        }
        info.put("usable", true);
        return info;
    }

    /** 深检查才做：真读一遍，报出行数。读不动就如实回报，不抛异常 —— 探活本身不该失败。 */
    private Map<String, Object> readCounts() {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("deliveryRows", count("定投素材表", () -> deliverySheetReader.readAll().size()));
        counts.put("accounts", count("账户字典", () -> accountDictReader.readAll().size()));
        counts.put("products", count("商品映射表", () -> productDictReader.readAll().size()));
        return counts;
    }

    private Object count(String label, CountSupplier supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            log.warn("ping 深检查读取 {} 失败: {}", label, e.getMessage());
            return "读取失败: " + e.getMessage();
        }
    }

    @FunctionalInterface
    private interface CountSupplier {
        int get() throws IOException;

        default List<String> unused() {
            return List.of();
        }
    }
}
