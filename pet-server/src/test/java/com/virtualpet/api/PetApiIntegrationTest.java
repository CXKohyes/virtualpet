package com.virtualpet.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.virtualpet.pet.Pet;
import com.virtualpet.pet.PetMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 接口集成测试（TECH_DESIGN 5、10.1）。
 *
 * <p>跑在 H2 的 MySQL 兼容模式上，表结构由同一份 Liquibase changelog 建立，
 * 所以迁移脚本本身也在这批测试里被执行过一遍。</p>
 *
 * <p>每个用例用独立的 deviceId，互不干扰。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PetApiIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PetMapper petMapper;

    // ================================================================ 匿名会话

    @Test
    @DisplayName("建立会话：拿到玩家 ID 和令牌，还没有宠物")
    void establishSession() throws Exception {
        JsonNode data = dataOf(postJson("/api/v1/session", null, Map.of("deviceId", newDeviceId()), 200));

        assertThat(data.path("playerId").asLong()).isPositive();
        assertThat(data.path("token").asText()).isNotBlank();
        assertThat(data.path("pet").isNull()).isTrue();
    }

    @Test
    @DisplayName("同一设备再次会话：玩家不变，令牌换新，旧令牌失效")
    void sessionIsStablePerDeviceButRotatesToken() throws Exception {
        String deviceId = newDeviceId();
        JsonNode first = dataOf(postJson("/api/v1/session", null, Map.of("deviceId", deviceId), 200));
        JsonNode second = dataOf(postJson("/api/v1/session", null, Map.of("deviceId", deviceId), 200));

        assertThat(second.path("playerId").asLong()).isEqualTo(first.path("playerId").asLong());
        assertThat(second.path("token").asText()).isNotEqualTo(first.path("token").asText());

        // 旧令牌已经不能用了
        mockMvc.perform(get("/api/v1/pets/me").header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + first.path("token").asText()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("deviceId 缺失 -> 400")
    void sessionRequiresDeviceId() throws Exception {
        postJson("/api/v1/session", null, Map.of("deviceId", "  "), 400);
    }

    // ================================================================ 鉴权

    @Test
    @DisplayName("没有令牌 -> 401")
    void missingTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/pets/me")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("令牌无效 -> 401，且不区分设备是否存在")
    void invalidTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/pets/me").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/pets/me").header(HttpHeaders.AUTHORIZATION, "Basic abc"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("还没领养时查询宠物 -> 404 PET_NOT_FOUND")
    void petNotFoundBeforeAdoption() throws Exception {
        Session session = newSession();
        assertThat(codeOf(getJson("/api/v1/pets/me", session.token(), 404))).isEqualTo("PET_NOT_FOUND");
    }

    // ================================================================ 领养

    @Test
    @DisplayName("领养宠物：初始 80/80/80/80/100，1 级，幼年，状态正常")
    void createPet() throws Exception {
        Session session = newSession();
        JsonNode pet = dataOf(createPet(session, "CAT", "Mimi"));

        assertThat(pet.path("species").asText()).isEqualTo("CAT");
        assertThat(pet.path("name").asText()).isEqualTo("Mimi");
        assertThat(pet.path("satiety").asInt()).isEqualTo(80);
        assertThat(pet.path("mood").asInt()).isEqualTo(80);
        assertThat(pet.path("hygiene").asInt()).isEqualTo(80);
        assertThat(pet.path("energy").asInt()).isEqualTo(80);
        assertThat(pet.path("health").asInt()).isEqualTo(100);
        assertThat(pet.path("level").asInt()).isEqualTo(1);
        assertThat(pet.path("exp").asInt()).isZero();
        assertThat(pet.path("evolutionStage").asInt()).isZero();
        assertThat(pet.path("status").asText()).isEqualTo("NORMAL");
        assertThat(pet.path("sleepingSince").isNull()).isTrue();
        assertThat(pet.path("cooldowns").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("中文名字可以正常保存和返回")
    void chineseNameRoundTrips() throws Exception {
        Session session = newSession();
        JsonNode pet = dataOf(createPet(session, "DRAGON", "小焰"));

        assertThat(pet.path("name").asText()).isEqualTo("小焰");
        assertThat(dataOf(getJson("/api/v1/pets/me", session.token(), 200)).path("name").asText())
                .isEqualTo("小焰");
    }

    @Test
    @DisplayName("名字去掉首尾空格")
    void nameIsTrimmed() throws Exception {
        Session session = newSession();
        JsonNode pet = dataOf(createPet(session, "DOG", "  旺财  "));
        assertThat(pet.path("name").asText()).isEqualTo("旺财");
    }

    @Test
    @DisplayName("重复领养 -> 409 PET_ALREADY_EXISTS，不覆盖原存档")
    void duplicateAdoptionConflicts() throws Exception {
        Session session = newSession();
        createPet(session, "CAT", "Mimi");

        assertThat(codeOf(createPetRaw(session, "DOG", "Wangcai", 409))).isEqualTo("PET_ALREADY_EXISTS");
        // 原宠物没被换掉
        assertThat(dataOf(getJson("/api/v1/pets/me", session.token(), 200)).path("species").asText())
                .isEqualTo("CAT");
    }

    @Test
    @DisplayName("名字不合法 -> 400 INVALID_NAME")
    void invalidNames() throws Exception {
        assertThat(codeOf(createPetRaw(newSession(), "CAT", "", 400))).isEqualTo("INVALID_NAME");
        assertThat(codeOf(createPetRaw(newSession(), "CAT", "   ", 400))).isEqualTo("INVALID_NAME");
        assertThat(codeOf(createPetRaw(newSession(), "CAT", "一二三四五六七八九", 400))).isEqualTo("INVALID_NAME");
        assertThat(codeOf(createPetRaw(newSession(), "CAT", "a b", 400))).isEqualTo("INVALID_NAME");
        assertThat(codeOf(createPetRaw(newSession(), "CAT", "<script>", 400))).isEqualTo("INVALID_NAME");
    }

    @Test
    @DisplayName("未知物种 -> 400")
    void unknownSpeciesRejected() throws Exception {
        Session session = newSession();
        mockMvc.perform(post("/api/v1/pets")
                        .header(HttpHeaders.AUTHORIZATION, bearer(session))
                        .characterEncoding("UTF-8")
                        .contentType(JSON)
                        .content("{\"species\":\"SLIME\",\"name\":\"Mimi\"}"))
                .andExpect(status().isBadRequest());
    }

    // ================================================================ 操作

    @Test
    @DisplayName("喂食：属性变化、经验 +6、冷却 60 秒")
    void feed() throws Exception {
        Session session = newSession();
        createPet(session, "CAT", "Mimi");

        JsonNode data = dataOf(act(session, "FEED", "req-1", 200));

        // 基础效果是 +30，但属性上限 100，80+30 实际只涨到 100，deltas 返回生效后的真实差值
        assertThat(data.path("deltas").path("satiety").asInt()).isEqualTo(20);
        assertThat(data.path("deltas").path("mood").asInt()).isEqualTo(3);
        assertThat(data.path("deltas").path("hygiene").asInt()).isEqualTo(-2);
        assertThat(data.path("xpGained").asInt()).isEqualTo(6);
        assertThat(data.path("levelUp").asBoolean()).isFalse();
        assertThat(data.path("evolved").asBoolean()).isFalse();
        assertThat(data.path("messageKey").asText()).isEqualTo("FEED_OK");
        assertThat(data.path("cooldownUntil").isNull()).isFalse();

        JsonNode pet = data.path("pet");
        assertThat(pet.path("satiety").asInt()).isEqualTo(100); // 80+30 被钳制
        assertThat(pet.path("mood").asInt()).isEqualTo(83);
        assertThat(pet.path("hygiene").asInt()).isEqualTo(78);
        assertThat(pet.path("exp").asInt()).isEqualTo(6);
        assertThat(pet.path("cooldowns").has("FEED")).isTrue();
    }

    @Test
    @DisplayName("玩耍的代价和收益：心情涨到上限，精力 -12")
    void playEffects() throws Exception {
        Session session = newSession();
        createPet(session, "CAT", "Mimi");

        JsonNode data = dataOf(act(session, "PLAY", "req-1", 200));
        // 猫的心情收益是 22*1.25=28，但 80+28 撞上限，实际只涨 20
        assertThat(data.path("deltas").path("mood").asInt()).isEqualTo(20);
        assertThat(data.path("deltas").path("satiety").asInt()).isEqualTo(-5);
        assertThat(data.path("deltas").path("hygiene").asInt()).isEqualTo(-4);
        assertThat(data.path("deltas").path("energy").asInt()).isEqualTo(-12);
        assertThat(data.path("xpGained").asInt()).isEqualTo(8);
    }

    @Test
    @DisplayName("相同 clientRequestId 重复提交只算一次，且绕开冷却")
    void actionIsIdempotent() throws Exception {
        Session session = newSession();
        createPet(session, "CAT", "Mimi");

        String requestId = "same-id-" + UUID.randomUUID();
        JsonNode first = dataOf(actWithId(session, "FEED", requestId, 200));
        // 紧接着用同一个 clientRequestId 再发一次：冷却还没过，但幂等应当先命中
        JsonNode replay = dataOf(actWithId(session, "FEED", requestId, 200));

        assertThat(replay.path("deltas").path("satiety").asInt())
                .isEqualTo(first.path("deltas").path("satiety").asInt());
        assertThat(replay.path("pet").path("exp").asInt()).isEqualTo(6);
        assertThat(replay.path("pet").path("satiety").asInt()).isEqualTo(100);

        // 再来一个全新的 clientRequestId 才会撞冷却
        assertThat(codeOf(act(session, "FEED", "another-id", 429))).isEqualTo("ACTION_COOLDOWN");
    }

    @Test
    @DisplayName("不同操作各自独立冷却")
    void cooldownIsPerAction() throws Exception {
        Session session = newSession();
        createPet(session, "CAT", "Mimi");

        act(session, "FEED", "req-feed", 200);
        // 喂食在冷却中，但玩耍不受影响
        act(session, "PLAY", "req-play", 200);
    }

    @Test
    @DisplayName("非法操作名 -> 400 INVALID_ACTION")
    void invalidActionRejected() throws Exception {
        Session session = newSession();
        createPet(session, "CAT", "Mimi");

        assertThat(codeOf(act(session, "FLY", "req-1", 400))).isEqualTo("INVALID_ACTION");
        assertThat(codeOf(act(session, "", "req-1", 400))).isEqualTo("INVALID_ACTION");
    }

    @Test
    @DisplayName("无效果操作 -> 409 ACTION_NO_EFFECT，带明确原因")
    void noEffectAction() throws Exception {
        Session session = newSession();
        createPet(session, "CAT", "Mimi");

        // 初始精力 80，高于 70，不能睡
        JsonNode error = act(session, "SLEEP", "req-1", 409);
        assertThat(codeOf(error)).isEqualTo("ACTION_NO_EFFECT");
        assertThat(error.path("message").asText()).isEqualTo("它还不困");

        // 没在睡觉时唤醒
        assertThat(act(session, "WAKE", "req-2", 409).path("message").asText()).isEqualTo("它并没有在睡觉");
    }

    @Test
    @DisplayName("饱食到 95 后不能再喂")
    void feedBlockedWhenFull() throws Exception {
        Session session = newSession();
        createPet(session, "CAT", "Mimi");
        // 直接把宠物喂到 95
        Pet pet = petOf(session);
        pet.setSatiety(95);
        petMapper.updateById(pet);

        assertThat(act(session, "FEED", "req-1", 409).path("message").asText()).isEqualTo("它现在不饿");
    }

    // ================================================================ 睡觉

    @Test
    @DisplayName("睡觉 -> SLEEPING；精力到 95 自动醒来并拿到睡觉经验")
    void sleepThenAutoWake() throws Exception {
        Session session = newSession();
        createPet(session, "CAT", "Mimi");

        // 先玩一次把精力降到 68，才满足睡眠条件
        act(session, "PLAY", "req-play", 200);

        JsonNode sleep = dataOf(act(session, "SLEEP", "req-sleep", 200));
        assertThat(sleep.path("pet").path("status").asText()).isEqualTo("SLEEPING");
        assertThat(sleep.path("pet").path("sleepingSince").isNull()).isFalse();

        // 精力 68 需要 3 小时到 95
        rewindTime(session, 3);
        JsonNode pet = dataOf(getJson("/api/v1/pets/me", session.token(), 200));

        assertThat(pet.path("sleepingSince").isNull()).isTrue();
        assertThat(pet.path("energy").asInt()).isEqualTo(98);
        assertThat(pet.path("satiety").asInt()).isEqualTo(66);
        // 睡觉经验每小时 2 点，3 小时 6 点，加上之前玩耍的 8 点
        assertThat(pet.path("exp").asInt()).isEqualTo(14);
    }

    @Test
    @DisplayName("睡觉中再睡觉 -> 409")
    void sleepTwiceRejected() throws Exception {
        Session session = newSession();
        createPet(session, "CAT", "Mimi");
        act(session, "PLAY", "req-play", 200);
        act(session, "SLEEP", "req-sleep", 200);

        assertThat(act(session, "SLEEP", "req-sleep-2", 409).path("message").asText())
                .isEqualTo("它已经睡着了");
    }

    @Test
    @DisplayName("唤醒：提前结束睡觉，按已睡整小时结算经验")
    void wakeUpEarly() throws Exception {
        Session session = newSession();
        createPet(session, "CAT", "Mimi");
        act(session, "PLAY", "req-play", 200);
        act(session, "SLEEP", "req-sleep", 200);

        rewindTime(session, 2);
        JsonNode wake = dataOf(act(session, "WAKE", "req-wake", 200));

        assertThat(wake.path("pet").path("sleepingSince").isNull()).isTrue();
        // 2 小时睡觉 = 4 点经验，加上玩耍的 8 点
        assertThat(wake.path("pet").path("exp").asInt()).isEqualTo(12);
    }

    // ================================================================ 离线结算

    @Test
    @DisplayName("离线 8 小时：按清醒衰减表下降")
    void offlineSettlement() throws Exception {
        Session session = newSession();
        createPet(session, "CAT", "Mimi");

        rewindTime(session, 8);
        JsonNode pet = dataOf(getJson("/api/v1/pets/me", session.token(), 200));

        assertThat(pet.path("satiety").asInt()).isEqualTo(40);  // 80-40
        assertThat(pet.path("mood").asInt()).isEqualTo(48);     // 80-32
        assertThat(pet.path("hygiene").asInt()).isEqualTo(62);  // 猫 -25%: 80-18
        assertThat(pet.path("energy").asInt()).isEqualTo(48);   // 80-32
        assertThat(pet.path("health").asInt()).isEqualTo(100);
    }

    @Test
    @DisplayName("离线超过 12 小时只按 12 小时结算，超出部分不累计")
    void offlineCapApplies() throws Exception {
        Session session = newSession();
        createPet(session, "CAT", "Mimi");

        rewindTime(session, 24);
        JsonNode pet = dataOf(getJson("/api/v1/pets/me", session.token(), 200));

        assertThat(pet.path("satiety").asInt()).isEqualTo(20);  // 80-60，不是 80-120
        assertThat(pet.path("mood").asInt()).isEqualTo(32);
        assertThat(pet.path("hygiene").asInt()).isEqualTo(53);
        assertThat(pet.path("energy").asInt()).isEqualTo(32);
        // 饱食跌破 25，健康 -2/小时
        assertThat(pet.path("health").asInt()).isEqualTo(76);

        // 紧接着再查一次：超出的 12 小时没有攒着
        JsonNode again = dataOf(getJson("/api/v1/pets/me", session.token(), 200));
        assertThat(again.path("satiety").asInt()).isEqualTo(20);
    }

    @Test
    @DisplayName("长期无人照顾会生病，健康最低到 0 但不会死亡")
    void sicknessAfterProlongedAbsence() throws Exception {
        Session session = newSession();
        createPet(session, "CAT", "Mimi");

        // 第一次 12 小时：饱食 80-60=20 跌破 25，健康 -2/小时
        rewindTime(session, 12);
        JsonNode once = dataOf(getJson("/api/v1/pets/me", session.token(), 200));
        assertThat(once.path("health").asInt()).isEqualTo(76);
        assertThat(once.path("status").asText()).isNotEqualTo("SICK");

        // 第二次 12 小时：饱食和心情都归零，健康 -3/小时
        rewindTime(session, 12);
        JsonNode twice = dataOf(getJson("/api/v1/pets/me", session.token(), 200));
        assertThat(twice.path("health").asInt()).isEqualTo(40);
        assertThat(twice.path("satiety").asInt()).isZero();
        assertThat(twice.path("mood").asInt()).isZero();

        // 第三次 12 小时：三项全空，健康 -5/小时，跌到下限
        rewindTime(session, 12);
        JsonNode thrice = dataOf(getJson("/api/v1/pets/me", session.token(), 200));
        assertThat(thrice.path("health").asInt()).isZero();
        assertThat(thrice.path("status").asText()).isEqualTo("SICK");

        // 宠物永不死亡：健康就是 0，记录仍在
        rewindTime(session, 12);
        JsonNode again = dataOf(getJson("/api/v1/pets/me", session.token(), 200));
        assertThat(again.path("health").asInt()).isZero();
        assertThat(again.path("status").asText()).isEqualTo("SICK");
    }

    // ================================================================ 配置与重置

    @Test
    @DisplayName("游戏配置免鉴权，包含物种、操作、等级阈值和进化条件")
    void gameConfigIsPublic() throws Exception {
        JsonNode data = dataOf(getJson("/api/v1/game/config", null, 200));

        assertThat(data.path("offlineCapHours").asInt()).isEqualTo(12);
        assertThat(data.path("maxLevel").asInt()).isEqualTo(10);
        assertThat(data.path("expThresholds")).hasSize(9);
        assertThat(data.path("expThresholds").get(0).asInt()).isEqualTo(40);
        assertThat(data.path("species")).hasSize(3);
        assertThat(data.path("actions")).hasSize(5);
        assertThat(data.path("evolution")).hasSize(3);
    }

    @Test
    @DisplayName("重置存档：宠物和日志都删掉，可以重新领养")
    void resetDeletesPetAndLogs() throws Exception {
        Session session = newSession();
        createPet(session, "CAT", "Mimi");
        act(session, "FEED", "req-1", 200);

        mockMvc.perform(delete("/api/v1/pets/me").header(HttpHeaders.AUTHORIZATION, bearer(session)))
                .andExpect(status().isOk());

        assertThat(codeOf(getJson("/api/v1/pets/me", session.token(), 404))).isEqualTo("PET_NOT_FOUND");

        // 重置后可以重新领养，而且是一张全新的存档
        JsonNode fresh = dataOf(createPet(session, "DOG", "Wangcai"));
        assertThat(fresh.path("species").asText()).isEqualTo("DOG");
        assertThat(fresh.path("exp").asInt()).isZero();
        assertThat(fresh.path("satiety").asInt()).isEqualTo(80);
    }

    @Test
    @DisplayName("重置不存在的宠物也是成功（幂等）")
    void resetIsIdempotent() throws Exception {
        Session session = newSession();
        mockMvc.perform(delete("/api/v1/pets/me").header(HttpHeaders.AUTHORIZATION, bearer(session)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("没有 dev profile 时开发用时间推进接口不存在（404 而不是 500）")
    void devEndpointIsAbsentOutsideDevProfile() throws Exception {
        Session session = newSession();
        createPet(session, "CAT", "Mimi");

        JsonNode envelope = postJson("/api/v1/dev/advance-time", bearer(session),
                Map.of("hours", "8"), 404);
        assertThat(codeOf(envelope)).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("访问不存在的接口返回 404 而不是 500")
    void unknownPathIsNotFound() throws Exception {
        // 带令牌：鉴权在路由之前，没令牌会先被拦成 401
        Session session = newSession();
        assertThat(codeOf(getJson("/api/v1/does-not-exist", session.token(), 404))).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("全部响应都带 code / message / serverTime")
    void responsesShareTheEnvelope() throws Exception {
        JsonNode envelope = getJson("/api/v1/game/config", null, 200);
        assertThat(envelope.path("code").asText()).isEqualTo("OK");
        assertThat(envelope.path("message").asText()).isEqualTo("success");
        assertThat(envelope.path("serverTime").asText()).endsWith("Z");
    }

    // ================================================================ 测试辅助

    private record Session(long playerId, String token) {
    }

    private static String newDeviceId() {
        return "device-" + UUID.randomUUID();
    }

    private static String bearer(Session session) {
        return "Bearer " + session.token();
    }

    private Session newSession() throws Exception {
        JsonNode data = dataOf(postJson("/api/v1/session", null,
                Map.of("deviceId", newDeviceId()), 200));
        return new Session(data.path("playerId").asLong(), data.path("token").asText());
    }

    /** 领养宠物，断言 200，返回完整响应体（和 act / getJson 保持一致，取 data 用 dataOf）。 */
    private JsonNode createPet(Session session, String species, String name) throws Exception {
        return createPetRaw(session, species, name, 200);
    }

    /** 领养宠物，返回完整响应体（含错误情况）。 */
    private JsonNode createPetRaw(Session session, String species, String name, int expectedStatus)
            throws Exception {
        return postJson("/api/v1/pets", bearer(session), Map.of("species", species, "name", name), expectedStatus);
    }

    /**
     * 执行操作并断言状态码，返回完整响应体。
     *
     * <p>clientRequestId 在 {@code label} 后面加了随机后缀：{@code client_request_id} 是全表唯一索引，
     * 用例之间如果复用同一个值，后跑的用例会命中前面用例的日志而被当成重复请求，
     * 结果什么都没执行就返回了。</p>
     */
    private JsonNode act(Session session, String action, String label, int expectedStatus) throws Exception {
        return actWithId(session, action, label + "-" + UUID.randomUUID(), expectedStatus);
    }

    /** 需要精确控制 clientRequestId 时用这个（幂等用例）。 */
    private JsonNode actWithId(Session session, String action, String clientRequestId, int expectedStatus)
            throws Exception {
        return postJson("/api/v1/pets/me/actions", bearer(session),
                Map.of("action", action, "clientRequestId", clientRequestId), expectedStatus);
    }

    private JsonNode postJson(String path, String authorization, Map<String, String> body, int expectedStatus)
            throws Exception {
        var request = post(path)
                .characterEncoding(StandardCharsets.UTF_8.name())
                .contentType(JSON)
                .content(objectMapper.writeValueAsBytes(body));
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        String response = mockMvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(response);
    }

    private JsonNode getJson(String path, String token, int expectedStatus) throws Exception {
        var request = get(path);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        String response = mockMvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(response);
    }

    private static JsonNode dataOf(JsonNode envelope) {
        return envelope.path("data");
    }

    private static String codeOf(JsonNode envelope) {
        return envelope.path("code").asText();
    }

    private Pet petOf(Session session) {
        Pet pet = petMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(Pet.class)
                        .eq(Pet::getPlayerId, session.playerId()));
        assertThat(pet).isNotNull();
        return pet;
    }

    /** 把宠物的结算游标往前挪，模拟离线了这么久。 */
    private void rewindTime(Session session, long hours) {
        Pet pet = petOf(session);
        pet.setLastSettledAt(pet.getLastSettledAt().minus(hours, ChronoUnit.HOURS));
        if (pet.getSleepingSince() != null) {
            pet.setSleepingSince(pet.getSleepingSince().minus(hours, ChronoUnit.HOURS));
        }
        petMapper.updateById(pet);
    }
}
