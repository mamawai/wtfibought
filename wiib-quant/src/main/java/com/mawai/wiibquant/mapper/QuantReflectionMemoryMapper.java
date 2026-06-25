package com.mawai.wiibquant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.QuantReflectionMemory;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface QuantReflectionMemoryMapper extends BaseMapper<QuantReflectionMemory> {

    @Select("SELECT * FROM quant_reflection_memory WHERE symbol = #{symbol} AND regime = #{regime} " +
            "ORDER BY created_at DESC LIMIT #{limit}")
    List<QuantReflectionMemory> selectByRegime(@Param("symbol") String symbol,
                                                @Param("regime") String regime,
                                                @Param("limit") int limit);

    @Select("SELECT * FROM quant_reflection_memory WHERE symbol = #{symbol} " +
            "ORDER BY created_at DESC LIMIT #{limit}")
    List<QuantReflectionMemory> selectRecent(@Param("symbol") String symbol, @Param("limit") int limit);

    @Delete("DELETE FROM quant_reflection_memory WHERE created_at < NOW() - INTERVAL '30 days'")
    void cleanupOld();
}
