package com.mawai.wiibsim.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.mawai.wiibcommon.dto.BStockDTO;
import com.mawai.wiibcommon.entity.BStock;

import java.math.BigDecimal;
import java.util.List;

/** bStock 读取服务：静态信息读 bstock 表，实时价/K线走 Binance（REST/Redis）。 */
public interface BStockService extends IService<BStock> {

    /** 全部上架 bStock（静态信息 + 实时价 + 24h 涨跌），按 sort 排序 */
    List<BStockDTO> listAll();

    /** 单只详情（按现货符号，如 NVDABUSDT） */
    BStockDTO detail(String symbol);

    /** 最新价（Redis 优先，未命中回退 REST） */
    BigDecimal price(String symbol);

    /** 是否 bStock 现货符号（现货引擎据此判卖出瞬时结算 vs crypto 5min） */
    boolean isBStockSymbol(String symbol);
}
