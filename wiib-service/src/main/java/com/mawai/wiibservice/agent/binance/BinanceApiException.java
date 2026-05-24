package com.mawai.wiibservice.agent.binance;

/**
 * Binance 接口业务错误：HTTP 4xx/5xx 且响应体形如 {"code":-2010,"msg":"..."}。
 * 网络层异常（超时、DNS）不在此封装范围内，由调用方按 RestClientException 处理。
 */
public class BinanceApiException extends RuntimeException {

    private final int httpStatus;
    private final int code;
    private final String msg;

    public BinanceApiException(int httpStatus, int code, String msg) {
        super("Binance错误 http=" + httpStatus + " code=" + code + " msg=" + msg);
        this.httpStatus = httpStatus;
        this.code = code;
        this.msg = msg;
    }

    public int getHttpStatus() { return httpStatus; }
    public int getCode() { return code; }
    public String getMsg() { return msg; }
}
