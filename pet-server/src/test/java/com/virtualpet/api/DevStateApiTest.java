package com.virtualpet.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 开发用状态铺设接口测试（批次 5 验收工具）。
 *
 * <p>覆盖的是<b>验收路径本身</b>：进化和健康恢复在真实节奏下要几十分钟才能走到，
 * 没有这个工具就无法回归。这里断言的是"铺完状态之后，真实规则给出的结论"，
 * 所以顺带也把 {@code PetProgressService} 和生病滞回在 HTTP 层验了一遍。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "dev"})
@TestPropertySource(properties = "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl")
class DevStateApiTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------------------------------------------------------- 进化

    @Test
    @DisplayName("经验推到 150 -> 4 级，并且进化到成长形态")
    void reachesGrowthStage() throws Exception {
        String token = newSessionToken();
        createPet(token, "CAT", "Mimi");

        JsonNode pet = dataOf(setState(token, Map.of("exp", 150)));

        assertThat(pet.path("level").asInt()).isEqualTo(4);
        assertThat(pet.path("evolutionStage").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("经验推到 490 -> 8 级，四项达标后进化到最终形态")
    void reachesFinalStage() throws Exception {
        String token = newSessionToken();
        createPet(token, "DRAGON", "Long");

        JsonNode pet = dataOf(setState(token, Map.of("exp", 490)));

        assertThat(pet.path("level").asInt()).isEqualTo(8);
        // 初始属性 80 / 健康 100，已经满足"健康 ≥70 且四项 ≥50"
        assertThat(pet.path("evolutionStage").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("等级够了但属性不达标，不进化")
    void doesNotEvolveWhenAttributesFallShort() throws Exception {
        String token = newSessionToken();
        createPet(token, "CAT", "Mimi");

        // 一次性把经验和属性都铺下去，属性全会拦在成长形态门外
        JsonNode pet = dataOf(setState(token, Map.of(
                "exp", 490, "health", 40, "satiety", 20, "mood", 20, "hygiene", 20, "energy", 20)));

        assertThat(pet.path("level").asInt()).isEqualTo(8);
        assertThat(pet.path("evolutionStage").asInt()).isEqualTo(0);
    }

    @Test
    @DisplayName("进化不可逆：属性掉下来也不会退化")
    void evolutionIsIrreversible() throws Exception {
        String token = newSessionToken();
        createPet(token, "CAT", "Mimi");
        setState(token, Map.of("exp", 490, "health", 100, "satiety", 80, "mood", 80, "hygiene", 80, "energy", 80));

        JsonNode pet = dataOf(setState(token, Map.of(
                "health", 10, "satiety", 0, "mood", 0, "hygiene", 0, "energy", 0)));

        assertThat(pet.path("evolutionStage").asInt()).isEqualTo(2);
    }

    // ---------------------------------------------------------- 生病与恢复

    @Test
    @DisplayName("健康压到 30 以下进入生病")
    void entersSickState() throws Exception {
        String token = newSessionToken();
        createPet(token, "DOG", "Wang");

        JsonNode pet = dataOf(setState(token, Map.of("health", 29)));

        assertThat(pet.path("status").asText()).isEqualTo("SICK");
    }

    @Test
    @DisplayName("生病是滞回：回到 30–49 之间不解除，到 50 才解除（PRD 2.3）")
    void sicknessIsHysteresis() throws Exception {
        String token = newSessionToken();
        createPet(token, "DOG", "Wang");

        assertThat(dataOf(setState(token, Map.of("health", 29))).path("status").asText()).isEqualTo("SICK");
        assertThat(dataOf(setState(token, Map.of("health", 40))).path("status").asText())
                .as("40 在 30–49 之间，应当保持生病")
                .isEqualTo("SICK");
        assertThat(dataOf(setState(token, Map.of("health", 49))).path("status").asText())
                .as("49 仍然不够")
                .isEqualTo("SICK");
        assertThat(dataOf(setState(token, Map.of("health", 50))).path("status").asText())
                .as("到 50 才解除")
                .isNotEqualTo("SICK");
    }

    @Test
    @DisplayName("健康的人设到 30–49 之间不会变成生病")
    void healthyPetDoesNotBecomeSickBetweenThresholds() throws Exception {
        String token = newSessionToken();
        createPet(token, "DOG", "Wang");

        JsonNode pet = dataOf(setState(token, Map.of("health", 40)));

        assertThat(pet.path("status").asText()).isNotEqualTo("SICK");
    }

    @Test
    @DisplayName("四项都高于 60 时，健康按小时恢复")
    void healthRecoversWhenAllCoreAreHigh() throws Exception {
        String token = newSessionToken();
        createPet(token, "CAT", "Mimi");

        // 健康 40 刚好没过生病线，四项拉满
        JsonNode before = dataOf(setState(token, Map.of(
                "satiety", 100, "mood", 100, "hygiene", 100, "energy", 100, "health", 40)));
        assertThat(before.path("health").asInt()).isEqualTo(40);

        // 4 小时后四项仍然都高于 60（饱食 -20、心情 -16、清洁 -12、精力 -16），健康 +1/小时
        JsonNode after = dataOf(advanceTime(token, 4));

        assertThat(after.path("health").asInt()).isEqualTo(44);
        assertThat(after.path("satiety").asInt()).isEqualTo(80);
    }

    @Test
    @DisplayName("四项里有任何一项掉到 60 以下，健康就不再恢复")
    void healthStopsRecoveringWhenOneCoreDrops() throws Exception {
        String token = newSessionToken();
        createPet(token, "CAT", "Mimi");

        // 心情只给 65：4 小时后掉到 49，健康增益不成立
        setState(token, Map.of("satiety", 100, "mood", 65, "hygiene", 100, "energy", 100, "health", 40));
        JsonNode after = dataOf(advanceTime(token, 4));

        assertThat(after.path("mood").asInt()).isEqualTo(49);
        assertThat(after.path("health").asInt())
                .as("心情低于阈值，健康既不涨也不掉（没跌破流失线）")
                .isEqualTo(40);
    }

    /**
     * 健康恢复的窗口比直觉窄得多。
     *
     * <p>增益要求四项<b>全部高于</b> 60，而饱食每小时掉 5 —— 满值 100 撑不过 8 小时
     * （100 - 5×8 = 60，正好不满足"高于"）。所以<b>一次结算最多只能有 8 小时</b>，
     * 7 小时是安全值。这条边界直接决定了玩家该怎么照顾宠物，值得钉住。</p>
     */
    @Test
    @DisplayName("健康恢复的窗口上限是 8 小时：满值撑到 8 小时就不涨了")
    void healthRecoveryWindowIsEightHours() throws Exception {
        String token = newSessionToken();
        createPet(token, "CAT", "Mimi");

        // 8 小时：饱食正好落到 60，不满足"高于 60"
        setState(token, Map.of("satiety", 100, "mood", 100, "hygiene", 100, "energy", 100, "health", 40));
        JsonNode eightHours = dataOf(advanceTime(token, 8));
        assertThat(eightHours.path("satiety").asInt()).isEqualTo(60);
        assertThat(eightHours.path("health").asInt()).as("正好卡在阈值上，不恢复").isEqualTo(40);

        // 7 小时：饱食 65，还在阈值之上，健康 +1/小时
        setState(token, Map.of("satiety", 100, "mood", 100, "hygiene", 100, "energy", 100, "health", 40));
        JsonNode sevenHours = dataOf(advanceTime(token, 7));
        assertThat(sevenHours.path("satiety").asInt()).isEqualTo(65);
        assertThat(sevenHours.path("health").asInt()).isEqualTo(47);
    }

    @Test
    @DisplayName("生病后持续照护，可以恢复到 50 以上并解除生病")
    void recoversFromSickness() throws Exception {
        String token = newSessionToken();
        createPet(token, "DOG", "Wang");

        JsonNode sick = dataOf(setState(token, Map.of("health", 25)));
        assertThat(sick.path("status").asText()).isEqualTo("SICK");

        // 狗的健康恢复有 +1/小时 加成，所以是 2/小时。每段 6 小时，留出窗口余量。
        int health = 25;
        for (int round = 1; round <= 3; round += 1) {
            setState(token, Map.of("satiety", 100, "mood", 100, "hygiene", 100, "energy", 100));
            JsonNode after = dataOf(advanceTime(token, 6));
            health += 12;

            assertThat(after.path("health").asInt()).as("第 %d 轮恢复", round).isEqualTo(health);
            if (health >= 50) {
                assertThat(after.path("status").asText()).as("过 50 就该解除生病").isNotEqualTo("SICK");
            } else {
                assertThat(after.path("status").asText()).as("还没到 50，继续病着").isEqualTo("SICK");
            }
        }

        assertThat(health).as("三轮下来确实过线了").isGreaterThanOrEqualTo(50);
    }

    // ---------------------------------------------------------- 参数校验与权限

    @Test
    @DisplayName("属性超出 0–100 或经验为负 -> 400")
    void validatesRanges() throws Exception {
        String token = newSessionToken();
        createPet(token, "CAT", "Mimi");

        for (String body : new String[]{
                "{\"satiety\":101}",
                "{\"health\":-1}",
                "{\"exp\":-5}",
                "{\"exp\":999999}",
        }) {
            mockMvc.perform(post("/api/v1/dev/set-state")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .characterEncoding("UTF-8")
                            .contentType(JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    @DisplayName("什么都不传等于什么都不改")
    void emptyRequestKeepsState() throws Exception {
        String token = newSessionToken();
        createPet(token, "CAT", "Mimi");

        JsonNode pet = dataOf(setState(token, Map.of()));

        assertThat(pet.path("satiety").asInt()).isEqualTo(80);
        assertThat(pet.path("level").asInt()).isEqualTo(1);
        assertThat(pet.path("evolutionStage").asInt()).isEqualTo(0);
    }

    @Test
    @DisplayName("和普通接口一样需要令牌")
    void requiresToken() throws Exception {
        mockMvc.perform(post("/api/v1/dev/set-state")
                        .characterEncoding("UTF-8")
                        .contentType(JSON)
                        .content("{\"exp\":150}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("没有宠物时 -> 404")
    void requiresPet() throws Exception {
        String token = newSessionToken();

        mockMvc.perform(post("/api/v1/dev/set-state")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .characterEncoding("UTF-8")
                        .contentType(JSON)
                        .content("{\"exp\":150}"))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- 辅助

    private String newSessionToken() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("deviceId", "device-" + UUID.randomUUID()));
        String response = mockMvc.perform(post("/api/v1/session")
                        .characterEncoding("UTF-8")
                        .contentType(JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(response).path("data").path("token").asText();
    }

    private void createPet(String token, String species, String name) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("species", species, "name", name));
        mockMvc.perform(post("/api/v1/pets")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .characterEncoding("UTF-8")
                        .contentType(JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private String setState(String token, Map<String, ?> changes) throws Exception {
        return mockMvc.perform(post("/api/v1/dev/set-state")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .characterEncoding("UTF-8")
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(changes)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String advanceTime(String token, int hours) throws Exception {
        return mockMvc.perform(post("/api/v1/dev/advance-time")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .characterEncoding("UTF-8")
                        .contentType(JSON)
                        .content("{\"hours\":" + hours + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private JsonNode dataOf(String response) throws Exception {
        return objectMapper.readTree(response).path("data");
    }
}
