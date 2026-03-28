package com.mawai.wiibservice.invitecode.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("linuxdo_invite_code")
public class LinuxdoInviteCode {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("code")
    private String code;

    @TableField("email")
    private String email;

    @TableField("claimed_at")
    private LocalDateTime claimedAt;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
