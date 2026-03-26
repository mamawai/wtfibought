package com.mawai.wiibservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.BlackjackConvertLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

@Mapper
public interface BlackjackConvertLogMapper extends BaseMapper<BlackjackConvertLog> {

    @Select("SELECT COALESCE(SUM(amount), 0) FROM blackjack_convert_log WHERE user_id = #{userId}")
    BigDecimal sumTotalConverted(@Param("userId") Long userId);
}
