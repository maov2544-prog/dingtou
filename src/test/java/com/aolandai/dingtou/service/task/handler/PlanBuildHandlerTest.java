package com.aolandai.dingtou.service.task.handler;

import com.aolandai.dingtou.dto.plan.PlanBuildParam;
import com.aolandai.dingtou.dto.plan.PlanBuildResult;
import com.aolandai.dingtou.exception.BizException;
import com.aolandai.dingtou.service.plan.PlanBuildService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code planBuild} 任务单测：参数解析与透传（用假实现，不碰千川）。 */
class PlanBuildHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 假实现：记录收到的入参。 */
    private static final class FakeService implements PlanBuildService {
        final AtomicReference<PlanBuildParam> last = new AtomicReference<>();
        PlanBuildResult next = new PlanBuildResult("a", "p", null, "", null, null, "",
                0, "", null, null, 0, true, "", List.of(), List.of());

        @Override
        public PlanBuildResult build(PlanBuildParam param) {
            last.set(param);
            return next;
        }
    }

    private static JsonNode params(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    @DisplayName("taskCode 是 planBuild，且只认这个")
    void codeIsPlanBuild() {
        assertEquals("planBuild", new PlanBuildHandler(new FakeService()).code());
    }

    @Test
    @DisplayName("必填参数透传；videoId / executionDate 不传时为 null")
    void passesRequiredParams() throws Exception {
        FakeService fake = new FakeService();
        new PlanBuildHandler(fake).handle(params("""
                {"awemeShowId":"610129yiyi","productName":"护臀膏"}"""));

        PlanBuildParam p = fake.last.get();
        assertEquals("610129yiyi", p.awemeShowId());
        assertEquals("护臀膏", p.productName());
        assertTrue(p.videoIds().isEmpty(), "不传 videoId 时服务会自己去素材表找「本条」");
        assertNull(p.executionDate(), "不传日期时服务用今天");
    }

    @Test
    @DisplayName("★ videoId 传【数字字符串】也要能收（影刀常把 19 位数字当字符串传）")
    void acceptsVideoIdAsString() throws Exception {
        FakeService fake = new FakeService();
        new PlanBuildHandler(fake).handle(params("""
                {"awemeShowId":"610129yiyi","productName":"护臀膏",
                 "videoId":"7687379351705059825"}"""));

        assertEquals(List.of(7687379351705059825L), fake.last.get().videoIds());
    }

    @Test
    @DisplayName("★ videoId 传数字也能收（19 位超过 int32，必须按 long 处理）")
    void acceptsVideoIdAsNumber() throws Exception {
        FakeService fake = new FakeService();
        new PlanBuildHandler(fake).handle(params("""
                {"awemeShowId":"610129yiyi","productName":"护臀膏",
                 "videoId":7687379351705059825}"""));

        assertEquals(List.of(7687379351705059825L), fake.last.get().videoIds());
    }

    @Test
    @DisplayName("executionDate 传 yyyy-MM-dd 生效（用于补跑历史日期）")
    void acceptsExecutionDate() throws Exception {
        FakeService fake = new FakeService();
        new PlanBuildHandler(fake).handle(params("""
                {"awemeShowId":"610129yiyi","productName":"护臀膏",
                 "executionDate":"2026-09-30"}"""));

        assertEquals(LocalDate.of(2026, 9, 30), fake.last.get().executionDate());
    }

    @Test
    @DisplayName("缺必填参数 → code=2（影刀不该重试一个必然失败的请求）")
    void missingRequiredParamsIsBadRequest() throws Exception {
        PlanBuildHandler handler = new PlanBuildHandler(new FakeService());

        assertThrows(BizException.class, () -> handler.handle(params("""
                {"productName":"护臀膏"}""")));
        assertThrows(BizException.class, () -> handler.handle(params("""
                {"awemeShowId":"610129yiyi"}""")));
        assertThrows(BizException.class, () -> handler.handle(params("{}")));
    }

    @Test
    @DisplayName("videoId 不是数字 → code=2，且提示里带原值")
    void invalidVideoIdIsBadRequest() throws Exception {
        PlanBuildHandler handler = new PlanBuildHandler(new FakeService());
        BizException e = assertThrows(BizException.class, () -> handler.handle(params("""
                {"awemeShowId":"610129yiyi","productName":"护臀膏","videoId":"abc"}""")));
        assertTrue(e.getMessage().contains("videoId"), e.getMessage());
    }

    @Test
    @DisplayName("executionDate 格式错 → code=2")
    void invalidDateIsBadRequest() throws Exception {
        PlanBuildHandler handler = new PlanBuildHandler(new FakeService());
        assertThrows(BizException.class, () -> handler.handle(params("""
                {"awemeShowId":"610129yiyi","productName":"护臀膏","executionDate":"10/05"}""")));
    }
}
