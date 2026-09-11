package com.mawai.wiibagent.chat.gate;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * 对话并发闸门：全局 N 轮 + 每用户 1 轮，超限直接拒绝。
 * <p>
 * 不排队：排队意味着 SSE 连接挂着干等，还要另外处理等待超时。
 * <p>
 * 限的是"同时在跑的对话轮"而不是在线人数——发一句话占一个名额、这一轮跑完就还，
 * 用户打字和读答案的时间都不占，所以 10 个名额能服务远多于 10 个在线用户。
 * <p>
 * 限流保的是行情配额：它按出口 IP 算且对话与策略执行轨共用一个 REST 客户端，打爆了两边一起瘫。
 * 每用户 1 轮防"一个人开十个标签页把别人全挡在外面"。
 */
@Component
public class ChatConcurrencyGate {

    /** 拒因。调用方要据此给两个不同的错误码：是"你已有一轮在跑"还是"人满了"，对用户是两回事 */
    public enum Acquire { OK, USER_BUSY, GLOBAL_FULL }

    private final Semaphore slots;
    private final Set<Long> activeUsers = ConcurrentHashMap.newKeySet();

    public ChatConcurrencyGate(@Value("${agent.workbench.max-concurrent-chats:10}") int globalLimit) {
        this.slots = new Semaphore(globalLimit);
    }

    public Acquire tryAcquire(long userId) {
        // 先占用户位自己的再占全局的
        if (!activeUsers.add(userId)) {
            return Acquire.USER_BUSY;
        }
        if (!slots.tryAcquire()) {
            activeUsers.remove(userId);
            return Acquire.GLOBAL_FULL;
        }
        return Acquire.OK;
    }

    /**
     * 还名额，<b>必须与 tryAcquire 成功配对</b>：名额漏满就对所有人永久拒绝，
     * 是全套设计里唯一不可恢复的失败模式。
     * 许可的还与不还跟着 activeUsers.remove 走，所以重复释放不会凭空多出名额。
     */
    public void release(long userId) {
        if (activeUsers.remove(userId)) {
            slots.release();
        }
    }
}
