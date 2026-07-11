package com.mawai.wiibquant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.QuantDeepAnalysis;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface QuantDeepAnalysisMapper extends BaseMapper<QuantDeepAnalysis> {

    /** 到期且未对账的研判行（反连接免游标，重启零状态；K线未就绪的行自然留在结果里下轮重试）。 */
    @Select("""
            SELECT a.* FROM quant_deep_analysis a
            WHERE a.close_time <= #{dueBefore}
              AND NOT EXISTS (SELECT 1 FROM quant_narrative_verification v WHERE v.analysis_id = a.id)
            ORDER BY a.close_time ASC
            LIMIT #{limit}
            """)
    List<QuantDeepAnalysis> selectDueUnverified(@Param("dueBefore") long dueBefore, @Param("limit") int limit);
}
