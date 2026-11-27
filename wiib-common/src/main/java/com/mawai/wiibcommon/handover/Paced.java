package com.mawai.wiibcommon.handover;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 周期任务按"上次跑完的时刻"控制节奏，不跟着进程启动走（{@link PacedAspect}）：定时器勤触发（比如每分钟），
 * 离上次跑完不到 every 就跳过，跑完把时刻记进 Redis 的 wiib:job:&lt;key&gt;:last
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Paced {

    /** Redis 键中间那段，比如 agent:news */
    String key();

    /** 间隔毫秒，可以写 ${配置:默认值} */
    String every();
}
