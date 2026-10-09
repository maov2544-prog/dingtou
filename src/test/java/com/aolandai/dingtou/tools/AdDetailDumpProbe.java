package com.aolandai.dingtou.tools;

import com.aolandai.dingtou.DingtouApplication;
import com.aolandai.dingtou.client.QianchuanTokenClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 手工探针：把【乘方计划详情】接口的<b>原始应答</b>整份打出来。
 *
 * <p>接口：{@code GET /v1.0/qianchuan/uni_promotion/ad/detail/}
 * （SDK：{@code QianchuanUniPromotionAdDetailV10Api}）</p>
 *
 * <pre>
 * java -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 \
 *   -cp "target/test-classes;target/classes;&lt;依赖&gt;" \
 *   com.aolandai.dingtou.tools.AdDetailDumpProbe [advertiserId] [adId]
 * </pre>
 *
 * <p>不传参数时用内置组合（官方【母婴商品】+ 指定计划）。</p>
 *
 * <p>⚠️ <b>走原始 HTTP 而不是 SDK</b>：SDK 的对象会把「接口没返回的字段」和
 * 「返回了 null 的字段」混在一起，看不出接口<b>实际</b>给了什么。
 * 原始 JSON 才是可信的。</p>
 *
 * <p>⚠️ 只读接口，<b>不花钱</b>。</p>
 *
 * <p>⚠️ 手工探针，不进自动化测试（surefire 已排除 {@code **&#47;tools/**}）。</p>
 */
public final class AdDetailDumpProbe {

    private static final Logger log = LoggerFactory.getLogger(AdDetailDumpProbe.class);

    /** 探针专用假 auth-token（`dingtou.auth-token` 有占位值校验）。 */
    private static final String PROBE_AUTH_TOKEN = "probe-only-not-a-real-token";

    private static final String DEFAULT_ADVERTISER_ID = "1823379540880396";
    private static final String DEFAULT_AD_ID = "1876856364806187";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AdDetailDumpProbe() {
    }

    public static void main(String[] args) throws Exception {
        String advertiserId = arg(args, 0, DEFAULT_ADVERTISER_ID);
        String adId = arg(args, 1, DEFAULT_AD_ID);

        log.info("========== AdDetailDumpProbe 开始 ==========");
        log.info("账户 = {}，计划 = {}", advertiserId, adId);

        // 起一个最小 Spring 上下文只为读配置（api-base-url）
        String baseUrl;
        try (var context = new SpringApplicationBuilder(DingtouApplication.class)
                .web(WebApplicationType.NONE)
                .run("--dingtou.auth-token=" + PROBE_AUTH_TOKEN, "--server.port=0")) {
            baseUrl = context.getEnvironment()
                    .getProperty("dingtou.qianchuan.api-base-url", "https://api.oceanengine.com");
        }
        log.info("接口基址 = {}", baseUrl);

        String token = QianchuanTokenClient.getAccessToken();
        log.info("已取得 Access-Token（长度 {}）", token.length());

        String url = baseUrl + "/open_api/v1.0/qianchuan/uni_promotion/ad/detail/"
                + "?advertiser_id=" + advertiserId + "&ad_id=" + adId;
        log.info("请求 URL = {}", url);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Access-Token", token)
                .header("Content-Type", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        log.info("HTTP 状态 = {}", response.statusCode());

        String body = response.body();
        log.info("========== 原始应答（pretty）==========");
        try {
            JsonNode root = MAPPER.readTree(body);
            System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
            printFieldInventory(root);
        } catch (Exception e) {
            log.warn("应答不是合法 JSON，原样输出：{}", e.getMessage());
            System.out.println(body);
        }

        printSdkSchema();
        log.info("========== AdDetailDumpProbe 结束 ==========");
    }

    /** 把应答里出现过的所有字段路径列出来（便于一眼看全「接口到底给了什么」）。 */
    private static void printFieldInventory(JsonNode root) {
        List<String> paths = new ArrayList<>();
        collect(root, "", paths);
        log.info("========== 应答字段清单（共 {} 条路径）==========", paths.size());
        for (String path : paths) {
            System.out.println("  " + path);
        }
    }

    private static void collect(JsonNode node, String prefix, List<String> out) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
                JsonNode value = entry.getValue();
                if (value == null || value.isNull()) {
                    out.add(path + " = null");
                } else if (value.isArray()) {
                    out.add(path + " = [数组，长度 " + value.size() + "]");
                    if (value.size() > 0) {
                        collect(value.get(0), path + "[0]", out);
                    }
                } else if (value.isObject()) {
                    out.add(path + " = {对象}");
                    collect(value, path, out);
                } else {
                    out.add(path + " = " + value.asText());
                }
            });
        }
    }

    /** 顺带把 SDK 里该响应类的字段名全列一遍（含接口没返回的那些）。 */
    private static void printSdkSchema() {
        Class<?> clazz;
        try {
            clazz = Class.forName("com.bytedance.ads.model"
                    + ".QianchuanUniPromotionAdDetailV10Response");
        } catch (ClassNotFoundException e) {
            log.warn("找不到 SDK 响应类，跳过字段清单");
            return;
        }
        log.info("========== SDK 响应类字段（{}）==========", clazz.getSimpleName());
        for (Field f : clazz.getDeclaredFields()) {
            if (f.getName().startsWith("SERIALIZED_NAME_")) {
                System.out.println("  " + f.getName().replace("SERIALIZED_NAME_", ""));
            }
        }
    }

    private static String arg(String[] args, int index, String fallback) {
        if (args == null || args.length <= index || args[index] == null || args[index].isBlank()) {
            return fallback;
        }
        return args[index].trim();
    }
}
