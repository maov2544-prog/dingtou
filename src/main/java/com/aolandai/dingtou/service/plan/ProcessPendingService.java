package com.aolandai.dingtou.service.plan;

import com.aolandai.dingtou.dto.plan.ProcessPendingParam;
import com.aolandai.dingtou.dto.plan.ProcessPendingResult;

/**
 * {@code processPending} —— <b>一次处理整批「待投放」的行</b>。
 *
 * <h2>它解决什么</h2>
 * <p>改造前影刀要「逐行调 {@code decide} → 按结果选 task → 逐行调用」，这意味着：</p>
 * <ul>
 *   <li>影刀里有一份业务规则（算 N、选场景）—— 规则一改就要人肉回归；</li>
 *   <li>同一账户被反复查（每行都遍历一次）；</li>
 *   <li>同步等待，慢任务必然超时。</li>
 * </ul>
 * <p>现在影刀只做三件事：<b>读表 → 提交整批 → 拿 {@code cells} 写回</b>。
 * 场景判断、去重、执行全在服务里。</p>
 *
 * <h2>⚠️ 重跑是安全的</h2>
 * <p>批次跑到一半失败时，影刀可以<b>原样重跑整批</b>：已建过计划的组合会被「查计划」查到 →
 * 走 C1 复用 → <b>不会重复建计划（不重复花钱）</b>。所以不需要断点续跑。</p>
 */
public interface ProcessPendingService {

    /**
     * 处理一批待投放的行。
     *
     * @throws Exception 整批级别的失败（参数错 / 频控）；<b>单个组合失败不会抛</b>，
     *                   而是记进结果里的 {@code FAILED}，不中断其他组合
     */
    ProcessPendingResult process(ProcessPendingParam param) throws Exception;
}
