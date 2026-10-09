package com.aolandai.dingtou.client;

import com.aolandai.dingtou.exception.RateLimitException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link QianchuanThrottle} 单测：<b>不真睡、不调接口</b> —— 注入假的 sleeper / 固定时钟 / 固定随机种子，
 * 断言「等了多久」和「间隔怎么变」。
 */
class QianchuanThrottleTest {

    /** 非峰值时刻（10:15），避免峰值规避干扰普通用例。 */
    private static final LocalTime OFF_PEAK = LocalTime.of(10, 15, 0);

    /** 记录每次等待的毫秒数。 */
    private final List<Long> sleeps = new ArrayList<>();

    private QianchuanThrottle newThrottle(long base, long max, int retries, long budget,
                                          LocalTime at) {
        return new QianchuanThrottle(
                new QianchuanThrottle.Settings(base, max, retries, budget, 5_000L, 60_000L,
                        true), // 峰值规避开着，才能测「峰值规避」那几个用例
                sleeps::add, () -> at, new Random(42));
    }

    // ==================== 基础节流 ====================

    @Test
    @DisplayName("第一次请求不等待；第二次起按间隔等待（含 ±25% 抖动）")
    void firstCallNoWaitThenInterval() throws Exception {
        QianchuanThrottle throttle = newThrottle(500, 3000, 3, 180_000, OFF_PEAK);

        throttle.execute(() -> new FakeResponse(0L), FakeResponse::code, "ctx");
        assertTrue(sleeps.isEmpty(), "第一次不该等待");

        throttle.execute(() -> new FakeResponse(0L), FakeResponse::code, "ctx");
        assertEquals(1, sleeps.size());
        assertInJitterRange(sleeps.get(0), 500, "第二次应等 500ms ±25%");
    }

    @Test
    @DisplayName("非频控错误码（如 40002）不重试、不等待 —— 那是业务错误，重试无意义")
    void businessErrorIsNotRetried() throws Exception {
        QianchuanThrottle throttle = newThrottle(500, 3000, 3, 180_000, OFF_PEAK);
        AtomicInteger calls = new AtomicInteger();

        FakeResponse result = throttle.execute(
                () -> {
                    calls.incrementAndGet();
                    return new FakeResponse(40002L);
                },
                FakeResponse::code, "ctx");

        assertEquals(1, calls.get(), "业务错误不该重试");
        assertTrue(sleeps.isEmpty(), "业务错误不该等待");
        assertEquals(40002L, result.code());
    }

    // ==================== 40100 重试 ====================

    @Test
    @DisplayName("40100 → 退避重试（首次约 5s），成功后返回")
    void retriesWithBackoffOnRateLimit() throws Exception {
        QianchuanThrottle throttle = newThrottle(0, 0, 3, 180_000, OFF_PEAK);
        AtomicInteger calls = new AtomicInteger();

        FakeResponse result = throttle.execute(
                () -> new FakeResponse(calls.incrementAndGet() == 1 ? 40100L : 0L),
                FakeResponse::code, "ctx");

        assertEquals(2, calls.get());
        assertEquals(0L, result.code());
        assertEquals(1, sleeps.size());
        assertInJitterRange(sleeps.get(0), 5_000, "首次退避应约 5s");
    }

    @Test
    @DisplayName("退避是指数增长：5s → 10s → 20s（不是线性 3/6/9）")
    void backoffGrowsExponentially() {
        QianchuanThrottle throttle = newThrottle(0, 0, 3, 600_000, OFF_PEAK);
        assertThrows(RateLimitException.class, () -> throttle.execute(
                () -> new FakeResponse(40100L), FakeResponse::code, "ctx"));

        assertEquals(3, sleeps.size(), "重试 3 次 → 等待 3 次");
        assertInJitterRange(sleeps.get(0), 5_000, "第 1 次退避 5s");
        assertInJitterRange(sleeps.get(1), 10_000, "第 2 次退避 10s");
        assertInJitterRange(sleeps.get(2), 20_000, "第 3 次退避 20s");
    }

    @Test
    @DisplayName("⚠️ 重试次数用尽 → 抛 RateLimitException，且信息里带「重试几次/等了多久」")
    void exhaustedRetriesThrows() {
        QianchuanThrottle throttle = newThrottle(0, 0, 2, 600_000, OFF_PEAK);

        RateLimitException e = assertThrows(RateLimitException.class, () -> throttle.execute(
                () -> new FakeResponse(40100L), FakeResponse::code, "千川计划列表(账户 1)"));

        assertTrue(e.getMessage().contains("重试次数已用尽"), e.getMessage());
        assertTrue(e.getMessage().contains("已重试 2 次"), "必须能看出重试了几次：" + e.getMessage());
        assertTrue(e.getMessage().contains("建议错峰"), "必须给出可执行的建议：" + e.getMessage());
    }

    @Test
    @DisplayName("⚠️ 本次调用的等待预算用尽 → 抛 RateLimitException（中止，不返回残缺结果）")
    void exhaustedBudgetThrows() {
        // 预算 1s，而首次退避就约 5s → 一次都不该等，直接判定预算不够
        QianchuanThrottle throttle = newThrottle(0, 0, 3, 1_000, OFF_PEAK);

        RateLimitException e = assertThrows(RateLimitException.class, () -> throttle.execute(
                () -> new FakeResponse(40100L), FakeResponse::code, "ctx"));

        assertTrue(e.getMessage().contains("等待预算已用尽"), e.getMessage());
        assertTrue(sleeps.isEmpty(), "明知会超预算就不该先睡 —— 否则白等一轮");
    }

    @Test
    @DisplayName("40110 / 40130 同样按频控处理（不只是 40100）")
    void otherRateLimitCodesAreHandled() throws Exception {
        QianchuanThrottle throttle = newThrottle(0, 0, 3, 180_000, OFF_PEAK);
        AtomicInteger calls = new AtomicInteger();

        throttle.execute(() -> new FakeResponse(calls.incrementAndGet() == 1 ? 40110L : 0L),
                FakeResponse::code, "ctx");

        assertEquals(2, calls.get(), "40110 也应触发重试");
    }

    // ==================== 自适应 ====================

    @Test
    @DisplayName("★ 自适应降速：被频控后本批间隔 ×2")
    void intervalDoublesAfterRateLimit() throws Exception {
        QianchuanThrottle throttle = newThrottle(500, 3000, 3, 180_000, OFF_PEAK);
        assertEquals(500, throttle.currentIntervalMillis());

        AtomicInteger calls = new AtomicInteger();
        throttle.execute(() -> new FakeResponse(calls.incrementAndGet() == 1 ? 40100L : 0L),
                FakeResponse::code, "ctx");

        assertEquals(1000, throttle.currentIntervalMillis(), "被频控一次后间隔应翻倍");
    }

    @Test
    @DisplayName("自适应间隔有上限，不会无限涨")
    void intervalIsCapped() throws Exception {
        QianchuanThrottle throttle = newThrottle(500, 1000, 5, 600_000, OFF_PEAK);

        // 前两次被频控、第三次成功 → 500→1000→（想翻到 2000 但封顶 1000）
        AtomicInteger calls = new AtomicInteger();
        throttle.execute(
                () -> new FakeResponse(calls.incrementAndGet() % 3 == 0 ? 0L : 40100L),
                FakeResponse::code, "ctx");

        assertEquals(1000, throttle.currentIntervalMillis(), "不得超过 max");
    }

    @Test
    @DisplayName("连续成功若干次后缓慢恢复速度（不会低于起始间隔）")
    void intervalRecoversAfterSuccesses() throws Exception {
        QianchuanThrottle throttle = newThrottle(500, 3000, 3, 180_000, OFF_PEAK);

        // 先被频控一次 → 1000
        AtomicInteger calls = new AtomicInteger();
        throttle.execute(() -> new FakeResponse(calls.incrementAndGet() == 1 ? 40100L : 0L),
                FakeResponse::code, "ctx");
        assertEquals(1000, throttle.currentIntervalMillis());

        // 再连续成功 9 次（加上重试成功那次共 10 次）→ 1000 × 0.8 = 800
        for (int i = 0; i < 9; i++) {
            throttle.execute(() -> new FakeResponse(0L), FakeResponse::code, "ctx");
        }
        assertEquals(800, throttle.currentIntervalMillis(), "连续成功应缓慢恢复速度");
    }

    @Test
    @DisplayName("恢复不会低于起始间隔（下限保护）")
    void recoveryNeverGoesBelowBase() throws Exception {
        QianchuanThrottle throttle = newThrottle(500, 3000, 3, 180_000, OFF_PEAK);

        for (int i = 0; i < 40; i++) {
            throttle.execute(() -> new FakeResponse(0L), FakeResponse::code, "ctx");
        }
        assertEquals(500, throttle.currentIntervalMillis(), "不应低于起始间隔");
    }

    // ==================== 峰值规避 ====================

    @Test
    @DisplayName("★ 峰值规避：距整点 30s 时，等到「过峰 +30s」再重试（共 60s）")
    void waitsUntilPastPeakWhenNearPeak() throws Exception {
        QianchuanThrottle throttle = newThrottle(0, 0, 3, 600_000, LocalTime.of(10, 29, 30));
        AtomicInteger calls = new AtomicInteger();

        throttle.execute(() -> new FakeResponse(calls.incrementAndGet() == 1 ? 40100L : 0L),
                FakeResponse::code, "ctx");

        assertEquals(1, sleeps.size());
        assertEquals(60_000L, sleeps.get(0),
                "距 10:30 还有 30s → 等到 10:30:30，共 60s（比 5s 退避长，取较大者）");
    }

    @Test
    @DisplayName("正踩在整点/半点上 → 等 30s 过峰")
    void waitsWhenExactlyAtPeak() throws Exception {
        QianchuanThrottle throttle = newThrottle(0, 0, 3, 600_000, LocalTime.of(10, 30, 0));
        AtomicInteger calls = new AtomicInteger();

        throttle.execute(() -> new FakeResponse(calls.incrementAndGet() == 1 ? 40100L : 0L),
                FakeResponse::code, "ctx");

        assertEquals(30_000L, sleeps.get(0), "正好在峰上 → 等 30s 过峰");
    }

    @Test
    @DisplayName("距峰值还远（>90s）→ 不做峰值规避，只用普通退避")
    void noPeakWaitWhenFarFromPeak() throws Exception {
        QianchuanThrottle throttle = newThrottle(0, 0, 3, 600_000, LocalTime.of(10, 15, 0));
        AtomicInteger calls = new AtomicInteger();

        throttle.execute(() -> new FakeResponse(calls.incrementAndGet() == 1 ? 40100L : 0L),
                FakeResponse::code, "ctx");

        assertInJitterRange(sleeps.get(0), 5_000, "远离峰值时应只用 5s 退避");
    }

    @Test
    @DisplayName("半点（:30）也算峰值，与整点同样规避")
    void halfHourIsAlsoPeak() throws Exception {
        QianchuanThrottle throttle = newThrottle(0, 0, 3, 600_000, LocalTime.of(11, 29, 0));
        AtomicInteger calls = new AtomicInteger();

        throttle.execute(() -> new FakeResponse(calls.incrementAndGet() == 1 ? 40100L : 0L),
                FakeResponse::code, "ctx");

        assertEquals(90_000L, sleeps.get(0), "距 11:30 还有 60s → 等到 11:30:30，共 90s");
    }

    // ==================== 工具 ====================

    /** 断言实际等待落在 {@code 基准 × (1 ± 25%)} 区间内。 */
    private static void assertInJitterRange(long actual, long base, String message) {
        long low = Math.round(base * 0.75);
        long high = Math.round(base * 1.25);
        assertTrue(actual >= low && actual <= high,
                message + "：实际 " + actual + "ms，期望区间 [" + low + ", " + high + "]");
    }

    /** 假响应：只需要一个业务错误码。 */
    private record FakeResponse(Long code) {
    }

    @Test
    @DisplayName("★★ 预算【按次】算（回归）：前一次调用把预算等光了，不该拖累后一次调用")
    void budgetIsPerCallNotSharedAcrossCalls() throws Exception {
        // 预算 8s，首次退避约 5s、第二次约 10s → 第一次调用必然「等一次后超预算」
        QianchuanThrottle throttle = newThrottle(0, 0, 5, 8_000, OFF_PEAK);

        RateLimitException first = assertThrows(RateLimitException.class, () -> throttle.execute(
                () -> new FakeResponse(40100L), FakeResponse::code, "第1次调用"));
        assertTrue(first.getMessage().contains("本次累计等待"), first.getMessage());
        assertTrue(sleeps.size() >= 1, "第 1 次调用应当真的等过：" + sleeps);

        sleeps.clear();

        // ⚠️ 关键：第 2 次调用必须拿到【完整】预算，而不是被第 1 次吃剩下的
        //    （旧实现把累计等待记在实例字段上 → 这里会立刻抛「预算已用尽」）
        AtomicInteger calls = new AtomicInteger();
        FakeResponse ok = throttle.execute(
                () -> new FakeResponse(calls.getAndIncrement() == 0 ? 40100L : 0L),
                FakeResponse::code, "第2次调用");

        assertEquals(0L, ok.code(), "第 2 次调用应当能等到重试成功（有独立预算）");
        assertEquals(1, sleeps.size(), "应当只等了一次退避：" + sleeps);
        assertTrue(throttle.lastCallWaitMillis() > 0,
                "lastCallWaitMillis 应当是【本次调用】的等待，而不是整批累计");
    }
}
