package com.mawai.wiibsim.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.InviteCode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface InviteCodeMapper extends BaseMapper<InviteCode> {

    /**
     * 原子消费一次邀请码，返回码 ID；不存在/已停用/次数用完都更新 0 行返回 null。
     * 条件里带 used_count < max_uses，并发注册也不会超用；注册事务回滚时消费一并回滚。
     */
    @Select("UPDATE invite_code SET used_count = used_count + 1, updated_at = NOW() " +
            "WHERE code = #{code} AND enabled = TRUE AND used_count < max_uses " +
            "RETURNING id")
    Long consume(@Param("code") String code);

    /** 作废邀请码 */
    @Update("UPDATE invite_code SET enabled = FALSE, updated_at = NOW() WHERE id = #{id}")
    int disable(@Param("id") Long id);
}
