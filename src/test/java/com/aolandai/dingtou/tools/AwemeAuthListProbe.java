package com.aolandai.dingtou.tools;

import com.aolandai.dingtou.DingtouApplication;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 手工探针：调【抖音号授权列表】接口 {@code GET /v1.0/qianchuan/aweme_auth_list/get/}
 * （SDK：{@code QianchuanAwemeAuthListGetV10Api}），把<b>原始应答</b>整份打出来。
 *
 * <h2>为什么试它</h2>
 * <p>上一个接口 {@code uni_aweme/authorized/get/} 的过滤条件只有
 * {@code search_key_words}（只能按<b>抖音号</b>搜），所以「用数值 UID 反查抖音号」
 * 只能<b>全量扫 5000+ 个达人、约 4.5 分钟</b>。</p>
 *
 * <p>而本接口的 {@code filtering} 里有 <b>{@code aweme_id}</b>（数值 UID）——
 * 如果能按它过滤，就<b>一次请求搞定</b>，不用全量扫。</p>
 *
 * <pre>
 * java -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 \
 *   -cp "target/test-classes;target/classes;&lt;依赖&gt;" \
 *   com.aolandai.dingtou.tools.AwemeAuthListProbe [advertiserId] [awemeId] [awemeShowId]
 * </pre>
 *
 * <p>三个参数都可省略：默认用实测组合（官方【母婴商品】+ 那个 unique_id 为空的达人的 UID）。</p>
 *
 * <p>⚠️ 只读接口，<b>不花钱</b>。</p>
 */
public final class AwemeAuthListProbe {

    private static final Logger log = LoggerFactory.getLogger(AwemeAuthListProbe.class);

    private static final String PROBE_AUTH_TOKEN = "probe-only-not-a-real-token";

    private static final String DEFAULT_ADVERTISER_ID = "1823379540880396";
    private static final String DEFAULT_AWEME_ID = "65628672972";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AwemeAuthListProbe() {
    }

    public static void main(String[] args) throws Exception {
        String advertiserId = arg(args, 0, DEFAULT_ADVERTISER_ID);
        String awemeId = arg(args, 1, DEFAULT_AWEME_ID);
        String awemeShowId = arg(args, 2, "");

        log.info("========== AwemeAuthListProbe 开始 ==========");
        log.info("账户 = {}，aweme_id(UID) = {}，aweme_show_id(抖音号) = {}",
                advertiserId, awemeId, awemeShowId.isEmpty() ? "（不传）" : awemeShowId);

        String baseUrl;
        try (var context = new SpringApplicationBuilder(DingtouApplication.class)
                .web(WebApplicationType.NONE)
                .run("--dingtou.auth-token=" + PROBE_AUTH_TOKEN, "--server.port=0",
                        "--logging.file.name=target/probe-logs/probe.log")) {
            baseUrl = context.getEnvironment()
                    .getProperty("dingtou.qianchuan.api-base-url", "https://api.oceanengine.com");
        }

        String token = ProbeTokens.qianchuan();
        log.info("已取得 Access-Token（长度 {}）", token.length());

        String url = buildUrl(baseUrl, advertiserId, awemeId, awemeShowId);
        log.info("请求 URL = {}", url);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
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
        log.info("========== AwemeAuthListProbe 结束 ==========");
    }

    private static String buildUrl(String baseUrl, String advertiserId, String awemeId,
                                   String awemeShowId) {
        Map<String, Object> filtering = new LinkedHashMap<>();
        // ⚠️ 实测（code=40000 "filtering: aweme_id: Field must be set to array"）：
        //    这些过滤字段都必须是【数组】，不能传标量
        if (!awemeId.isEmpty()) {
            filtering.put("aweme_id", List.of(Long.parseLong(awemeId)));
        }
        if (!awemeShowId.isEmpty()) {
            filtering.put("aweme_show_id", List.of(awemeShowId));
        }

        StringBuilder sb = new StringBuilder(baseUrl)
                .append("/open_api/v1.0/qianchuan/aweme_auth_list/get/")
                .append("?advertiser_id=").append(advertiserId)
                .append("&page=1&page_size=100");
        if (!filtering.isEmpty()) {
            String json = toJson(filtering);
            sb.append("&filtering=").append(URLEncoder.encode(json, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static String toJson(Map<String, Object> map) {
        try {
            return MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

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

    private static void printSdkSchema() {
        Class<?> clazz;
        try {
            clazz = Class.forName("com.bytedance.ads.model"
                    + ".QianchuanAwemeAuthListGetV10ResponseDataAuthorizationInfosInner");
        } catch (ClassNotFoundException e) {
            log.warn("找不到 SDK 应答条目类，跳过字段清单");
            return;
        }
        log.info("========== SDK 应答条目字段（{}）==========", clazz.getSimpleName());
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
