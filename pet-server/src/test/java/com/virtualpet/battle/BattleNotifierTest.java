package com.virtualpet.battle;

import com.virtualpet.game.BattleReport;
import com.virtualpet.game.BattleSide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 战报通知的单测（PRD 2.11）。
 *
 * <p>验两件事：推到哪个主题，以及推的内容里<b>没有战报本体</b>。</p>
 */
class BattleNotifierTest {

    private final SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-17T12:00:00Z"), ZoneOffset.UTC);
    private final BattleNotifier notifier = new BattleNotifier(template, clock);

    private static BattleResponse battle(Long id, BattleSide winner) {
        return new BattleResponse(
                id,
                BattleStatus.FINISHED.name(),
                BattleSide.CHALLENGER,
                new BattleResponse.BattlePet(1L, "小蓝", "DRAGON", 5, 1, 101, 26, 11, 10),
                new BattleResponse.BattlePet(2L, "咪咪", "CAT", 5, 1, 95, 23, 12, 16),
                winner,
                BattleReport.Outcome.KO,
                6,
                42L,
                // 故意塞一份很长的战报，用来证明它不会跟着通知一起推出去
                List.of(new BattleReport.BattleRound(1, List.of())),
                Instant.parse("2026-09-17T12:00:00Z"),
                Instant.parse("2026-09-17T12:00:00Z"));
    }

    @Test
    @DisplayName("推到 /topic/battles/{battleId}")
    void publishesToBattleTopic() {
        notifier.publishFinished(battle(7L, BattleSide.CHALLENGER));

        verify(template).convertAndSend(org.mockito.ArgumentMatchers.eq("/topic/battles/7"), any(Object.class));
    }

    @Test
    @DisplayName("通知里只有对战 ID 和状态")
    void notificationCarriesOnlyIdentity() {
        notifier.publishFinished(battle(7L, BattleSide.CHALLENGER));

        Map<String, Object> payload = capturedPayload();

        assertThat(payload).containsEntry("type", "BATTLE_FINISHED");
        assertThat(payload).containsEntry("battleId", 7L);
        assertThat(payload).containsEntry("status", "FINISHED");
        assertThat(payload).containsEntry("serverTime", "2026-09-17T12:00:00Z");
    }

    /**
     * 这条是隐私底线，不是"顺手精简报文"。
     *
     * <p>被挑战方事先不知道 battleId，只能订阅通配的 {@code /topic/battles/*}，
     * 那是个真广播。往通知里塞宠物名就等于把别人的宠物名广播给所有连上来的客户端。</p>
     */
    @Test
    @DisplayName("报文里不含任何宠物名字、胜负或回合数")
    void notificationLeaksNothingAboutParticipants() {
        notifier.publishFinished(battle(7L, BattleSide.CHALLENGER));

        Map<String, Object> payload = capturedPayload();

        assertThat(payload)
                .as("通配订阅是广播，报文里不能出现别人的东西")
                .doesNotContainKeys("challengerName", "defenderName", "winner", "rounds",
                        "timeline", "report", "challenger", "defender");

        // 连值里也不能夹带
        assertThat(payload.values().toString())
                .doesNotContain("小蓝")
                .doesNotContain("咪咪");
    }

    @Test
    @DisplayName("推送失败不影响对战本身：战报已经落库了")
    void publishFailureIsSwallowed() {
        doThrow(new IllegalStateException("broker 挂了"))
                .when(template).convertAndSend(anyString(), any(Object.class));

        assertThatCode(() -> notifier.publishFinished(battle(7L, BattleSide.CHALLENGER)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("主题前缀和文档一致")
    void topicPrefixMatchesDocumentation() {
        assertThat(BattleNotifier.topicPrefix()).isEqualTo("/topic/battles/");
        assertThat(BattleNotifier.topicFor(99L)).isEqualTo("/topic/battles/99");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedPayload() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(template).convertAndSend(anyString(), captor.capture());
        return (Map<String, Object>) captor.getValue();
    }
}
