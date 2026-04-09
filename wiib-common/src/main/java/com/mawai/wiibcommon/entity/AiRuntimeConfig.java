package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_runtime_config")
public class AiRuntimeConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String configName;

    private String apiKey;

    private String baseUrl;

    private String model;

    private Boolean enabled;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
