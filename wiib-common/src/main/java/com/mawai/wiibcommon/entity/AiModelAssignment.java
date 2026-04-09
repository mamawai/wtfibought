package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_model_assignment")
public class AiModelAssignment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String functionName;

    private Long configId;

    private String model;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
