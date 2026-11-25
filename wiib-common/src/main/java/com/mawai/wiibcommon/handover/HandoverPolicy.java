package com.mawai.wiibcommon.handover;

import java.time.Duration;

/**
 * 各服务在编排上的差别，每个服务声明一个 bean
 *
 * @param service      服务名：feed / sim / agent，Redis 键和 /status 里用
 * @param protocol     交接协议版本号，交接单或对端接口有不兼容改动就加一；新旧实例不一样就只能冷切
 * @param shutdownWait 关停时等手上的活多久
 * @param cutWait      提前收尾之后再等多久；0 是不提前收尾，等满 shutdownWait 就不再等
 */
public record HandoverPolicy(String service, int protocol, Duration shutdownWait, Duration cutWait) {
}
