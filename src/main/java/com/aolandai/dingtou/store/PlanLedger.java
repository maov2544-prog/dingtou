package com.aolandai.dingtou.store;

import com.aolandai.dingtou.config.StoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 计划台账：【达人 + 商品】→ 千川计划 ID。
 *
 * <p><b>这是「不靠解析计划名反查」这条硬性规则的落地点。</b>建完计划立刻登记，
 * 以后判定「该组合是否已有千川计划」先查本地台账（0 次接口调用），查不到再去千川查。</p>
 *
 * <p>⚠️ 台账与千川真实状态可能不一致（计划被人工删了）。所以台账命中时，
 * <b>只作为「大概有」的信号</b>；真正决定要往哪个计划加素材前，
 * 仍应调一次 {@code planQuery} 确认 —— 往一个不存在的计划加素材会失败，
 * 而重复建计划会花钱，两者都要避免。</p>
 */
@Component
public class PlanLedger extends JsonlStore<PlanEntry> {

    private static final Logger log = LoggerFactory.getLogger(PlanLedger.class);

    private final Map<PlanKey, PlanEntry> byKey = new LinkedHashMap<>();

    public PlanLedger(StoreProperties properties) throws IOException {
        super(properties.getPlanLedger(), PlanEntry.class);
        buildIndex();
        log.info("计划台账已加载: {} 个组合（{}）", byKey.size(), filePath());
    }

    @Override
    protected void reindex() {
        byKey.clear();
        for (PlanEntry entry : records()) {
            byKey.put(entry.key(), entry);
        }
    }

    /** 按【抖音号 + 商品名】查台账。 */
    public synchronized Optional<PlanEntry> find(String awemeShowId, String productName) {
        if (awemeShowId == null || awemeShowId.isBlank()
                || productName == null || productName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byKey.get(new PlanKey(awemeShowId, productName)));
    }

    /**
     * 登记 / 更新一条台账（同组合覆盖）。
     *
     * <p>幂等：同一组合重复登记只会更新，不会产生重复行。</p>
     */
    public synchronized PlanEntry register(String awemeShowId, String productName, String productId,
                                           Long advertiserId, String adId, String planName)
            throws IOException {
        if (adId == null || adId.isBlank()) {
            throw new IllegalArgumentException("adId（千川计划 ID）不能为空 —— 台账的核心就是这个 ID");
        }
        String now = now();
        PlanKey key = new PlanKey(awemeShowId, productName);
        PlanEntry existing = byKey.get(key);

        PlanEntry entry = existing == null
                ? new PlanEntry(awemeShowId.trim(), productName.trim(), productId,
                        advertiserId, adId, planName, now, now)
                : new PlanEntry(existing.awemeShowId(), existing.productName(),
                        productId == null ? existing.productId() : productId,
                        advertiserId == null ? existing.advertiserId() : advertiserId,
                        adId, planName, existing.createdAt(), now);

        mutate(records -> {
            records.removeIf(r -> r.key().equals(key));
            records.add(entry);
            return null;
        });
        log.info("计划台账已登记: {} + {} → adId={}（{}）",
                awemeShowId, productName, adId, existing == null ? "新增" : "更新");
        return entry;
    }

    /** 移除一条台账（计划被人工删掉时用）。 */
    public synchronized boolean remove(String awemeShowId, String productName) throws IOException {
        PlanKey key;
        try {
            key = new PlanKey(awemeShowId, productName);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (!byKey.containsKey(key)) {
            return false;
        }
        mutate(records -> {
            records.removeIf(r -> r.key().equals(key));
            return null;
        });
        log.info("计划台账已移除: {} + {}", awemeShowId, productName);
        return true;
    }

    public synchronized int entryCount() {
        return byKey.size();
    }

    private static String now() {
        return OffsetDateTime.now().toString();
    }
}
