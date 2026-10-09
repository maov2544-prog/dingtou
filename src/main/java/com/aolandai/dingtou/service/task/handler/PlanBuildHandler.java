package com.aolandai.dingtou.service.task.handler;

import com.aolandai.dingtou.dto.plan.PlanBuildParam;
import com.aolandai.dingtou.service.plan.PlanBuildService;
import com.aolandai.dingtou.service.task.TaskHandler;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code planBuild} —— <b>A / B / C1 / C2 一站式投放</b>：算 N → 判场景 → 执行。
 *
 * <pre>
 * { "requestId": "...",
 *   "params": {
 *     "awemeShowId": "610129yiyi",     // 必填，抖音号
 *     "productName": "护臀膏",          // 必填，商品名
 *     "videoId": 7687379351705059825,  // 选填，C1 的「本条素材」（素材表 H 视频id）
 *     "videoIds": [111, 222],          // 选填，同一组合多条新视频（与 videoId 可同时用，会合并）
 *     "executionDate": "2026-10-05"    // 选填，计划名里的日期；默认今天
 *   } }
 * </pre>
 *
 * <p>⚠️ <b>调用方不需要知道场景</b>：服务自己判断「没有计划就建计划、有计划就加素材 + 改名」，
 * 所以这里没有 scenario 参数。业务规则改了只改服务，影刀不用动。</p>
 *
 * <p>应答 {@code data} 关键字段：</p>
 * <pre>
 * {
 *   "advertiserId": 1823379540880396, "advertiserName": "官方【母婴商品】",
 *   "awemeUid": 2071074583877891,        // 抖音号的【数值 UID】
 *   "productId": 3593275488396811650,
 *   "planName": "610129yiyi-护臀膏-10.5",
 *   "action": "CREATE_PLAN",             // CREATE_PLAN（建了新计划）/ REUSE_PLAN（复用已有）
 *   "adId": 1877746196794116,            // 建成或复用的计划 ID
 *   "materialCount": 12,                 // 本次加入计划的素材条数
 *   "dryRun": true,                      // ⚠️ 默认 true = 没真调
 *   "requestBody": "{...}",              // 实际（将要）发出的请求体，dry-run 核对用
 *   "cells": [ { "row": 4992, "column": "K", "value": "全域PC" },
 *              { "row": 4992, "column": "N", "value": "已投" } ],
 *   "warnings": []
 * }
 * </pre>
 *
 * <p>⚠️ <b>这是花钱的操作</b>（建乘方计划），受 {@code dingtou.dry-run} 保护 —— 演练模式下不会真调。</p>
 *
 * <p>⚠️ 应答里的 {@code warnings} 必须看：非空说明<b>没有全部办成</b>
 * （没账户 / 未授权 / 商品映射不到 / 素材池为空），不能当成「都投了」。</p>
 */
@Component
public class PlanBuildHandler implements TaskHandler {

    private final PlanBuildService service;

    public PlanBuildHandler(PlanBuildService service) {
        this.service = service;
    }

    @Override
    public String code() {
        return "planBuild";
    }

    @Override
    public String description() {
        return "A/B/C1/C2 一站式投放：服务自己算 N + 判场景 + 执行（花钱，受 dry-run 保护）";
    }

    @Override
    public Object handle(JsonNode params) throws Exception {
        JsonNode safeParams = Params.requireParams(params, "awemeShowId / productName");
        String awemeShowId = Params.requiredText(safeParams, "awemeShowId");
        String productName = Params.requiredText(safeParams, "productName");
        // 兼容两种传法：单个 videoId（影刀逐行）或 videoIds 数组（同一组合多条新视频）
        List<Long> videoIds = new ArrayList<>(Params.optionalLongList(safeParams, "videoIds"));
        Long videoId = Params.optionalLong(safeParams, "videoId");
        if (videoId != null && !videoIds.contains(videoId)) {
            videoIds.add(videoId);
        }
        LocalDate executionDate = Params.optionalDate(safeParams, "executionDate", null);
        return service.build(new PlanBuildParam(awemeShowId, productName, videoIds, executionDate));
    }
}
