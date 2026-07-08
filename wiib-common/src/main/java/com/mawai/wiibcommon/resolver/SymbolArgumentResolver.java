package com.mawai.wiibcommon.resolver;

import com.mawai.wiibcommon.annotation.Symbol;
import com.mawai.wiibcommon.constant.QuantConstants;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;

import org.springframework.core.MethodParameter;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * @Symbol 参数解析：取 query 参数 symbol（缺省 BTCUSDT）→ normalizeSymbol → 注入。
 * 非法抛 BizException(PARAM_ERROR)，全局处理器统一返回 symbol 格式错误。
 */
public class SymbolArgumentResolver implements HandlerMethodArgumentResolver {

    private static final String PARAM_NAME = "symbol";
    private static final String DEFAULT_SYMBOL = "BTCUSDT";

    @Override
    public boolean supportsParameter(@NonNull MethodParameter parameter) {
        return parameter.hasParameterAnnotation(Symbol.class)
                && parameter.getParameterType() == String.class;
    }

    @Override
    public Object resolveArgument(@NonNull MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  @NonNull NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        String raw = webRequest.getParameter(PARAM_NAME);
        if (raw == null || raw.isBlank()) {
            raw = DEFAULT_SYMBOL;
        }
        try {
            return QuantConstants.normalizeSymbol(raw);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "symbol格式错误");
        }
    }
}
