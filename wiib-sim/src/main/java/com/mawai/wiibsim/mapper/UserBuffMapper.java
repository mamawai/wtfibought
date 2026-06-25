package com.mawai.wiibsim.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.UserBuff;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserBuffMapper extends BaseMapper<UserBuff> {

    @Update("UPDATE user_buff SET is_used = TRUE WHERE id = #{id} AND is_used = FALSE AND expire_at > NOW()")
    int markUsed(@Param("id") Long id);
}
