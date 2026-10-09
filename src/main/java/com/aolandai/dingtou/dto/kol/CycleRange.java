package com.aolandai.dingtou.dto.kol;

import java.time.LocalDate;

/**
 * 一个「周期」区间（来自 sheet 名 {@code 2026-09-13到2026-10-04}）。
 *
 * @param sheetName  sheet 名（原样，如 {@code 2026-09-13到2026-10-04}）
 * @param startDate  周期开始日
 * @param endDate    周期结束日
 */
public record CycleRange(String sheetName, LocalDate startDate, LocalDate endDate) {

    /** 判断某个日期是否落在 {@code [startDate, endDate]}（<b>闭区间</b>）。 */
    public boolean contains(LocalDate date) {
        return date != null && !date.isBefore(startDate) && !date.isAfter(endDate);
    }
}
