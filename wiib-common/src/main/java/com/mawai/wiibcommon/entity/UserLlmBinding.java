package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 用途 → 端点绑定：某个用途（对话主模型 / 对话轻模型 / 交易员）显式指定用哪条 {@link UserLlmEndpoint}。
 * 一人一用途至多一行（唯一键 user_id+purpose）；没有行 = 跟随默认端点。
 * 端点被删时其绑定行一并删（回落默认），所以这张表里的 endpoint_id 永远指向活着的端点。
 */
@Data
@TableName("user_llm_binding")
public class UserLlmBinding {

    /** 用途常量：CHAT_MAIN 对话主模型 / CHAT_LIGHT 对话轻模型（后台子 agent） / TRADER 交易员 */
    public static final String CHAT_MAIN = "CHAT_MAIN";
    public static final String CHAT_LIGHT = "CHAT_LIGHT";
    public static final String TRADER = "TRADER";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String purpose;
    private Long endpointId;
}
