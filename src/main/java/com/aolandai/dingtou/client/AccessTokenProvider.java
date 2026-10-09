package com.aolandai.dingtou.client;

/**
 * 千川 Access-Token 提供者。
 *
 * <p>⚠️ <b>为什么不直接每次调接口前换一次 token：</b>换取 token 本身是一次外部 HTTP 调用，
 * 逐条任务都换会让「一次批量投放」变成几百次多余的往返；而且对方服务抖一下，
 * 整批任务全挂。所以要缓存 + 按需刷新。</p>
 */
public interface AccessTokenProvider {

    /**
     * 取当前有效的 Access-Token，<b>缓存过期时自动刷新</b>。
     *
     * @throws Exception 换取失败（网络 / 凭据未配置 / 对方返回错误）
     */
    String token() throws Exception;

    /**
     * 主动作废缓存。
     *
     * <p>用途：接口返回鉴权失败（token 被服务端提前失效）时，调它强制下次重新换取。</p>
     */
    void invalidate();

    /** 当前缓存的 token 剩余有效秒数；无缓存返回 0。用于探活与排障。 */
    long remainingSeconds();
}
