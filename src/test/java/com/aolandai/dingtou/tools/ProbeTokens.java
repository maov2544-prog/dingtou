package com.aolandai.dingtou.tools;

import com.aolandai.dingtou.client.AccessTokenProvider;
import com.aolandai.dingtou.client.HttpTokenProvider;
import com.aolandai.dingtou.config.QianchuanProperties;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>探针专用的 Token 入口</b>（只给 {@code src/test/java/tools/} 下的手工探针用）。
 *
 * <h2>为什么要有它</h2>
 * <p>原来探针直接调 {@code client/QianchuanTokenClient.getAccessToken()} ——
 * 那是**早期硬编码的静态实现**，生产代码已经改用
 * {@link HttpTokenProvider} + {@code TokenEndpoint}（可配置、带 TTL、带环境变量）。</p>
 *
 * <p>两套 token 实现长期并存会出事（以后改 token 逻辑容易漏改一套），所以把旧的两个静态类删掉，
 * 给探针留这一个入口 —— 它内部走的是**和生产同一套** {@link HttpTokenProvider}。</p>
 *
 * <h2>⚠️ 不启动 Spring 也能用</h2>
 * <p>{@link QianchuanProperties} 有完整默认值，直接 {@code new} 就能拿到端点配置 ——
 * 所以「纯静态探针」（不起 Spring 的那些）也能用。</p>
 *
 * <p>⚠️ 手工工具，不进自动化测试（surefire 已排除 {@code **&#47;tools/**}）。</p>
 */
public final class ProbeTokens {

    /** 按 state 缓存 provider（provider 自己带 token 缓存，这里只是别重复 new）。 */
    private static final Map<String, AccessTokenProvider> PROVIDERS = new ConcurrentHashMap<>();

    private ProbeTokens() {
    }

    /** 千川 PC 侧 token（{@code state=shop_qianchuan}）。 */
    public static String qianchuan() throws Exception {
        return provider("qianchuan", true).token();
    }

    /** 随心推侧 token（{@code state=shop_suixintui}）。 */
    public static String suixintui() throws Exception {
        return provider("suixintui", false).token();
    }

    /** 清掉缓存（token 失效时调）。 */
    public static void invalidateAll() {
        PROVIDERS.values().forEach(AccessTokenProvider::invalidate);
    }

    private static AccessTokenProvider provider(String key, boolean qianchuanSide) {
        return PROVIDERS.computeIfAbsent(key, k -> {
            QianchuanProperties props = new QianchuanProperties();
            return new HttpTokenProvider(qianchuanSide
                    ? props.qianchuanEndpoint() : props.suixintuiEndpoint());
        });
    }
}
