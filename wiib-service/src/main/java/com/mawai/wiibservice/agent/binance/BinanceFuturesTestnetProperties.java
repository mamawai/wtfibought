package com.mawai.wiibservice.agent.binance;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Binance USDT-M 合约 Testnet 配置。
 * 与主网行情配置 {@link com.mawai.wiibcommon.config.BinanceProperties} 完全隔离：
 * 行情走主网真实数据，仅交易走 Testnet 模拟环境。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "binance-testnet")
public class BinanceFuturesTestnetProperties {

    /** Testnet REST 基址，官方文档当前推荐 demo-fapi 域名 */
    private String restBaseUrl = "https://demo-fapi.binance.com";

    /** 在 https://testnet.binancefuture.com/ 用 GitHub 登录后申请 */
    private String apiKey;

    private String secretKey;

    /** 接收窗口毫秒，Binance 服务端时间与请求 timestamp 差 ≤ recvWindow 才接受 */
    private long recvWindow = 5000;

    private int connectTimeout = 5000;

    private int readTimeout = 10000;

    /** 允许交易的 symbol 白名单，传入不在白名单的 symbol 直接抛异常拒绝 */
    private List<String> symbols = List.of("BTCUSDT", "ETHUSDT");
}
