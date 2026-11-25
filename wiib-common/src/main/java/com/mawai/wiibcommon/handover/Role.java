package com.mawai.wiibcommon.handover;

/** 实例的身份。只有编排自己用，别处不引用 */
public enum Role {
    /** 待命：起好了、连好库和 Redis，不发起任何活 */
    STANDBY,
    /** 在任：发起新活 */
    ACTIVE,
    /** 收尾：不发起新活，只做完手上的 */
    DRAINING
}
