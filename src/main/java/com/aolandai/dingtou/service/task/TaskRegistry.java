package com.aolandai.dingtou.service.task;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 任务注册表：Spring 启动时把所有 {@link TaskHandler} Bean 收进一张 {@code code → handler} 表。
 *
 * <p>⚠️ <b>校验放在构造器里</b>，让配置错误在<b>启动阶段</b>就炸，而不是等影刀调到才发现：</p>
 * <ul>
 *   <li>{@code code} 为空 → 报错；</li>
 *   <li>{@code code} 重复 → 报错，并打印冲突的两个类名（这种 bug 靠运行日志很难定位）；</li>
 *   <li>一个 handler 都没有 → 报错（说明组件扫描没生效，服务起来也是废的）。</li>
 * </ul>
 */
@Component
public class TaskRegistry {

    private final Map<String, TaskHandler> handlers;

    public TaskRegistry(List<TaskHandler> discovered) {
        Map<String, TaskHandler> collected = new LinkedHashMap<>();
        for (TaskHandler handler : discovered) {
            String code = handler.code();
            if (code == null || code.isBlank()) {
                throw new IllegalStateException(
                        "TaskHandler 的 code 不能为空: " + handler.getClass().getName());
            }
            TaskHandler previous = collected.put(code, handler);
            if (previous != null) {
                throw new IllegalStateException("taskCode 重复: '" + code + "' 同时被 "
                        + previous.getClass().getName() + " 和 "
                        + handler.getClass().getName() + " 占用");
            }
        }
        if (collected.isEmpty()) {
            throw new IllegalStateException("没有发现任何 TaskHandler，请检查组件扫描范围");
        }
        this.handlers = Collections.unmodifiableMap(collected);
    }

    /** 按任务码取处理器；未知任务码返回空。 */
    public Optional<TaskHandler> find(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(handlers.get(code));
    }

    /** 全部已注册的任务码（保持注册顺序）。 */
    public Set<String> codes() {
        return handlers.keySet();
    }

    public int size() {
        return handlers.size();
    }
}
