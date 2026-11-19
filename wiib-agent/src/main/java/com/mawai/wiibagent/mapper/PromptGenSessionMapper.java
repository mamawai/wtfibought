package com.mawai.wiibagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.PromptGenSession;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PromptGenSessionMapper extends BaseMapper<PromptGenSession> {

    /** 开新对话：整行覆盖 */
    @Insert("""
            INSERT INTO prompt_gen_session (user_id, endpoint_id, trader_id, output_lang, state, updated_at)
            VALUES (#{userId}, #{endpointId}, #{traderId}, #{outputLang}, #{state}, now())
            ON CONFLICT (user_id) DO UPDATE SET
                endpoint_id = EXCLUDED.endpoint_id,
                trader_id   = EXCLUDED.trader_id,
                output_lang = EXCLUDED.output_lang,
                state       = EXCLUDED.state,
                updated_at  = now()
            """)
    int upsert(PromptGenSession session);

    /** 一轮结束：只换对话历史 */
    @Update("UPDATE prompt_gen_session SET state = #{state}, updated_at = now() WHERE user_id = #{userId}")
    int updateState(@Param("userId") long userId, @Param("state") byte[] state);

    /** 回退：对话历史和正文语言换成那一版当时的，端点和 trader 不动 */
    @Update("""
            UPDATE prompt_gen_session SET state = #{state}, output_lang = #{outputLang}, updated_at = now()
            WHERE user_id = #{userId}
            """)
    int restore(@Param("userId") long userId, @Param("state") byte[] state, @Param("outputLang") String outputLang);
}
