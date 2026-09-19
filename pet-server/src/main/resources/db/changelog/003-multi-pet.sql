--liquibase formatted sql

-- ============================================================
-- 多宠物槽（PRD 2.1、功能优先级 P2）
--
-- 这次改动动了两个语义：
--
-- 1. pets 从「一名玩家一只」变成「一名玩家最多 maxSlots 只，各有槽位号」。
-- 2. players.active_pet_id 记住「当前宠物」—— /pets/me 的语义随之从
--    「我的宠物」变成「我的当前宠物」，于是四个操作、照护日志、对战
--    这些既有接口**一个字节都不用改**。
--
-- 迁移为什么是安全的：
--   存量数据里 uk_pets_player_id 是唯一键，每名玩家最多一只宠物，
--   所以加 slot 列时全部落到 0 号槽，新的 UNIQUE(player_id, slot) 一定成立。
--   **先加复合唯一、再删单列唯一**（删在 004），中间不会出现无约束窗口。
--
-- active_pet_id 刻意**不加外键**：送走宠物要物理删除 pets 行，加了外键会让
-- 删除失败。这和 002 里 battles.*_pet_id 不加外键是同一个理由，
-- 一致性由 PetService 在同一个事务里保证。
--
-- 本文没有新增时间列，所以不涉及 001 那段 TIMESTAMP 默认值的坑。
-- ============================================================

--changeset virtual-pet:020-pets-add-slot
ALTER TABLE pets ADD COLUMN slot INT NOT NULL DEFAULT 0;
--rollback ALTER TABLE pets DROP COLUMN slot;

--changeset virtual-pet:021-pets-unique-player-slot
-- 取代原来的 UNIQUE(player_id)。旧的那条由 004 删除 —— 删唯一约束的语法
-- 各方言不同，纯 SQL 写不成两边通吃，所以那一句走了 Liquibase 的 change type。
ALTER TABLE pets ADD CONSTRAINT uk_pets_player_slot UNIQUE (player_id, slot);
--rollback ALTER TABLE pets DROP INDEX uk_pets_player_slot;

--changeset virtual-pet:022-pets-index-player
-- **必须在 004 删唯一键之前建好。**少了它，004 会在两个引擎上分别失败，
-- 而且失败方式完全不同：
--
--   MySQL：uk_pets_player_id 同时是对外键 fk_pets_player 而言「可用」的那个索引，
--          直接删会报 ERROR 1553 "Cannot drop index ... needed in a foreign key
--          constraint"。MySQL 不会自动把外键改挂到别的索引上，所以必须先有替代品。
--
--   H2：   约束确实被删掉了，但它留下的**支撑索引被转交给了外键**，而那个索引
--          仍然是 UNIQUE 的 —— 唯一性照样在强制，一个玩家依然只能有一只宠物。
--          这个失败特别隐蔽：查 information_schema 会看到约束真的没了，
--          可插入行为一点没变。本批次就是靠一条「同玩家插两只」的行为断言
--          才把它抓出来的，光看元数据会误判成成功。
--
-- 顺带一提，这个索引本来也该建：多宠物槽之后「查某个玩家的全部宠物」成了主路径，
-- 原先是被 UNIQUE(player_id) 顺便覆盖的，而那条马上要删。
CREATE INDEX idx_pets_player ON pets (player_id);
--rollback DROP INDEX idx_pets_player ON pets;

--changeset virtual-pet:023-players-add-active-pet-id
ALTER TABLE players ADD COLUMN active_pet_id BIGINT NULL;
--rollback ALTER TABLE players DROP COLUMN active_pet_id;
