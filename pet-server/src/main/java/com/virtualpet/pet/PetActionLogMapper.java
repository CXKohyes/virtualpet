package com.virtualpet.pet;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 操作日志数据访问。只做数据访问，不写业务分支。
 */
public interface PetActionLogMapper extends BaseMapper<PetActionLog> {

    /**
     * 查询该宠物每种操作最近一次的执行时间，用于算冷却。
     *
     * <p>使用参数绑定，不拼接用户输入。</p>
     */
    @Select("""
            SELECT action AS action, MAX(created_at) AS last_used_at
            FROM pet_action_logs
            WHERE pet_id = #{petId}
            GROUP BY action
            """)
    List<ActionUsage> selectLastUsedByAction(@Param("petId") Long petId);
}
