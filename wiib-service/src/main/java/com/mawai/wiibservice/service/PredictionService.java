package com.mawai.wiibservice.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.mawai.wiibcommon.dto.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface PredictionService {

    PredictionRoundResponse getCurrentRound();

    PredictionBetResponse buy(Long userId, PredictionBuyRequest req);

    PredictionBetResponse sell(Long userId, Long betId, BigDecimal contracts);

    IPage<PredictionBetResponse> getUserBets(Long userId, int pageNum, int pageSize);

    IPage<PredictionRoundResponse> getSettledRounds(int pageNum, int pageSize);

    List<PredictionBetLiveResponse> getLiveActivity();

    PredictionPnlResponse getUserPnl(Long userId);

    List<Map<String, Object>> getPriceHistory();

    void createNewRound();

    void lockRound(long windowStart);

    void settlePreviousRound();

    void syncOpenPrice();
}
