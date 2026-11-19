package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 交易指令生成器的当前对话：一人一行，开新对话整行覆盖。
 * state 是序列化后的完整消息历史，续聊和页面恢复都从它来。
 */
@Data
@TableName("prompt_gen_session")
public class PromptGenSession {

    @TableId(type = IdType.INPUT)
    private Long userId;
    private Long endpointId;
    /** 这段对话为哪个 trader 写；空=没有 trader，按平台默认规格 */
    private Long traderId;
    /** 指令正文用哪门语言（zh/en）；对话本身跟 agent 语言 */
    private String outputLang;
    private byte[] state;
    private LocalDateTime updatedAt;
}
