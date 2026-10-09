package com.aolandai.dingtou.service.qianchuan;

import com.aolandai.dingtou.client.QianchuanClientFactory;
import com.aolandai.dingtou.dto.qianchuan.VideoProductResult;
import com.bytedance.ads.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>抖音分享链接 → 商品 ID</b>。
 *
 * <h2>来源</h2>
 * <p>算法移植自老板给的 {@code ProductIdFromVideo.java}（1060 行示例），
 * 但<b>重写成了 Spring Bean</b> —— 原版全是 {@code static} 可变状态，
 * 作者自己都注明「HTTP 服务下必须串行」→ <b>并发不安全</b>，不能直接搬。</p>
 *
 * <h2>链路（4 级优先级拿作者 uid）</h2>
 * <pre>
 * 分享口令
 *   ↓ ① v.douyin.com 短链，手动多跳跟随（≤10 跳）
 *   ↓ ② 每跳同时扫 URL 与正文：
 *        · {@code /video/(\d{10,25})} → 视频ID
 *        · {@code social_author_id} → 作者数值 uid（分享参数自带，不走风控）
 *   ↓ ③ 视频列表翻页（账户 + uid）→ 匹配视频ID → product_info.id
 *   ↓ ④ 兜底：品名反查
 * </pre>
 *
 * <h2>⚠️ 三个必须记住的点</h2>
 * <ul>
 *   <li><b>视频列表接口 {@code count} 最多 20</b>（写 50 报 {@code 40000}）；</li>
 *   <li>短链请求必须带<b>移动端 UA</b>，否则拿不到落地页；</li>
 *   <li>{@code social_author_id} 要<b>先 URL 解码再正则</b>（它在 URL 编码的 JSON 里）。</li>
 * </ul>
 */
@Component
public class PlanMaterialResolver {

    private static final Logger log = LoggerFactory.getLogger(PlanMaterialResolver.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MOBILE_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";

    /** 视频列表接口的硬上限（写大了报 40000）。 */
    private static final int VIDEO_PAGE_SIZE = 20;

    private static final int MAX_VIDEO_PAGES = 100;
    private static final int MAX_HOPS = 10;
    private static final int HTTP_TIMEOUT_MS = 20000;

    private static final Pattern SHORT_LINK =
            Pattern.compile("https?://v\\.douyin\\.com/[\\w\\-]+/?");

    private static final Pattern AUTHOR_ID =
            Pattern.compile("social_author_id[\"']?\\s*[:=]\\s*\"?(\\d{5,25})");

    /** 视频ID的 4 种出现形态（按命中概率排序）。 */
    private static final String[] ITEM_ID_PATTERNS = {
            "/video/(\\d{10,25})",
            "/share/video/(\\d{10,25})",
            "modal_id=(\\d{10,25})",
            "item_id=(\\d{10,25})"
    };

    /** 查「该账户 + 该抖音号」的视频列表（含商品信息）。 */
    @FunctionalInterface
    public interface VideoListFetcher {
        JsonNode fetch(long advertiserId, long awemeUid, long cursor, long count)
                throws ApiException;
    }

    /** 按品名反查商品（兜底）。 */
    @FunctionalInterface
    public interface ProductSearchFetcher {
        JsonNode fetch(long advertiserId, long awemeUid, String productName) throws ApiException;
    }

    /**
     * 从分享文本里抠抖音名：{@code 看看【尚尚孕期（努力版）的作品】} → {@code 尚尚孕期（努力版）}。
     *
     * <p>⚠️ 必须用<b>非贪婪</b> {@code (.+?)} + 把「的作品/作品」整体作为后缀 ——
     * 写成 {@code ([^】]+)的?作品} 会把「的」吃进昵称（实测踩过，昵称变成「xxx的」）。</p>
     */
    private static final Pattern NICKNAME_IN_SHARE =
            Pattern.compile("【(.+?)(?:的作品|作品)】");

    private final QianchuanClientFactory clientFactory;
    private final AwemeShowIdResolver awemeShowIdResolver;

    public PlanMaterialResolver(QianchuanClientFactory clientFactory,
                                AwemeShowIdResolver awemeShowIdResolver) {
        this.clientFactory = clientFactory;
        this.awemeShowIdResolver = awemeShowIdResolver;
    }

    /**
     * 主入口：分享口令 → 商品 ID。
     *
     * <p>⚠️ <b>不抛异常</b>：失败原因放在 {@link VideoProductResult#error()}，
     * 便于调用方（影刀）统一处理。</p>
     */
    public VideoProductResult resolve(String shareText, long advertiserId) {
        return resolve(shareText, advertiserId, null, null, null);
    }

    /**
     * 主入口（可注入 fetcher，供单测）。
     *
     * @param awemeUidOverride   已知作者 uid 时直接给（跳过解析）
     * @param douyinIdOverride   已知抖音号字符串（提高授权号检索命中率）
     * @param videoListFetcher   查视频列表；{@code null} = 用真实接口
     */
    public VideoProductResult resolve(String shareText, long advertiserId,
                                      Long awemeUidOverride, String douyinIdOverride,
                                      VideoListFetcher videoListFetcher) {
        String stage = "参数校验";
        // ⚠️ 声明在 try 外 —— catch 里也要能拿到（失败时把已解析的部分带回去）
        String catchItemId = "";
        long catchUid = 0;
        try {
            if (shareText == null || shareText.isBlank()) {
                return fail(stage, "分享口令为空", null, 0);
            }
            if (advertiserId <= 0) {
                return fail(stage, "advertiserId 非法：" + advertiserId, null, 0);
            }

            // ① 短链 → 视频ID（顺带抠 social_author_id）
            stage = "解析视频ID";
            ShareParse parsed = parseShare(shareText);
            if (parsed.itemId.isEmpty()) {
                return fail(stage, "短链解析失败（可能被风控/已过期/视频私密）："
                        + "未能从分享口令或落地页里找到视频ID", null, 0);
            }
            // ⚠️ 从这里开始，任何失败都要【带上已解析出的 videoId / uid】——
            //    否则调用方拿不到「已经解析出来的部分」，排障会瞎。
            String parsedItemId = parsed.itemId;
            catchItemId = parsedItemId;
            catchUid = parsed.authorUid;
            log.info("分享解析：视频ID={} 作者uid={}（来自分享参数={}）",
                    parsed.itemId, parsed.authorUid, parsed.authorUid > 0);

            // ② 作者 uid：手动 > 分享参数 > 视频ID直查 > 授权号检索
            stage = "解析作者uid";
            long uid = 0;
            if (awemeUidOverride != null && awemeUidOverride > 0) {
                uid = awemeUidOverride;
                log.info("作者 uid 用调用方指定的：{}", uid);
            } else if (parsed.authorUid > 0) {
                uid = parsed.authorUid;
                log.info("作者 uid 来自分享参数 social_author_id：{}", uid);
            } else {
                uid = resolveAuthorUidByItemId(parsed.itemId, parsed.landingBody);
                if (uid > 0) {
                    log.info("作者 uid 来自视频ID直查：{}", uid);
                }
            }
            catchUid = uid;
            if (uid <= 0) {
                return fail(stage, "拿不到作者数值 uid（分享参数没有、直查也失败）"
                        + " —— 请让影刀从素材表把该行的抖音号一起传上来",
                        parsedItemId, 0);
            }

            // ③ 视频列表 → 商品ID
            stage = "查询视频商品";
            JsonNode hit = findVideoProduct(advertiserId, uid, parsed.itemId, videoListFetcher);
            if (hit != null) {
                JsonNode pi = hit.path("product_info");

                // ⚠️ 视频列表应答里【没有 author 字段】→ 抖音名从分享文本抠，
                //    抖音号用数值 uid 反查（授权达人接口）
                String nickname = extractNicknameFromShare(shareText);
                String douyinId = douyinIdOverride;
                if (douyinId == null || douyinId.isBlank()) {
                    douyinId = resolveDouyinId(advertiserId, uid);
                }

                VideoProductResult ok = new VideoProductResult(parsed.itemId, uid,
                        nickname, douyinId == null ? "" : douyinId,
                        pi.path("id").asText(""), pi.path("name").asText(""),
                        hit.path("title").asText(""), pi.path("img").asText(""),
                        List.of(), "", "");
                log.info("解析成功：视频ID={} → 商品ID={}（{}）；抖音名={} 抖音号={}",
                        ok.videoId(), ok.productId(), ok.productName(),
                        ok.awemeName(), ok.awemeShowId());
                return ok;
            }

            // ④ 兜底：品名反查
            stage = "品名反查";
            log.warn("视频列表没匹配到视频ID {}，转品名反查兜底", parsed.itemId);
            List<String> candidates = searchProductByName(advertiserId, uid);
            VideoProductResult miss = new VideoProductResult(parsed.itemId, uid, "", "",
                    "", "", "", "", candidates, stage,
                    candidates.isEmpty()
                            ? "视频列表未匹配到该视频，品名反查也没搜到候选商品"
                            : "视频列表未匹配到该视频，以下是品名反查的候选，请人工确认");
            return miss;

        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            log.warn("解析分享链接失败（卡在 {}）：{}", stage, msg);
            // ⚠️ 尽量把已经解析出来的信息带回去（变量可能还没赋值，用 try 兜）
            return fail(stage, msg, catchItemId, catchUid);
        }
    }

    private static VideoProductResult fail(String stage, String error, String itemId, long uid) {
        return new VideoProductResult(itemId, uid <= 0 ? null : uid, "", "", "", "", "",
                "", List.of(), stage, error);
    }

    /** 从分享文本抠抖音名（{@code 看看【xxx的作品】}）；抠不到返回空串。 */
    static String extractNicknameFromShare(String shareText) {
        if (shareText == null || shareText.isEmpty()) {
            return "";
        }
        Matcher m = NICKNAME_IN_SHARE.matcher(shareText);
        return m.find() ? m.group(1).trim() : "";
    }

    /** 数值 uid → 抖音号（授权达人接口反查）；失败返回空串。 */
    private String resolveDouyinId(long advertiserId, long uid) {
        try {
            var map = awemeShowIdResolver.resolveBatch(advertiserId, java.util.List.of(uid));
            String id = map.get(uid);
            if (id != null && !id.isBlank()) {
                log.info("抖音号反查成功：uid={} → {}", uid, id);
                return id;
            }
            log.info("抖音号反查没命中（uid={}）—— 该达人可能没授权给账户 {}", uid, advertiserId);
        } catch (Exception e) {
            // ⚠️ 反查失败不影响主流程（商品ID 才是关键）
            log.info("抖音号反查失败（不影响商品ID）：{}", e.getMessage());
        }
        return "";
    }

    // ==================== ① 短链解析 ====================

    /** 短链解析结果。 */
    private record ShareParse(String itemId, long authorUid, String landingBody) {
    }

    private ShareParse parseShare(String shareText) throws IOException {
        Matcher m = SHORT_LINK.matcher(shareText);
        if (!m.find()) {
            // 口令里没有短链，可能直接给了长链或视频ID
            String direct = extractItemId(shareText);
            if (direct != null) {
                return new ShareParse(direct, extractAuthorIdFromShare(shareText), "");
            }
            throw new IOException("未能从分享口令里提取到 v.douyin.com 短链");
        }
        String url = m.group();
        log.debug("短链 = {}", url);

        String itemId = null;
        long authorUid = 0;
        String landingBody = "";

        for (int hop = 1; hop <= MAX_HOPS; hop++) {
            HttpURLConnection conn = null;
            int status = 0;
            String location = null;
            String body = "";
            try {
                conn = (HttpURLConnection) toUrl(url).openConnection();
                conn.setInstanceFollowRedirects(false);   // 自己读 Location
                conn.setRequestProperty("User-Agent", MOBILE_UA);
                conn.setRequestProperty("Referer", "https://www.douyin.com/");
                conn.setConnectTimeout(HTTP_TIMEOUT_MS);
                conn.setReadTimeout(HTTP_TIMEOUT_MS);
                status = conn.getResponseCode();
                location = conn.getHeaderField("Location");
                if (status == 200) {
                    body = readStream(conn.getInputStream());
                    landingBody = body;
                }
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }

            if (itemId == null) {
                itemId = extractItemId(url);
                if (itemId == null) {
                    itemId = extractItemId(body);
                }
            }
            if (authorUid == 0) {
                authorUid = extractAuthorIdFromShare(url);
                if (authorUid == 0 && !body.isEmpty()) {
                    authorUid = extractAuthorIdFromShare(body);
                }
            }
            if (itemId != null && authorUid != 0) {
                break;   // 两样都拿到了，不用继续跳
            }

            boolean isRedirect = status == 301 || status == 302 || status == 303
                    || status == 307 || status == 308;
            if (isRedirect && location != null && !location.isEmpty()) {
                url = resolveRelative(url, location.trim());
                log.debug("第 {} 跳 = {}", hop, url);
                continue;
            }
            break;
        }
        return new ShareParse(itemId == null ? "" : itemId, authorUid, landingBody);
    }

    /** 从文本里抠视频ID（4 种形态）。 */
    static String extractItemId(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        for (String p : ITEM_ID_PATTERNS) {
            Matcher mm = Pattern.compile(p).matcher(text);
            if (mm.find()) {
                return mm.group(1);
            }
        }
        return null;
    }

    /**
     * 从分享链接里提取作者数值 uid。
     *
     * <p>短链展开后的 URL 带 {@code activity_info}（URL 编码的 JSON），
     * 形如 {@code activity_info=%7B%22social_author_id%22%3A%223206653666920558%22...}
     * —— <b>先 URL 解码再正则</b>。</p>
     *
     * @return 作者 uid；取不到返回 0
     */
    static long extractAuthorIdFromShare(String raw) {
        if (raw == null || raw.isEmpty()) {
            return 0;
        }
        String decoded;
        try {
            decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (Exception e) {
            decoded = raw;
        }
        Matcher m = AUTHOR_ID.matcher(decoded);
        return m.find() ? Long.parseLong(m.group(1)) : 0;
    }

    // ==================== ② 视频ID → 作者 uid（兜底路径） ====================

    private long resolveAuthorUidByItemId(String itemId, String landingBody) {
        // 取法 1：iesdouyin 公开 iteminfo（非官方）
        try {
            String api = "https://www.iesdouyin.com/web/api/v2/aweme/iteminfo/?item_ids=" + itemId;
            String resp = httpGetRaw(api, "https://www.douyin.com/");
            JsonNode root = MAPPER.readTree(resp);
            JsonNode list = root.path("item_list");
            if (list.isArray() && !list.isEmpty()) {
                String uid = list.get(0).path("author").path("uid").asText("");
                if (!uid.isEmpty()) {
                    log.info("[作者直查] iteminfo 命中 uid={} 昵称={}", uid,
                            list.get(0).path("author").path("nickname").asText(""));
                    return Long.parseLong(uid);
                }
            }
            log.info("[作者直查] iteminfo 返回空列表（接口可能已被限制）");
        } catch (Exception e) {
            log.info("[作者直查] iteminfo 失败：{}", e.getMessage());
        }

        // 取法 2：落地页 HTML 正则抠（兜底）
        try {
            String html = landingBody == null || landingBody.isEmpty()
                    ? httpGetRaw("https://www.iesdouyin.com/share/video/" + itemId,
                            "https://www.douyin.com/")
                    : landingBody;
            Matcher mUid = Pattern.compile("\"uid\"\\s*:\\s*\"(\\d{5,25})\"").matcher(html);
            if (mUid.find()) {
                log.info("[作者直查] 落地页抠到 uid={}（请人工核对是否作者本人）", mUid.group(1));
                return Long.parseLong(mUid.group(1));
            }
            log.info("[作者直查] 落地页无作者信息（可能撞风控验证码页）");
        } catch (Exception e) {
            log.info("[作者直查] 落地页解析失败：{}", e.getMessage());
        }
        return 0;
    }

    // ==================== ③ 视频列表 → 商品ID ====================

    private JsonNode findVideoProduct(long advertiserId, long uid, String itemId,
                                      VideoListFetcher injected) throws Exception {
        VideoListFetcher fetcher = injected != null ? injected : clientFactory.videoListFetcher();
        long cursor = 0;
        for (int page = 1; page <= MAX_VIDEO_PAGES; page++) {
            JsonNode data = fetcher.fetch(advertiserId, uid, cursor, VIDEO_PAGE_SIZE);
            if (data == null) {
                return null;
            }
            JsonNode videos = data.path("video_list");
            if (videos.isArray()) {
                for (JsonNode v : videos) {
                    if (itemId.equals(v.path("aweme_item_id").asText(""))) {
                        return v;
                    }
                }
            }
            JsonNode pageInfo = data.path("page_info");
            if (pageInfo.path("has_more").asInt(0) != 1) {
                log.info("视频列表已翻完（共 {} 页），未匹配到视频ID {}", page, itemId);
                return null;
            }
            cursor = pageInfo.path("cursor").asLong(0);
            if (cursor <= 0) {
                log.warn("has_more=1 但没有 cursor，停止翻页");
                return null;
            }
        }
        log.warn("视频列表达到最大页数 {}，停止翻页", MAX_VIDEO_PAGES);
        return null;
    }

    // ==================== ④ 兜底：品名反查 ====================

    private List<String> searchProductByName(long advertiserId, long uid) {
        try {
            JsonNode data = clientFactory.productSearchFetcher().fetch(advertiserId, uid, "");
            List<String> out = new ArrayList<>();
            JsonNode list = data == null ? null : data.path("product_list");
            if (list != null && list.isArray()) {
                for (JsonNode p : list) {
                    out.add(p.path("id").asText("") + " | " + p.path("name").asText(""));
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("品名反查失败：{}", e.getMessage());
            return List.of();
        }
    }

    // ==================== HTTP / URL 工具 ====================

    private static String httpGetRaw(String url, String referer) throws IOException {
        HttpURLConnection conn = null;
        int status = 0;
        String resp = "";
        try {
            conn = (HttpURLConnection) toUrl(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", MOBILE_UA);
            conn.setRequestProperty("Referer", referer);
            conn.setRequestProperty("Accept-Encoding", "identity");   // 别 gzip，省得解压
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            status = conn.getResponseCode();
            resp = readStream(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        if (status >= 400) {
            throw new IOException("HTTP " + status + " url=" + url);
        }
        return resp;
    }

    private static String readStream(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    /** 字符串 → URL（替代 Java 20+ 已弃用的 {@code new URL(String)}）。 */
    private static URL toUrl(String s) throws IOException {
        try {
            return URI.create(s).toURL();
        } catch (IllegalArgumentException e) {
            try {
                return new URI(null, null, s, null).toURL();
            } catch (URISyntaxException ex) {
                throw new IOException("无法解析为 URL: " + s, e);
            }
        }
    }

    /** 把 Location 补全为绝对地址（可能是相对路径或协议相对路径）。 */
    private static String resolveRelative(String baseUrl, String location) {
        if (location == null || location.isEmpty()) {
            return baseUrl;
        }
        if (location.startsWith("http://") || location.startsWith("https://")) {
            return location;
        }
        if (location.startsWith("//")) {
            return "https:" + location;
        }
        try {
            URI base = URI.create(baseUrl);
            String origin = base.getScheme() + "://" + base.getHost()
                    + (base.getPort() > 0 ? ":" + base.getPort() : "");
            return location.startsWith("/") ? origin + location : origin + "/" + location;
        } catch (IllegalArgumentException e) {
            return location;
        }
    }

    /** 去掉空白/emoji/不可见字符（昵称匹配用）。 */
    static String clean(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("[\\s\\u3000\\uFE0F\\u200D]", "")
                .replaceAll("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}]", "")
                .trim();
    }
}
