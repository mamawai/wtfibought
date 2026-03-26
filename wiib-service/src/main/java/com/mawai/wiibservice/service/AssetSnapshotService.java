package com.mawai.wiibservice.service;

import com.mawai.wiibcommon.dto.AssetSnapshotDTO;
import com.mawai.wiibcommon.dto.CategoryAveragesDTO;

import java.util.List;

public interface AssetSnapshotService {

    void snapshotAll();

    AssetSnapshotDTO getRealtimeSnapshot(Long userId);

    List<AssetSnapshotDTO> getHistory(Long userId, int days);

    CategoryAveragesDTO getCategoryAverages(Long userId, int days);
}
