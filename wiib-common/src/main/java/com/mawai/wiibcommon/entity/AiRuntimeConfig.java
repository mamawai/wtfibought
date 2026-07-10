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

    /** 思考档位 none/low/medium/high；null=不传，走模型默认。ALWAYS：清空档位=写 null，默认策略会跳过 null 导致清不掉 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String reasoningEffort;

    /** 上游协议：openai=/v1/chat/completions，responses=/v1/responses */
    private String apiProtocol;

    private Boolean enabled;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
