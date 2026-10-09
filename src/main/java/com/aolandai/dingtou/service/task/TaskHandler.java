package com.aolandai.dingtou.service.task;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 任务处理器：<b>一个 taskCode 一个实现类</b>。
 *
 * <p>加一个新任务 = 加一个 {@code @Component} 实现类，<b>不用碰分发器、不用碰注册表</b>（开闭原则）。</p>
 *
 * <pre>
 * 影刀 ──POST /api/v1/task/{taskCode}──► TaskController
 *                                          └─ TaskDispatcher ──► TaskHandler.handle(params)
 * </pre>
 */
public interface TaskHandler {

    /** 任务码，对应 URL 里的 {@code {taskCode}}。全工程唯一，重复会在启动时直接报错。 */
    String code();

    /**
     * 执行业务。
     *
     * @param params 请求体里的 {@code params} 节点；影刀没传时为 {@code null}
     * @return 放进 {@code ApiResponse.data} 的对象（会被 Jackson 序列化）
     * @throws Exception 业务异常抛 {@code BizException}；限流抛 {@code RateLimitException}
     */
    Object handle(JsonNode params) throws Exception;

    /** 一句话说明，用于启动横幅打印任务清单。 */
    default String description() {
        return "";
    }
}
