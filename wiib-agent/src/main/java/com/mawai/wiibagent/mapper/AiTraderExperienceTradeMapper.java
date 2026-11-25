package com.mawai.wiibagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.AiTraderExperienceTrade;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AiTraderExperienceTradeMapper extends BaseMapper<AiTraderExperienceTrade> {

    /** 同一条经验同一笔单子只记一次：撞上唯一键不插，返回 0 */
    @Insert("""
            INSERT INTO ai_trader_experience_trade (experience_id, position_id, review_id, mark, net, closed_day,
                                                    open_round_from, open_round_to, segment_no)
            VALUES (#{experienceId}, #{positionId}, #{reviewId}, #{mark}, #{net}, #{closedDay},
                    #{openRoundFrom}, #{openRoundTo}, #{segmentNo})
            ON CONFLICT (experience_id, position_id) DO NOTHING
            """)
    int insertIgnore(AiTraderExperienceTrade row);
}
