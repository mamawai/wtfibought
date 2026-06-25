package com.mawai.wiibsim.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.OptionSettlement;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

@Mapper
public interface OptionSettlementMapper extends BaseMapper<OptionSettlement> {

    @Select("SELECT COALESCE(SUM(settlement_amount), 0) FROM option_settlement " +
            "WHERE user_id = #{userId}")
    BigDecimal sumSettlementAmount(@Param("userId") Long userId);
}
