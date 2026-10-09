package com.aolandai.dingtou.tools;

import com.aolandai.dingtou.DingtouApplication;
import com.aolandai.dingtou.dto.qianchuan.VideoProductResult;
import com.aolandai.dingtou.service.qianchuan.PlanMaterialResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * 手工探针：验证 <b>抖音分享链接 → 视频ID → 商品ID</b>。
 *
 * <pre>
 * java -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 \
 *   -cp "target/test-classes;target/classes;&lt;依赖&gt;" \
 *   com.aolandai.dingtou.tools.ResolveProductByVideoProbe "&lt;分享口令&gt;" &lt;advertiserId&gt;
 * </pre>
 *
 * <p>⚠️ 只读、不花钱。手工探针，不进自动化测试。</p>
 */
public final class ResolveProductByVideoProbe {

    private static final Logger log = LoggerFactory.getLogger(ResolveProductByVideoProbe.class);

    private static final String PROBE_AUTH_TOKEN = "probe-only-not-a-real-token";

    /** 老板给的真实样本。 */
    private static final String DEFAULT_LINK =
            "0.56 复制打开抖音，看看【尚尚孕期（努力版）的作品】孕期皮肤干敏的姐妹看过来✨澳兰黛孕产面膜 "
            + "无酒精香... https://v.douyin.com/0q-qmQYi6hQ/ 11/09 A@g.oQ :1pm OKW:/  ";

    private static final long DEFAULT_ADVERTISER_ID = 1823379540880396L;

    private ResolveProductByVideoProbe() {
    }

    public static void main(String[] args) {
        String link = args != null && args.length > 0 && !args[0].isBlank()
                ? args[0] : DEFAULT_LINK;
        long advertiserId = args != null && args.length > 1 && !args[1].isBlank()
                ? Long.parseLong(args[1].trim()) : DEFAULT_ADVERTISER_ID;

        log.info("========== ResolveProductByVideoProbe 开始（只读，不花钱）==========");
        log.info("账户 = {}，分享口令长度 = {}", advertiserId, link.length());

        try (var context = new SpringApplicationBuilder(DingtouApplication.class)
                .web(WebApplicationType.NONE)
                .run("--dingtou.auth-token=" + PROBE_AUTH_TOKEN, "--server.port=0")) {

            PlanMaterialResolver resolver = context.getBean(PlanMaterialResolver.class);
            long startedAt = System.currentTimeMillis();
            VideoProductResult r = resolver.resolve(link, advertiserId);
            long cost = System.currentTimeMillis() - startedAt;

            log.info("========== 结果（耗时 {}s）==========", cost / 1000);
            log.info("视频ID      : {}", r.videoId());
            log.info("作者数值UID : {}", r.awemeUid());
            log.info("抖音名      : {}", r.awemeName());
            log.info("抖音号      : {}", r.awemeShowId());
            log.info("商品ID      : {}", r.productId());
            log.info("商品名      : {}", r.productName());
            log.info("视频标题    : {}", r.title());
            log.info("卡在哪一步  : {}", r.stage());
            log.info("失败原因    : {}", r.error().isEmpty() ? "（无）" : r.error());
            if (!r.candidates().isEmpty()) {
                log.info("候选商品（{} 条）：", r.candidates().size());
                r.candidates().stream().limit(10).forEach(c -> log.info("    {}", c));
            }
            log.info("是否拿到商品ID: {}", r.found() ? "✅ 是" : "❌ 否");
        }
        log.info("========== ResolveProductByVideoProbe 结束 ==========");
    }
}
