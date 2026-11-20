package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.dto.AssetSnapshotDTO;
import com.mawai.wiibcommon.dto.CategoryAveragesDTO;

import java.time.YearMonth;
import java.util.List;

public interface AssetSnapshotService {

    void snapshotAll();

    AssetSnapshotDTO getRealtimeSnapshot(Long userId);

    List<AssetSnapshotDTO> getHistory(Long userId, int days);

    /** 指定月份逐日快照，给首页月度盈亏网格用。快照表只写到昨天，所以返回里天然没有今天 */
    List<AssetSnapshotDTO> getMonthly(Long userId, YearMonth month);

    CategoryAveragesDTO getCategoryAverages(Long userId);
}
