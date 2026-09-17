package com.virtualpet.pet;

/**
 * 一次带结算的宠物读取结果。
 *
 * @param pet        结算后的宠物实体
 * @param settlement 本次结算的变化摘要；没有经过时间时为 {@code null}
 */
public record PetSnapshot(Pet pet, SettlementSummary settlement) {

    public static PetSnapshot withoutSettlement(Pet pet) {
        return new PetSnapshot(pet, null);
    }
}
