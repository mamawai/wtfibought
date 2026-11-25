package com.mawai.wiibagent.handover;

import com.mawai.wiibagent.learning.ReviewScheduler;
import com.mawai.wiibagent.trader.wakeup.TraderScheduler;
import com.mawai.wiibcommon.handover.InFlight;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 旧实例收尾时还持有的：唤醒还持有的 trader，加上没放掉的复盘位。清零了 /status 的 drained 才会是真 */
@Component
@RequiredArgsConstructor
public class HeldTraders implements InFlight {

    private final TraderScheduler scheduler;
    private final ReviewScheduler reviews;

    @Override
    public String name() {
        return "held";
    }

    @Override
    public int count() {
        return scheduler.heldHere().size() + reviews.heldHereCount();
    }
}
