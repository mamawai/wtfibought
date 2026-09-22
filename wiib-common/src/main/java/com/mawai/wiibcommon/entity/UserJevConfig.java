package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户自带的 Jev 决策模型（TypeSafe System One）配置：一人一份，URL + 模型 + key。
 * <p>
 * 与 {@link UserLlmEndpoint} 分开存：Jev 不是聊天模型，没有思考档位、搜索、用途绑定，一个模型全站共用。
 * 当前只给研判工作台路由用；以后别处要用 Jev 也读这一份。
 */
@Data
@TableName("user_jev_config")
public class UserJevConfig {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String baseUrl;
    /** jev-latest 或钉死的版本号 */
    private String model;
    /** AES-GCM 密文 base64(iv+cipher)，密钥来自 WIIB_TRADER_KEY_SECRET */
    private String apiKeyEnc;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
