package com.aolandai.dingtou.dto.sheet;

import java.util.Optional;

/**
 * 定投素材表的「投放状态」（原列名「投放备注」，老板 2026-09-30 要求改名）。
 *
 * <p>流转：RPA+代码写入行 → {@link #PENDING_REVIEW} → 商务审核通过 → {@link #PENDING_DELIVERY}
 * → 代码投放完成 → {@link #DELIVERED}。</p>
 *
 * <p>⚠️ 只有 {@link #PENDING_DELIVERY} 的行才需要进入投放环节。</p>
 */
public enum DeliveryStatus {

    /** 待商务审核（刚由 RPA 写入，还没人工过）。 */
    PENDING_REVIEW("待审核"),

    /** 审核通过，等待投放。 */
    PENDING_DELIVERY("待投放"),

    /** 已投放完成。 */
    DELIVERED("已投"),

    /** 商务判定不合规、不投放。 */
    REJECTED("不投放");

    private final String text;

    DeliveryStatus(String text) {
        this.text = text;
    }

    public String text() {
        return text;
    }

    /**
     * 文本 → 枚举，<b>容忍未知值</b>。
     *
     * <p>刻意返回 {@code Optional} 而不是抛异常：这张表是人工在维护的，
     * 随时可能冒出一个没见过的写法（「已投放」「已完成」…）。
     * 读到未知值时应该<b>跳过并记日志</b>，而不是让整轮任务崩掉。</p>
     */
    public static Optional<DeliveryStatus> of(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String normalized = text.trim();
        for (DeliveryStatus status : values()) {
            if (status.text.equals(normalized)) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }

    /** 是否已投放完成（供步骤 4 批量通知筛选）。 */
    public static boolean isDelivered(String text) {
        return of(text).filter(s -> s == DELIVERED).isPresent();
    }

    /** 是否待投放（唯一需要进入投放环节的状态）。 */
    public static boolean isPendingDelivery(String text) {
        return of(text).filter(s -> s == PENDING_DELIVERY).isPresent();
    }
}
