package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.mawai.wiibcommon.handler.JsonbStringTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作台对话历史（展示用）：user/assistant 消息按会话落库，支撑历史会话列表与回看。
 * <p>
 * 续聊上下文不靠它——那是 workbench_chat_context（模型侧完整消息历史）的事，sessionId 同值；
 * 本表只为前端展示，所以 agent 调度/HITL 过程事件不存。
 */
@Data
@TableName(value = "workbench_chat_message", autoResultMap = true)
public class WorkbenchChatMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话号，形如 wb-{userId}-{uuid}；与 workbench_chat_context.session_id 同值 */
    private String sessionId;

    private Long userId;

    /** user=用户提问 assistant=最终答案 */
    private String role;

    private String content;

    /**
     * 以下 6 列是这一轮的读数，只有 assistant 行有值（user 行与历史老数据一律 null）。
     * 口径与 {@code ai_trader_decision} 同源：token 三项 null=上游端点没返回 usage，不是 0。
     */
    private String modelLabel;

    private Integer modelCalls;

    private Long promptTokens;

    private Long completionTokens;

    private Long totalTokens;

    private Integer latencyMs;

    /** 这一轮联网搜索的来源，JSON 数组 [{url,title}]；只有搜过的 assistant 行有值 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String sources;

    /** 这一轮结束时的上下文用量（token），中断的轮与老数据为 null */
    private Long contextTokens;

    /** 这一轮时对话主模型端点配的上下文窗口（token） */
    private Integer contextWindow;

    /** true=上游没报用量，contextTokens 是按字数估的 */
    private Boolean contextEstimated;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
