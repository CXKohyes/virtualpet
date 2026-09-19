package com.virtualpet.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.virtualpet.game.GameRules;
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
    @DisplayName("槽位满了再领养 -> 409 PET_SLOTS_FULL，已有宠物不受影响")
    void adoptionIsRejectedWhenSlotsAreFull() throws Exception {
        Session session = newSession();
        createPet(session, "CAT", "Mimi");
        createPet(session, "DOG", "Wangcai");
        createPet(session, "DRAGON", "Xiaoyan");

        // 这条原来叫 duplicateAdoptionConflicts，断言的是「第二次领养就 409」。
        // 多宠物槽之后重复领养是正常操作，冲突只在槽位真的满了（第四只）时发生。
        assertThat(codeOf(createPetRaw(session, "CAT", "Duoyu", 409))).isEqualTo("PET_SLOTS_FULL");
        // 原有三只没被换掉：当前宠物仍是最后领养的那只
        assertThat(dataOf(getJson("/api/v1/pets/me", session.token(), 200)).path("species").asText())
                .isEqualTo("DRAGON");
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
        // 回放返回的是当初那条日志，前端据此更新日志区就不会重复记一笔
        assertThat(replay.path("journalEntry").path("id").asLong())
                .isEqualTo(first.path("journalEntry").path("id").asLong());

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

    /**
     * 唤醒必须真的把 {@code sleeping_since} 写回 null。
     *
     * <p>上面那条测试只看了响应体 —— 而响应体是用内存里的实体拼的，就算没落库也一样是
     * {@code null}。这条换个角度：唤醒之后<b>重新读一次库</b>。曾经就是因为
     * MyBatis-Plus 默认跳过 null 字段，唤醒写不进库，重新读回来还是"在睡觉"。</p>
     */
    @Test
    @DisplayName("唤醒后重新读库：sleepingSince 确实被清掉了")
    void wakeUpPersistsToDatabase() throws Exception {
        Session session = newSession();
        createPet(session, "CAT", "Mimi");
        act(session, "PLAY", "req-play", 200);
        act(session, "SLEEP", "req-sleep", 200);
        rewindTime(session, 2);
        act(session, "WAKE", "req-wake", 200);

        // 直接看数据库那一行
        assertThat(petOf(session).getSleepingSince())
                .as("MyBatis-Plus 默认跳过 null 字段，唤醒曾经写不进库")
                .isNull();

        // 再走一遍接口重新读，确认对外的表现也是清醒的
        JsonNode reloaded = dataOf(getJson("/api/v1/pets/me", session.token(), 200));
        assertThat(reloaded.path("sleepingSince").isNull())
                .as("重新读回来还带着 sleepingSince 的话，界面会显示正常状态却给「唤醒」按钮")
                .isTrue();
        assertThat(reloaded.path("status").asText()).isNotEqualTo("SLEEPING");
    }

    @Test
    @DisplayName("醒着的时候再唤醒 -> 409，不会重复发睡觉经验")
    void wakingTwiceIsRejected() throws Exception {
        Session session = newSession();
        createPet(session, "CAT", "Mimi");
        act(session, "PLAY", "req-play", 200);
        act(session, "SLEEP", "req-sleep", 200);
        rewindTime(session, 2);
        JsonNode first = dataOf(act(session, "WAKE", "req-wake-1", 200));

        // 再等两小时。宠物醒着，这两小时不该产生任何经验；
        // 要是第二次唤醒被放行，它会拿陈旧的 sleepingSince 又发一份睡觉经验
        rewindTime(session, 2);
        assertThat(act(session, "WAKE", "req-wake-2", 409).path("message").asText())
                .isEqualTo("它并没有在睡觉");

        assertThat(first.path("pet").path("exp").asInt()).as("玩耍 8 + 睡 2 小时 4").isEqualTo(12);
        assertThat(dataOf(getJson("/api/v1/pets/me", session.token(), 200)).path("exp").asInt())
                .as("经验只能来自真实经过的时间，反复唤醒不该刷出经验")
                .isEqualTo(12);
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

    @Test
    @DisplayName("离线后查询会带上结算摘要（PRD 2.5）")
    void settlementSummaryIsReturned() throws Exception {
        Session session = newSession();
        createPet(session, "CAT", "Mimi");

        // 刚创建完立刻查：没有经过时间，不该有摘要
        JsonNode immediate = dataOf(getJson("/api/v1/pets/me", session.token(), 200));
        assertThat(immediate.path("settlement").isNull()).isTrue();

        rewindTime(session, 12);
        JsonNode summary = dataOf(getJson("/api/v1/pets/me", session.token(), 200)).path("settlement");

        assertThat(summary.path("settledHours").asLong()).isEqualTo(12);
        assertThat(summary.path("deltas").path("satiety").asInt()).isEqualTo(-60);
        assertThat(summary.path("deltas").path("mood").asInt()).isEqualTo(-48);
        assertThat(summary.path("deltas").path("hygiene").asInt()).isEqualTo(-27);
        assertThat(summary.path("deltas").path("energy").asInt()).isEqualTo(-48);
        assertThat(summary.path("deltas").path("health").asInt()).isEqualTo(-24);
        assertThat(summary.path("statusBefore").asText()).isEqualTo("NORMAL");
        assertThat(summary.path("statusAfter").asText()).isEqualTo("HUNGRY");
        assertThat(summary.path("wokeUp").asBoolean()).isFalse();

        // 紧接着再查一次：时间没有前进，摘要又回到 null
        JsonNode again = dataOf(getJson("/api/v1/pets/me", session.token(), 200));
        assertThat(again.path("settlement").isNull()).isTrue();
    }

    @Test
    @DisplayName("睡觉中途醒来时摘要会标明 wokeUp")
    void settlementSummaryReportsWakingUp() throws Exception {
        Session session = newSession();
        createPet(session, "CAT", "Mimi");
        act(session, "PLAY", "req-play", 200);
        act(session, "SLEEP", "req-sleep", 200);

        rewindTime(session, 3);
        JsonNode summary = dataOf(getJson("/api/v1/pets/me", session.token(), 200)).path("settlement");

        assertThat(summary.path("wokeUp").asBoolean()).isTrue();
        assertThat(summary.path("sleptHours").asLong()).isEqualTo(3);
        assertThat(summary.path("statusBefore").asText()).isEqualTo("SLEEPING");
    }

    @Test
    @DisplayName("照护日志来自数据库，新的在前，刷新页面也还在")
    void journalIsServedFromDatabase() throws Exception {
        Session session = newSession();
        createPet(session, "CAT", "Mimi");

        assertThat(dataOf(getJson("/api/v1/pets/me/journal", session.token(), 200)).size()).isZero();

        JsonNode feedAck = dataOf(act(session, "FEED", "journal-1", 200));
        act(session, "PLAY", "journal-2", 200);

        JsonNode entries = dataOf(getJson("/api/v1/pets/me/journal", session.token(), 200));
        assertThat(entries.size()).isEqualTo(2);

        // 操作响应里带回来的那条，就是日志列表里的那一条
        assertThat(feedAck.path("journalEntry").path("id").asLong())
                .isEqualTo(entries.get(1).path("id").asLong());

        // 新的在前
        assertThat(entries.get(0).path("action").asText()).isEqualTo("PLAY");
        assertThat(entries.get(1).path("action").asText()).isEqualTo("FEED");
        assertThat(entries.get(1).path("xpGained").asInt()).isEqualTo(6);
        assertThat(entries.get(1).path("messageKey").asText()).isEqualTo("FEED_OK");
        assertThat(entries.get(1).path("levelUp").asBoolean()).isFalse();
        assertThat(entries.get(1).path("deltas").path("mood").asInt()).isEqualTo(3);
        assertThat(entries.get(1).path("at").asText()).isNotBlank();
    }

    @Test
    @DisplayName("日志条数会被钳制在 1–50")
    void journalLimitIsClamped() throws Exception {
        Session session = newSession();
        createPet(session, "CAT", "Mimi");
        act(session, "FEED", "limit-1", 200);
        act(session, "PLAY", "limit-2", 200);

        assertThat(dataOf(getJson("/api/v1/pets/me/journal?limit=1", session.token(), 200)).size())
                .isEqualTo(1);
        assertThat(dataOf(getJson("/api/v1/pets/me/journal?limit=999", session.token(), 200)).size())
                .isEqualTo(2);
        // 0 和负数被抬到 1，而不是报错
        assertThat(dataOf(getJson("/api/v1/pets/me/journal?limit=0", session.token(), 200)).size())
                .isEqualTo(1);
        assertThat(dataOf(getJson("/api/v1/pets/me/journal?limit=-5", session.token(), 200)).size())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("日志接口同样需要令牌，且送走宠物后清空")
    void journalFollowsResetAndAuth() throws Exception {
        Session session = newSession();

        mockMvc.perform(get("/api/v1/pets/me/journal")).andExpect(status().isUnauthorized());

        JsonNode pet = dataOf(createPet(session, "CAT", "Mimi"));
        act(session, "FEED", "reset-journal", 200);
        assertThat(dataOf(getJson("/api/v1/pets/me/journal", session.token(), 200)).size()).isEqualTo(1);

        release(session, pet.path("id").asLong());

        // 宠物没了，日志也一起删了
        assertThat(codeOf(getJson("/api/v1/pets/me/journal", session.token(), 404)))
                .isEqualTo("PET_NOT_FOUND");
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
    @DisplayName("送走宠物：宠物和日志都删掉，可以重新领养")
    void releaseDeletesPetAndLogs() throws Exception {
        Session session = newSession();
        JsonNode pet = dataOf(createPet(session, "CAT", "Mimi"));
        act(session, "FEED", "req-1", 200);

        release(session, pet.path("id").asLong());

        assertThat(codeOf(getJson("/api/v1/pets/me", session.token(), 404))).isEqualTo("PET_NOT_FOUND");

        // 送走后可以重新领养，而且是一张全新的存档
        JsonNode fresh = dataOf(createPet(session, "DOG", "Wangcai"));
        assertThat(fresh.path("species").asText()).isEqualTo("DOG");
        assertThat(fresh.path("exp").asInt()).isZero();
        assertThat(fresh.path("satiety").asInt()).isEqualTo(80);
    }

    @Test
    @DisplayName("重复送走同一只返回 404，不是静默成功")
    void releasingTwiceIsNotFound() throws Exception {
        Session session = newSession();
        JsonNode pet = dataOf(createPet(session, "CAT", "Mimi"));
        long petId = pet.path("id").asLong();

        release(session, petId);
        // 原来这里是「幂等返回 200」。单宠物时代「重置」没有对象，现在
        // 「送走哪一只」是有明确对象的，对象已经没了就该说没了 ——
        // 客户端也能据此知道自己的名册是旧的。
        assertThat(codeOf(releaseRaw(session, petId, 404))).isEqualTo("PET_NOT_FOUND");
    }

    @Test
    @DisplayName("退役的 DELETE /pets/me 返回 400 而不是 500")
    void retiredResetRouteIsNotAServerError() throws Exception {
        Session session = newSession();
        createPet(session, "CAT", "Mimi");

        // "me" 往 Long 上转失败。没有专门的处理器时这会落到兜底分支变成 500，
        // 客户端把 URL 写错、服务端却报「服务器开小差了」，排查方向会被带偏。
        var response = mockMvc.perform(delete("/api/v1/pets/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(session)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(objectMapper.readTree(response).path("code").asText()).isEqualTo("INVALID_REQUEST");
    }

    // ================================================================ 多宠物槽

    @Test
    @DisplayName("名册返回全部宠物，按槽位升序，标出当前那只")
    void rosterListsAllPets() throws Exception {
        Session session = newSession();
        JsonNode first = dataOf(createPet(session, "CAT", "甲"));
        JsonNode second = dataOf(createPet(session, "DOG", "乙"));

        JsonNode roster = dataOf(getJson("/api/v1/pets", session.token(), 200));

        assertThat(roster).hasSize(2);
        assertThat(roster.get(0).path("slot").asInt()).isZero();
        assertThat(roster.get(1).path("slot").asInt()).isEqualTo(1);
        assertThat(roster.get(0).path("id").asLong()).isEqualTo(first.path("id").asLong());
        // 只有一只是当前宠物，且是最后领养的那只
        assertThat(roster.get(0).path("active").asBoolean()).isFalse();
        assertThat(roster.get(1).path("active").asBoolean()).isTrue();
        assertThat(roster.get(1).path("id").asLong()).isEqualTo(second.path("id").asLong());
    }

    @Test
    @DisplayName("切换当前宠物后，/pets/me 跟着变")
    void activatingSwitchesWhatMeReturns() throws Exception {
        Session session = newSession();
        JsonNode first = dataOf(createPet(session, "CAT", "甲"));
        createPet(session, "DOG", "乙");

        JsonNode switched = dataOf(activateRaw(session, first.path("id").asLong(), 200));

        assertThat(switched.path("id").asLong()).isEqualTo(first.path("id").asLong());
        assertThat(switched.path("active").asBoolean()).isTrue();
        assertThat(dataOf(getJson("/api/v1/pets/me", session.token(), 200)).path("id").asLong())
                .isEqualTo(first.path("id").asLong());
    }

    @Test
    @DisplayName("切换别人的宠物返回 404，和切换不存在的宠物无从区分")
    void activatingStrangersPetIsNotFound() throws Exception {
        Session mine = newSession();
        Session theirs = newSession();
        JsonNode theirPet = dataOf(createPet(theirs, "DRAGON", "别人的"));

        long strangerId = theirPet.path("id").asLong();
        assertThat(codeOf(activateRaw(mine, strangerId, 404))).isEqualTo("PET_NOT_FOUND");
        assertThat(codeOf(activateRaw(mine, 999_999L, 404))).isEqualTo("PET_NOT_FOUND");
    }

    @Test
    @DisplayName("配置接口返回槽位上限")
    void configExposesMaxSlots() throws Exception {
        JsonNode data = dataOf(getJson("/api/v1/game/config", null, 200));
        assertThat(data.path("maxSlots").asInt()).isEqualTo(GameRules.MAX_PET_SLOTS);
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

    /**
     * POST 一个 JSON 请求体并断言状态码，返回完整响应体。
     *
     * <p>体是 {@code Map<String, ?>} 而不是 {@code Map<String, String>}：
     * {@code /pets/me/active} 的 {@code petId} 是数字，用字符串传虽然 Jackson
     * 多半也能转，但那样测的就不是真实的请求形状了。</p>
     */
    private JsonNode postJson(String path, String authorization, Map<String, ?> body, int expectedStatus)
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

    /** 送走一只宠物并断言状态码，返回完整响应体。 */
    private JsonNode releaseRaw(Session session, Object petId, int expectedStatus) throws Exception {
        var request = delete("/api/v1/pets/{petId}", petId)
                .header(HttpHeaders.AUTHORIZATION, bearer(session));
        String response = mockMvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(response);
    }

    /** 送走一只宠物，断言 200。 */
    private JsonNode release(Session session, Object petId) throws Exception {
        return releaseRaw(session, petId, 200);
    }

    /** 切换当前宠物。 */
    private JsonNode activateRaw(Session session, Object petId, int expectedStatus) throws Exception {
        return postJson("/api/v1/pets/me/active", bearer(session), Map.of("petId", petId), expectedStatus);
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
