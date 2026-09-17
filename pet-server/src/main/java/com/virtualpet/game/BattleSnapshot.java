package com.virtualpet.game;

/**
 * 参战宠物的快照（PRD 2.11「服务端保存双方宠物快照，不读取对方实时状态」）。
 *
 * <p>开战时冻结，之后整场战斗只读这一份数据。被挑战方可以在离线状态下被挑战 ——
 * 这正是"异步对战"的含义。挑战结束后对方的养成、进化、生病都不会影响已经打完的战报。</p>
 *
 * @param petId          宠物 ID，只用于回溯，不用于读实时状态
 * @param name           宠物名字
 * @param species        物种，决定对战倾向
 * @param level          等级，决定基础战斗属性
 * @param evolutionStage 进化阶段，决定基础战斗属性
 * @param health         开战时的健康，用来折算开场生命
 * @param attributes     开战时五项属性，目前只有 health 参与折算，其余留作展示
 */
public record BattleSnapshot(
        Long petId,
        String name,
        Species species,
        int level,
        int evolutionStage,
        PetAttributes attributes) {

    /** 开战时的健康。 */
    public int health() {
        return attributes.health();
    }

    /** 最大生命，由等级、进化阶段和物种倾向算出。 */
    public int maxHp() {
        return BattleRules.maxHp(level, evolutionStage, species);
    }

    /** 开场生命，按健康折算。 */
    public int startingHp() {
        return BattleRules.startingHp(maxHp(), health());
    }

    public int attack() {
        return BattleRules.attack(level, evolutionStage, species);
    }

    public int defense() {
        return BattleRules.defense(level, evolutionStage, species);
    }

    public int speed() {
        return BattleRules.speed(level, species);
    }

    public int regenPerRound() {
        return BattleRules.regenPerRound(maxHp(), level, species);
    }
}
