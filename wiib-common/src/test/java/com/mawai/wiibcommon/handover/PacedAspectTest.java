package com.mawai.wiibcommon.handover;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 按上次跑完的时刻控制节奏：没到点跳过；跑完（失败也算）记下时刻；间隔可以写配置占位符 */
class PacedAspectTest {

    static final Map<String, String> REDIS = new ConcurrentHashMap<>();

    @Configuration
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class Ctx {
        @Bean
        @SuppressWarnings("unchecked")
        StringRedisTemplate redis() {
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            ValueOperations<String, String> ops = mock(ValueOperations.class);
            when(redis.opsForValue()).thenReturn(ops);
            when(ops.get(anyString())).thenAnswer(i -> REDIS.get(i.<String>getArgument(0)));
            doAnswer(i -> REDIS.put(i.getArgument(0), i.getArgument(1))).when(ops).set(anyString(), anyString());
            return redis;
        }

        @Bean
        PacedAspect pacedAspect(ObjectProvider<StringRedisTemplate> redis) {
            return new PacedAspect(redis);
        }

        @Bean
        Job job() {
            return new Job();
        }
    }

    public static class Job {
        private final AtomicInteger runs = new AtomicInteger();
        boolean fail;

        @Paced(key = "t:job", every = "${job.every:1000}")
        public void run() {
            runs.incrementAndGet();
            if (fail) {
                throw new IllegalStateException("上游挂了");
            }
        }

        public int runs() {
            return runs.get();
        }

        public void failing() {
            fail = true;
        }
    }

    @Test
    void 没到点跳过_到点才跑_失败也记时刻() {
        REDIS.clear();
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(Ctx.class)) {
            Job job = ctx.getBean(Job.class);
            PacedAspect aspect = ctx.getBean(PacedAspect.class);
            long[] now = {10_000};
            aspect.nowMs = () -> now[0];

            job.run();
            assertThat(job.runs()).isEqualTo(1);
            assertThat(REDIS).containsEntry("wiib:job:t:job:last", "10000");

            now[0] = 10_999;
            job.run();
            assertThat(job.runs()).isEqualTo(1);

            now[0] = 11_000;
            job.failing();
            assertThatThrownBy(job::run).isInstanceOf(IllegalStateException.class);
            assertThat(job.runs()).isEqualTo(2);
            assertThat(REDIS).containsEntry("wiib:job:t:job:last", "11000");
        }
    }
}
