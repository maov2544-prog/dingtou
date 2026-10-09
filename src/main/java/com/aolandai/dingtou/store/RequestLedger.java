package com.aolandai.dingtou.store;

import com.aolandai.dingtou.config.StoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 幂等流水账：{@code requestId → 执行结果}。
 *
 * <p>解决的问题：<b>影刀超时后会重试，而重试「建千川计划」是要花钱的。</b></p>
 *
 * <p>用法（{@code TaskDispatcher} 里的顺序）：</p>
 * <pre>
 *   Optional&lt;RequestEntry&gt; existing = ledger.find(requestId);
 *   if (existing.isPresent() &amp;&amp; existing.get().isDone())  → 回放 responseJson，不执行
 *   if (existing.isPresent() &amp;&amp; existing.get().isPending()) → 拒绝并发重复提交
 *   else → tryBegin（落盘 PENDING）→ 执行 → markDone / markFailed
 * </pre>
 */
@Component
public class RequestLedger extends JsonlStore<RequestEntry> {

    private static final Logger log = LoggerFactory.getLogger(RequestLedger.class);

    /** requestId → 记录 */
    private final Map<String, RequestEntry> byRequestId = new LinkedHashMap<>();

    public RequestLedger(StoreProperties properties) throws IOException {
        super(properties.getRequestLedger(), RequestEntry.class);
        buildIndex();
        log.info("幂等流水已加载: {} 条（{}）", size(), filePath());
    }

    @Override
    protected void reindex() {
        byRequestId.clear();
        for (RequestEntry entry : records()) {
            // 同一 requestId 后写的覆盖先写的（正常不会出现，出现说明文件被人工改过）
            byRequestId.put(entry.requestId(), entry);
        }
    }

    /** 按 requestId 查历史。 */
    public synchronized Optional<RequestEntry> find(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byRequestId.get(requestId));
    }

    /**
     * 尝试受理一个请求（落盘 PENDING）。
     *
     * <p><b>一次调用同时完成「查重」与「占位」</b>，调用方不用先 find 再 tryBegin ——
     * 那样在并发下会有竞态窗口。</p>
     *
     * <p>判定规则：</p>
     * <ul>
     *   <li>不存在 → 受理，返回空，调用方<b>可以执行</b>；</li>
     *   <li>已存在且状态是 {@code PENDING} 或 {@code DONE} → 返回该记录，调用方<b>不要执行</b>；</li>
     *   <li>已存在但状态是 {@code FAILED} → <b>允许重试</b>：重置为 PENDING 并返回空。
     *       （内部错误是瞬时故障，影刀带同一个 requestId 重试是合理行为）</li>
     * </ul>
     *
     * @return 空 = 首次受理（或失败后重试），可以执行；有值 = 已存在，按返回记录处理
     */
    public synchronized Optional<RequestEntry> tryBegin(String requestId, String taskCode)
            throws IOException {
        RequestEntry existing = byRequestId.get(requestId);
        if (existing != null && existing.status() != RequestStatus.FAILED) {
            return Optional.of(existing);
        }
        String now = now();
        return mutate(records -> {
            if (existing == null) {
                records.add(new RequestEntry(requestId, taskCode, RequestStatus.PENDING,
                        null, null, now, now));
            } else {
                for (int i = 0; i < records.size(); i++) {
                    if (records.get(i).requestId().equals(requestId)) {
                        records.set(i, existing.transition(RequestStatus.PENDING, null, null, now));
                        return Optional.<RequestEntry>empty();
                    }
                }
            }
            return Optional.<RequestEntry>empty();
        });
    }

    /** 标记完成并落盘完整应答（供下次同 requestId 原样回放）。 */
    public synchronized void markDone(String requestId, String responseJson) throws IOException {
        update(requestId, RequestStatus.DONE, responseJson, null);
    }

    /** 标记失败。 */
    public synchronized void markFailed(String requestId, String message) throws IOException {
        update(requestId, RequestStatus.FAILED, null, message);
    }

    private void update(String requestId, RequestStatus status, String responseJson, String message)
            throws IOException {
        mutate(records -> {
            for (int i = 0; i < records.size(); i++) {
                RequestEntry entry = records.get(i);
                if (entry.requestId().equals(requestId)) {
                    records.set(i, entry.transition(status, responseJson, message, now()));
                    return null;
                }
            }
            // 理论上不会走到这里（tryBegin 一定先落过盘）；真发生了要留痕而不是静默丢
            log.error("幂等流水里找不到 requestId={}，无法标记为 {}（请检查是否被人工改过）",
                    requestId, status);
            return null;
        });
    }

    public synchronized int entryCount() {
        return byRequestId.size();
    }

    private static String now() {
        return OffsetDateTime.now().toString();
    }

    /** 仅供测试：清空内存索引缓存（文件不变）。 */
    synchronized List<RequestEntry> snapshot() {
        return List.copyOf(byRequestId.values());
    }
}
