package com.mawai.wiibsim.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.Settlement;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

@Mapper
public interface SettlementMapper extends BaseMapper<Settlement> {

    /** 删除用户全部待结算（爆仓/恢复兜底用） */
    @Delete("DELETE FROM settlement WHERE user_id = #{userId} AND status = 'PENDING'")
    int deletePendingByUserId(@Param("userId") Long userId);

    /** 用户所有已结算卖出的总额（卖出收入） */
    @Select("SELECT COALESCE(SUM(amount), 0) FROM settlement " +
            "WHERE user_id = #{userId} AND status = 'SETTLED'")
    BigDecimal sumSettledAmount(@Param("userId") Long userId);
}
