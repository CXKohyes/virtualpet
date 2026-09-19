package com.virtualpet.pet;

import com.virtualpet.common.BusinessException;
import com.virtualpet.common.ErrorCode;
import com.virtualpet.game.GameRules;
import com.virtualpet.game.Species;
import com.virtualpet.player.PlayerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 多宠物槽的服务层行为（PRD 2.1、2.2）。
 *
 * <p>断言尽量落在<b>库里真实存着什么</b>，而不只是接口返回了什么 —— 这类
 * 「写不进去但响应看着对」的问题（{@code sleepingSince} 当年就是）只有直接查库
 * 才抓得住，所以这里注入了 {@link JdbcTemplate}。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PetServiceTest {

    @Autowired
    private PetService petService;
    @Autowired
    private PlayerService playerService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private Clock clock;

    private Long newPlayer() {
        return playerService.establish("petsvc-" + UUID.randomUUID()).playerId();
    }

    private Long activePetIdInDb(Long playerId) {
        return jdbc.queryForObject(
                "SELECT active_pet_id FROM players WHERE id = ?", Long.class, playerId);
    }

    // ------------------------------------------------------------ 槽位分配

    @Test
    @DisplayName("三只宠物依次占 0/1/2 号槽，第四只被拒绝")
    void slotsAreAssignedInOrderAndCapped() {
        Long playerId = newPlayer();

        Pet a = petService.create(playerId, Species.CAT, "甲");
        Pet b = petService.create(playerId, Species.DOG, "乙");
        Pet c = petService.create(playerId, Species.DRAGON, "丙");

        assertThat(List.of(a.getSlot(), b.getSlot(), c.getSlot())).containsExactly(0, 1, 2);

        assertThatThrownBy(() -> petService.create(playerId, Species.CAT, "丁"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PET_SLOTS_FULL));
    }

    @Test
    @DisplayName("送走中间那只后，它的槽位被下一只重新用上")
    void freedSlotIsReused() {
        Long playerId = newPlayer();
        petService.create(playerId, Species.CAT, "甲");
        Pet middle = petService.create(playerId, Species.DOG, "乙");
        petService.create(playerId, Species.DRAGON, "丙");

        petService.release(playerId, middle.getId());

        // 按「数量 + 1」挑槽的实现会给出 3，那样三个槽位的上限就名存实亡了
        Pet rehomed = petService.create(playerId, Species.CAT, "丁");
        assertThat(rehomed.getSlot()).isEqualTo(1);
    }

    // ------------------------------------------------------------ 当前宠物

    @Test
    @DisplayName("领养后新来的那只成为当前宠物")
    void newestPetBecomesActive() {
        Long playerId = newPlayer();
        petService.create(playerId, Species.CAT, "甲");
        Pet second = petService.create(playerId, Species.DOG, "乙");

        assertThat(activePetIdInDb(playerId)).isEqualTo(second.getId());
        assertThat(petService.findPet(playerId).getId()).isEqualTo(second.getId());
    }

    @Test
    @DisplayName("切换当前宠物，返回的那只已经结算过")
    void activateSwitchesCurrentPet() {
        Long playerId = newPlayer();
        Pet first = petService.create(playerId, Species.CAT, "甲");
        petService.create(playerId, Species.DOG, "乙");

        PetSnapshot snapshot = petService.activate(playerId, first.getId());

        assertThat(snapshot.pet().getId()).isEqualTo(first.getId());
        assertThat(activePetIdInDb(playerId)).isEqualTo(first.getId());
    }

    @Test
    @DisplayName("送走当前宠物后，当前宠物落到剩下里槽位最小的那只")
    void releasingActiveFallsBackToLowestSlot() {
        Long playerId = newPlayer();
        Pet first = petService.create(playerId, Species.CAT, "甲");
        Pet second = petService.create(playerId, Species.DOG, "乙");
        assertThat(activePetIdInDb(playerId)).isEqualTo(second.getId());

        petService.release(playerId, second.getId());

        assertThat(activePetIdInDb(playerId)).isEqualTo(first.getId());
    }

    @Test
    @DisplayName("送走最后一只后，active_pet_id 在库里真的变成 NULL")
    void releasingLastPetClearsActivePetId() {
        Long playerId = newPlayer();
        Pet only = petService.create(playerId, Species.CAT, "独苗");
        // 先确认它真的被设上过。少了这一句，万一是「从来没设成功过」，
        // 下面的 isNull 会因为恒为 null 而通过，测试就成了空转。
        assertThat(activePetIdInDb(playerId)).isEqualTo(only.getId());

        petService.release(playerId, only.getId());

        // 这一条是直接查库的回归测试，专门钉住 MyBatis-Plus 的 null 更新陷阱：
        // 默认的 NOT_NULL 策略会把 null 字段从 UPDATE 里剔掉，"置空"这一步会静默失效，
        // 而接口响应完全看不出来（它用的不是库里的值）。pets.sleepingSince 当年就是这么坏的。
        assertThat(activePetIdInDb(playerId)).isNull();
        assertThat(petService.findPet(playerId)).isNull();
    }

    @Test
    @DisplayName("active_pet_id 落空时，回落到槽位最小的那只（回填漏跑的兜底）")
    void fallsBackToLowestSlotWhenActiveIsMissing() {
        Long playerId = newPlayer();
        Pet first = petService.create(playerId, Species.CAT, "甲");
        petService.create(playerId, Species.DOG, "乙");

        // 模拟迁移回填没跑到的状态
        jdbc.update("UPDATE players SET active_pet_id = NULL WHERE id = ?", playerId);

        assertThat(petService.findPet(playerId).getId())
                .as("active_pet_id 为空不该让玩家被告知「还没有领养宠物」")
                .isEqualTo(first.getId());
    }

    @Test
    @DisplayName("active_pet_id 指向一只已不存在的宠物时也回落到槽位最小的那只")
    void fallsBackWhenActivePointsToDeletedPet() {
        Long playerId = newPlayer();
        Pet first = petService.create(playerId, Species.CAT, "甲");
        Pet second = petService.create(playerId, Species.DOG, "乙");

        // 绕过 release，直接把指向第二只的指针留成悬空
        jdbc.update("UPDATE pets SET player_id = player_id WHERE id = ?", second.getId());
        jdbc.update("DELETE FROM pets WHERE id = ?", second.getId());

        assertThat(petService.findPet(playerId).getId()).isEqualTo(first.getId());
    }

    // ------------------------------------------------------------ 归属校验

    @Test
    @DisplayName("拿别人的宠物 ID 来切换，报的是「没找到」而不是「不是你的」")
    void strangersPetIsIndistinguishableFromMissing() {
        Long mine = newPlayer();
        Long theirs = newPlayer();
        Pet theirPet = petService.create(theirs, Species.DRAGON, "别人的");

        assertThatThrownBy(() -> petService.activate(mine, theirPet.getId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PET_NOT_FOUND));

        assertThatThrownBy(() -> petService.activate(mine, 999_999L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PET_NOT_FOUND));
    }

    // ------------------------------------------------------------ 名册

    @Test
    @DisplayName("名册按槽位升序，且只有一只是当前宠物")
    void rosterIsOrderedAndMarksExactlyOneActive() {
        Long playerId = newPlayer();
        Pet first = petService.create(playerId, Species.CAT, "甲");
        petService.create(playerId, Species.DOG, "乙");
        Pet third = petService.create(playerId, Species.DRAGON, "丙");
        petService.activate(playerId, first.getId());

        List<PetResponse> roster = petService.roster(playerId);

        assertThat(roster).extracting(PetResponse::slot).containsExactly(0, 1, 2);
        assertThat(roster).filteredOn(PetResponse::active)
                .extracting(PetResponse::id).containsExactly(first.getId());
        assertThat(roster).extracting(PetResponse::id)
                .containsExactly(first.getId(), roster.get(1).id(), third.getId());
    }

    @Test
    @DisplayName("名册会结算每一只宠物，不只是当前那只")
    void rosterSettlesInactivePetsToo() {
        Long playerId = newPlayer();
        Pet inactive = petService.create(playerId, Species.CAT, "甲");
        petService.create(playerId, Species.DOG, "乙");   // 后领养的这只才是当前宠物

        // 把「甲」的结算游标拨回 8 小时前，模拟它被冷落了一段时间
        Instant eightHoursAgo = Instant.now(clock).minusSeconds(8 * 3600L);
        jdbc.update("UPDATE pets SET last_settled_at = ? WHERE id = ?",
                Timestamp.from(eightHoursAgo), inactive.getId());

        PetResponse entry = petService.roster(playerId).stream()
                .filter(item -> item.id().equals(inactive.getId()))
                .findFirst()
                .orElseThrow();

        assertThat(entry.active()).isFalse();
        // 只读不结算的实现会让这两条都失败：摘要为 null，游标也没动
        assertThat(entry.settlement())
                .as("非活跃宠物也必须被结算，否则名册显示的是旧值，看不出谁在挨饿")
                .isNotNull();
        assertThat(entry.settlement().deltas().satiety()).isLessThan(0);

        Instant settledAt = jdbc.queryForObject(
                "SELECT last_settled_at FROM pets WHERE id = ?", Timestamp.class, inactive.getId())
                .toInstant();
        assertThat(settledAt).isAfter(eightHoursAgo);
    }

    @Test
    @DisplayName("没有宠物时名册是空的，不报错")
    void rosterIsEmptyWithoutPets() {
        assertThat(petService.roster(newPlayer())).isEmpty();
    }

    // ------------------------------------------------------------ 上限常量

    @Test
    @DisplayName("槽位上限就是游戏规则里那个常量")
    void capMatchesGameRules() {
        Long playerId = newPlayer();
        for (int i = 0; i < GameRules.MAX_PET_SLOTS; i += 1) {
            petService.create(playerId, Species.CAT, "宠" + i);
        }
        assertThatThrownBy(() -> petService.create(playerId, Species.CAT, "多出来的"))
                .isInstanceOf(BusinessException.class);
    }
}
