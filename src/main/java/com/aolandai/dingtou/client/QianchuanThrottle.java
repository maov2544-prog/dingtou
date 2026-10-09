package com.aolandai.dingtou.client;

import com.aolandai.dingtou.exception.RateLimitException;
import com.bytedance.ads.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalTime;
import java.util.Random;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 千川请求限流器：<b>自适应节流 + 智能重试</b>。
 *
 * <h2>为什么不是「设一个固定间隔就完事」</h2>
 * <p>{@code 40100} 是<b>接口全局总频控</b>（所有开发者共享），由平台<b>动态调整</b> ——
 * 工作日投放峰期（整点 / 半点）阈值收紧，平峰期宽松。所以<b>不存在「固定间隔 100% 不触发」</b>，
 * 只有经验稳妥值。正确做法是「<b>通畅时快、拥堵时自动降速</b>」。</p>
 *
 * <h2>四个机制</h2>
 * <ol>
 *   <li><b>抖动（Jitter）</b>：实际等待 = 基准 × (1 ± 25%)。没有抖动时，多个任务/实例
 *       会<b>同步重试形成脉冲</b>，反而更容易被判定为超限；</li>
 *   <li><b>自适应降速（AIMD）</b>：一次 {@code 40100} → 本批后续请求间隔 <b>×2</b>（封顶）；
 *       连续若干次成功后缓慢 <b>×0.8</b> 恢复。不用人去猜阈值；</li>
 *   <li><b>指数退避</b>：重试等待 {@code 5s → 10s → 20s}（封顶 60s）。线性 3s/6s/9s
 *       对「全局堵车」太短 —— 3 秒后大概率还堵；</li>
 *   <li><b>峰值规避</b>：若重试时刻落在<b>整点/半点前 90 秒内</b>，等到「过峰 +30s」。
 *       峰期阈值收紧，硬撞只会白白消耗重试次数。</li>
 * </ol>
 *
 * <h2>⚠️ 生命周期 = 「一次任务」，且必须<b>跨账户</b>共享</h2>
 * <p>自适应间隔会随本任务的拥堵情况变化，所以：</p>
 * <ul>
 *   <li><b>一次任务 new 一个</b>（见 {@code QianchuanThrottleFactory#newThrottle()}），
 *       不能做成 Spring 单例 —— 否则两个并发任务会互相干扰对方的间隔；</li>
 *   <li>⚠️ <b>但同一个任务内必须共用一个</b>：{@code 40100} 是<b>接口全局总频控</b>
 *       （所有开发者共享），与「哪个账户」无关。曾经按账户各 new 一个，
 *       导致账户 1 学到「该降速了」、账户 2 又从起始间隔猛打 ——
 *       实测直接被频控打到<b>整批失败</b>。</li>
 * </ul>
 *
 * <h2>⚠️ 预算耗尽时「中止」而不是「返回残缺结果」</h2>
 * <p>本次调用的等待预算用尽仍被拦住 → 抛 {@link RateLimitException}。
 * 返回残缺结果会被下游误当成「这就是全部达标计划」，进而漏掉达人。</p>
 *
 * <h2>🔴 预算为什么【按次】算，而不是整批共享（2026-10-05 改）</h2>
 * <p>原实现把「累计等待」记在实例字段上 = <b>整个任务共享一个预算</b>。单任务时没问题，
 * 但<b>批量任务下会互相饿死</b>：一个组合被 40100 连续拦 5 次就能吃掉 137 秒
 * （实测退避 3→4→8→20→36→64s），10 个组合的批次会在第 2~3 个就把 300s 预算耗光 → 整批失败。</p>
 *
 * <p>现在改成：<b>间隔（{@link #currentIntervalMillis}）仍然跨调用共享</b>
 * （40100 是全局频控，学到的节奏对后续请求同样有效），
 * 但<b>等待预算按每次 {@link #execute} 独立计算</b> ——
 * 一个请求等多久，不该决定别的请求能不能等。</p>
 *
 * <p>⚠️ 代价：不再有「整批总时长上限」。批量任务的时长由<b>任务级超时</b>管
 * （异步化之后由任务状态机负责），不在这里管 —— 这里的职责只有一个：
 * <b>这一次调用最多等多久</b>。</p>
 */
public final class QianchuanThrottle {

    private static final Logger log = LoggerFactory.getLogger(QianchuanThrottle.class);

    /** 抖动比例：实际等待 = 基准 × (1 ± 25%)。 */
    private static final double JITTER_RATIO = 0.25;

    /** 连续成功多少次后尝试恢复速度。 */
    private static final int RECOVERY_STREAK = 10;

    /** 恢复时把间隔缩小到的比例。 */
    private static final double RECOVERY_FACTOR = 0.8;

    /** 距整点/半点多少秒内算「临近峰值」。 */
    private static final int PEAK_LOOKAHEAD_SECONDS = 90;

    /** 过峰后再等多少秒才重试。 */
    private static final int PEAK_CLEAR_SECONDS = 30;

    /** 半个峰期（秒）：整点与半点各一次，即每 1800 秒一个峰。 */
    private static final int HALF_PEAK_SECONDS = 1800;

    /** 一次「可能被频控」的调用。 */
    @FunctionalInterface
    public interface Attempt<T> {
        T run() throws ApiException;
    }

    /** 睡眠实现；抽出来是为了单测能注入「不真睡」。 */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis);
    }

    /** 生产用睡眠：被打断时恢复中断标志，不吞掉。 */
    private static final Sleeper REAL_SLEEPER = millis -> {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    };

    /**
     * 限流参数（来自配置 {@code dingtou.qianchuan.*}）。
     *
     * <p>用一个参数对象而不是 7 个位置参数：全是 {@code long} 的位置参数
     * <b>极易传错顺序</b>，而传错「退避」和「预算」不会编译报错，只会在线上表现为
     * 「莫名其妙等很久」或「很快就放弃」。</p>
     *
     * @param baseIntervalMillis      通畅时的起始请求间隔
     * @param maxIntervalMillis       自适应降速的间隔上限
     * @param maxRetries              被频控后最多重试几次
     * @param totalBudgetMillis       <b>单次调用</b>的最大等待（不是整批）—— 见类注释「预算为什么按次算」
     * @param initialBackoffMillis    首次退避（之后翻倍）
     * @param maxBackoffMillis        退避上限
     * @param peakAvoidance           是否启用峰值规避（整点/半点前后）
     */
    public record Settings(
            long baseIntervalMillis,
            long maxIntervalMillis,
            int maxRetries,
            long totalBudgetMillis,
            long initialBackoffMillis,
            long maxBackoffMillis,
            boolean peakAvoidance) {
    }

    private final Settings settings;
    private final Sleeper sleeper;
    private final Supplier<LocalTime> clock;
    private final Random random;

    /** 当前实际使用的间隔（会随拥堵自适应）。 */
    private long currentIntervalMillis;

    /** 连续成功次数，用于「缓慢恢复速度」。 */
    private int successStreak;

    /**
     * <b>上一次</b> {@link #execute} 调用累计等了多少毫秒 —— 仅供日志/探针观察。
     *
     * <p>⚠️ 注意它是「按次」的，不是「整批累计」。原因见类注释「预算为什么按次算」。</p>
     */
    private long lastCallWaitMillis;

    /** 本批是否还没发过请求（第一次不必等间隔）。 */
    private boolean firstCall = true;

    /** 生产构造。 */
    public QianchuanThrottle(Settings settings) {
        this(settings, REAL_SLEEPER, LocalTime::now, new Random());
    }

    /** 全参数构造（测试可注入 sleeper / clock / random）。 */
    QianchuanThrottle(Settings settings, Sleeper sleeper, Supplier<LocalTime> clock, Random random) {
        validate(settings);
        this.settings = settings;
        this.sleeper = sleeper;
        this.clock = clock;
        this.random = random;
        this.currentIntervalMillis = settings.baseIntervalMillis();
    }

    /** 启动即校验：参数自相矛盾要在启动时炸，而不是等到跑批量任务时才表现怪异。 */
    private static void validate(Settings s) {
        if (s.baseIntervalMillis() < 0 || s.maxIntervalMillis() < 0 || s.totalBudgetMillis() < 0
                || s.initialBackoffMillis() < 0 || s.maxBackoffMillis() < 0) {
            throw new IllegalArgumentException("间隔 / 预算 / 退避都不能为负: " + s);
        }
        if (s.maxIntervalMillis() < s.baseIntervalMillis()) {
            throw new IllegalArgumentException(
                    "maxIntervalMillis 不能小于 baseIntervalMillis: " + s);
        }
        if (s.maxBackoffMillis() < s.initialBackoffMillis()) {
            throw new IllegalArgumentException(
                    "maxBackoffMillis 不能小于 initialBackoffMillis: " + s);
        }
        if (s.maxRetries() < 0) {
            throw new IllegalArgumentException("maxRetries 不能为负: " + s);
        }
    }

    /**
     * 测试用：<b>不真正等待</b>、<b>关闭峰值规避</b>（避免单测受真实时钟影响而随机失败），
     * 但保留重试与退避的判定逻辑。
     */
    public static QianchuanThrottle forTest() {
        return forTest(0L, 0L, 3, Long.MAX_VALUE);
    }

    /**
     * 测试用：自定义间隔与重试参数，同样<b>不真正等待</b>、关闭峰值规避。
     *
     * <p>用于验证「自适应间隔怎么变」这类需要非零基准的场景
     * （基准为 0 时看不出翻倍效果）。</p>
     */
    public static QianchuanThrottle forTest(long baseIntervalMillis, long maxIntervalMillis,
                                            int maxRetries, long totalBudgetMillis) {
        return new QianchuanThrottle(
                new Settings(baseIntervalMillis, maxIntervalMillis, maxRetries, totalBudgetMillis,
                        5_000L, 60_000L, false),
                millis -> { }, () -> LocalTime.of(10, 15, 0), new Random(0));
    }

    /**
     * 执行一次调用，内部负责「等待 → 重试 → 退避」。
     *
     * @param attempt 真正的调用
     * @param codeOf  从返回值里取业务错误码（如 {@code Response::getCode}）；
     *                取到 40100/40110/40130 就认为是频控
     * @param context 调用场景描述，只用于错误信息与日志（如「千川计划列表(账户 X)」）
     * @return 成功时的返回值
     * @throws RateLimitException 重试次数或等待预算用尽
     */
    public <T> T execute(Attempt<T> attempt, Function<T, Long> codeOf, String context)
            throws ApiException {
        if (!firstCall) {
            sleepQuietly(jitter(currentIntervalMillis));
        }
        firstCall = false;

        // ⚠️ 预算【按次】算（局部变量），不是整批共享（见类注释「预算为什么按次算」）
        long waitedThisCall = 0L;

        for (int retry = 0; ; retry++) {
            T result = attempt.run();
            Long code = codeOf.apply(result);
            if (!ApiErrors.isRateLimit(code)) {
                onSuccess();
                lastCallWaitMillis = waitedThisCall;
                return result;
            }

            successStreak = 0;
            if (retry >= settings.maxRetries()) {
                lastCallWaitMillis = waitedThisCall;
                throw exhausted(code, context, retry, "重试次数已用尽", waitedThisCall);
            }
            long wait = backoffMillis(retry);
            if (waitedThisCall + wait > settings.totalBudgetMillis()) {
                lastCallWaitMillis = waitedThisCall;
                throw exhausted(code, context, retry, "本次调用的等待预算已用尽",
                        waitedThisCall);
            }
            // ⚠️ 必须记日志：否则「中间重试了几次、每次等多久」完全看不见，
            //    线上只会看到最后那条「重试用尽」，无法判断是峰期还是间隔设小了
            log.warn("被频控 code={}，第 {}/{} 次重试前等待 {}s（当前请求间隔 {}ms，累计已等 {}s）：{}",
                    code, retry + 1, settings.maxRetries(), wait / 1000,
                    currentIntervalMillis, waitedThisCall / 1000, context);
            sleepQuietly(wait);
            waitedThisCall += wait;
            lastCallWaitMillis = waitedThisCall;

            // 自适应降速：本批后续请求也一起变慢，避免刚退避完又踩上去
            currentIntervalMillis = Math.min(
                    Math.max(currentIntervalMillis * 2, settings.baseIntervalMillis()),
                    settings.maxIntervalMillis());
        }
    }

    /** 当前实际间隔（毫秒）—— 供日志/探针观察自适应效果。 */
    public long currentIntervalMillis() {
        return currentIntervalMillis;
    }

    /** <b>上一次调用</b>累计等待时长（毫秒）—— 供日志/探针观察频控代价。 */
    public long lastCallWaitMillis() {
        return lastCallWaitMillis;
    }

    // ==================== 内部 ====================

    /** 重试等待：指数退避 × 抖动，并与「峰值规避」取较大者。 */
    private long backoffMillis(int retry) {
        long base = Math.min(settings.initialBackoffMillis() << Math.min(retry, 16),
                settings.maxBackoffMillis());
        return Math.max(jitter(base), millisUntilPastPeak());
    }

    /**
     * 若「现在」正踩在峰值上或临近下一个峰值，返回需要等待的毫秒数；否则返回 0。
     *
     * <p>峰 = 每小时的 0 分与 30 分（{@code HALF_PEAK_SECONDS}）。</p>
     */
    private long millisUntilPastPeak() {
        if (!settings.peakAvoidance()) {
            return 0L;
        }
        LocalTime now = clock.get();
        int secondsIntoHour = now.getMinute() * 60 + now.getSecond();
        int intoHalfPeak = secondsIntoHour % HALF_PEAK_SECONDS;

        if (intoHalfPeak == 0) {
            // 正踩在峰值上
            return PEAK_CLEAR_SECONDS * 1000L;
        }
        int secondsToNextPeak = HALF_PEAK_SECONDS - intoHalfPeak;
        if (secondsToNextPeak > PEAK_LOOKAHEAD_SECONDS) {
            return 0L;
        }
        return (secondsToNextPeak + PEAK_CLEAR_SECONDS) * 1000L;
    }

    /** 加抖动：{@code base × (1 ± JITTER_RATIO)}。 */
    private long jitter(long baseMillis) {
        if (baseMillis <= 0L) {
            return 0L;
        }
        double factor = 1.0 + (random.nextDouble() * 2 - 1) * JITTER_RATIO;
        return Math.max(0L, Math.round(baseMillis * factor));
    }

    /** 连续成功后缓慢恢复速度（有下限，不会低于起始间隔）。 */
    private void onSuccess() {
        if (++successStreak < RECOVERY_STREAK) {
            return;
        }
        successStreak = 0;
        if (currentIntervalMillis > settings.baseIntervalMillis()) {
            currentIntervalMillis = Math.max(settings.baseIntervalMillis(),
                    (long) (currentIntervalMillis * RECOVERY_FACTOR));
        }
    }

    private void sleepQuietly(long millis) {
        if (millis > 0L) {
            sleeper.sleep(millis);
        }
    }

    /**
     * 构造「重试/预算用尽」的异常。
     *
     * <p>信息里必须带上<b>已重试几次、等了多久、当前间隔</b> —— 否则运维只看到
     * 「被频控」，无法判断是「撞上峰期」还是「间隔设得太小」。</p>
     */
    private RateLimitException exhausted(Long code, String context, int retried, String reason,
                                        long waitedThisCall) {
        RateLimitException original = ApiErrors.rateLimitOrNull(code, context, null);
        String detail = context + " 被频控拦截，" + reason
                + "（已重试 " + retried + " 次，本次累计等待 " + (waitedThisCall / 1000) + "s，"
                + "当前请求间隔 " + currentIntervalMillis + "ms）。"
                + "建议错峰重试（避开整点 / 半点）。";
        if (original != null) {
            return new RateLimitException(original.scope(), detail, original.suggestedBackoffMillis());
        }
        return new RateLimitException(RateLimitException.Scope.GLOBAL, detail,
                ApiErrors.GLOBAL_THROTTLE_BACKOFF_MILLIS);
    }
}
