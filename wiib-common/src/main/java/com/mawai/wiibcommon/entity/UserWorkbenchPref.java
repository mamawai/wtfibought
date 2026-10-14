package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 研判工作台的用户偏好：一人一行，没行就全按默认。
 * 目前只有后台子 agent 的开关与同时在跑的上限。
 */
@Data
@TableName("user_workbench_pref")
public class UserWorkbenchPref {

    @TableId(value = "user_id", type = IdType.INPUT)
    private Long userId;
    /** 允许主 agent 派后台子 agent（delegate）；深研判不受它管 */
    private Boolean subAgentsEnabled;
    /** 同一用户同时在跑的子运行数上限 */
    private Integer subAgentLimit;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
