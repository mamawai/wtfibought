package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_runtime_toggle")
public class AiRuntimeToggle {

    @TableId(type = IdType.INPUT)
    private String toggleKey;

    private String valueJson;

    private Long updatedBy;

    private LocalDateTime updatedAt;

    private String reason;
}
