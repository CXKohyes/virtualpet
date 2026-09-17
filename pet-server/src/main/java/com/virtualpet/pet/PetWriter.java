package com.virtualpet.pet;

import com.virtualpet.common.BusinessException;
import com.virtualpet.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * 宠物的写事务单元：统一负责事务边界、结算写回和乐观锁重试。
 *
 * <p>为什么用 {@link TransactionTemplate} 而不是 {@code @Transactional}：乐观锁冲突后要
 * 「回滚并重开事务」才能读到最新版本（MySQL 默认 REPEATABLE READ 下，同一个事务里重读
 * 拿到的还是旧快照）。在同一个事务内重试是无效的，所以事务边界必须由外层控制。</p>
 *
 * <p>重试次数遵循 TECH_DESIGN 6.2：最多重试 2 次（共 3 次尝试）。</p>
 */
@Component
public class PetWriter {

    private static final Logger log = LoggerFactory.getLogger(PetWriter.class);

    /** 首次 + 最多 2 次重试。 */
    private static final int MAX_ATTEMPTS = 3;

    private final TransactionTemplate transactionTemplate;
    private final PetSettlementService settlementService;
    private final PetMapper petMapper;
    private final Clock clock;

    public PetWriter(TransactionTemplate transactionTemplate,
                     PetSettlementService settlementService,
                     PetMapper petMapper,
                     Clock clock) {
        this.transactionTemplate = transactionTemplate;
        this.settlementService = settlementService;
        this.petMapper = petMapper;
        this.clock = clock;
    }

    /**
     * 在一个新事务里执行写单元，遇到乐观锁冲突就整体回滚重来。
     *
     * @param unit 事务内要执行的动作，冲突时抛 {@link OptimisticLockConflictException}
     */
    public <T> T runWithRetry(Supplier<T> unit) {
        OptimisticLockConflictException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return transactionTemplate.execute(status -> unit.get());
            } catch (OptimisticLockConflictException conflict) {
                last = conflict;
                log.warn("宠物版本冲突，第 {} 次尝试失败，准备重试", attempt);
            }
        }
        log.warn("宠物版本冲突重试用尽", last);
        throw new BusinessException(ErrorCode.CONFLICT);
    }

    /** 把结算结果写进实体（不落库）。 */
    public void applySettlement(Pet pet, SettlementResult result) {
        PetConverter.applyState(pet, result.state());
        touch(pet);
    }

    /** 更新实体上的 {@code updated_at}。 */
    public void touch(Pet pet) {
        pet.setUpdatedAt(Instant.now(clock));
    }

    /** 结算宠物，返回结果但不写库。 */
    public SettlementResult settle(Pet pet) {
        return settlementService.settle(PetConverter.toState(pet));
    }

    /** 按乐观锁更新，影响行数不是 1 说明被人抢先改了。 */
    public void updateOrConflict(Pet pet) {
        if (petMapper.updateById(pet) != 1) {
            throw new OptimisticLockConflictException();
        }
    }

    /** 只用于触发事务回滚，不对外暴露。 */
    public static class OptimisticLockConflictException extends RuntimeException {
        public OptimisticLockConflictException() {
            super("宠物记录已被其他请求修改");
        }
    }
}
