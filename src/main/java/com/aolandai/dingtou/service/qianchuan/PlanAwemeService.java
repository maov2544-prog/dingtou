package com.aolandai.dingtou.service.qianchuan;

import com.aolandai.dingtou.dto.qianchuan.PlanAweme;
import com.aolandai.dingtou.dto.qianchuan.PlanAwemeLookupResult;
import com.aolandai.dingtou.dto.qianchuan.PlanRef;
import com.bytedance.ads.ApiException;
import com.bytedance.ads.model.QianchuanUniPromotionAdDetailV10Response;

import java.util.List;
import java.util.Optional;

/**
 * <b>通用能力</b>：按【投放账户 + 计划 ID】查该计划的<b>达人（抖音）信息</b>。
 *
 * <p>⚠️ 刻意<b>不绑定任何业务域</b>（不带 kol 前缀、不依赖配置表）——
 * 任何业务线（达人巡查/催更、投放复盘、素材分析…）都可以注入使用。</p>
 *
 * <p>数据来源：{@code GET /qianchuan/uni_promotion/ad/detail/}（乘方计划详情）的
 * {@code data.aweme_infos[]}。该接口返回体很轻（不含视频素材），适合批量调用。</p>
 *
 * <p>⚠️ <b>必须看返回里的 {@code warnings}</b>：某个计划查失败不会中断整批，
 * 但「漏查了某个计划」和「该计划确实没有达人」是两回事。</p>
 */
public interface PlanAwemeService {

    /**
     * 查单个计划。
     *
     * @return 达人信息；查不到（接口返回空 / 该计划不存在 / 查询失败）时为空
     */
    Optional<PlanAweme> lookup(long advertiserId, long adId) throws Exception;

    /**
     * 批量查。
     *
     * <p>⚠️ 一个计划若返回<b>多个达人</b>，只取<b>第一个</b>并记一条 warning ——
     * 静默丢弃是不可接受的（会让人以为「这个计划只有一个达人」）。</p>
     */
    PlanAwemeLookupResult lookupAll(List<PlanRef> refs) throws Exception;

    /**
     * 底层的「查一次详情」函数抽象 —— 便于单测注入假实现，<b>不真调千川</b>。
     *
     * <p>与 {@code QianchuanPlanService.PlanListFetcher} 同样的设计：把 SDK 调用
     * 收敛到一个函数式接口，测试就能完全不碰网络。</p>
     */
    @FunctionalInterface
    interface PlanDetailFetcher {
        QianchuanUniPromotionAdDetailV10Response fetch(long advertiserId, long adId)
                throws ApiException;
    }
}
