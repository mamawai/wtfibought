package com.mawai.wiibcommon.annotation;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;

import java.lang.annotation.*;

/**
 * 规范化交易对参数：读请求 query 参数 symbol（缺省 BTCUSDT），经 QuantConstants.normalizeSymbol
 * 校验+规范化后注入到 String 参数；非法 symbol 抛 BizException(PARAM_ERROR)，由全局处理器统一返回。
 * 免去各端点重复的 normalize try-catch 样板。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Parameter(in = ParameterIn.QUERY, name = "symbol", required = false,
        example = "BTCUSDT", description = "交易对（默认 BTCUSDT）") // 供 Swagger 文档展示
public @interface Symbol {
}
