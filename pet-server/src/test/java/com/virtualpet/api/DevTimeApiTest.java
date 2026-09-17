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
 * 开发用时间推进接口测试（TECH_DESIGN 5.7）。
 *
 * <p>必须激活 {@code dev} profile，接口才会存在。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "dev"})
// dev profile 会打开 MyBatis 的 SQL 打印，测试里关掉，免得刷屏
@TestPropertySource(properties = "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl")
class DevTimeApiTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("推进时间会走真实结算：8 小时后属性按清醒表下降")
    void advanceTimeSettlesForReal() throws Exception {
        String token = newSessionToken();
        createPet(token, "CAT", "Mimi");

        JsonNode pet = dataOf(advanceTime(token, 8));

        assertThat(pet.path("satiety").asInt()).isEqualTo(40);
        assertThat(pet.path("mood").asInt()).isEqualTo(48);
        assertThat(pet.path("hygiene").asInt()).isEqualTo(62);
        assertThat(pet.path("energy").asInt()).isEqualTo(48);
    }

    @Test
    @DisplayName("推进 24 小时同样只按 12 小时封顶")
    void advanceTimeRespectsOfflineCap() throws Exception {
        String token = newSessionToken();
        createPet(token, "CAT", "Mimi");

        JsonNode pet = dataOf(advanceTime(token, 24));

        assertThat(pet.path("satiety").asInt()).isEqualTo(20);
        assertThat(pet.path("health").asInt()).isEqualTo(76);
    }

    @Test
    @DisplayName("时间推进接口和普通接口一样需要令牌")
    void advanceTimeRequiresToken() throws Exception {
        mockMvc.perform(post("/api/v1/dev/advance-time")
                        .characterEncoding("UTF-8")
                        .contentType(JSON)
                        .content("{\"hours\":8}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("小时数超出范围 -> 400")
    void hoursAreValidated() throws Exception {
        String token = newSessionToken();
        createPet(token, "CAT", "Mimi");

        mockMvc.perform(post("/api/v1/dev/advance-time")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .characterEncoding("UTF-8")
                        .contentType(JSON)
                        .content("{\"hours\":0}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/dev/advance-time")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .characterEncoding("UTF-8")
                        .contentType(JSON)
                        .content("{\"hours\":9999}"))
                .andExpect(status().isBadRequest());
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

    private JsonNode advanceTime(String token, int hours) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("hours", hours));
        String response = mockMvc.perform(post("/api/v1/dev/advance-time")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .characterEncoding("UTF-8")
                        .contentType(JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(response);
    }

    private static JsonNode dataOf(JsonNode envelope) {
        return envelope.path("data");
    }
}
