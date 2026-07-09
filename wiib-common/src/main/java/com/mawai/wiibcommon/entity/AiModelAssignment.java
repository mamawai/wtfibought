package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 功能位 → LLM 配置的指针（更换 LLM=改 configId 即可）。
 * 模型名不在此表——归属 {@link AiRuntimeConfig}（一条配置=一个具体 LLM：key+baseUrl+model）。
 */
@Data
@TableName("ai_model_assignment")
public class AiModelAssignment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String functionName;

    private Long configId;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
