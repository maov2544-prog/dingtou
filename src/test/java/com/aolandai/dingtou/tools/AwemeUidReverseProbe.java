package com.aolandai.dingtou.tools;

import com.aolandai.dingtou.DingtouApplication;
import com.aolandai.dingtou.client.QianchuanClientFactory;
import com.aolandai.dingtou.service.qianchuan.AwemeUidResolver;
import com.bytedance.ads.model.QianchuanUniAwemeAuthorizedGetV10Response;
import com.bytedance.ads.model.QianchuanUniAwemeAuthorizedGetV10ResponseDataAwemeIdListInner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 手工探针：<b>验证「用数值 UID 反查抖音号」是否可行</b>。
 *
 * <p>背景：乘方计划详情接口 {@code uni_promotion/ad/detail/} 有时只返回抖音名、
 * {@code unique_id}（抖音号）为空 —— 实测占 12.6%（175 个计划里 22 个）。
 * 但同一应答里 {@code aweme_uid}（数值 UID）<b>是有值的</b>。</p>
 *
 * <p>本探针拿那个 UID 去【授权达人列表】接口 {@code uni_aweme/authorized/get/}
 * 全量扫描，看能不能反查出 {@code aweme_show_id}（抖音号）。</p>
 *
 * <pre>
 * java -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 \
 *   -cp "target/test-classes;target/classes;&lt;依赖&gt;" \
 *   com.aolandai.dingtou.tools.AwemeUidReverseProbe [advertiserId] [awemeUid]
 * </pre>
 *
 * <p>⚠️ 只读接口，<b>不花钱</b>。</p>
 */
public final class AwemeUidReverseProbe {

    private static final Logger log = LoggerFactory.getLogger(AwemeUidReverseProbe.class);

    private static final String PROBE_AUTH_TOKEN = "probe-only-not-a-real-token";

    /** 默认：官方【母婴商品】+ 那个 unique_id 为空的达人。 */
    private static final String DEFAULT_ADVERTISER_ID = "1823379540880396";
    private static final String DEFAULT_AWEME_UID = "65628672972";

    private static final long PAGE_SIZE = 100L;
    private static final long MAX_PAGES = 50L;

    private AwemeUidReverseProbe() {
    }

    public static void main(String[] args) throws Exception {
        long advertiserId = Long.parseLong(arg(args, 0, DEFAULT_ADVERTISER_ID));
        long targetUid = Long.parseLong(arg(args, 1, DEFAULT_AWEME_UID));

        log.info("========== AwemeUidReverseProbe 开始 ==========");
        log.info("账户 = {}，要找的数值 UID = {}", advertiserId, targetUid);

        try (var context = new SpringApplicationBuilder(DingtouApplication.class)
                .web(WebApplicationType.NONE)
                .run("--dingtou.auth-token=" + PROBE_AUTH_TOKEN, "--server.port=0")) {

            QianchuanClientFactory factory = context.getBean(QianchuanClientFactory.class);
            AwemeUidResolver.AuthorizedAwemeFetcher fetcher = factory.authorizedAwemeFetcher();

            List<QianchuanUniAwemeAuthorizedGetV10ResponseDataAwemeIdListInner> all = scanAll(
                    fetcher, advertiserId);

            log.info("========== 授权达人列表：共 {} 个 ==========", all.size());
            printSamples(all);

            findAndReport(all, targetUid);
        }
        log.info("========== AwemeUidReverseProbe 结束 ==========");
    }

    /** 逐页扫完（接口只支持 page/page_size，没有「按 UID 过滤」）。 */
    private static List<QianchuanUniAwemeAuthorizedGetV10ResponseDataAwemeIdListInner> scanAll(
            AwemeUidResolver.AuthorizedAwemeFetcher fetcher, long advertiserId) throws Exception {
        List<QianchuanUniAwemeAuthorizedGetV10ResponseDataAwemeIdListInner> all = new ArrayList<>();
        for (long page = 1; page <= MAX_PAGES; page++) {
            QianchuanUniAwemeAuthorizedGetV10Response response =
                    fetcher.fetch(advertiserId, null, page, PAGE_SIZE);
            if (response == null || response.getData() == null
                    || response.getData().getAwemeIdList() == null) {
                break;
            }
            List<QianchuanUniAwemeAuthorizedGetV10ResponseDataAwemeIdListInner> list =
                    response.getData().getAwemeIdList();
            all.addAll(list);
            log.info("第 {} 页：{} 条（累计 {}）", page, list.size(), all.size());
            if (list.size() < PAGE_SIZE) {
                break;
            }
        }
        return all;
    }

    private static void printSamples(
            List<QianchuanUniAwemeAuthorizedGetV10ResponseDataAwemeIdListInner> all) {
        int limit = Math.min(3, all.size());
        for (int i = 0; i < limit; i++) {
            log.info("样例[{}]：aweme_id={} aweme_show_id={} aweme_name={}",
                    i, all.get(i).getAwemeId(), all.get(i).getAwemeShowId(),
                    all.get(i).getAwemeName());
        }
    }

    /** 找到目标 UID 就把它<b>所有字段</b>打出来。 */
    private static void findAndReport(
            List<QianchuanUniAwemeAuthorizedGetV10ResponseDataAwemeIdListInner> all,
            long targetUid) {
        QianchuanUniAwemeAuthorizedGetV10ResponseDataAwemeIdListInner hit = null;
        for (var item : all) {
            if (item != null && item.getAwemeId() != null && item.getAwemeId() == targetUid) {
                hit = item;
                break;
            }
        }

        if (hit == null) {
            log.warn("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
            log.warn("❌ 【方案 A 不可行】：该 UID 不在本账户的授权达人列表里（扫了 {} 个都没找到）", all.size());
            log.warn("   可能原因：达人已解除授权 / 该达人属于别的账户 / UID 类型不对");
            log.warn("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
            return;
        }

        log.info("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
        log.info("✅ 【方案 A 可行】：反查到了！");
        log.info("   aweme_uid（数值）    = {}", hit.getAwemeId());
        log.info("   aweme_show_id（抖音号）= {}", hit.getAwemeShowId());
        log.info("   aweme_name（抖音名）  = {}", hit.getAwemeName());
        log.info("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");

        log.info("========== 该达人条目的【全部字段】==========");
        for (Method m : hit.getClass().getMethods()) {
            String name = m.getName();
            if (!name.startsWith("get") || name.equals("getClass")
                    || m.getParameterCount() != 0) {
                continue;
            }
            try {
                Object value = m.invoke(hit);
                System.out.println("  " + Character.toLowerCase(name.charAt(3))
                        + name.substring(4) + " = " + value);
            } catch (Exception e) {
                System.out.println("  " + name + " = <取值失败: " + e.getMessage() + ">");
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
