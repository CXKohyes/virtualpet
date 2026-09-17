package com.virtualpet.battle;

import com.virtualpet.game.BattleSide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 战报就绪通知（PRD 2.11、TECH_DESIGN 5.9）。
 *
 * <p><b>只推通知，不推结果。</b>消息里只有一个对战 ID、状态和跳转要用的信息，
 * 战报本体一律走 {@code GET /api/v1/battles/{id}} 取。这样做的两个理由：</p>
 *
 * <ol>
 *   <li>WebSocket 不该进养成主链路。通道断了、重连了、订阅晚了，
 *       都只能影响"提不提示"，不能影响"数据对不对"。</li>
 *   <li>战报可能很大（几十个回合），推给所有订阅者既浪费又难做鉴权 ——
 *       而 REST 那条路径已经有完整的令牌校验。</li>
 * </ol>
 *
 * <p>主题按对战分：{@code /topic/battles/{battleId}}。双方各自订阅自己那一场，
 * 不会收到别人的战报。</p>
 */
@Component
public class BattleNotifier {

    private static final Logger log = LoggerFactory.getLogger(BattleNotifier.class);

    static final String TOPIC_PREFIX = "/topic/battles/";

    private final SimpMessagingTemplate messagingTemplate;
    private final Clock clock;

    public BattleNotifier(SimpMessagingTemplate messagingTemplate, Clock clock) {
        this.messagingTemplate = messagingTemplate;
        this.clock = clock;
    }

    /**
     * 广播"这一场打完了"。
     *
     * <p><b>报文里只有对战 ID 和状态，没有任何宠物名字、胜负或回合。</b>
     * 原因是被挑战方<b>事先不知道 battleId</b>（那要等打完才有），
     * 所以他不可能提前订阅 {@code /topic/battles/{那一场}}。前端因此只能订阅通配的
     * {@code /topic/battles/*}，而那是个真广播 —— 谁都能收到别人的对战通知。
     * 往里面塞名字就等于把别人的宠物名广播给所有人。</p>
     *
     * <p>所以通知只负责说"有一场打完了，编号是 N"，客户端拿着这个编号去查自己的
     * 最近对战列表（那条路径有完整的令牌校验），是自己的就刷新。多一次往返，
     * 换掉一整类越权泄露。</p>
     *
     * <p>推送失败不能影响对战本身 —— 战报已经落库了，客户端刷新页面照样看得到。
     * 所以这里只记日志，不往外抛。</p>
     */
    public void publishFinished(BattleResponse battle) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "BATTLE_FINISHED");
        payload.put("battleId", battle.id());
        payload.put("status", battle.status());
        payload.put("serverTime", Instant.now(clock).toString());

        try {
            messagingTemplate.convertAndSend(topicFor(battle.id()), payload);
        } catch (RuntimeException cause) {
            log.warn("对战 {} 的通知推送失败，战报已经落库，不影响查询", battle.id(), cause);
        }
    }

    /** 某一场对战的主题。 */
    public static String topicFor(Long battleId) {
        return TOPIC_PREFIX + battleId;
    }

    /** 主题前缀，测试和文档用。 */
    public static String topicPrefix() {
        return TOPIC_PREFIX;
    }

    /** 供测试断言用：把胜负方转成字符串。 */
    static String sideName(BattleSide side) {
        return side == null ? null : side.name();
    }
}
