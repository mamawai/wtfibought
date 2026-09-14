package com.mawai.wiibquant.controller;

import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibquant.whale.WhaleQueryService;
import com.mawai.wiibquant.whale.WhaleQueryService.CoinDetail;
import com.mawai.wiibquant.whale.WhaleQueryService.Summary;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hyperliquid 大户持仓两个 GET，游客可读（agent 进程的 SaTokenConfig 放行）。
 * 与 /api/ai/quant/econ-calendar 同组，前端归 quantApi。
 */
@RestController
@RequestMapping("/api/ai/quant/whale")
@RequiredArgsConstructor
public class WhaleController {

    private final WhaleQueryService queryService;

    /** 首页卡：各币最新一轮 */
    @GetMapping("/summary")
    public Result<Summary> summary() {
        return Result.ok(queryService.summary());
    }

    /** Coin 页：一个币的最新快照全量，含两个分桶；不在配置里或没东西可展示回 null */
    @GetMapping("/{coin}")
    public Result<CoinDetail> coin(@PathVariable String coin) {
        return Result.ok(queryService.coin(coin));
    }
}
