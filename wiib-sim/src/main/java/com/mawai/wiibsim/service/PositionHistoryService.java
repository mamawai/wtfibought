package com.mawai.wiibsim.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mawai.wiibcommon.dto.PositionFillDTO;
import com.mawai.wiibcommon.dto.PositionHistoryDTO;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 合约仓位历史，只读。
 * <p>
 * 自己看自己走 /api/futures/position-history，看别人走 /api/ranking/users/{id}/position-history，
 * 两条路查的是同一份数据，只差一道隐私门控——门控留在 controller（同 PublicTradeService 的分工），
 * 因为"看自己永远放行"这条规则得知道当前登录人是谁，这里拿不到也不该拿。
 * AI Trader 子账户的那份走 /internal/futures/{userId}/position-history，由 agent 公开出去。
 */
@Service
@RequiredArgsConstructor
public class PositionHistoryService {

    /** 单页封顶，同 PublicTradeService 那条口径 */
    private static final int MAX_PAGE_SIZE = 100;

    private final FuturesPositionMapper futuresPositionMapper;

    /** symbol 传 null 即不筛币种 */
    public IPage<PositionHistoryDTO> page(Long userId, String symbol, int pageNum, int pageSize) {
        // 空串归一成 null：SQL 侧只认 null 为"不筛"（原 <if> 里 symbol != '' 的语义挪到这）
        String symbolFilter = (symbol == null || symbol.isEmpty()) ? null : symbol;
        Page<PositionHistoryDTO> page = new Page<>(Math.max(pageNum, 1), Math.clamp(pageSize, 1, MAX_PAGE_SIZE));
        futuresPositionMapper.selectPositionHistory(page, userId, symbolFilter);
        attachFills(page.getRecords());
        return page;
    }

    /**
     * 给这一页的每条仓位挂上成交明细。
     * <p>
     * 明细一次全取回来在内存里分组，不是逐行去查——一页 20 条就是 20 次往返，
     * 典型的 N+1。分组用 orDefault 兜底：破产清零那批仓位一单都没有，挂空表比挂 null 好使。
     */
    private void attachFills(List<PositionHistoryDTO> rows) {
        if (rows.isEmpty()) return;
        Long[] ids = rows.stream().map(PositionHistoryDTO::getId).toArray(Long[]::new);
        Map<Long, List<PositionFillDTO>> byPosition = futuresPositionMapper.selectFillsByPositionIds(ids)
                .stream()
                .collect(Collectors.groupingBy(PositionFillDTO::getPositionId));
        rows.forEach(row -> row.setFills(byPosition.getOrDefault(row.getId(), List.of())));
    }
}
