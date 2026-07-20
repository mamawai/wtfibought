package com.mawai.wiibsim.service;

import com.mawai.wiibsim.mapper.CommentNotificationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;

/**
 * 通知点对点推送。目的地 {@code /user/queue/notification}，Spring 按连接的 Principal(=userId) 找人。
 * <p>
 * 推送一律走事务提交后：在事务里推的话，回滚了对方角标已经加过了，点开又什么都没有。
 * 推送失败只记日志——用户下次打开页面会重新拉未读数，不该让一条通知推不出去把整个评论操作搞挂。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationPushService {

    private final SimpMessagingTemplate messagingTemplate;
    private final CommentNotificationMapper notificationMapper;

    /** 事务提交后推最新未读数给接收者。多标签页时该用户每条连接都会收到 */
    public void pushUnreadAfterCommit(long receiverId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            pushUnread(receiverId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                pushUnread(receiverId);
            }
        });
    }

    private void pushUnread(long receiverId) {
        try {
            long unread = notificationMapper.countUnread(receiverId);
            messagingTemplate.convertAndSendToUser(
                    String.valueOf(receiverId), "/queue/notification", Map.of("unread", unread));
        } catch (Exception e) {
            log.warn("[Notification] 未读数推送失败 receiverId={} msg={}", receiverId, e.toString());
        }
    }
}
