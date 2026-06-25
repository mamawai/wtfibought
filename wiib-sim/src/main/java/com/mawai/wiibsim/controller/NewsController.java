package com.mawai.wiibsim.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.entity.News;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibsim.mapper.NewsMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Tag(name = "新闻接口")
@RestController
@RequestMapping("/api/news")
@RequiredArgsConstructor
public class NewsController {

    private final NewsMapper newsMapper;

    @GetMapping("/stock/{stockCode}")
    @Operation(summary = "获取股票相关新闻（按日期）")
    public Result<List<News>> getNewsByStock(
            @PathVariable String stockCode,
            @RequestParam(required = false) String date) {
        LocalDate queryDate = date != null ? LocalDate.parse(date) : LocalDate.now();

        List<LocalDate> validDates = getRecentTradingDays();
        if (!validDates.contains(queryDate)) {
            return Result.fail("日期必须在最近5个开盘日内");
        }

        LocalDateTime startOfDay = queryDate.atStartOfDay();
        LocalDateTime endOfDay = queryDate.atTime(LocalTime.MAX);

        List<News> news = newsMapper.selectList(
            new LambdaQueryWrapper<News>()
                .eq(News::getStockCode, stockCode)
                .ge(News::getPublishTime, startOfDay)
                .le(News::getPublishTime, endOfDay)
                .orderByDesc(News::getPublishTime)
        );
        return Result.ok(news);
    }

    private List<LocalDate> getRecentTradingDays() {
        List<LocalDate> tradingDays = new ArrayList<>();
        LocalDate current = LocalDate.now();
        while (tradingDays.size() < 5) {
            DayOfWeek dow = current.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                tradingDays.add(current);
            }
            current = current.minusDays(1);
        }
        return tradingDays;
    }
}
