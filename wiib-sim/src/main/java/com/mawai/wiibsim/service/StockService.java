package com.mawai.wiibsim.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.mawai.wiibcommon.dto.StockDTO;
import com.mawai.wiibcommon.entity.Stock;

import java.util.List;

/**
 * 股票服务接口
 */
public interface StockService extends IService<Stock> {

    /**
     * 根据股票ID查找
     * @param id 股票ID
     * @return 股票实体
     */
    Stock findById(Long id);

    /**
     * 获取股票详情（含公司信息）
     * @param id 股票ID
     * @return 股票DTO
     */
    StockDTO getStockDetail(Long id);

    /**
     * 获取所有股票列表
     * @return 股票列表
     */
    List<StockDTO> listAllStocks();

    /**
     * 分页查询股票列表
     * @param pageNum 页码（从1开始）
     * @param pageSize 每页大小
     * @return 分页结果
     */
    IPage<StockDTO> listStocksByPage(int pageNum, int pageSize);

    /**
     * 获取涨幅榜
     * @param limit 数量限制
     * @return 涨幅榜
     */
    List<StockDTO> getTopGainers(int limit);

    /**
     * 获取跌幅榜
     * @param limit 数量限制
     * @return 跌幅榜
     */
    List<StockDTO> getTopLosers(int limit);
}
