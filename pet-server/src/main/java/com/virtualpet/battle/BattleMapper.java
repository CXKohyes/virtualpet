package com.virtualpet.battle;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 对战记录的持久化（TECH_DESIGN 4.5）。 */
@Mapper
public interface BattleMapper extends BaseMapper<Battle> {
}
