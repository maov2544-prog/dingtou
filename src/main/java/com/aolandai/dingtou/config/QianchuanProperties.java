package com.aolandai.dingtou.config;

import com.aolandai.dingtou.client.TokenEndpoint;
import com.bytedance.ads.model.QianchuanUniPromotionListV10AdlabScene;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * 千川 / 随心推 接入配置（前缀 {@code dingtou.qianchuan}）。
 *
 * <pre>
 * dingtou:
 *   qianchuan:
 *     api-base-url: https://api.oceanengine.com
 *     token-url: https://aocilenda.cn/qianchuan/callback
 *     bearer-env-var: QIANCHUAN_SERVICE_BEARER_TOKEN
 *     qianchuan-state: shop_qianchuan     # 千川 PC 版
 *     suixintui-state: shop_suixintui     # 随心推
 *     token-ttl-minutes: 30
 *     request-interval-millis: 500        # 起始间隔（自适应会自己翻倍）
 *     max-request-interval-millis: 8000   # 自适应上限（实测 3000 仍被频控）
 *     rate-limit-retries: 5               # 被频控后重试次数（退避 5/10/20/40/60s）
 *     rate-limit-budget-millis: 300000    # 【单次调用】最大等待，用尽即中止
 *     rate-limit-initial-backoff-millis: 5000   # 首次退避（之后翻倍）
 *     rate-limit-max-backoff-millis: 60000      # 退避上限
 *     rate-limit-peak-avoidance: true           # 整点/半点前后规避
 *     max-pages: 100                      # 计划列表翻页上限（太小会漏数据）
 *     query-scenes: UNI_PROJECT,OVERALL_PROJECT  # 查哪些场景（不能为空）
 *     plan-aweme-max-items: 200           # planAwemeLookup 单批上限
 * </pre>
 *
 * <p>⚠️ <b>两套 token 同源不同 state</b>：自建服务端是同一个地址、用 {@code state} 区分。
 * 拿错一套的表现是「一直鉴权失败」，所以这里把两个 state 显式配置出来、并在换取时校验返回的 state。</p>
 */
@Validated
@ConfigurationProperties(prefix = "dingtou.qianchuan")
public class QianchuanProperties {

    /** 千川 OpenAPI 基地址。 */
    @NotBlank
    private String apiBaseUrl = "https://api.oceanengine.com";

    /** 换取 token 的自建服务端地址（不含 state 参数，由 {@link TokenEndpoint#url()} 拼）。 */
    @NotBlank
    private String tokenUrl = "https://aocilenda.cn/qianchuan/callback";

    /**
     * 存放换取凭据的环境变量名。
     *
     * <p>⚠️ 必须是 <b>Windows 系统级</b>环境变量 —— 服务以 LocalSystem 运行时读不到用户级变量。
     * 值只有 8 字符是正常的：它是换取真 token 的凭据，不是残缺值。</p>
     */
    @NotBlank
    private String bearerEnvVar = "QIANCHUAN_SERVICE_BEARER_TOKEN";

    /** 千川 PC 版 token 的 state。 */
    @NotBlank
    private String qianchuanState = "shop_qianchuan";

    /** 随心推 token 的 state。 */
    @NotBlank
    private String suixintuiState = "shop_suixintui";

    /** token 缓存时长（分钟）。 */
    @Positive
    private int tokenTtlMinutes = 30;

    /** 换取 token 的连接超时（秒）。 */
    @Positive
    private int connectTimeoutSeconds = 15;

    /** 换取 token 的读超时（秒）。 */
    @Positive
    private int readTimeoutSeconds = 30;

    /**
     * <b>相邻两次对外请求之间的间隔</b>（毫秒）—— 千川计划列表 / 计划详情 / 随心推订单
     * <b>共用这一个开关</b>。
     *
     * <p>⚠️ 这是「<b>通畅时</b>的起始速度」，不是「保证不触发频控的固定值」——
     * {@code 40100} 是<b>全局动态阈值</b>，不存在固定安全值。拥堵时
     * {@link com.aolandai.dingtou.client.QianchuanThrottle} 会自动把间隔翻倍。</p>
     */
    @PositiveOrZero
    private long requestIntervalMillis = 500L;

    /**
     * 自适应降速的<b>间隔上限</b>（毫秒）。
     *
     * <p>被频控后间隔会翻倍，但不会无限涨 —— 否则一次踩雷会把整批拖到不可用。</p>
     *
     * <p>⚠️ 默认 <b>8000</b> 是实测调出来的：2026-10-04 实跑时，间隔涨到 3000ms
     * <b>仍在报 40100</b>（千川计划列表接口），说明 3 秒对那个接口不够慢。
     * 上限只在<b>被频控后</b>才会触及，平峰期不影响速度。</p>
     */
    @Positive
    private long maxRequestIntervalMillis = 8000L;

    /**
     * 被频控（40100/40110/40130）后，单次请求最多重试几次。
     *
     * <p>默认 5 → 退避约 5/10/20/40/60s（合计约 135s）。实测出现过「3/3 次重试用满」
     * 才勉强通过的情况，所以留出更多余量。</p>
     */
    @PositiveOrZero
    private int rateLimitRetries = 5;

    /**
     * 单次批量任务的<b>总等待预算</b>（毫秒），默认 300 秒。
     *
     * <p>用尽仍被拦住 → 抛异常中止，<b>不返回残缺结果</b>（残缺结果会被误当成
     * 「这就是全部达标计划」，进而漏掉达人）。</p>
     *
     * <p>取值需 ≥ 「重试次数 × 最大退避」：5 次重试最多等 135s，300s 留了一倍余量。</p>
     */
    @Positive
    private long rateLimitBudgetMillis = 300_000L;

    /**
     * 查询千川计划时向前回溯的天数。
     *
     * <p>⚠️ 查计划接口的 {@code startTime} 是<b>必传</b>参数，而且回溯太短会漏掉老计划
     * → 误判「没有计划」→ 重复建计划（花钱）。所以默认给足一年。</p>
     */
    @Positive
    private int planQueryLookbackDays = 365;

    /**
     * 「UID → 抖音号」反查结果的缓存时长（秒）。
     *
     * <p>用途：乘方计划详情接口的 {@code unique_id}（抖音号）有时为空，用 {@code aweme_uid}
     * 去【抖音号授权列表】反查。抖音号基本不变，但达人可能自己改、授权关系也可能变，
     * 所以给个 TTL（默认 1 小时）。</p>
     */
    @Positive
    private int awemeShowIdCacheSeconds = 3600;

    /**
     * 千川计划列表的<b>翻页上限</b>（单账户单场景）。
     *
     * <p>⚠️ 太小会<b>静默漏数据</b>：实测某账户单个场景有 <b>26 页</b>（2592 个计划），
     * 而旧上限 20 直接漏掉 23% —— 连带漏掉达标计划和达人。
     * 默认 100 页（= 10000 个计划）；配合「按消耗降序 + 低于阈值即停」，正常只翻 1~2 页。</p>
     */
    @Positive
    private int maxPages = 100;

    /**
     * 查询千川计划时要查的<b>场景</b>列表。
     *
     * <p>默认全域（{@code UNI_PROJECT}）+ 乘方（{@code OVERALL_PROJECT}）都查 ——
     * 本系统建的是<b>乘方</b>计划，只查全域会漏掉自己建的全部计划。</p>
     *
     * <p>⚠️ <b>不能配空</b>：空 = 一个场景都不查 = 永远查不到计划，而且不报错。</p>
     */
    @NotEmpty
    private List<QianchuanUniPromotionListV10AdlabScene> queryScenes = List.of(
            QianchuanUniPromotionListV10AdlabScene.UNI_PROJECT,
            QianchuanUniPromotionListV10AdlabScene.OVERALL_PROJECT);

    /**
     * {@code planAwemeLookup} 单批最多查几个计划。
     *
     * <p>详情接口是「<b>一计划一次请求</b>」，不能批量 —— 不限批会让影刀一次传上千条
     * 把服务拖很久，且必然触发频控。</p>
     */
    @Positive
    private int planAwemeMaxItems = 200;

    /**
     * 被频控后<b>首次</b>退避的毫秒数（之后按 2 倍指数增长，封顶见
     * {@link #rateLimitMaxBackoffMillis}）。
     *
     * <p>⚠️ 线性 3s/6s/9s 对「全局堵车」太短 —— 3 秒后大概率还堵。</p>
     */
    @Positive
    private long rateLimitInitialBackoffMillis = 5_000L;

    /** 退避的<b>上限</b>毫秒（首次 5s → 10s → 20s → 40s → 封顶 60s）。 */
    @Positive
    private long rateLimitMaxBackoffMillis = 60_000L;

    /**
     * 是否启用<b>峰值规避</b>。
     *
     * <p>开启时：若重试时刻落在整点/半点前 90 秒内，则等到「过峰 +30 秒」再重试 ——
     * 峰期阈值收紧，硬撞只会白白消耗重试次数。</p>
     *
     * <p>关掉可让重试行为更可预测（不因时间点而变），但峰期更容易耗光重试次数。</p>
     */
    private boolean rateLimitPeakAvoidance = true;

    /** 千川侧端点。 */
    public TokenEndpoint qianchuanEndpoint() {
        return new TokenEndpoint(tokenUrl, qianchuanState, bearerEnvVar,
                tokenTtlMinutes, connectTimeoutSeconds, readTimeoutSeconds);
    }

    /** 随心推侧端点。 */
    public TokenEndpoint suixintuiEndpoint() {
        return new TokenEndpoint(tokenUrl, suixintuiState, bearerEnvVar,
                tokenTtlMinutes, connectTimeoutSeconds, readTimeoutSeconds);
    }

    @AssertTrue(message = "dingtou.qianchuan 的两个 state 不能相同（千川与随心推必须是两套 token）")
    public boolean isStatesDistinct() {
        return qianchuanState != null && !qianchuanState.equals(suixintuiState);
    }

    /** 启动横幅用。⚠️ 只打印 state 与凭据变量名，绝不打印凭据本身。 */
    public String describe() {
        return "api=" + apiBaseUrl
                + "，千川 state=" + qianchuanState
                + "，随心推 state=" + suixintuiState
                + "，凭据变量=" + bearerEnvVar
                + "，token 缓存=" + tokenTtlMinutes + " 分钟"
                + "，请求间隔=" + requestIntervalMillis + "ms（自适应上限 " + maxRequestIntervalMillis
                + "ms，频控重试 " + rateLimitRetries + " 次，预算 " + (rateLimitBudgetMillis / 1000) + "s）"
                + "，查询场景=" + queryScenes
                + "，翻页上限=" + maxPages;
    }

    // ==================== getter / setter ====================

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    public String getTokenUrl() {
        return tokenUrl;
    }

    public void setTokenUrl(String tokenUrl) {
        this.tokenUrl = tokenUrl;
    }

    public String getBearerEnvVar() {
        return bearerEnvVar;
    }

    public void setBearerEnvVar(String bearerEnvVar) {
        this.bearerEnvVar = bearerEnvVar;
    }

    public String getQianchuanState() {
        return qianchuanState;
    }

    public void setQianchuanState(String qianchuanState) {
        this.qianchuanState = qianchuanState;
    }

    public String getSuixintuiState() {
        return suixintuiState;
    }

    public void setSuixintuiState(String suixintuiState) {
        this.suixintuiState = suixintuiState;
    }

    public int getTokenTtlMinutes() {
        return tokenTtlMinutes;
    }

    public void setTokenTtlMinutes(int tokenTtlMinutes) {
        this.tokenTtlMinutes = tokenTtlMinutes;
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public int getReadTimeoutSeconds() {
        return readTimeoutSeconds;
    }

    public void setReadTimeoutSeconds(int readTimeoutSeconds) {
        this.readTimeoutSeconds = readTimeoutSeconds;
    }

    public long getRequestIntervalMillis() {
        return requestIntervalMillis;
    }

    public void setRequestIntervalMillis(long requestIntervalMillis) {
        this.requestIntervalMillis = requestIntervalMillis;
    }

    public long getMaxRequestIntervalMillis() {
        return maxRequestIntervalMillis;
    }

    public void setMaxRequestIntervalMillis(long maxRequestIntervalMillis) {
        this.maxRequestIntervalMillis = maxRequestIntervalMillis;
    }

    public int getRateLimitRetries() {
        return rateLimitRetries;
    }

    public void setRateLimitRetries(int rateLimitRetries) {
        this.rateLimitRetries = rateLimitRetries;
    }

    public long getRateLimitBudgetMillis() {
        return rateLimitBudgetMillis;
    }

    public void setRateLimitBudgetMillis(long rateLimitBudgetMillis) {
        this.rateLimitBudgetMillis = rateLimitBudgetMillis;
    }

    public int getMaxPages() {
        return maxPages;
    }

    public void setMaxPages(int maxPages) {
        this.maxPages = maxPages;
    }

    public List<QianchuanUniPromotionListV10AdlabScene> getQueryScenes() {
        return queryScenes;
    }

    public void setQueryScenes(List<QianchuanUniPromotionListV10AdlabScene> queryScenes) {
        this.queryScenes = queryScenes;
    }

    public int getPlanAwemeMaxItems() {
        return planAwemeMaxItems;
    }

    public void setPlanAwemeMaxItems(int planAwemeMaxItems) {
        this.planAwemeMaxItems = planAwemeMaxItems;
    }

    public long getRateLimitInitialBackoffMillis() {
        return rateLimitInitialBackoffMillis;
    }

    public void setRateLimitInitialBackoffMillis(long rateLimitInitialBackoffMillis) {
        this.rateLimitInitialBackoffMillis = rateLimitInitialBackoffMillis;
    }

    public long getRateLimitMaxBackoffMillis() {
        return rateLimitMaxBackoffMillis;
    }

    public void setRateLimitMaxBackoffMillis(long rateLimitMaxBackoffMillis) {
        this.rateLimitMaxBackoffMillis = rateLimitMaxBackoffMillis;
    }

    public boolean isRateLimitPeakAvoidance() {
        return rateLimitPeakAvoidance;
    }

    public void setRateLimitPeakAvoidance(boolean rateLimitPeakAvoidance) {
        this.rateLimitPeakAvoidance = rateLimitPeakAvoidance;
    }

    public int getPlanQueryLookbackDays() {
        return planQueryLookbackDays;
    }

    public void setPlanQueryLookbackDays(int planQueryLookbackDays) {
        this.planQueryLookbackDays = planQueryLookbackDays;
    }

    public int getAwemeShowIdCacheSeconds() {
        return awemeShowIdCacheSeconds;
    }

    public void setAwemeShowIdCacheSeconds(int awemeShowIdCacheSeconds) {
        this.awemeShowIdCacheSeconds = awemeShowIdCacheSeconds;
    }
}
