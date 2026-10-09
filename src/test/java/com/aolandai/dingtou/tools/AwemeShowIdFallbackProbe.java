package com.aolandai.dingtou.tools;

import com.aolandai.dingtou.DingtouApplication;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 手工探针：验证<b>「抖音号兜底」整条链路</b>的覆盖率。
 *
 * <p>链路：</p>
 * <ol>
 *   <li>对每个【账户:计划】调 {@code uni_promotion/ad/detail/} → 拿
 *       {@code aweme_infos[0]} 的 {@code unique_id}（抖音号）、{@code aweme_uid}、{@code aweme_name}；</li>
 *   <li>凡是 {@code unique_id} 为空的，把它的 {@code aweme_uid} 收集起来；</li>
 *   <li><b>按账户分组</b>，<b>一次</b>调 {@code aweme_auth_list/get/}（{@code filtering.aweme_id} 是数组）
 *       → 拿 {@code aweme_show_id}（抖音号）。</li>
 * </ol>
 *
 * <p>输入文件每行 {@code advertiserId:adId}（{@code #} 开头为注释）。</p>
 *
 * <pre>
 * java -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 \
 *   -cp "target/test-classes;target/classes;&lt;依赖&gt;" \
 *   com.aolandai.dingtou.tools.AwemeShowIdFallbackProbe [refs文件] [--delayMs=800]
 * </pre>
 *
 * <p>⚠️ 只读接口，<b>不花钱</b>。</p>
 */
public final class AwemeShowIdFallbackProbe {

    private static final Logger log = LoggerFactory.getLogger(AwemeShowIdFallbackProbe.class);

    private static final String PROBE_AUTH_TOKEN = "probe-only-not-a-real-token";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AwemeShowIdFallbackProbe() {
    }

    public static void main(String[] args) throws Exception {
        Path refsFile = Path.of(args != null && args.length > 0 && !args[0].startsWith("--")
                ? args[0] : "target/empty-uid-plans.txt");
        long delayMs = parseDelay(args);

        List<String> refs = Files.readAllLines(refsFile, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
        log.info("========== AwemeShowIdFallbackProbe 开始 ==========");
        log.info("待查 {} 个【账户:计划】（来自 {}）", refs.size(), refsFile);

        String baseUrl;
        try (var context = new SpringApplicationBuilder(DingtouApplication.class)
                .web(WebApplicationType.NONE)
                .run("--dingtou.auth-token=" + PROBE_AUTH_TOKEN, "--server.port=0",
                        "--logging.file.name=target/probe-logs/probe.log")) {
            baseUrl = context.getEnvironment()
                    .getProperty("dingtou.qianchuan.api-base-url", "https://api.oceanengine.com");
        }
        String token = ProbeTokens.qianchuan();
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

        // 1) 逐个计划查 detail，收集 UID
        List<Row> rows = new ArrayList<>();
        Map<Long, Set<Long>> uidsByAccount = new LinkedHashMap<>();
        for (String ref : refs) {
            String[] parts = ref.split(":");
            long advertiserId = Long.parseLong(parts[0]);
            long adId = Long.parseLong(parts[1]);
            Row row = fetchDetail(http, baseUrl, token, advertiserId, adId);
            rows.add(row);
            if (row.uniqueId().isEmpty() && row.awemeUid() != null) {
                uidsByAccount.computeIfAbsent(advertiserId, k -> new LinkedHashSet<>())
                        .add(row.awemeUid());
            }
            sleep(delayMs);
        }

        // 2) 按账户批量反查
        Map<String, String> showIdByUid = new LinkedHashMap<>();
        for (Map.Entry<Long, Set<Long>> entry : uidsByAccount.entrySet()) {
            long advertiserId = entry.getKey();
            List<Long> uids = new ArrayList<>(entry.getValue());
            log.info("账户 {}：批量反查 {} 个 UID", advertiserId, uids.size());
            Map<String, String> found = batchReverse(http, baseUrl, token, advertiserId, uids);
            showIdByUid.putAll(found);
            sleep(delayMs);
        }

        // 3) 汇总
        report(rows, showIdByUid);
        log.info("========== AwemeShowIdFallbackProbe 结束 ==========");
    }

    private record Row(long advertiserId, long adId, String uniqueId, Long awemeUid,
                       String awemeName) {
    }

    private static Row fetchDetail(HttpClient http, String baseUrl, String token,
                                   long advertiserId, long adId) throws Exception {
        String url = baseUrl + "/open_api/v1.0/qianchuan/uni_promotion/ad/detail/"
                + "?advertiser_id=" + advertiserId + "&ad_id=" + adId;
        JsonNode root = get(http, url, token);
        JsonNode infos = root.path("data").path("aweme_infos");
        if (!infos.isArray() || infos.isEmpty()) {
            return new Row(advertiserId, adId, "", null, "");
        }
        JsonNode first = infos.get(0);
        String uniqueId = first.path("unique_id").asText("");
        Long uid = first.path("aweme_uid").isNumber() ? first.path("aweme_uid").asLong() : null;
        String name = first.path("aweme_name").asText("");
        return new Row(advertiserId, adId, uniqueId, uid, name);
    }

    /** 一次请求查多个 UID。 */
    private static Map<String, String> batchReverse(HttpClient http, String baseUrl, String token,
                                                    long advertiserId, List<Long> uids)
            throws Exception {
        Map<String, Object> filtering = new LinkedHashMap<>();
        filtering.put("aweme_id", uids);
        String url = baseUrl + "/open_api/v1.0/qianchuan/aweme_auth_list/get/"
                + "?advertiser_id=" + advertiserId + "&page=1&page_size=100"
                + "&filtering=" + URLEncoder.encode(MAPPER.writeValueAsString(filtering),
                StandardCharsets.UTF_8);
        JsonNode root = get(http, url, token);
        Map<String, String> out = new LinkedHashMap<>();
        JsonNode infos = root.path("data").path("authorization_infos");
        if (infos.isArray()) {
            for (JsonNode item : infos) {
                long uid = item.path("aweme_id").asLong();
                String showId = item.path("aweme_show_id").asText("");
                if (!showId.isEmpty()) {
                    out.put(String.valueOf(uid), showId);
                }
            }
        }
        log.info("  账户 {}：请求 {} 个 UID，反查到 {} 个", advertiserId, uids.size(), out.size());
        return out;
    }

    private static JsonNode get(HttpClient http, String url, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Access-Token", token)
                .header("Content-Type", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = http.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return MAPPER.readTree(response.body());
    }

    private static void report(List<Row> rows, Map<String, String> showIdByUid) {
        log.info("========== 覆盖率汇总 ==========");
        int empty = 0;
        int recovered = 0;
        System.out.println("账户:计划 | 抖音名 | unique_id | UID | 反查到的抖音号 | 结果");
        for (Row row : rows) {
            String ref = row.advertiserId() + ":" + row.adId();
            if (!row.uniqueId().isEmpty()) {
                System.out.printf("%s | %s | %s | %s | - | 原本就有%n",
                        ref, row.awemeName(), row.uniqueId(), row.awemeUid());
                continue;
            }
            empty++;
            String recoveredId = row.awemeUid() == null ? null
                    : showIdByUid.get(String.valueOf(row.awemeUid()));
            if (recoveredId != null) {
                recovered++;
                System.out.printf("%s | %s | (空) | %s | %s | ✅ 反查成功%n",
                        ref, row.awemeName(), row.awemeUid(), recoveredId);
            } else {
                System.out.printf("%s | %s | (空) | %s | - | ❌ 反查不到%n",
                        ref, row.awemeName(), row.awemeUid());
            }
        }
        log.info("unique_id 为空的：{} 个；其中反查成功：{} 个", empty, recovered);
        if (empty > 0) {
            log.info("兜底覆盖率 = {}/{} = {}%", recovered, empty,
                    Math.round(recovered * 100.0 / empty));
        }
    }

    private static long parseDelay(String[] args) {
        if (args != null) {
            for (String a : args) {
                if (a.startsWith("--delayMs=")) {
                    return Long.parseLong(a.substring("--delayMs=".length()));
                }
            }
        }
        return 800L;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
