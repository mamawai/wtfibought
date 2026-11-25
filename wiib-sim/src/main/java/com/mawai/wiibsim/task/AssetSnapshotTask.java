package com.mawai.wiibsim.task;

import com.mawai.wiibsim.service.AssetSnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

@Slf4j
@Component
@RequiredArgsConstructor
public class AssetSnapshotTask {

    private final AssetSnapshotService assetSnapshotService;
    /** 会改账的后台活交给它，关停时等它清零 */
    private final Executor executor;

    @Scheduled(cron = "0 0 0 * * *")
    public void dailySnapshot() {
        executor.execute(() -> {
            log.info("开始每日资产快照(收尾昨日)");
            assetSnapshotService.snapshotAll();
        });
    }
}
