package com.mawai.wiibagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.JevPredictionRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface JevPredictionRunMapper extends BaseMapper<JevPredictionRun> {

    /** 全部局，新的在前；第一条就是当前局 */
    @Select("SELECT * FROM jev_prediction_run ORDER BY run_no DESC")
    List<JevPredictionRun> selectAllDesc();
}
