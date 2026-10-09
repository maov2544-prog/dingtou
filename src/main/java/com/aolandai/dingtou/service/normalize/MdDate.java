package com.aolandai.dingtou.service.normalize;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 「M.D」文本日期的解析与格式化。
 *
 * <p>业务表里的日期列（定投素材表 {@code A 日期} / {@code F 视频发布日期}）存的是
 * <b>M.D 文本</b>，例如 {@code 9.2} / {@code 9.19} / {@code 9.30}，
 * <b>不带前导零</b>，也<b>不带年份</b>。</p>
 *
 * <p>⚠️ 两个必须记住的坑：</p>
 * <ul>
 *   <li><b>禁止按数值比较</b>：{@code 9.19} 会被当成 9.19 &lt; 9.2，排序全错。
 *       本类一律按字符串拆 {@code .} 解析。</li>
 *   <li><b>跨年取最近的年</b>：M.D 不带年份，解析时以「基准日」为锚，
 *       在 基准年-1 / 基准年 / 基准年+1 三年里挑<b>距离基准日最近</b>的那个。</li>
 * </ul>
 *
 * <p>⚠️ 一个无法在代码层修复的数据问题：Excel 把 {@code 9.2} 当小数存，
 * 于是 <b>{@code 9.10} 会退化成 {@code 9.1}</b>（尾零丢失），被解析成 9 月 1 日。
 * 这类行只能靠上游把日期列改成文本格式来根治。</p>
 */
public final class MdDate {

    private MdDate() {
    }

    /**
     * 把日期格式化成 M.D 文本（<b>无前导零</b>）。
     *
     * @param date 日期，不可为 null
     * @return 形如 {@code 9.2} / {@code 9.30} / {@code 10.5} 的文本
     */
    public static String format(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("date 不能为空");
        }
        return date.getMonthValue() + "." + date.getDayOfMonth();
    }

    /**
     * 把 M.D 文本解析成日期，年份取「距基准日最近的那个」。
     *
     * @param text M.D 文本，形如 {@code 9.2}
     * @param base 基准日（通常传「今天」）
     * @return 解析出的日期
     * @throws IllegalArgumentException 文本为空、格式非法，或月日组合在任何候选年份都不成立
     */
    public static LocalDate parse(String text, LocalDate base) {
        if (base == null) {
            throw new IllegalArgumentException("base 不能为空");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("M.D 文本不能为空");
        }

        String trimmed = text.trim();
        int dot = trimmed.indexOf('.');
        if (dot <= 0 || dot == trimmed.length() - 1) {
            throw new IllegalArgumentException("不是合法的 M.D 文本: " + text);
        }

        int month;
        int day;
        try {
            month = Integer.parseInt(trimmed.substring(0, dot).trim());
            day = Integer.parseInt(trimmed.substring(dot + 1).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("不是合法的 M.D 文本: " + text, e);
        }
        if (month < 1 || month > 12 || day < 1 || day > 31) {
            throw new IllegalArgumentException("M.D 越界: " + text);
        }

        LocalDate nearest = null;
        for (int year = base.getYear() - 1; year <= base.getYear() + 1; year++) {
            LocalDate candidate = tryDate(year, month, day);
            if (candidate == null) {
                continue;
            }
            if (nearest == null
                    || distance(base, candidate) < distance(base, nearest)) {
                nearest = candidate;
            }
        }
        if (nearest == null) {
            throw new IllegalArgumentException("M.D 在任何候选年份都不成立: " + text);
        }
        return nearest;
    }

    /** 构造日期；月日组合非法（如 2.30）时返回 null 而不是抛异常，交给上层挑候选年。 */
    private static LocalDate tryDate(int year, int month, int day) {
        try {
            return LocalDate.of(year, month, day);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long distance(LocalDate base, LocalDate candidate) {
        return Math.abs(ChronoUnit.DAYS.between(base, candidate));
    }
}
