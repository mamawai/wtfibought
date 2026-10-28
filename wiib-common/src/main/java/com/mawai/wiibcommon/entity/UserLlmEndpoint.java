package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户自带 LLM 端点（BYOK）：一条 = 协议 + Base URL + key + 模型名（+ 思考档位），一人多条。
 * <p>
 * 全站的 BYOK 总配置在这里（AI 页「模型配置」维护），对话/交易员/复盘教练只做<b>选择</b>：
 * 按用途绑定到某一条（{@link UserLlmBinding}），没绑定的用途落到 {@code isDefault} 那条。
 * 一人恰有一条默认（只要还有端点）；只配一条时它就是全局默认，谁都用它。
 */
@Data
@TableName("user_llm_endpoint")
public class UserLlmEndpoint {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    /** 用户给的名字（下拉框里认它） */
    private String name;
    /** 上游协议，见 AiProtocols：openai / responses / anthropic / gemini */
    private String apiProtocol;
    private String baseUrl;
    private String model;
    /**
     * 思考档位 none/low/medium/high，可空=不传走模型默认。模型支不支持这个参数查不到，所以由用户自己选。
     * ALWAYS：改回"不传"是合法操作，默认策略会跳过 null。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String reasoningEffort;
    /**
     * 服务端联网搜索：请求里声明该协议的服务端搜索工具才搜（opt-in）；勾了的保存前会探测，探测不过不让存。
     * 能声明的协议才存 true（AiProtocols.supportsServerSearch）。当前只有对话的主 agent 与 researcher worker 会用到。
     */
    private Boolean webSearch;
    /** 上下文窗口（token）：对话主模型的上下文用量达到它的 90% 时，下一轮开始前整理上下文；用户按模型实际窗口填 */
    private Integer contextWindow;
    /** AES-GCM 密文 base64(iv+cipher)，密钥来自 WIIB_TRADER_KEY_SECRET */
    private String apiKeyEnc;
    /** 默认端点：没按用途绑定的地方都用它；一人至多一条为 true */
    private Boolean isDefault;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
