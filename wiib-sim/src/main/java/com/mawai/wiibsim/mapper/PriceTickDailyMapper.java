package com.mawai.wiibsim.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.PriceTickDaily;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.mawai.wiibcommon.dto.KlineDTO;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface PriceTickDailyMapper extends BaseMapper<PriceTickDaily> {

    @Select("SELECT trade_date as date, " +
            "prices[1] as open, " +
            "prices[array_length(prices,1)] as close, " +
            "(SELECT max(v) FROM unnest(prices) v) as high, " +
            "(SELECT min(v) FROM unnest(prices) v) as low " +
            "FROM price_tick_daily " +
            "WHERE stock_id = #{stockId} AND trade_date < #{today} " +
            "ORDER BY trade_date DESC LIMIT #{limit}")
    List<KlineDTO> selectKline(@Param("stockId") Long stockId, @Param("today") LocalDate today, @Param("limit") int limit);
}
