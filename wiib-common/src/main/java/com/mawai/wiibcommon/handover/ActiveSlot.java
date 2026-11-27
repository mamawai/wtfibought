package com.mawai.wiibcommon.handover;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 里记着的"这个服务现在哪个槽位在任"：键 wiib:active:<服务>，值是槽位字母，不过期。
 * 没分槽位时用不到它，Redis 连接用到时才取
 */
public class ActiveSlot {

    private final ObjectProvider<StringRedisTemplate> redis;
    private final String key;

    public ActiveSlot(ObjectProvider<StringRedisTemplate> redis, String service) {
        this.redis = redis;
        this.key = "wiib:active:" + service;
    }

    /** 现在在任的槽位，没记过返回 null */
    public String current() {
        return redis.getObject().opsForValue().get(key);
    }

    /** 没人记过时记成 slot，记上了返回 true */
    public boolean claim(String slot) {
        return Boolean.TRUE.equals(redis.getObject().opsForValue().setIfAbsent(key, slot));
    }

    /** 记成 slot（接手、恢复在任时） */
    public void set(String slot) {
        redis.getObject().opsForValue().set(key, slot);
    }
}
