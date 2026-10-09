package com.aolandai.dingtou.controller;

import com.aolandai.dingtou.dto.api.ApiRequest;
import com.aolandai.dingtou.dto.api.ApiResponse;
import com.aolandai.dingtou.service.task.TaskDispatcher;
import com.aolandai.dingtou.service.task.TaskJobQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统一任务入口。<b>只做「收请求 → 交给分发器 → 发应答」，不含任何业务判断。</b>
 *
 * <pre>
 * POST /api/v1/task/{taskCode}    影刀提交任务
 * GET  /api/v1/task/{taskId}      影刀轮询异步任务结果
 * </pre>
 *
 * <p>鉴权由 {@code AuthInterceptor} 在拦截器里完成，本类不关心。</p>
 */
@RestController
@RequestMapping("/api/v1/task")
public class TaskController {

    private final TaskDispatcher dispatcher;
    private final TaskJobQueryService jobQueryService;

    public TaskController(TaskDispatcher dispatcher, TaskJobQueryService jobQueryService) {
        this.dispatcher = dispatcher;
        this.jobQueryService = jobQueryService;
    }

    /**
     * 提交任务。
     *
     * @param taskCode 任务码，如 {@code ping} / {@code decide}
     * @param request  请求体；{@code ping} 这类无参任务允许不带 body
     */
    @PostMapping("/{taskCode}")
    public ApiResponse submit(@PathVariable String taskCode,
                              @RequestBody(required = false) ApiRequest request) {
        return dispatcher.dispatch(taskCode, request);
    }

    /**
     * 轮询异步任务结果（配合应答里的 {@code code=4 + taskId}）。
     *
     * <p>三种结果见 {@link TaskJobQueryService}：不存在 → {@code code=1}；
     * 还在跑 → {@code code=4 + taskId}；跑完了 → <b>原样回放</b>当初那份应答。</p>
     */
    @GetMapping("/{taskId}")
    public ApiResponse poll(@PathVariable String taskId) {
        return jobQueryService.poll(taskId);
    }
}
