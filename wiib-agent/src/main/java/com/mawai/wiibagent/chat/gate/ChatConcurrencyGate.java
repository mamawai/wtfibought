package com.mawai.wiibagent.chat.gate;

import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.handover.InFlight;
import com.mawai.wiibcommon.handover.Intake;
import com.mawai.wiibcommon.handover.Start;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * 对话并发闸门：全局 N 轮 + 每用户 1 轮，超限直接拒绝。工作台对话和提示词生成共用这一套。
 * <p>
 * 不排队：排队意味着 SSE 连接挂着干等，还要另外处理等待超时。
 * <p>
 * 限的是"同时在跑的对话轮"而不是在线人数——发一句话占一个名额、这一轮跑完就还，
 * 用户打字和读答案的时间都不占，所以 10 个名额能服务远多于 10 个在线用户。
 * <p>
 * 限流保的是行情配额：它按出口 IP 算且对话与策略执行轨共用一个 REST 客户端，打爆了两边一起瘫。
 * 每用户 1 轮防"一个人开十个标签页把别人全挡在外面"。
 * <p>
 * 用户位记在 Redis（agent:chat:busy:&lt;userId&gt;，值是"谁在跑:槽位"）：交接期间新旧两个实例都看得到，
 * 不会同一个人两边各跑一轮、互相覆盖会话上下文。全局名额留在进程内，两个实例各算各的。
 * 进程被杀掉没来得及还的用户位，这个槽位下一个进程启动时清掉（{@link #clearLeftovers}）。
 * <p>
 * 进活的口是"接新对话"：关着时新的一轮直接拒，回"服务正在更新"。
 * 占着的名额是一份手上的活（chat-slots），从占到到还。
 */
@Slf4j
@Component
@Order(70)
public class ChatConcurrencyGate implements Intake, InFlight {

    /** 拒因。调用方要据此给不同的错误码：你已有一轮在跑、人满了、服务正在更新，对用户是三回事 */
    public enum Acquire { OK, USER_BUSY, GLOBAL_FULL, CLOSED }

    /** 这个用户的那一轮是谁在跑：工作台对话还是提示词生成 */
    public enum Owner { WORKBENCH, PROMPT_GEN }

    /** Redis 里用户位的过期，盖过一轮最长的时间；占着它的进程没了、这个槽位也没再起来时靠它放掉 */
    static final Duration USER_SLOT_TTL = Duration.ofHours(1);

    private static final String KEY_PREFIX = "agent:chat:busy:";

    /** 值对得上才删 */
    private static final RedisScript<Long> DELETE_IF_SAME = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end", Long.class);

    private final StringRedisTemplate redis;
    /** 本实例的槽位，记进用户位的值里 */
    private final String slot;
    private final Semaphore slots;
    /** 本实例占着的用户位：userId → 写进 Redis 的值 */
    private final Map<Long, String> mine = new ConcurrentHashMap<>();
    /** 接新对话的口 */
    private volatile boolean open;

    public ChatConcurrencyGate(StringRedisTemplate redis, @Value("${WIIB_SLOT:}") String slot,
                               @Value("${agent.workbench.max-concurrent-chats:10}") int globalLimit) {
        this.redis = redis;
        this.slot = slot;
        this.slots = new Semaphore(globalLimit);
    }

    /**
     * 进程启动时清掉这个槽位上一个进程留下的用户位：一个槽位同时只有一个进程，
     * 这时 Redis 里值以本槽位结尾的都是上一个进程占的
     */
    @PostConstruct
    void clearLeftovers() {
        String suffix = ":" + slot;
        try (Cursor<String> keys = redis.scan(ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(500).build())) {
            while (keys.hasNext()) {
                String key = keys.next();
                String value = redis.opsForValue().get(key);
                if (value != null && value.endsWith(suffix)) {
                    redis.execute(DELETE_IF_SAME, List.of(key), value);
                    log.info("[ChatGate] 清掉上一个进程留下的用户位 {}", key);
                }
            }
        }
    }

    @Override
    public void open(Start how) {
        open = true;
    }

    @Override
    public void close() {
        open = false;
    }

    @Override
    public String name() {
        return "chat-slots";
    }

    @Override
    public int count() {
        return mine.size();
    }

    public Acquire tryAcquire(long userId, Owner owner) {
        String value = owner.name() + ":" + slot;
        // 先占本实例的用户位，再占 Redis 里的（另一个实例也看得到），最后占全局名额
        if (mine.putIfAbsent(userId, value) != null) {
            return Acquire.USER_BUSY;
        }
        boolean claimed;
        try {
            claimed = Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(userId), value, USER_SLOT_TTL));
        } catch (RuntimeException e) {
            mine.remove(userId);
            throw e;
        }
        if (!claimed) {
            mine.remove(userId);
            return Acquire.USER_BUSY;
        }
        // 占上了再看口：关口那边先关口再数占着的，两边总有一边看得到对方
        if (!open) {
            mine.remove(userId);
            free(userId, value);
            return Acquire.CLOSED;
        }
        if (!slots.tryAcquire()) {
            mine.remove(userId);
            free(userId, value);
            return Acquire.GLOBAL_FULL;
        }
        return Acquire.OK;
    }

    /**
     * 占名额，占不到按拒因抛对应错误码；占到了调用方负责配对 release。
     * 用户位被提示词生成占着时单给一个码，工作台据此说"提示词生成中"
     */
    public void acquireOrThrow(long userId, Owner owner) {
        Acquire acquired = tryAcquire(userId, owner);
        if (acquired == Acquire.CLOSED) {
            throw new BizException(ErrorCode.CHAT_SERVICE_UPDATING);
        }
        if (acquired == Acquire.GLOBAL_FULL) {
            throw new BizException(ErrorCode.CHAT_CAPACITY_FULL);
        }
        if (acquired == Acquire.USER_BUSY) {
            throw new BizException(holder(userId).startsWith(Owner.PROMPT_GEN.name() + ":")
                    ? ErrorCode.PROMPT_GEN_RUNNING : ErrorCode.CHAT_ALREADY_RUNNING);
        }
    }

    /** 占着这个用户位的值；本实例没占就看 Redis，都没有是空串 */
    private String holder(long userId) {
        String local = mine.get(userId);
        if (local != null) {
            return local;
        }
        String shared = redis.opsForValue().get(key(userId));
        return shared == null ? "" : shared;
    }

    /** 这个用户此刻有没有一轮在跑（不分会话，也不分实例） */
    public boolean isBusy(long userId) {
        return mine.containsKey(userId) || Boolean.TRUE.equals(redis.hasKey(key(userId)));
    }

    /**
     * 还名额，<b>必须与 tryAcquire 成功配对</b>：名额漏满就对所有人永久拒绝，
     * 是全套设计里唯一不可恢复的失败模式。
     * 许可的还与不还跟着本实例用户位的摘除走，所以重复释放不会凭空多出名额。
     */
    public void release(long userId) {
        String value = mine.remove(userId);
        if (value != null) {
            slots.release();
            free(userId, value);
        }
    }

    /** 删 Redis 里的用户位：过期后被别人占上的不删；删不掉只记日志，等它过期 */
    private void free(long userId, String value) {
        try {
            redis.execute(DELETE_IF_SAME, List.of(key(userId)), value);
        } catch (RuntimeException e) {
            log.warn("[ChatGate] 删用户位失败 userId={} msg={}", userId, e.toString());
        }
    }

    private static String key(long userId) {
        return KEY_PREFIX + userId;
    }
}
