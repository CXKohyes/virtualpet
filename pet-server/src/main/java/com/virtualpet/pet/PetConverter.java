package com.virtualpet.pet;

import com.virtualpet.game.PetAttributes;
import com.virtualpet.game.Species;

/**
 * {@link Pet} 实体与 {@link PetState} 领域状态之间的转换。
 *
 * <p>枚举在数据库里存字符串（AGENTS.md 5.4），实体本身保持 {@code String} 字段，
 * 转换集中在这里，好处是 {@code game} 包的枚举完全不依赖持久化框架，
 * 而且直接查库也能读懂内容。</p>
 */
public final class PetConverter {

    private PetConverter() {
    }

    /** 实体 -> 领域状态。 */
    public static PetState toState(Pet pet) {
        return new PetState(
                new PetAttributes(
                        pet.getSatiety(), pet.getMood(), pet.getHygiene(), pet.getEnergy(), pet.getHealth()),
                parseSpecies(pet.getSpecies()),
                Boolean.TRUE.equals(pet.getSick()),
                pet.getSleepingSince(),
                pet.getLastSettledAt());
    }

    /**
     * 领域状态 -> 实体。
     *
     * <p>{@code status} 是冗余列，每次都从状态推导后写回，保证它和属性一致。</p>
     */
    public static void applyState(Pet pet, PetState state) {
        PetAttributes attributes = state.attributes();
        pet.setSatiety(attributes.satiety());
        pet.setMood(attributes.mood());
        pet.setHygiene(attributes.hygiene());
        pet.setEnergy(attributes.energy());
        pet.setHealth(attributes.health());
        pet.setSpecies(state.species().name());
        pet.setSick(state.sick());
        pet.setStatus(state.status().name());
        pet.setSleepingSince(state.sleepingSince());
        pet.setLastSettledAt(state.lastSettledAt());
    }

    private static Species parseSpecies(String raw) {
        try {
            return Species.valueOf(raw);
        } catch (IllegalArgumentException exception) {
            // 数据库里出现了未知物种，说明数据被改坏了，直接失败而不是猜一个
            throw new IllegalStateException("数据库中出现了未知物种: " + raw, exception);
        }
    }
}
