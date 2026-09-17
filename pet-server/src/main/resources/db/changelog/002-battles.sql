--liquibase formatted sql

-- ============================================================
-- P1 异步对战（PRD 2.11、TECH_DESIGN 4.5）
--
-- 两条设计要点：
--
-- 1. 双方宠物的状态以**快照**形式存进来（JSON），开战后不再读实时数据。
--    被挑战方可以全程不在线，这正是"异步"的含义；也是"不读取对方实时状态"
--    这条要求的落点 —— 对方的养成、进化、生病都不会影响已经打完的战报。
--
-- 2. seed 单独存一列。同一个种子加同一对快照必须能复现出逐字节相同的战报，
--    所以复现需要的输入要么在快照里、要么在这一列里，不能散在别处。
--
-- 时间列沿用 001 的约定：TIMESTAMP NOT NULL 一律显式写 DEFAULT，
-- 否则 MySQL 5.7 会给出 '0000-00-00' 并与 NO_ZERO_DATE 冲突。
-- ============================================================

--changeset virtual-pet:010-create-battles
CREATE TABLE battles (
    id                   BIGINT      NOT NULL AUTO_INCREMENT,
    challenger_player_id BIGINT      NOT NULL,
    defender_player_id   BIGINT      NOT NULL,
    challenger_pet_id    BIGINT      NOT NULL,
    defender_pet_id      BIGINT      NOT NULL,
    challenger_snapshot  TEXT        NOT NULL,
    defender_snapshot    TEXT        NOT NULL,
    seed                 BIGINT      NOT NULL,
    status               VARCHAR(16) NOT NULL,
    result_json          TEXT        NULL,
    created_at           TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at          TIMESTAMP   NULL,
    CONSTRAINT pk_battles PRIMARY KEY (id),
    CONSTRAINT fk_battles_challenger FOREIGN KEY (challenger_player_id) REFERENCES players (id),
    CONSTRAINT fk_battles_defender FOREIGN KEY (defender_player_id) REFERENCES players (id)
);
--rollback DROP TABLE battles;

--changeset virtual-pet:011-create-battles-indexes
-- 「我的最近对战」要同时按挑战方和被挑战方查，两边各建一个索引
CREATE INDEX idx_battles_challenger ON battles (challenger_player_id, id);
CREATE INDEX idx_battles_defender ON battles (defender_player_id, id);
--rollback DROP INDEX idx_battles_defender ON battles;
