package com.mawai.wiibsim.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.mawai.wiibcommon.entity.FuturesOrder;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.dto.PositionFillDTO;
import com.mawai.wiibcommon.dto.PositionHistoryDTO;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 仓位历史查询真跑验收（非单测）：起完整 Spring 上下文、真连本地 PG。
 * <p>
 * 【为什么非真跑不可】这条查询整个身家都在一段手写 SQL 里，而它有三处只有真发给 DB 才现形的风险：
 * <ol>
 *   <li>聚合口径本身——部分平仓过的仓位，已平仓量/平仓均价/已实现盈亏必须是全部平仓单加出来的，
 *       不是仓位表上那两个残值列。mock 掉 mapper 就是把被测对象一起 mock 掉。</li>
 *   <li>下划线转驼峰——PositionHistoryDTO 不是实体、没有 resultMap，全靠 MyBatis 自动映射。
 *       哪天配置被关掉，closeAvgPrice / roiPct 这些多词字段会<b>静默变 null</b>，编译和单测都不吭声，
 *       只有页面上一片"—"。</li>
 *   <li>分页插件对 &lt;script&gt; + LEFT JOIN 子查询的 COUNT 改写——total 算错同样不报错。</li>
 * </ol>
 * <p>
 * 跑法（项目根）：
 * <pre>
 * set -a &amp;&amp; source .env.local &amp;&amp; set +a
 * WIIB_REAL_RUN=1 mvn -o test -pl wiib-sim -am -DskipTests=false \
 *   -Dtest=PositionHistoryRealRunTest -Dsurefire.failIfNoSpecifiedTests=false \
 *   -DREDIS_PASSWORD=
 * </pre>
 * 末尾空的 {@code -DREDIS_PASSWORD=} 同 FuturesPositionIndexRealRunTest，理由见那个类的注释。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "WIIB_REAL_RUN", matches = "1")
class PositionHistoryRealRunTest {

    /** 测试用户 id 取一个真实业务绝不会用到的负数，跑完即删，不碰任何存量数据 */
    private static final Long TEST_USER_ID = -90210L;

    private static final String SYMBOL = "BTCUSDT";

    @Autowired
    private PositionHistoryService positionHistoryService;

    @Autowired
    private FuturesPositionMapper positionMapper;

    @Autowired
    private FuturesOrderMapper orderMapper;

    private final List<Long> createdPositionIds = new ArrayList<>();
    private final List<Long> createdOrderIds = new ArrayList<>();

    @AfterEach
    void 清掉本次建的测试数据() {
        createdOrderIds.forEach(orderMapper::deleteById);
        createdPositionIds.forEach(positionMapper::deleteById);
        createdOrderIds.clear();
        createdPositionIds.clear();
    }

    /**
     * 一笔"开 1 → 平 0.4 → 平 0.6"的多头仓位，验聚合是不是真把两次平仓加起来了。
     * <p>
     * 这正是仓位表两个残值列骗人的场景：全平后 quantity 只剩 0.6、closed_pnl 只记最后那笔，
     * 直接读它们会把这笔生意说小一半。
     */
    @Test
    void 部分平仓过的仓位_已平仓量与平仓均价按全部平仓单加权() {
        LocalDateTime openedAt = LocalDateTime.now().minusHours(5);
        Long posId = 建仓(openedAt, new BigDecimal("100.00000000"), new BigDecimal("0.6"), BigDecimal.ZERO);

        // 开仓：投入保证金 60，手续费 1
        建单(posId, "OPEN_LONG", new BigDecimal("1.0"), new BigDecimal("100"),
                new BigDecimal("100.00"), new BigDecimal("60.00"), new BigDecimal("1.00"), null);
        // 首次平 0.4 @110 → 赚 4；二次平 0.6 @120 → 赚 12。两笔各 1 元手续费
        建单(posId, "CLOSE_LONG", new BigDecimal("0.4"), new BigDecimal("110"),
                new BigDecimal("44.00"), null, new BigDecimal("1.00"), new BigDecimal("4.00"));
        建单(posId, "CLOSE_LONG", new BigDecimal("0.6"), new BigDecimal("120"),
                new BigDecimal("72.00"), null, new BigDecimal("1.00"), new BigDecimal("12.00"));

        PositionHistoryDTO row = 查第一条();

        // 已平仓量 = 0.4 + 0.6，不是仓位表上残留的 0.6
        assertThat(row.getClosedQty()).isEqualByComparingTo("1.0");
        // 平仓均价 = (44 + 72) / 1.0 = 116，两次成交按量加权，不是最后一次的 120
        assertThat(row.getCloseAvgPrice()).isEqualByComparingTo("116");
        assertThat(row.getEntryPrice()).isEqualByComparingTo("100");
        // 已实现盈亏 = (4 + 12) − 手续费 3 = 13
        assertThat(row.getRealizedPnl()).isEqualByComparingTo("13.00");
        assertThat(row.getCommission()).isEqualByComparingTo("3.00");
        // 投资回报率 = 13 / 投入保证金 60 = 21.67%
        assertThat(row.getInvestedMargin()).isEqualByComparingTo("60.00");
        assertThat(row.getRoiPct()).isEqualByComparingTo("21.67");
        // 开/平仓时间两头都在，前端靠这两个算持仓时长
        assertThat(row.getOpenedAt()).isNotNull();
        assertThat(row.getClosedAt()).isNotNull();

        // 明细要能还原过程：开 1.0@100，再 0.4@110 和 0.6@120 两刀平掉，一条都不能少
        assertThat(row.getFills()).hasSize(3);
        assertThat(row.getFills()).extracting(f -> f.getQuantity().stripTrailingZeros().toPlainString())
                .containsExactly("1", "0.4", "0.6");
        assertThat(row.getFills()).extracting(f -> f.getPrice().stripTrailingZeros().toPlainString())
                .containsExactly("100", "110", "120");
        assertThat(row.getFills()).extracting(PositionFillDTO::getOrderSide)
                .containsExactly("OPEN_LONG", "CLOSE_LONG", "CLOSE_LONG");
        // 开仓单没有已实现盈亏，两笔平仓单各自记各自的
        assertThat(row.getFills().get(0).getRealizedPnl()).isNull();
        assertThat(row.getFills().get(1).getRealizedPnl()).isEqualByComparingTo("4.00");
        assertThat(row.getFills().get(2).getRealizedPnl()).isEqualByComparingTo("12.00");
    }

    /** 资金费是真金白银扣走的，必须从已实现盈亏里减掉，口径与排行榜「交易盈利」一致 */
    @Test
    void 已实现盈亏扣掉资金费() {
        Long posId = 建仓(LocalDateTime.now().minusHours(2), new BigDecimal("100.00000000"),
                new BigDecimal("1.0"), new BigDecimal("2.50"));
        建单(posId, "OPEN_LONG", new BigDecimal("1.0"), new BigDecimal("100"),
                new BigDecimal("100.00"), new BigDecimal("50.00"), new BigDecimal("1.00"), null);
        建单(posId, "CLOSE_LONG", new BigDecimal("1.0"), new BigDecimal("110"),
                new BigDecimal("110.00"), null, new BigDecimal("1.00"), new BigDecimal("10.00"));

        PositionHistoryDTO row = 查第一条();

        // 10 − 手续费 2 − 资金费 2.5 = 5.5
        assertThat(row.getRealizedPnl()).isEqualByComparingTo("5.50");
        assertThat(row.getFundingFeeTotal()).isEqualByComparingTo("2.50");
        assertThat(row.getRoiPct()).isEqualByComparingTo("11.00");   // 5.5 / 50
    }

    /**
     * 破产清零那批仓位（BankruptcyServiceImpl 只改 status、不落平仓单）一单都没有。
     * 这时候必须给 null 让前端显示"—"，填 0 会被读成"这仓平在 0 块钱"。
     */
    @Test
    void 没有平仓单的仓位_平仓均价与回报率为空而不是零() {
        Long posId = 建仓(LocalDateTime.now().minusDays(1), new BigDecimal("100.00000000"),
                new BigDecimal("1.0"), BigDecimal.ZERO);

        PositionHistoryDTO row = 查第一条();

        assertThat(row.getCloseAvgPrice()).isNull();
        assertThat(row.getRoiPct()).isNull();
        assertThat(row.getClosedQty()).isEqualByComparingTo("0");
        assertThat(row.getRealizedPnl()).isEqualByComparingTo("0");
        // 一单都没有时给空表不给 null，前端少写一个判空分支
        assertThat(row.getFills()).isEmpty();
    }

    /** 分页插件得能给这段 LEFT JOIN 子查询正确改写出 COUNT，total 算错一样不报错 */
    @Test
    void 分页total与页大小都对() {
        for (int i = 0; i < 3; i++) {
            建仓(LocalDateTime.now().minusHours(10 + i), new BigDecimal("100.00000000"),
                    new BigDecimal("1.0"), BigDecimal.ZERO);
        }

        IPage<PositionHistoryDTO> page = positionHistoryService.page(TEST_USER_ID, null, 1, 2);

        assertThat(page.getTotal()).isEqualTo(3);
        assertThat(page.getRecords()).hasSize(2);
        assertThat(positionHistoryService.page(TEST_USER_ID, null, 2, 2).getRecords()).hasSize(1);
    }

    /** 只查已平的，OPEN 的仓位不能混进历史 */
    @Test
    void 持仓中的仓位不进历史() {
        FuturesPosition open = new FuturesPosition();
        open.setUserId(TEST_USER_ID);
        open.setSymbol(SYMBOL);
        open.setSide("LONG");
        open.setMarginMode(FuturesPosition.ISOLATED);
        open.setLeverage(10);
        open.setQuantity(new BigDecimal("1.0"));
        open.setEntryPrice(new BigDecimal("100.00000000"));
        open.setMargin(new BigDecimal("10.00"));
        open.setFundingFeeTotal(BigDecimal.ZERO);
        open.setStatus("OPEN");
        positionMapper.insert(open);
        createdPositionIds.add(open.getId());

        assertThat(positionHistoryService.page(TEST_USER_ID, null, 1, 20).getTotal()).isZero();
    }

    // ==================== helpers ====================

    private PositionHistoryDTO 查第一条() {
        List<PositionHistoryDTO> records = positionHistoryService.page(TEST_USER_ID, null, 1, 20).getRecords();
        assertThat(records).hasSize(1);
        return records.getFirst();
    }

    /** quantity 传全平后残留的那一段（真实数据就是这样），entryPrice 是开仓均价 */
    private Long 建仓(LocalDateTime openedAt, BigDecimal entryPrice, BigDecimal leftoverQty, BigDecimal fundingFee) {
        FuturesPosition pos = new FuturesPosition();
        pos.setUserId(TEST_USER_ID);
        pos.setSymbol(SYMBOL);
        pos.setSide("LONG");
        pos.setMarginMode(FuturesPosition.ISOLATED);
        pos.setLeverage(10);
        pos.setQuantity(leftoverQty);
        pos.setEntryPrice(entryPrice);
        pos.setMargin(new BigDecimal("10.00"));
        pos.setFundingFeeTotal(fundingFee);
        pos.setStatus("CLOSED");
        pos.setCreatedAt(openedAt);
        positionMapper.insert(pos);
        createdPositionIds.add(pos.getId());
        return pos.getId();
    }

    private void 建单(Long positionId, String orderSide, BigDecimal qty, BigDecimal price,
                      BigDecimal filledAmount, BigDecimal marginAmount, BigDecimal commission,
                      BigDecimal realizedPnl) {
        FuturesOrder order = new FuturesOrder();
        order.setUserId(TEST_USER_ID);
        order.setPositionId(positionId);
        order.setSymbol(SYMBOL);
        order.setOrderSide(orderSide);
        order.setOrderType("MARKET");
        order.setMarginMode(FuturesPosition.ISOLATED);
        order.setQuantity(qty);
        order.setLeverage(10);
        order.setFilledPrice(price);
        order.setFilledAmount(filledAmount);
        order.setMarginAmount(marginAmount);
        order.setCommission(commission);
        order.setRealizedPnl(realizedPnl);
        order.setStatus("FILLED");
        orderMapper.insert(order);
        createdOrderIds.add(order.getId());
    }
}
