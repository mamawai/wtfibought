package com.mawai.wiibservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.AiTradingDecision;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface AiTradingDecisionMapper extends BaseMapper<AiTradingDecision> {

    @Select("SELECT * FROM ai_trading_decision WHERE symbol = #{symbol} ORDER BY created_at DESC LIMIT #{limit}")
    List<AiTradingDecision> selectRecentBySymbol(@Param("symbol") String symbol, @Param("limit") int limit);

    @Select("SELECT COALESCE(MAX(cycle_no), 0) FROM ai_trading_decision")
    int selectMaxCycleNo();

    @Select("SELECT * FROM ai_trading_decision ORDER BY created_at DESC LIMIT #{limit}")
    List<AiTradingDecision> selectRecent(@Param("limit") int limit);

    @Select("""
            SELECT
                COUNT(*)::int AS "samples",
                COUNT(*) FILTER (WHERE reasoning LIKE '%side=LONG%')::int AS "longSamples",
                COUNT(*) FILTER (WHERE reasoning LIKE '%side=SHORT%')::int AS "shortSamples",
                MIN(created_at) AS "firstSeenAt",
                MAX(created_at) AS "latestSeenAt"
            FROM ai_trading_decision
            WHERE reasoning LIKE '%[Strategy-SHADOW_5OF7]%'
            """)
    Map<String, Object> selectShadow5of7Stats();
}
