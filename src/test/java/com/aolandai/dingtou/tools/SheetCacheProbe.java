package com.aolandai.dingtou.tools;

import com.aolandai.dingtou.DingtouApplication;
import com.aolandai.dingtou.source.AccountDictReader;
import com.aolandai.dingtou.source.DeliverySheetLocator;
import com.aolandai.dingtou.source.DeliverySheetReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * 手工探针：验证<b>素材表 / 账户字典的读取缓存</b>是否生效。
 *
 * <p>背景：轮询时是「一次读表 → 逐个组合处理」，而每个组合都会再读一次表
 * （{@code PlanTargetResolver} → {@code locate()} → {@code readAll()}）
 * → 50 个组合 = 读表 50 次。缓存后应该只读 1 次。</p>
 *
 * <p>⚠️ 只读，不改任何文件。</p>
 */
public final class SheetCacheProbe {

    private static final Logger log = LoggerFactory.getLogger(SheetCacheProbe.class);

    private static final String PROBE_AUTH_TOKEN = "probe-only-not-a-real-token";

    private SheetCacheProbe() {
    }

    public static void main(String[] args) {
        log.info("========== SheetCacheProbe 开始（只读）==========");
        try (var context = new SpringApplicationBuilder(DingtouApplication.class)
                .web(WebApplicationType.NONE)
                .run("--dingtou.auth-token=" + PROBE_AUTH_TOKEN, "--server.port=0")) {

            DeliverySheetReader reader = context.getBean(DeliverySheetReader.class);
            AccountDictReader accountReader = context.getBean(AccountDictReader.class);
            DeliverySheetLocator locator = context.getBean(DeliverySheetLocator.class);

            // 第 1 次：真读（未命中缓存）
            long t1 = time(() -> reader.readAll().size());
            // 第 2、3 次：应命中缓存
            long t2 = time(() -> reader.readAll().size());
            long t3 = time(() -> reader.readAll().size());
            log.info("素材表读取 3 次耗时：第1次 {}ms（真读）/ 第2次 {}ms / 第3次 {}ms",
                    t1, t2, t3);

            long a1 = time(() -> accountReader.readAll().size());
            long a2 = time(() -> accountReader.readAll().size());
            log.info("账户字典读取 2 次耗时：第1次 {}ms（真读）/ 第2次 {}ms", a1, a2);

            // 模拟「逐组合处理」：同一组合定位 3 次
            long l1 = time(() -> locator.locate("272886488", "益生元身体乳").size());
            long l2 = time(() -> locator.locate("272886488", "益生元身体乳").size());
            long l3 = time(() -> locator.locate("272886488", "益生元身体乳").size());
            log.info("同一组合定位 3 次耗时：{}ms / {}ms / {}ms（第2、3次应远快于第1次）",
                    l1, l2, l3);

            log.info("结论：{}", (t2 < t1 / 2 && t3 < t1 / 2)
                    ? "✅ 缓存生效（重复读取显著变快）" : "⚠️ 缓存可能没生效，请检查");

            reader.invalidate();
            log.info("已手动 invalidate，下次读会重新真读");
        }
        log.info("========== SheetCacheProbe 结束 ==========");
    }

    private static long time(java.util.concurrent.Callable<Integer> action) {
        long start = System.currentTimeMillis();
        try {
            action.call();
        } catch (Exception e) {
            log.warn("调用失败：{}", e.getMessage());
        }
        return System.currentTimeMillis() - start;
    }
}
