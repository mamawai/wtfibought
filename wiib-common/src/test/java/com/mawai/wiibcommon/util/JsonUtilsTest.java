package com.mawai.wiibcommon.util;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;

/** 钉住 MAPPER 的几项配置：业务 JSON 的线上形状都靠它们 */
class JsonUtilsTest {

    record Row(String name, BigDecimal price, Integer count) {}

    record Counter(int n, long total, boolean done) {}

    @Test
    void null字段在对象_Map_树里都缺席() {
        assertThat(MAPPER.writeValueAsString(new Row("x", null, null))).isEqualTo("{\"name\":\"x\"}");

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("a", 1);
        map.put("b", null);
        assertThat(MAPPER.writeValueAsString(map)).isEqualTo("{\"a\":1}");

        ObjectNode node = MAPPER.createObjectNode().put("a", 1).put("b", (String) null).putNull("c");
        assertThat(MAPPER.writeValueAsString(node)).isEqualTo("{\"a\":1}");
    }

    @Test
    void 树里的小数原样转出去不丢位() {
        String json = "{\"equity\":1234.5600,\"big\":12345678901234567890.123456789,\"tiny\":1E-7}";
        JsonNode node = MAPPER.readTree(json);
        assertThat(MAPPER.writeValueAsString(node)).isEqualTo(json);
        assertThat(node.get("equity").decimalValue()).isEqualTo(new BigDecimal("1234.5600"));
    }

    @Test
    void 对象里的BigDecimal写读都保留标度() {
        Row back = MAPPER.readValue(MAPPER.writeValueAsString(new Row("x", new BigDecimal("0.00010000"), 1)), Row.class);
        assertThat(back.price()).isEqualTo(new BigDecimal("0.00010000"));
    }

    @Test
    void 字符串数字asDecimal与直接new的BigDecimal一致() {
        JsonNode node = MAPPER.readTree("{\"p\":\"0.01634790\"}");
        assertThat(node.get("p").asDecimal()).isEqualTo(new BigDecimal("0.01634790"));
    }

    @Test
    void null读进基本类型给默认值() {
        Counter c = MAPPER.readValue("{\"n\":null,\"total\":null,\"done\":null}", Counter.class);
        assertThat(c).isEqualTo(new Counter(0, 0L, false));
    }

    @Test
    void 读时放行单引号_尾逗号_注释_未转义换行() {
        assertThat(MAPPER.readTree("{'a':1}").get("a").asInt()).isEqualTo(1);
        assertThat(MAPPER.readTree("{\"a\":1,}").get("a").asInt()).isEqualTo(1);
        assertThat(MAPPER.readTree("// 说明\n{\"a\":1}").get("a").asInt()).isEqualTo(1);
        assertThat(MAPPER.readTree("{\"a\":\"第一行\n第二行\"}").get("a").asString()).isEqualTo("第一行\n第二行");
    }
}
