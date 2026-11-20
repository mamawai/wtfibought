package com.mawai.wiibcommon.exception;

import cn.dev33.satoken.exception.NotLoginException;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.util.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

/**
 * 全局异常处理器（三模块共享）
 * 放 wiib-common，feed/sim/agent 均 scanBasePackages 到 com.mawai.wiibcommon，自动生效。
 * <p>
 * <b>错误文案在这里成文</b>：业务代码一路只传 {@link ErrorCode}，语言按当次请求的 X-Lang 头
 * （见 {@code RequestLangFilter}）现查词表。所以加一个错误码只要加一条枚举 + 两门语言各一条词条，
 * 沿途的 service/controller 一个字都不用改。
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final MessageCatalog messages;

    @ExceptionHandler(BizException.class)
    public Result<?> handleBizException(BizException e) {
        String msg = e.render(messages);
        log.warn("业务异常: {}", msg);
        return Result.fail(e.getCode(), msg);
    }

    @ExceptionHandler(NotLoginException.class)
    public Result<?> handleNotLoginException(NotLoginException e) {
        String key = switch (e.getType()) {
            case NotLoginException.NOT_TOKEN -> "error.token.missing";
            case NotLoginException.INVALID_TOKEN -> "error.token.invalid";
            case NotLoginException.TOKEN_TIMEOUT -> "error.token.timeout";
            case NotLoginException.BE_REPLACED -> "error.token.replaced";
            case NotLoginException.KICK_OUT -> "error.token.kickedOut";
            case NotLoginException.TOKEN_FREEZE -> "error.token.frozen";
            case NotLoginException.NO_PREFIX_MESSAGE -> "error.token.noPrefix";
            default -> "error.token.notLoggedIn";
        };
        log.warn("登录异常: type={}, key={}", e.getType(), key);
        return Result.fail(ErrorCode.UNAUTHORIZED.getCode(), messages.get(key));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<?> handleValidation(MethodArgumentNotValidException e) {
        // 字段名 + 校验注解自带的说明，成不了词表条目；一条都拼不出来才回落到那句通用的
        String field = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElseGet(() -> messages.get("error.request.validationFailed"));
        log.warn("参数校验异常: {}", field);
        return Result.fail(ErrorCode.PARAM_ERROR.getCode(), field);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<?> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return Result.fail(ErrorCode.PARAM_ERROR.getCode(), messages.get("error.request.bodyMalformed"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<?> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("缺少请求参数: {}", e.getParameterName());
        return Result.fail(ErrorCode.PARAM_ERROR.getCode(),
                messages.get("error.request.missingParam", Map.of("name", e.getParameterName())));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<?> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型错误: {}={}", e.getName(), e.getValue());
        return Result.fail(ErrorCode.PARAM_ERROR.getCode(),
                messages.get("error.request.typeMismatch", Map.of("name", e.getName())));
    }

    /**
     * SSE 客户端断连：心跳往死 socket 一写就抛这个。属正常事件不是异常，
     * 返回 void 不写响应体——响应是 text/event-stream，往里塞 Result 只会再炸一次"没有转换器"。
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncNotUsable(AsyncRequestNotUsableException e) {
        log.debug("SSE 客户端已断开: {}", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<?> handleException(Exception e) {
        log.error("系统异常: ", e);
        return Result.fail(ErrorCode.SYSTEM_ERROR.getCode(), messages.get(ErrorCode.SYSTEM_ERROR.getMsgKey()));
    }
}
