package com.aolandai.dingtou.service.task.handler;

import com.aolandai.dingtou.service.kol.SuixintuiInvestService;
import com.aolandai.dingtou.service.task.TaskHandler;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * {@code suixintuiCreate} —— <b>A 场景</b>：给【达人 + 商品】建一个随心推，
 * 并返回「请影刀往素材表写什么」。
 *
 * <pre>
 * { "requestId": "...",
 *   "params": { "awemeShowId": "610129yiyi", "productName": "护臀膏" } }
 * </pre>
 *
 * <p>应答 {@code data} 关键字段：</p>
 * <pre>
 * {
 *   "advertiserId": 1823379540880396, "advertiserName": "官方【母婴商品】",
 *   "awemeId": 2071074583877891,          // 抖音号的【数值 UID】
 *   "productId": "3593275488396811650",
 *   "amount": 200, "deliveryTime": 24.0, // 回显（元 / 小时），便于核对单位
 *   "bidType": "MANUAL_BID", "roiGoal": 1.7, // 回显出价方式与支付 ROI
 *   "dryRun": true,                       // ⚠️ 默认 true = 没真建
 *   "orderId": null,                      // 真建成后才有
 *   "requestBody": "{...}",               // 实际（将要）发出的请求体，dry-run 核对用
 *   "cells": [ { "row": 123, "column": "K", "value": "全域随心推" } ],
 *   "warnings": []
 * }
 * </pre>
 *
 * <p>⚠️ <b>预算 / 时长 / ROI 有官方硬约束</b>（{@code amount} 元、[100, 5000000] 且 10 的倍数；
 * {@code delivery_time} 小时、白名单 2/6/12/24/48/72/96/120/144/168；
 * {@code roi_goal} 支付 ROI、[0.01, 10000] 且最多两位小数，<b>{@code bid_type=MANUAL_BID} 时必填</b>）。
 * 不满足时<b>连 dry-run 都会返回 {@code code=2}</b>，不会放行到「看起来能建」。</p>
 *
 * <p>⚠️ <b>服务端不写 Excel</b>：{@code cells} 是给影刀执行的写入指令
 * （不碰文件格式、不怕文件被占用）。</p>
 *
 * <p>⚠️ <b>这是花钱的操作</b>，受 {@code dingtou.dry-run} 保护 —— 演练模式下不会真建。</p>
 *
 * <p>⚠️ 应答里的 {@code warnings} 必须看：非空说明<b>没有全部办成</b>
 * （没账户 / 未授权 / 商品映射不到），不能当成「都投了」。</p>
 */
@Component
public class SuixintuiCreateHandler implements TaskHandler {

    private final SuixintuiInvestService service;

    public SuixintuiCreateHandler(SuixintuiInvestService service) {
        this.service = service;
    }

    @Override
    public String code() {
        return "suixintuiCreate";
    }

    @Override
    public String description() {
        return "A 场景：建随心推 + 返回「素材表要写哪些单元格」给影刀（花钱，受 dry-run 保护）";
    }

    @Override
    public Object handle(JsonNode params) throws Exception {
        JsonNode safeParams = Params.requireParams(params, "awemeShowId / productName");
        String awemeShowId = Params.requiredText(safeParams, "awemeShowId");
        String productName = Params.requiredText(safeParams, "productName");
        return service.invest(awemeShowId, productName);
    }
}
