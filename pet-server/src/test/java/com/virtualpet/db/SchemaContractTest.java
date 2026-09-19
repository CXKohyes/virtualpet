package com.virtualpet.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 数据库结构的契约测试（多宠物槽，P2）。
 *
 * <p><b>为什么需要它：</b>迁移脚本跑完不报错，不等于它真的做了该做的事。多宠物槽的
 * 关键一步是删掉旧的 {@code uk_pets_player_id}，而 Liquibase 的 change type 在某些
 * 方言上可能「成功执行但什么都没删」——那种情况所有既有测试照样全绿，直到批次 B
 * 的建宠逻辑莫名其妙失败才会暴露。这个类把结论直接钉下来。</p>
 *
 * <p>断言分两层：<b>元数据层</b>确认列和约束的名字在不在，<b>行为层</b>用真实的插入
 * 证明约束真的在起作用。行为层是更硬的一条——它不依赖任何引擎对索引命名的习惯。</p>
 *
 * <p>另外它还是「删列会不会被残留约束挡住」这条的<b>方言金丝雀</b>：H2 2.3.232 的
 * {@code dropMultipleColumnsConstraintsAndIndexes} 会自动删掉引用该列的<b>单列</b>约束，
 * 但<b>多列</b>约束会抛 90083。今天没有删列需求，留着是因为以后真要靠删列绕过约束时，
 * 这条差异会先在 MySQL 上通过、再在 H2 上炸——或者反过来。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SchemaContractTest {

    @Autowired
    private JdbcTemplate jdbc;

    // ------------------------------------------------------------ 元数据层

    @Test
    @DisplayName("pets 有 slot 列，players 有 active_pet_id 列")
    void newColumnsExist() {
        assertThat(columnsOf("PETS")).contains("SLOT");
        assertThat(columnsOf("PLAYERS")).contains("ACTIVE_PET_ID");
    }

    /**
     * <b>注意这条断言是弱信号，不能单独当结论用。</b>H2 在删掉唯一约束之后，会把它
     * 留下的支撑索引转交给外键（{@code fk_pets_player} 需要 player_id 上的索引），
     * 而那个索引仍然是 UNIQUE 的 —— 于是元数据里约束真的没了，插入行为却一点没变。
     * 这个坑在开发时就是这么踩到的：元数据断言全绿，行为断言全红。
     *
     * <p>所以真正的契约在下面那几条行为断言里，这条只是让人更快定位到问题在约束层。</p>
     */
    @Test
    @DisplayName("旧的 UNIQUE(player_id) 已经删掉，换了 UNIQUE(player_id, slot)")
    void singlePetUniqueWasReplacedByComposite() {
        List<String> constraints = uniqueConstraintsOf("PETS");

        assertThat(constraints)
                .as("uk_pets_player_id 必须已被 004 的 dropUniqueConstraint 删掉，"
                        + "否则一个玩家依然只能有一只宠物")
                .doesNotContain("UK_PETS_PLAYER_ID");

        assertThat(constraints)
                .as("新的复合唯一键必须存在")
                .contains("UK_PETS_PLAYER_SLOT");
    }

    @Test
    @DisplayName("四个新 changeset 都被记录为执行过")
    void newChangesetsWereRecorded() {
        // 这条把「没跑」和「跑了但没生效」区分开。少了它，一个被 Liquibase 静默
        // 跳过的 changeset 只会表现为后面那条约束断言的失败，而失败信息不会
        // 提示到底是哪种原因，排查会绕远路。
        assertThat(executedChangesets()).contains(
                "020-pets-add-slot",
                "021-pets-unique-player-slot",
                "022-pets-index-player",
                "023-players-add-active-pet-id",
                "030-drop-pets-player-fk",
                "031-drop-legacy-pets-unique",
                "032-recreate-pets-player-fk");
    }

    // ------------------------------------------------------------ 行为层

    @Test
    @DisplayName("同一玩家的两只宠物可以共存——旧唯一键确实不在了")
    void twoPetsForOnePlayerCoexist() {
        Long playerId = insertPlayer("schema-contract-two-pets");

        insertPet(playerId, 0);
        insertPet(playerId, 1);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pets WHERE player_id = ?", Integer.class, playerId);
        assertThat(count).isEqualTo(2);
    }

    @Test
    @DisplayName("同一玩家同一槽位插第二只会被拒绝——新唯一键确实生效")
    void sameSlotIsRejected() {
        Long playerId = insertPlayer("schema-contract-same-slot");
        insertPet(playerId, 0);

        assertThatThrownBy(() -> insertPet(playerId, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("送走中间那只后，它的槽位能被重新占用")
    void freedSlotCanBeReused() {
        Long playerId = insertPlayer("schema-contract-reuse-slot");

        insertPet(playerId, 0);
        Long middle = insertPet(playerId, 1);
        insertPet(playerId, 2);

        jdbc.update("DELETE FROM pets WHERE id = ?", middle);
        // 复用 1 号槽：如果实现按「数量 + 1」挑槽，这里会是 3，撞不上任何唯一键，
        // 但槽位会漂到 3 号，三个槽位的上限就名存实亡了。
        insertPet(playerId, 1);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM pets WHERE player_id = ?", Integer.class, playerId))
                .isEqualTo(3);
    }

    // ------------------------------------------------------------ 辅助

    private List<String> columnsOf(String table) {
        return jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = ?",
                String.class, table);
    }

    /**
     * 表的唯一约束名。
     *
     * <p><b>要查 {@code table_constraints} 而不是 {@code indexes}：</b>H2 会为约束
     * 另建一个支撑索引并自己起名（{@code uk_pets_player_slot} 的索引叫
     * {@code UK_PETS_PLAYER_SLOT_INDEX_2}），两者名字对不上。按索引名断言会得到
     * 「约束明明在，测试却说找不到」这种假失败。</p>
     *
     * <p>H2 把未加引号的标识符存成大写，所以归一化到大写再比。</p>
     */
    private List<String> uniqueConstraintsOf(String table) {
        return jdbc.queryForList("""
                        SELECT constraint_name FROM information_schema.table_constraints
                        WHERE table_name = ? AND constraint_type = 'UNIQUE'
                        """,
                        String.class, table)
                .stream()
                .map(String::toUpperCase)
                .toList();
    }

    /** 迁移是否真的被执行过——用来区分「没跑」和「跑了但没生效」。 */
    private List<String> executedChangesets() {
        return jdbc.queryForList("SELECT id FROM databasechangelog", String.class);
    }

    private Long insertPlayer(String deviceId) {
        jdbc.update("""
                        INSERT INTO players (device_id, token_hash, created_at, last_seen_at)
                        VALUES (?, ?, ?, ?)
                        """,
                deviceId, "hash-" + deviceId,
                Timestamp.from(Instant.parse("2026-09-19T00:00:00Z")),
                Timestamp.from(Instant.parse("2026-09-19T00:00:00Z")));
        return jdbc.queryForObject(
                "SELECT id FROM players WHERE device_id = ?", Long.class, deviceId);
    }

    private Long insertPet(Long playerId, int slot) {
        jdbc.update("""
                        INSERT INTO pets (player_id, slot, species, name, satiety, mood, hygiene,
                                          energy, health, status, last_settled_at)
                        VALUES (?, ?, 'CAT', ?, 80, 80, 80, 80, 100, 'NORMAL', ?)
                        """,
                playerId, slot, "槽位" + slot,
                Timestamp.from(Instant.parse("2026-09-19T00:00:00Z")));
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM pets WHERE player_id = ?", Long.class, playerId);
    }
}
