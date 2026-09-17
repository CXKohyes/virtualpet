package com.virtualpet.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.virtualpet.battle.Battle;
import com.virtualpet.battle.BattleMapper;
import com.virtualpet.battle.BattleSnapshotCodec;
import com.virtualpet.game.BattleReport;
import com.virtualpet.game.BattleSimulator;
import com.virtualpet.game.BattleSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 异步对战接口测试（PRD 2.11）。
 *
 * <p>最重要的是两条：快照必须是冻结的（打完再改宠物不影响战报），
 * 以及<b>只靠存档里的快照和种子就能把这场战斗一模一样地重跑出来</b>。
 * 后者是"确定性自动战斗"这个承诺的最终验收方式。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl")
class BattleApiIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BattleMapper battleMapper;

    @Autowired
    private BattleSnapshotCodec codec;

    /** 只在"打完事后再改对方宠物"那条测试里用，模拟对方继续养成。 */
    @Autowired
    private com.virtualpet.pet.PetMapper petMapper;

    // ================================================================ 好友码

    @Nested
    @DisplayName("好友码")
    class FriendCode {

        @Test
        @DisplayName("第一次取就发一个，反复取拿到的是同一个（幂等）")
        void friendCodeIsIssuedOnceAndStable() throws Exception {
            Session session = newSession();
            createPet(session, "CAT", "Mimi");

            String first = dataOf(friendCode(session)).path("friendCode").asText();
            String again = dataOf(friendCode(session)).path("friendCode").asText();

            assertThat(first).isNotBlank();
            assertThat(again).isEqualTo(first);
        }

        @Test
        @DisplayName("码里不含 0/O/1/I/L 这些形近字符，方便口头念和手输")
        void friendCodeAvoidsConfusableCharacters() throws Exception {
            for (int i = 0; i < 20; i += 1) {
                Session session = newSession();
                String code = dataOf(friendCode(session)).path("friendCode").asText();

                assertThat(code).hasSize(8);
                assertThat(code).matches("[A-Z2-9]+");
                assertThat(code).doesNotContain("O", "I", "L");
            }
        }

        @Test
        @DisplayName("不同玩家的好友码互不相同")
        void friendCodesAreUnique() throws Exception {
            String a = dataOf(friendCode(newSession())).path("friendCode").asText();
            String b = dataOf(friendCode(newSession())).path("friendCode").asText();
            String c = dataOf(friendCode(newSession())).path("friendCode").asText();

            assertThat(a).isNotEqualTo(b).isNotEqualTo(c);
        }

        @Test
        @DisplayName("好友码接口需要令牌")
        void friendCodeRequiresToken() throws Exception {
            mockMvc.perform(get("/api/v1/players/me/friend-code")).andExpect(status().isUnauthorized());
        }
    }

    // ================================================================ 发起挑战

    @Nested
    @DisplayName("发起挑战")
    class Challenge {

        @Test
        @DisplayName("挑战成功返回完整战报，双方名字和回合都在")
        void challengeReturnsFullReport() throws Exception {
            Session challenger = newSession();
            Session defender = newSession();
            createPet(challenger, "DRAGON", "小蓝");
            createPet(defender, "CAT", "咪咪");

            JsonNode battle = challenge(challenger, friendCodeOf(defender));

            assertThat(battle.path("status").asText()).isEqualTo("FINISHED");
            assertThat(battle.path("viewer").asText()).isEqualTo("CHALLENGER");
            assertThat(battle.path("challenger").path("name").asText()).isEqualTo("小蓝");
            assertThat(battle.path("defender").path("name").asText()).isEqualTo("咪咪");
            assertThat(battle.path("rounds").asInt()).isPositive();
            assertThat(battle.path("timeline")).isNotEmpty();
            assertThat(battle.path("seed").asLong()).isNotZero();
        }

        @Test
        @DisplayName("好友码大小写不敏感，前后空格也容忍")
        void friendCodeIsCaseInsensitive() throws Exception {
            Session challenger = newSession();
            Session defender = newSession();
            createPet(challenger, "CAT", "A");
            createPet(defender, "DOG", "B");

            String code = friendCodeOf(defender);
            JsonNode battle = challenge(challenger, "  " + code.toLowerCase() + "  ");

            assertThat(battle.path("defender").path("name").asText()).isEqualTo("B");
        }

        @Test
        @DisplayName("不能挑战自己")
        void cannotChallengeSelf() throws Exception {
            Session session = newSession();
            createPet(session, "CAT", "Mimi");

            assertThat(codeOf(postJson("/api/v1/battles", session.token(),
                    Map.of("friendCode", friendCodeOf(session)), 409)))
                    .isEqualTo("SELF_CHALLENGE");
        }

        @Test
        @DisplayName("好友码不存在 -> 404")
        void unknownFriendCode() throws Exception {
            Session session = newSession();
            createPet(session, "CAT", "Mimi");

            assertThat(codeOf(postJson("/api/v1/battles", session.token(),
                    Map.of("friendCode", "ZZZZZZZZ"), 404)))
                    .isEqualTo("FRIEND_CODE_NOT_FOUND");
        }

        @Test
        @DisplayName("对方还没领养宠物 -> 409")
        void opponentWithoutPet() throws Exception {
            Session challenger = newSession();
            Session defender = newSession();
            createPet(challenger, "CAT", "Mimi");

            assertThat(codeOf(postJson("/api/v1/battles", challenger.token(),
                    Map.of("friendCode", friendCodeOf(defender)), 409)))
                    .isEqualTo("OPPONENT_NO_PET");
        }

        @Test
        @DisplayName("自己没有宠物 -> 404")
        void challengerWithoutPet() throws Exception {
            Session challenger = newSession();
            Session defender = newSession();
            createPet(defender, "CAT", "Mimi");

            assertThat(codeOf(postJson("/api/v1/battles", challenger.token(),
                    Map.of("friendCode", friendCodeOf(defender)), 404)))
                    .isEqualTo("PET_NOT_FOUND");
        }

        @Test
        @DisplayName("缺少好友码 -> 400")
        void missingFriendCode() throws Exception {
            Session session = newSession();
            createPet(session, "CAT", "Mimi");

            mockMvc.perform(post("/api/v1/battles")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                            .characterEncoding("UTF-8")
                            .contentType(JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("对战不会改变双方宠物的养成状态")
        void battleDoesNotTouchPetState() throws Exception {
            Session challenger = newSession();
            Session defender = newSession();
            createPet(challenger, "CAT", "A");
            createPet(defender, "DOG", "B");

            JsonNode before = dataOf(getJson("/api/v1/pets/me", challenger.token()));
            challenge(challenger, friendCodeOf(defender));
            JsonNode after = dataOf(getJson("/api/v1/pets/me", challenger.token()));

            assertThat(after.path("exp").asInt()).isEqualTo(before.path("exp").asInt());
            assertThat(after.path("level").asInt()).isEqualTo(before.path("level").asInt());
            assertThat(after.path("satiety").asInt()).isEqualTo(before.path("satiety").asInt());
        }
    }

    // ================================================================ 确定性

    @Nested
    @DisplayName("确定性")
    class Determinism {

        @Test
        @DisplayName("只靠存档里的快照和种子，能把这场战斗一模一样地重跑出来")
        void battleCanBeReproducedFromStorage() throws Exception {
            Session challenger = newSession();
            Session defender = newSession();
            createPet(challenger, "DRAGON", "小蓝");
            createPet(defender, "DOG", "旺财");

            long battleId = challenge(challenger, friendCodeOf(defender)).path("id").asLong();

            Battle stored = battleMapper.selectById(battleId);
            BattleSnapshot challengerSnapshot = codec.readSnapshot(stored.getChallengerSnapshot());
            BattleSnapshot defenderSnapshot = codec.readSnapshot(stored.getDefenderSnapshot());
            BattleReport storedReport = codec.readReport(stored.getResultJson());

            // 拿存档里的两样东西重跑
            BattleReport replayed = BattleSimulator.simulate(
                    challengerSnapshot, defenderSnapshot, stored.getSeed());

            assertThat(objectMapper.writeValueAsString(replayed))
                    .as("重跑出来的战报必须和存档里的一模一样，否则「确定性」就是空话")
                    .isEqualTo(objectMapper.writeValueAsString(storedReport));
        }

        @Test
        @DisplayName("打完之后对方继续养成，已经打完的战报不受影响")
        void reportIsImmutableAfterBattle() throws Exception {
            Session challenger = newSession();
            Session defender = newSession();
            createPet(challenger, "CAT", "A");
            createPet(defender, "DRAGON", "B");

            long battleId = challenge(challenger, friendCodeOf(defender)).path("id").asLong();
            Battle before = battleMapper.selectById(battleId);

            // 被挑战方在这之后大幅变强。测试 profile 下没有 dev 铺状态接口，直接改库。
            var defenderPet = petMapper.selectById(
                    battleMapper.selectById(battleId).getDefenderPetId());
            defenderPet.setLevel(10);
            defenderPet.setEvolutionStage(2);
            petMapper.updateById(defenderPet);

            Battle after = battleMapper.selectById(battleId);

            assertThat(after.getResultJson())
                    .as("快照是冻结的：对方事后变强不会改写已经打完的战报")
                    .isEqualTo(before.getResultJson());
            assertThat(dataOf(getJson("/api/v1/battles/" + battleId, challenger.token()))
                    .path("defender").path("level").asInt())
                    .as("响应里读的是快照，不是对方的实时等级")
                    .isNotEqualTo(10);
        }

        @Test
        @DisplayName("同一对宠物打两次是两场不同的战斗（种子不同）")
        void eachChallengeGetsItsOwnSeed() throws Exception {
            Session challenger = newSession();
            Session defender = newSession();
            createPet(challenger, "CAT", "A");
            createPet(defender, "DOG", "B");

            String code = friendCodeOf(defender);
            JsonNode first = challenge(challenger, code);
            JsonNode second = challenge(challenger, code);

            assertThat(first.path("id").asLong()).isNotEqualTo(second.path("id").asLong());
            assertThat(first.path("seed").asLong()).isNotEqualTo(second.path("seed").asLong());
        }
    }

    // ================================================================ 查询

    @Nested
    @DisplayName("查询")
    class Query {

        @Test
        @DisplayName("双方都能查同一场，各自看到自己的视角")
        void bothSidesCanReadTheBattle() throws Exception {
            Session challenger = newSession();
            Session defender = newSession();
            createPet(challenger, "CAT", "A");
            createPet(defender, "DOG", "B");

            long battleId = challenge(challenger, friendCodeOf(defender)).path("id").asLong();

            assertThat(dataOf(getJson("/api/v1/battles/" + battleId, challenger.token()))
                    .path("viewer").asText()).isEqualTo("CHALLENGER");
            assertThat(dataOf(getJson("/api/v1/battles/" + battleId, defender.token()))
                    .path("viewer").asText()).isEqualTo("DEFENDER");
        }

        @Test
        @DisplayName("局外人查不到别人的对战（记录里有双方宠物的完整状态）")
        void outsidersCannotRead() throws Exception {
            Session challenger = newSession();
            Session defender = newSession();
            Session outsider = newSession();
            createPet(challenger, "CAT", "A");
            createPet(defender, "DOG", "B");
            createPet(outsider, "DRAGON", "C");

            long battleId = challenge(challenger, friendCodeOf(defender)).path("id").asLong();

            assertThat(codeOf(getJson("/api/v1/battles/" + battleId, outsider.token(), 404)))
                    .isEqualTo("BATTLE_NOT_FOUND");
        }

        @Test
        @DisplayName("不存在的对战 -> 404")
        void missingBattle() throws Exception {
            Session session = newSession();
            createPet(session, "CAT", "A");

            assertThat(codeOf(getJson("/api/v1/battles/999999", session.token(), 404)))
                    .isEqualTo("BATTLE_NOT_FOUND");
        }

        @Test
        @DisplayName("最近对战列表双方都能看到，而且是站在自己的视角")
        void recentListShowsBothSides() throws Exception {
            Session challenger = newSession();
            Session defender = newSession();
            createPet(challenger, "CAT", "小猫咪");
            createPet(defender, "DOG", "旺财");
            challenge(challenger, friendCodeOf(defender));

            JsonNode asChallenger = dataOf(getJson("/api/v1/battles", challenger.token()));
            JsonNode asDefender = dataOf(getJson("/api/v1/battles", defender.token()));

            assertThat(asChallenger).hasSize(1);
            assertThat(asDefender).hasSize(1);
            assertThat(asChallenger.get(0).path("viewer").asText()).isEqualTo("CHALLENGER");
            // 列表里的对手名字是"对方的"，两边看到的正好相反
            assertThat(asChallenger.get(0).path("opponentName").asText()).isEqualTo("旺财");
            assertThat(asDefender.get(0).path("opponentName").asText()).isEqualTo("小猫咪");
        }

        @Test
        @DisplayName("列表新的在前，轮数对得上")
        void recentListIsNewestFirst() throws Exception {
            Session challenger = newSession();
            Session defender = newSession();
            createPet(challenger, "CAT", "A");
            createPet(defender, "DOG", "B");
            String code = friendCodeOf(defender);

            long first = challenge(challenger, code).path("id").asLong();
            long second = challenge(challenger, code).path("id").asLong();

            JsonNode list = dataOf(getJson("/api/v1/battles", challenger.token()));

            assertThat(list).hasSize(2);
            assertThat(list.get(0).path("id").asLong()).isEqualTo(second);
            assertThat(list.get(1).path("id").asLong()).isEqualTo(first);
        }

        @Test
        @DisplayName("没有对战记录时返回空数组，不报错")
        void emptyListForNewPlayer() throws Exception {
            Session session = newSession();
            createPet(session, "CAT", "A");

            assertThat(dataOf(getJson("/api/v1/battles", session.token()))).isEmpty();
        }

        @Test
        @DisplayName("订阅主题由接口给出，前端不用把路径写死")
        void topicIsExposed() throws Exception {
            Session session = newSession();

            JsonNode topic = dataOf(getJson("/api/v1/battles/topic", session.token()));

            assertThat(topic.path("prefix").asText()).isEqualTo("/topic/battles/");
            assertThat(topic.path("pattern").asText()).isEqualTo("/topic/battles/{battleId}");
        }
    }

    // ================================================================ 辅助

    private record Session(Long playerId, String token) {
    }

    private Session newSession() throws Exception {
        JsonNode data = dataOf(postJson("/api/v1/session", null,
                Map.of("deviceId", "device-" + UUID.randomUUID()), 200));
        return new Session(data.path("playerId").asLong(), data.path("token").asText());
    }

    private JsonNode createPet(Session session, String species, String name) throws Exception {
        return dataOf(postJson("/api/v1/pets", session.token(),
                Map.of("species", species, "name", name), 200));
    }

    private JsonNode friendCode(Session session) throws Exception {
        return getJson("/api/v1/players/me/friend-code", session.token(), 200);
    }

    private String friendCodeOf(Session session) throws Exception {
        return dataOf(friendCode(session)).path("friendCode").asText();
    }

    private JsonNode challenge(Session session, String friendCode) throws Exception {
        return dataOf(postJson("/api/v1/battles", session.token(),
                Map.of("friendCode", friendCode), 200));
    }

    private JsonNode postJson(String path, String token, Map<String, String> body, int expectedStatus)
            throws Exception {
        var request = post(path).characterEncoding("UTF-8").contentType(JSON)
                .content(objectMapper.writeValueAsString(body));
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
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

    private JsonNode getJson(String path, String token) throws Exception {
        return getJson(path, token, 200);
    }

    private static JsonNode dataOf(JsonNode envelope) {
        return envelope.path("data");
    }

    private static String codeOf(JsonNode envelope) {
        return envelope.path("code").asText();
    }
}
