package com.aolandai.dingtou.service.kol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DeliveryStatusChecker#extractDate} 单测 —— 从计划名里抠日期。
 *
 * <p>用的都是<b>实测真实计划名</b>（格式很杂）。</p>
 */
class DeliveryStatusCheckerDateTest {

    /** 判定窗口：上一周期结束 2026-10-04 ~ 本周期开始 2026-10-11。 */
    private static final LocalDate START = LocalDate.of(2026, 10, 4);
    private static final LocalDate END = LocalDate.of(2026, 10, 11);

    private static Optional<LocalDate> parse(String planName) {
        return DeliveryStatusChecker.extractDate(planName, START, END);
    }

    @Test
    @DisplayName("★ 实测真实计划名：分隔符 - / 制表符 / 各种后缀 都能解析")
    void realPlanNames() {
        assertEquals(LocalDate.of(2026, 10, 4), parse("朗朗呀-儿童彩妆套装-10.4更新").orElseThrow());
        assertEquals(LocalDate.of(2026, 6, 19), parse("堇宝妈咪-护臀膏-6.19更新").orElseThrow());
        assertEquals(LocalDate.of(2026, 7, 26), parse("小骆爱健身\t橄榄油-7.26更新_转投").orElseThrow());
        assertEquals(LocalDate.of(2026, 2, 12),
                parse("旺崽小奶乐-抚触油-2.12更新（复活）_转投").orElseThrow());
        assertEquals(LocalDate.of(2026, 9, 22),
                parse("楠楠爸妈-洗护二合一/9.22更新_转投").orElseThrow());
    }

    @Test
    @DisplayName("★★ 回归：数字与「更新」之间有空格 → 必须能解析（曾因 group() 带尾随空格整批漏判）")
    void spaceBeforeUpdateKeyword() {
        // 实测真实计划名：H 列「素材更新时间」= 2026-07-09，与 7.9 一致
        assertEquals(LocalDate.of(2026, 7, 9),
                parse("\"小恩恩家有三宝-儿童洗发水-7.9 更新_转投").orElseThrow(),
                "`7.9 更新` 中间有空格，曾因 Integer.parseInt(\"9 \") 抛异常被吞掉");
        assertEquals(LocalDate.of(2026, 10, 4),
                parse("达人-商品-10.4  更新").orElseThrow(), "多个空格也要认");
    }

    @Test
    @DisplayName("★ 边界：窗口两端都算命中（闭区间）")
    void windowBoundariesAreInclusive() {
        assertTrue(START.equals(parse("达人-商品-10.4更新").orElseThrow()), "窗口起点应命中");
        assertTrue(END.equals(parse("达人-商品-10.11更新").orElseThrow()), "窗口终点应命中");
    }

    @Test
    @DisplayName("窗口外的日期解析得出来，但不算命中")
    void outsideWindowStillParses() {
        assertEquals(LocalDate.of(2026, 10, 3), parse("达人-商品-10.3更新").orElseThrow());
        // ⚠️ 10.12 晚于窗口结束（10-11）—— 现实中不会出现（运行日就是窗口结束日，
        //    素材更新是过去的事）。推断会退到上一年，回显年份不自然，
        //    但【判定结果一致：都不在窗口内】。这里只钉住判定语义。
        LocalDate twelve = parse("达人-商品-10.12更新").orElseThrow();
        assertTrue(twelve.isBefore(START) || twelve.isAfter(END),
                "10.12 不该落在窗口内，实际 " + twelve);
    }

    @Test
    @DisplayName("★ 远期日期不能被推成未来：2.12更新 在 10 月的窗口里必须是【当年】2 月")
    void farPastDateIsNotPushedToFuture() {
        LocalDate got = parse("旺崽小奶乐-抚触油-2.12更新（复活）_转投").orElseThrow();
        assertEquals(LocalDate.of(2026, 2, 12), got,
                "素材更新是过去的事，不能因为「离窗口更近」就推成 2027");
    }

    @Test
    @DisplayName("★ 跨年：周期在 12 月 ~ 次年 1 月时，1.3 要算成次年")
    void crossYearPicksNearestYear() {
        LocalDate start = LocalDate.of(2026, 12, 27);
        LocalDate end = LocalDate.of(2027, 1, 3);

        LocalDate got = DeliveryStatusChecker.extractDate("达人-商品-1.3更新", start, end)
                .orElseThrow();

        assertEquals(LocalDate.of(2027, 1, 3), got, "应推断为次年而不是 2026-01-03");
    }

    @Test
    @DisplayName("名字里有别的数字时，以「更新」为锚点，不会抓错")
    void anchorsOnUpdateKeyword() {
        // 3.15 是干扰项（商品名里的规格），真正的更新日期是 10.4
        assertEquals(LocalDate.of(2026, 10, 4),
                parse("达人-商品3.15装-10.4更新").orElseThrow());
    }

    @Test
    @DisplayName("没有「更新」二字时，退化成取最后一个 M.D")
    void fallsBackToLastMd() {
        assertEquals(LocalDate.of(2026, 10, 4), parse("达人-商品-10.4").orElseThrow());
    }

    @Test
    @DisplayName("解析不出日期 → empty（调用方按未交付处理并留 warning）")
    void noDateReturnsEmpty() {
        assertTrue(parse("达人-商品-无日期").isEmpty());
        assertTrue(parse("").isEmpty());
        assertTrue(parse(null).isEmpty());
    }

    @Test
    @DisplayName("非法日期（如 13.45）不炸，返回 empty")
    void invalidDateDoesNotThrow() {
        assertTrue(parse("达人-商品-13.45更新").isEmpty());
    }
}
