package com.mawai.wiibsim.service;

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

    /** 结算指定回合：缺价先查缓存再回源 REST，实在没价按作废退本金 */
    void settleRound(long windowStart);

    void syncOpenPrice();

    /** 补结算巡检：把早该结算却还停在 LOCKED 的回合逐个重跑 */
    void sweepStuckRounds();
}
