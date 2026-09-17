--liquibase formatted sql

-- ============================================================
-- 像素宠物屋初始表结构
-- 兼容 MySQL 5.7 与 H2 MySQL 兼容模式：
--   * 不使用 MySQL 8 独有语法
--   * 不写 ENGINE / CHARSET，交由 CREATE DATABASE 时的 utf8mb4 决定
--   * 时间字段统一 UTC 的 TIMESTAMP
-- ============================================================

--changeset virtual-pet:001-create-players
CREATE TABLE players (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    device_id    VARCHAR(64)  NOT NULL,
    token_hash   VARCHAR(128) NOT NULL,
    friend_code  VARCHAR(12)  NULL,
    created_at   TIMESTAMP    NOT NULL,
    last_seen_at TIMESTAMP    NOT NULL,
    CONSTRAINT pk_players PRIMARY KEY (id),
    CONSTRAINT uk_players_device_id UNIQUE (device_id),
    CONSTRAINT uk_players_friend_code UNIQUE (friend_code)
);
--rollback DROP TABLE players;

--changeset virtual-pet:002-create-players-indexes
CREATE INDEX idx_players_last_seen_at ON players (last_seen_at);
--rollback DROP INDEX idx_players_last_seen_at ON players;

--changeset virtual-pet:003-create-pets
CREATE TABLE pets (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    player_id       BIGINT      NOT NULL,
    species         VARCHAR(16) NOT NULL,
    name            VARCHAR(32) NOT NULL,
    satiety         INT         NOT NULL,
    mood            INT         NOT NULL,
    hygiene         INT         NOT NULL,
    energy          INT         NOT NULL,
    health          INT         NOT NULL,
    sick            TINYINT     NOT NULL DEFAULT 0,
    status          VARCHAR(16) NOT NULL,
    level           INT         NOT NULL DEFAULT 1,
    exp             INT         NOT NULL DEFAULT 0,
    evolution_stage INT         NOT NULL DEFAULT 0,
    sleeping_since  TIMESTAMP   NULL,
    last_settled_at TIMESTAMP   NOT NULL,
    version         INT         NOT NULL DEFAULT 0,
    created_at      TIMESTAMP   NOT NULL,
    updated_at      TIMESTAMP   NOT NULL,
    CONSTRAINT pk_pets PRIMARY KEY (id),
    CONSTRAINT uk_pets_player_id UNIQUE (player_id),
    CONSTRAINT fk_pets_player FOREIGN KEY (player_id) REFERENCES players (id)
);
--rollback DROP TABLE pets;

--changeset virtual-pet:004-create-pets-indexes
CREATE INDEX idx_pets_last_settled_at ON pets (last_settled_at);
CREATE INDEX idx_pets_status ON pets (status);
--rollback DROP INDEX idx_pets_status ON pets;

--changeset virtual-pet:005-create-pet-action-logs
CREATE TABLE pet_action_logs (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    pet_id            BIGINT      NOT NULL,
    action            VARCHAR(16) NOT NULL,
    client_request_id VARCHAR(64) NOT NULL,
    result_json       TEXT        NOT NULL,
    created_at        TIMESTAMP   NOT NULL,
    CONSTRAINT pk_pet_action_logs PRIMARY KEY (id),
    CONSTRAINT uk_pet_action_logs_client_request_id UNIQUE (client_request_id),
    CONSTRAINT fk_pet_action_logs_pet FOREIGN KEY (pet_id) REFERENCES pets (id)
);
--rollback DROP TABLE pet_action_logs;

--changeset virtual-pet:006-create-pet-action-logs-indexes
CREATE INDEX idx_pet_action_logs_pet_created ON pet_action_logs (pet_id, created_at);
--rollback DROP INDEX idx_pet_action_logs_pet_created ON pet_action_logs;
