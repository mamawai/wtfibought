package com.mawai.wiibservice.task;

import com.mawai.wiibservice.service.AssetSnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AssetSnapshotTask {

    private final AssetSnapshotService assetSnapshotService;

    @Scheduled(cron = "0 0 0 * * *")
    public void dailySnapshot() {
        Thread.startVirtualThread(() -> {
            log.info("开始每日资产快照(收尾昨日)");
            assetSnapshotService.snapshotAll();
        });
    }
}
