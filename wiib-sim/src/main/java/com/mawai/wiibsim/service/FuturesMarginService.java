package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.dto.FuturesAddMarginRequest;
import com.mawai.wiibcommon.dto.FuturesAdjustLeverageRequest;
import com.mawai.wiibcommon.dto.FuturesReduceMarginRequest;

/** 改已有仓位的保证金和杠杆 */
public interface FuturesMarginService {

    /** 逐仓追加保证金：钱从余额划进仓位 */
    void addMargin(Long userId, FuturesAddMarginRequest request);

    /** 逐仓减少保证金：钱从仓位划回余额，减完得守住维持保证金和起始保证金两条线 */
    void reduceMargin(Long userId, FuturesReduceMarginRequest request);

    /** 币种级调杠杆（多空共用，一次调整该币全部仓位）：全仓双向可调（改占用额度），逐仓只能调高（释放多余保证金回钱包） */
    void adjustLeverage(Long userId, FuturesAdjustLeverageRequest request);
}
