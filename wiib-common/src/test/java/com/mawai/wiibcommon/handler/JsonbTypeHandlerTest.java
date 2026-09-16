package com.mawai.wiibcommon.handler;

import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 止损/止盈列表存 PG jsonb 列 */
class JsonbTypeHandlerTest {

    @Test
    void 库里的老数据仍能读回() throws Exception {
        // PG 读出来是 jsonb 规范化后的样子：冒号逗号后带空格，数字按写入时的字面量
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("stop_losses")).thenReturn(
                "[{\"id\": \"sl-1\", \"price\": 95000.50, \"quantity\": 0.010}]");

        List<FuturesStopLoss> list = new FuturesStopLossListTypeHandler().getNullableResult(rs, "stop_losses");

        assertThat(list).containsExactly(
                new FuturesStopLoss("sl-1", new BigDecimal("95000.50"), new BigDecimal("0.010")));
    }

    @Test
    void 空列读成null() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("take_profits")).thenReturn(null);
        assertThat(new FuturesTakeProfitListTypeHandler().getNullableResult(rs, "take_profits")).isNull();
    }

    @Test
    void 写出的串再读回逐字段不变() throws Exception {
        List<FuturesTakeProfit> tps = List.of(
                new FuturesTakeProfit("tp-1", new BigDecimal("101000.0"), new BigDecimal("0.5")),
                new FuturesTakeProfit("tp-2", new BigDecimal("102000"), null));
        FuturesTakeProfitListTypeHandler handler = new FuturesTakeProfitListTypeHandler();

        PreparedStatement ps = mock(PreparedStatement.class);
        handler.setNonNullParameter(ps, 1, tps, null);
        ArgumentCaptor<Object> json = ArgumentCaptor.forClass(Object.class);
        verify(ps).setObject(eq(1), json.capture(), eq(Types.OTHER));

        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn((String) json.getValue());
        assertThat(handler.getNullableResult(rs, 1)).isEqualTo(tps);
    }
}
