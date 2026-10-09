package com.aolandai.dingtou.tools;

import com.aolandai.dingtou.client.SuixintuiTokenClient;
import com.bytedance.ads.ApiClient;
import com.bytedance.ads.api.QianchuanAwemeUniPromotionOrderGetV10Api;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10Count;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10DataPageInfoHasMore;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10MarketingGoal;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10OrderField;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10Response;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10ResponseData;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 手工探针：<b>实测「拉一个账户的全量随心推订单」要多久、多少条、最早到什么时候</b>。
 *
 * <h2>为什么要这个探针</h2>
 * <p>老板定了「首次拉全量 + 之后增量」，但三个关键数字全是空白：</p>
 * <ol>
 *   <li><b>拉一次要多久</b>（决定增量频率可不可行）；</li>
 *   <li><b>实际多少条</b>（代码注释里估的是「4 万+」，没实测过）；</li>
 *   <li><b>最早订单到什么时候</b>（决定首次全量的时间窗要往前推到哪 —— 接口硬约束 ≤180 天，可能要分片）。</li>
 * </ol>
 *
 * <p>顺带统计<b>订单状态分布</b>与<b>N 分布</b>（按【达人 + 商品】分组），
 * 用于核对「不算状态，有一条算一笔」这个口径。</p>
 *
 * <pre>
 * java -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 \
 *   -cp "target/test-classes;target/classes;&lt;依赖&gt;" \
 *   com.aolandai.dingtou.tools.SuixintuiOrderFullPullProbe [advertiserId] [--count=50] [--intervalMs=500]
 * </pre>
 *
 * <p>⚠️ <b>只读接口，不花钱。</b>但可能跑很久（几百页），建议后台跑。</p>
 *
 * <p>⚠️ 手工探针，不进自动化测试（surefire 已排除 {@code **&#47;tools/**}）。</p>
 */
public final class SuixintuiOrderFullPullProbe {

    private static final Logger log = LoggerFactory.getLogger(SuixintuiOrderFullPullProbe.class);

    /** 默认账户：官方【母婴商品】—— 我们最熟、数据最多的一个。 */
    private static final long DEFAULT_ADVERTISER_ID = 1823379540880396L;

    /** 翻页上限（防死循环）。 */
    private static final int MAX_PAGES = 3000;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private SuixintuiOrderFullPullProbe() {
    }

    public static void main(String[] args) throws Exception {
        long advertiserId = parseLong(args, 0, DEFAULT_ADVERTISER_ID);
        int pageSize = (int) parseLongFlag(args, "--count", 50L);
        long intervalMs = parseLongFlag(args, "--intervalMs", 500L);

        log.info("========== SuixintuiOrderFullPullProbe 开始（只读，不花钱）==========");
        log.info("账户 = {}，每页 = {}，页间隔 = {}ms", advertiserId, pageSize, intervalMs);
        log.info("⚠️ 不传时间窗 = 拉全量。若接口拒绝，会自动改用分片模式。");

        String token = SuixintuiTokenClient.getAccessToken();
        ApiClient apiClient = new ApiClient();
        apiClient.setBasePath("https://api.oceanengine.com");
        apiClient.addDefaultHeader("Access-Token", token);

        QianchuanAwemeUniPromotionOrderGetV10Api api =
                new QianchuanAwemeUniPromotionOrderGetV10Api();
        api.setApiClient(apiClient);

        long startedAt = System.currentTimeMillis();
        List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> all =
                pullAll(api, advertiserId, pageSize, intervalMs);
        long costMs = System.currentTimeMillis() - startedAt;

        report(all, advertiserId, pageSize, costMs);
        log.info("========== SuixintuiOrderFullPullProbe 结束 ==========");
    }

    /** 逐页拉全量，每页打印进度。 */
    private static List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> pullAll(
            QianchuanAwemeUniPromotionOrderGetV10Api api, long advertiserId, int pageSize,
            long intervalMs) throws Exception {

        QianchuanAwemeUniPromotionOrderGetV10Count count = countOf(pageSize);
        List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> collected =
                new ArrayList<>();
        Map<String, QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> byOrderId =
                new LinkedHashMap<>();
        Long cursor = null;
        long startedAt = System.currentTimeMillis();

        for (int page = 1; page <= MAX_PAGES; page++) {
            QianchuanAwemeUniPromotionOrderGetV10Response response =
                    api.openApiV10QianchuanAwemeUniPromotionOrderGetGet(
                            advertiserId,
                            QianchuanAwemeUniPromotionOrderGetV10MarketingGoal.VIDEO_PROM_GOODS,
                            null,
                            QianchuanAwemeUniPromotionOrderGetV10OrderField.ORDER_CREATE_TIME,
                            cursor,
                            count);

            Long code = response.getCode();
            if (code == null || code != 0L) {
                log.error("第 {} 页失败：code={} message={} request_id={}",
                        page, code, response.getMessage(), response.getRequestId());
                if (page == 1) {
                    throw new IllegalStateException("首页就失败，无法继续：code=" + code
                            + " message=" + response.getMessage());
                }
                break;
            }
            QianchuanAwemeUniPromotionOrderGetV10ResponseData data = response.getData();
            if (data == null) {
                log.error("第 {} 页 data 为空，停止", page);
                break;
            }
            List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> list =
                    data.getOrderList();
            int got = list == null ? 0 : list.size();
            if (list != null) {
                for (var order : list) {
                    // 按 order_id 去重（增量拉取时靠它兜边界重叠）
                    byOrderId.putIfAbsent(String.valueOf(order.getOrderId()), order);
                }
            }
            long elapsed = System.currentTimeMillis() - startedAt;
            log.info("第 {} 页：本页 {} 条，去重后累计 {} 条，已耗时 {}s",
                    page, got, byOrderId.size(), elapsed / 1000);

            var pageInfo = data.getPageInfo();
            boolean hasMore = pageInfo != null
                    && pageInfo.getHasMore()
                    == QianchuanAwemeUniPromotionOrderGetV10DataPageInfoHasMore.NUMBER_1;
            if (!hasMore) {
                log.info("has_more=0，拉完（共 {} 页）", page);
                break;
            }
            cursor = pageInfo.getCursor();
            if (cursor == null) {
                log.warn("has_more=1 但没有 cursor，停止");
                break;
            }
            if (intervalMs > 0) {
                Thread.sleep(intervalMs);
            }
        }
        collected.addAll(byOrderId.values());
        return collected;
    }

    private static void report(
            List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> all,
            long advertiserId, int pageSize, long costMs) {

        log.info("========== 实测结果 ==========");
        log.info("账户            : {}", advertiserId);
        log.info("每页条数        : {}", pageSize);
        log.info("总条数（去重后）: {}", all.size());
        log.info("总耗时          : {}s（{} 分 {} 秒）",
                costMs / 1000, costMs / 60000, (costMs / 1000) % 60);
        if (!all.isEmpty()) {
            log.info("平均每页耗时    : {}ms",
                    costMs / Math.max(1, (all.size() + pageSize - 1) / pageSize));
        }

        // 订单创建时间范围
        TreeMap<String, Integer> byDay = new TreeMap<>();
        for (var order : all) {
            String t = String.valueOf(order.getOrderCreateTime());
            byDay.merge(t.length() >= 10 ? t.substring(0, 10) : t, 1, Integer::sum);
        }
        if (!byDay.isEmpty()) {
            log.info("最早订单日      : {}", byDay.firstKey());
            log.info("最晚订单日      : {}", byDay.lastKey());
            log.info("跨天跨度        : {} 天（决定首次全量要不要分片）",
                    java.time.temporal.ChronoUnit.DAYS.between(
                            java.time.LocalDate.parse(byDay.firstKey()),
                            java.time.LocalDate.parse(byDay.lastKey())));
            log.info("按天分布（前 5 / 后 5）：");
            List<String> keys = new ArrayList<>(byDay.keySet());
            for (int i = 0; i < Math.min(5, keys.size()); i++) {
                log.info("    {} → {}", keys.get(i), byDay.get(keys.get(i)));
            }
            for (int i = Math.max(5, keys.size() - 5); i < keys.size(); i++) {
                log.info("    {} → {}", keys.get(i), byDay.get(keys.get(i)));
            }
        }

        // 状态分布
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (var order : all) {
            byStatus.merge(String.valueOf(order.getStatus()), 1, Integer::sum);
        }
        log.info("========== 订单状态分布（口径：不看状态，全部算一笔）==========");
        byStatus.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> log.info("    {} → {}", e.getKey(), e.getValue()));

        // 按【达人 + 商品】分组 → N 分布
        Map<String, Integer> byCombo = new LinkedHashMap<>();
        Set<String> awemes = new LinkedHashSet<>();
        Set<String> products = new LinkedHashSet<>();
        for (var order : all) {
            String aweme = order.getAwemeInfo() == null ? null
                    : String.valueOf(order.getAwemeInfo().getAwemeShowId());
            String product = order.getProductInfo() == null ? null
                    : String.valueOf(order.getProductInfo().getProductId());
            if (aweme == null || product == null || "null".equals(aweme) || "null".equals(product)) {
                continue;
            }
            awemes.add(aweme);
            products.add(product);
            byCombo.merge(aweme + "|" + product, 1, Integer::sum);
        }
        log.info("========== 【达人 + 商品】分组 ==========");
        log.info("不同抖音号      : {}", awemes.size());
        log.info("不同商品        : {}", products.size());
        log.info("组合数          : {}", byCombo.size());
        int n1 = 0;
        int n2 = 0;
        int n3 = 0;
        int nGt3 = 0;
        for (int n : byCombo.values()) {
            if (n == 1) {
                n1++;
            } else if (n == 2) {
                n2++;
            } else if (n == 3) {
                n3++;
            } else {
                nGt3++;
            }
        }
        log.info("N=1 : {}（{}%）", n1, pct(n1, byCombo.size()));
        log.info("N=2 : {}（{}%）", n2, pct(n2, byCombo.size()));
        log.info("N=3 : {}（{}%）", n3, pct(n3, byCombo.size()));
        log.info("N>3 : {}（{}%）", nGt3, pct(nGt3, byCombo.size()));
        log.info("N>=3（会转千川）: {} 个组合", n3 + nGt3);
        log.info("当前时间        : {}", LocalDateTime.now().format(TS));
    }

    private static String pct(int part, int total) {
        return total == 0 ? "0" : String.valueOf(Math.round(part * 1000.0 / total) / 10.0);
    }

    private static QianchuanAwemeUniPromotionOrderGetV10Count countOf(int pageSize) {
        if (pageSize <= 10) {
            return QianchuanAwemeUniPromotionOrderGetV10Count.NUMBER_10;
        }
        if (pageSize <= 20) {
            return QianchuanAwemeUniPromotionOrderGetV10Count.NUMBER_20;
        }
        return QianchuanAwemeUniPromotionOrderGetV10Count.NUMBER_50;
    }

    private static long parseLong(String[] args, int index, long fallback) {
        if (args == null || args.length <= index || args[index] == null
                || args[index].startsWith("--")) {
            return fallback;
        }
        try {
            return Long.parseLong(args[index].trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long parseLongFlag(String[] args, String flag, long fallback) {
        if (args == null) {
            return fallback;
        }
        for (String a : args) {
            if (a != null && a.startsWith(flag + "=")) {
                try {
                    return Long.parseLong(a.substring(flag.length() + 1).trim());
                } catch (NumberFormatException e) {
                    return fallback;
                }
            }
        }
        return fallback;
    }
}
