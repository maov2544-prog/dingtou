package com.aolandai.dingtou.service.aggregate;

import com.aolandai.dingtou.dto.CountResult;

import java.io.IOException;

/**
 * 累计随心推笔数聚合服务。
 *
 * <p><b>接口与实现分离</b>：{@code service/task/handler} 只依赖这个接口，
 * 不依赖 {@code DefaultCumulativeCountService}，也不认识 {@code store/} 与 {@code source/}。
 * 好处有两个：任务层可以脱离数据源单测；换取数策略（比如以后接实时接口）不用改任务层。</p>
 *
 * <p>取数策略（两段式，老板 2026-09-30 定）：</p>
 * <ol>
 *   <li><b>权威</b>：{@link CountSource#ORDER_SNAPSHOT} —— 每日拉取的随心推订单快照；</li>
 *   <li><b>近似</b>：{@link CountSource#DELIVERY_SHEET} —— 定投素材表预筛。</li>
 * </ol>
 */
public interface CumulativeCountService {

    /**
     * 统计某个【抖音号 + 商品名】的累计随心推笔数。
     *
     * @param awemeShowId 抖音号（定投素材表的「抖音号」）
     * @param productName 商品名（定投素材表的「产品」）
     * @return 统计结果；<b>永不返回 null</b>，取不到数据时 {@code source = NONE}、{@code count = 0}
     * @throws IOException 读表失败（表被占用 / 路径配错等）
     */
    CountResult count(String awemeShowId, String productName) throws IOException;
}
