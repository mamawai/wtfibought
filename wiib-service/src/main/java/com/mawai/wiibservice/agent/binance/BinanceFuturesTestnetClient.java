package com.mawai.wiibservice.agent.binance;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibservice.agent.binance.model.AccountInfo;
import com.mawai.wiibservice.agent.binance.model.OrderResponse;
import com.mawai.wiibservice.agent.binance.model.PlaceOrderRequest;
import com.mawai.wiibservice.agent.binance.model.PositionModeResponse;
import com.mawai.wiibservice.agent.binance.model.PositionRisk;
import com.mawai.wiibservice.agent.binance.model.SetLeverageResponse;
import com.mawai.wiibservice.agent.binance.model.SimpleAck;
import com.mawai.wiibservice.agent.binance.model.UserTrade;
import com.mawai.wiibservice.agent.binance.model.IncomeRecord;
import com.mawai.wiibcommon.config.BaseRestTemplateConfig;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Binance USDT-M 合约 Testnet 客户端：仅交易，行情走主网 {@link com.mawai.wiibcommon.market.BinanceRestClient}。
 * 未配置 api-key/secret-key 时不抛错（允许零配置启动），但任何 SIGNED 调用会即时报错。
 */
@Slf4j
@Component
public class BinanceFuturesTestnetClient extends BaseRestTemplateConfig {

    private static final String HEADER_API_KEY = "X-MBX-APIKEY";

    private final BinanceFuturesTestnetProperties props;
    private final BinanceSigner signer;
    private final RestTemplate restTemplate;

    public BinanceFuturesTestnetClient(BinanceFuturesTestnetProperties props) {
        this.props = props;
        this.restTemplate = createRestTemplate(props.getConnectTimeout(), props.getReadTimeout());
        boolean hasSecret = props.getSecretKey() != null && !props.getSecretKey().isBlank();
        this.signer = hasSecret ? new BinanceSigner(props.getSecretKey()) : null;
    }

    @PostConstruct
    public void logStartup() {
        boolean configured = signer != null
                && props.getApiKey() != null && !props.getApiKey().isBlank();
        if (configured) {
            log.info("[BinanceTestnet] 已配置 baseUrl={} symbols={} recvWindow={}",
                    props.getRestBaseUrl(), props.getSymbols(), props.getRecvWindow());
        } else {
            log.info("[BinanceTestnet] 未配置 api-key/secret-key，SIGNED 调用前需先在 application.yml 填写");
        }
    }

    /** symbol 白名单校验：不在白名单立即拒绝，避免误调到 BTC/ETH 之外的标的 */
    void ensureSymbolAllowed(String symbol) {
        if (symbol == null || !props.getSymbols().contains(symbol)) {
            throw new IllegalArgumentException(
                    "symbol不在白名单: " + symbol + "，允许的symbols=" + props.getSymbols());
        }
    }

    // ==================== Task 2: 账户类 ====================

    /** GET /fapi/v3/account 账户信息（含余额/未实现盈亏/所有 asset/有仓位的 symbol） */
    public AccountInfo getAccount() {
        String json = signedRequest(HttpMethod.GET, "/fapi/v3/account", null);
        return JSON.parseObject(json, AccountInfo.class);
    }

    /**
     * GET /fapi/v3/positionRisk 持仓详情（含 entryPrice/markPrice/liquidationPrice）。
     * 仅返回有持仓或挂单的 symbol。symbol 传 null 时查所有，非 null 时强制白名单校验。
     */
    public List<PositionRisk> getPositionRisk(String symbol) {
        LinkedHashMap<String, Object> params = new LinkedHashMap<>();
        if (symbol != null) {
            ensureSymbolAllowed(symbol);
            params.put("symbol", symbol);
        }
        String json = signedRequest(HttpMethod.GET, "/fapi/v3/positionRisk", params);
        return JSON.parseArray(json, PositionRisk.class);
    }

    // ==================== Task 3: 下单类 ====================

    /**
     * POST /fapi/v1/order 下单。
     * 不同 type 的字段约束（如 LIMIT 必须带 timeInForce+price）由 Binance 服务端校验，本方法不重复。
     */
    public OrderResponse placeOrder(PlaceOrderRequest req) {
        ensureSymbolAllowed(req.getSymbol());
        LinkedHashMap<String, Object> params = new LinkedHashMap<>();
        params.put("symbol", req.getSymbol());
        params.put("side", req.getSide());
        params.put("type", req.getType());
        putIfNotNull(params, "quantity", req.getQuantity());
        putIfNotNull(params, "timeInForce", req.getTimeInForce());
        putIfNotNull(params, "price", req.getPrice());
        putIfNotNull(params, "positionSide", req.getPositionSide());
        putIfNotNull(params, "reduceOnly", req.getReduceOnly());
        putIfNotNull(params, "newClientOrderId", req.getNewClientOrderId());
        putIfNotNull(params, "newOrderRespType", req.getNewOrderRespType());
        putIfNotNull(params, "stopPrice", req.getStopPrice());
        putIfNotNull(params, "closePosition", req.getClosePosition());
        putIfNotNull(params, "workingType", req.getWorkingType());
        putIfNotNull(params, "priceProtect", req.getPriceProtect());
        putIfNotNull(params, "goodTillDate", req.getGoodTillDate());

        String json = signedRequest(HttpMethod.POST, "/fapi/v1/order", params);
        return JSON.parseObject(json, OrderResponse.class);
    }

    /** DELETE /fapi/v1/order 撤单：orderId 与 origClientOrderId 至少一个非空 */
    public OrderResponse cancelOrder(String symbol, Long orderId, String origClientOrderId) {
        ensureSymbolAllowed(symbol);
        if (orderId == null && (origClientOrderId == null || origClientOrderId.isBlank())) {
            throw new IllegalArgumentException("orderId 与 origClientOrderId 至少传一个");
        }
        LinkedHashMap<String, Object> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        putIfNotNull(params, "orderId", orderId);
        putIfNotNull(params, "origClientOrderId", origClientOrderId);
        String json = signedRequest(HttpMethod.DELETE, "/fapi/v1/order", params);
        return JSON.parseObject(json, OrderResponse.class);
    }

    /** GET /fapi/v1/order 查单：orderId 与 origClientOrderId 至少一个非空 */
    public OrderResponse queryOrder(String symbol, Long orderId, String origClientOrderId) {
        ensureSymbolAllowed(symbol);
        if (orderId == null && (origClientOrderId == null || origClientOrderId.isBlank())) {
            throw new IllegalArgumentException("orderId 与 origClientOrderId 至少传一个");
        }
        LinkedHashMap<String, Object> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        putIfNotNull(params, "orderId", orderId);
        putIfNotNull(params, "origClientOrderId", origClientOrderId);
        String json = signedRequest(HttpMethod.GET, "/fapi/v1/order", params);
        return JSON.parseObject(json, OrderResponse.class);
    }

    /**
     * GET /fapi/v1/openOrders 当前挂单。
     * 必须传 symbol（受白名单约束）；不传时权重 40 太重，且会跨白名单，故强制要求。
     */
    public List<OrderResponse> getOpenOrders(String symbol) {
        ensureSymbolAllowed(symbol);
        LinkedHashMap<String, Object> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        String json = signedRequest(HttpMethod.GET, "/fapi/v1/openOrders", params);
        return JSON.parseArray(json, OrderResponse.class);
    }

    /** DELETE /fapi/v1/allOpenOrders 撤销该 symbol 下全部挂单 */
    public SimpleAck cancelAllOpenOrders(String symbol) {
        ensureSymbolAllowed(symbol);
        LinkedHashMap<String, Object> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        String json = signedRequest(HttpMethod.DELETE, "/fapi/v1/allOpenOrders", params);
        return JSON.parseObject(json, SimpleAck.class);
    }

    // ==================== Task 4: 设置类 ====================

    /** POST /fapi/v1/leverage 调整开仓杠杆（1~125） */
    public SetLeverageResponse setLeverage(String symbol, int leverage) {
        ensureSymbolAllowed(symbol);
        if (leverage < 1 || leverage > 125) {
            throw new IllegalArgumentException("leverage 必须在 1~125 之间，传入=" + leverage);
        }
        LinkedHashMap<String, Object> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("leverage", leverage);
        String json = signedRequest(HttpMethod.POST, "/fapi/v1/leverage", params);
        return JSON.parseObject(json, SetLeverageResponse.class);
    }

    /**
     * POST /fapi/v1/marginType 切换 symbol 的保证金模式。
     * 若已是目标模式，Binance 返回 -4046 错误，调用方按需自行 catch 忽略。
     */
    public SimpleAck setMarginType(String symbol, String marginType) {
        ensureSymbolAllowed(symbol);
        if (!"ISOLATED".equals(marginType) && !"CROSSED".equals(marginType)) {
            throw new IllegalArgumentException("marginType 仅支持 ISOLATED / CROSSED，传入=" + marginType);
        }
        LinkedHashMap<String, Object> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("marginType", marginType);
        String json = signedRequest(HttpMethod.POST, "/fapi/v1/marginType", params);
        return JSON.parseObject(json, SimpleAck.class);
    }

    /**
     * POST /fapi/v1/positionSide/dual 切换持仓模式（账户级，影响所有 symbol）。
     * @param dualSidePosition true=双向(同时持多空) / false=单向
     * 持仓或挂单存在时切换会失败 -4059，调用方按需自行 catch。
     */
    public SimpleAck setPositionMode(boolean dualSidePosition) {
        LinkedHashMap<String, Object> params = new LinkedHashMap<>();
        // Binance 文档明确要求该参数为 STRING 类型 "true"/"false"
        params.put("dualSidePosition", dualSidePosition ? "true" : "false");
        String json = signedRequest(HttpMethod.POST, "/fapi/v1/positionSide/dual", params);
        return JSON.parseObject(json, SimpleAck.class);
    }

    /** GET /fapi/v1/positionSide/dual 查询当前持仓模式 */
    public PositionModeResponse getPositionMode() {
        String json = signedRequest(HttpMethod.GET, "/fapi/v1/positionSide/dual", null);
        return JSON.parseObject(json, PositionModeResponse.class);
    }

    // ==================== HTTP 通用 ====================

    /**
     * 发起 SIGNED 请求：自动追加 recvWindow + timestamp + signature，自动带 X-MBX-APIKEY。
     * 所有参数走 query string（包括 POST/PUT/DELETE），request body 为空，签名内容即为 query string。
     */
    String signedRequest(HttpMethod method, String path, LinkedHashMap<String, Object> params) {
        if (signer == null) {
            throw new IllegalStateException("Binance Testnet 未配置 secret-key，无法发起 SIGNED 请求");
        }
        LinkedHashMap<String, Object> all = (params == null) ? new LinkedHashMap<>() : new LinkedHashMap<>(params);
        all.put("recvWindow", props.getRecvWindow());
        all.put("timestamp", System.currentTimeMillis());

        String query = buildQueryString(all);
        String signature = signer.sign(query);
        URI uri = URI.create(props.getRestBaseUrl() + path + "?" + query + "&signature=" + signature);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HEADER_API_KEY, props.getApiKey());

        try {
            ResponseEntity<String> resp = restTemplate.exchange(uri, method, new HttpEntity<>(headers), String.class);
            return resp.getBody();
        } catch (HttpStatusCodeException e) {
            throw parseError(e);
        }
    }

    private BinanceApiException parseError(HttpStatusCodeException e) {
        String body = e.getResponseBodyAsString();
        int code = -1;
        String msg = body;
        try {
            JSONObject json = JSON.parseObject(body);
            if (json != null) {
                code = json.getIntValue("code", -1);
                if (json.containsKey("msg")) msg = json.getString("msg");
            }
        } catch (Exception ignore) { /* 非标准错误体，保留原 body */ }
        return new BinanceApiException(e.getStatusCode().value(), code, msg);
    }

    /** 按 LinkedHashMap 插入顺序拼 key=value&...，value 做 application/x-www-form-urlencoded 编码 */
    private static String buildQueryString(Map<String, Object> params) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Object> e : params.entrySet()) {
            if (e.getValue() == null) continue;
            if (!first) sb.append('&');
            sb.append(e.getKey()).append('=')
                    .append(URLEncoder.encode(stringify(e.getValue()), StandardCharsets.UTF_8));
            first = false;
        }
        return sb.toString();
    }

    /** BigDecimal 用 toPlainString 避免科学计数法（"1.0E-5" → "0.00001"），其余对象用 String.valueOf */
    private static String stringify(Object value) {
        if (value instanceof java.math.BigDecimal bd) return bd.toPlainString();
        return String.valueOf(value);
    }

    private static void putIfNotNull(LinkedHashMap<String, Object> params, String key, Object value) {
        if (value == null) return;
        if (value instanceof String s && s.isBlank()) return;
        params.put(key, value);
    }

    // ==================== 历史查询（监测看板数据源）====================

    /** GET /fapi/v1/userTrades 账户成交明细（symbol 必填；窗口≤7天；fromId 增量游标；limit≤1000）。 */
    public List<UserTrade> getUserTrades(String symbol, Long startTime, Long endTime, Long fromId, Integer limit) {
        ensureSymbolAllowed(symbol);
        LinkedHashMap<String, Object> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        putIfNotNull(params, "startTime", startTime);
        putIfNotNull(params, "endTime", endTime);
        putIfNotNull(params, "fromId", fromId);
        putIfNotNull(params, "limit", limit);
        String json = signedRequest(HttpMethod.GET, "/fapi/v1/userTrades", params);
        return JSON.parseArray(json, UserTrade.class);
    }

    /** GET /fapi/v1/allOrders 所有订单含 FILLED/CANCELED/EXPIRED 终态（symbol 必填；limit≤1000）。 */
    public List<OrderResponse> getAllOrders(String symbol, Long startTime, Long endTime, Long orderId, Integer limit) {
        ensureSymbolAllowed(symbol);
        LinkedHashMap<String, Object> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        putIfNotNull(params, "orderId", orderId);
        putIfNotNull(params, "startTime", startTime);
        putIfNotNull(params, "endTime", endTime);
        putIfNotNull(params, "limit", limit);
        String json = signedRequest(HttpMethod.GET, "/fapi/v1/allOrders", params);
        return JSON.parseArray(json, OrderResponse.class);
    }

    /** GET /fapi/v1/income 损益流水（symbol 可选；窗口默认7天、可设≤200天；limit≤1000）。 */
    public List<IncomeRecord> getIncome(String symbol, String incomeType, Long startTime, Long endTime, Integer limit) {
        LinkedHashMap<String, Object> params = new LinkedHashMap<>();
        if (symbol != null) {
            ensureSymbolAllowed(symbol);
            params.put("symbol", symbol);
        }
        putIfNotNull(params, "incomeType", incomeType);
        putIfNotNull(params, "startTime", startTime);
        putIfNotNull(params, "endTime", endTime);
        putIfNotNull(params, "limit", limit);
        String json = signedRequest(HttpMethod.GET, "/fapi/v1/income", params);
        return JSON.parseArray(json, IncomeRecord.class);
    }
}
