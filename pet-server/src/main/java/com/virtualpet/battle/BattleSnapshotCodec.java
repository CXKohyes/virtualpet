package com.virtualpet.battle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.virtualpet.game.BattleReport;
import com.virtualpet.game.BattleSnapshot;
import org.springframework.stereotype.Component;

/**
 * 快照与战报的 JSON 编解码。
 *
 * <p>单独抽出来是为了让"存进去的东西"和"算出来的东西"只有一处序列化入口：
 * 复现一场战斗要的是<b>当年的那份快照</b>，如果各处自己拼 JSON，
 * 很容易出现读回来的字段和写进去的对不上，那时候复现就悄悄失效了。</p>
 *
 * <p>出入都用同一个 {@link ObjectMapper}（Spring 注入的那个），
 * 保证写和读用的是同一套规则。</p>
 */
@Component
public class BattleSnapshotCodec {

    private final ObjectMapper objectMapper;

    public BattleSnapshotCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String writeSnapshot(BattleSnapshot snapshot) {
        return write(snapshot, "宠物快照");
    }

    public BattleSnapshot readSnapshot(String json) {
        return read(json, BattleSnapshot.class, "宠物快照");
    }

    public String writeReport(BattleReport report) {
        return write(report, "战报");
    }

    public BattleReport readReport(String json) {
        return read(json, BattleReport.class, "战报");
    }

    private String write(Object value, String what) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException cause) {
            // 走到这里说明是代码问题不是数据问题，让它炸出来比悄悄存个空值强
            throw new IllegalStateException("序列化" + what + "失败", cause);
        }
    }

    private <T> T read(String json, Class<T> type, String what) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException cause) {
            throw new IllegalStateException("反序列化" + what + "失败", cause);
        }
    }
}
