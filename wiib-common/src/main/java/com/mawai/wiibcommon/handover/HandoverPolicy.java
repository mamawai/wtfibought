package com.mawai.wiibcommon.handover;

import java.time.Duration;

/**
 * 各服务在编排上的差别，每个服务声明一个 bean
 *
 * @param shutdownWait 关停时等手上的活多久
 * @param cutWait      提前收尾之后再等多久；0 是不提前收尾，等满 shutdownWait 就不再等
 */
public record HandoverPolicy(Duration shutdownWait, Duration cutWait) {
}
