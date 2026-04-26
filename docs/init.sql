-- What If I Bought 数据库初始化脚本 (PostgreSQL)
-- websocket 连接失败请使用台湾或日本节点

-- ============================================
-- 0. 创建数据库（手动执行）
-- ============================================
-- CREATE DATABASE wiib ENCODING 'UTF8';

-- ============================================
-- 1. 用户表
-- ============================================
CREATE TABLE IF NOT EXISTS "user" (
    id BIGSERIAL PRIMARY KEY,
    linux_do_id VARCHAR(64) NOT NULL UNIQUE,
    username VARCHAR(64) NOT NULL,
    avatar VARCHAR(256),
    balance DECIMAL(18,2) NOT NULL DEFAULT 100000.00,
    frozen_balance DECIMAL(18,2) NOT NULL DEFAULT 0,
    margin_loan_principal DECIMAL(18,2) NOT NULL DEFAULT 0,
    margin_interest_accrued DECIMAL(18,2) NOT NULL DEFAULT 0,
    margin_interest_last_date DATE,
    is_bankrupt BOOLEAN NOT NULL DEFAULT FALSE,
    bankrupt_count INT NOT NULL DEFAULT 0,
    bankrupt_at TIMESTAMP,
    bankrupt_reset_date DATE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE "user" IS '用户表';
COMMENT ON COLUMN "user".id IS '主键';
COMMENT ON COLUMN "user".linux_do_id IS 'LinuxDo用户ID，OAuth登录标识';
COMMENT ON COLUMN "user".username IS '用户名';
COMMENT ON COLUMN "user".avatar IS '头像URL';
COMMENT ON COLUMN "user".balance IS '可用余额';
COMMENT ON COLUMN "user".frozen_balance IS '冻结余额（限价买单冻结）';
COMMENT ON COLUMN "user".margin_loan_principal IS '杠杆借款本金';
COMMENT ON COLUMN "user".margin_interest_accrued IS '杠杆应计利息（未支付）';
COMMENT ON COLUMN "user".margin_interest_last_date IS '杠杆计息上次日期（用于补记）';
COMMENT ON COLUMN "user".is_bankrupt IS '是否破产（爆仓后禁用交易）';
COMMENT ON COLUMN "user".bankrupt_count IS '破产次数';
COMMENT ON COLUMN "user".bankrupt_at IS '爆仓时间';
COMMENT ON COLUMN "user".bankrupt_reset_date IS '恢复日期（交易日09:00恢复）';
COMMENT ON COLUMN "user".created_at IS '创建时间';
COMMENT ON COLUMN "user".updated_at IS '更新时间';

CREATE INDEX idx_user_bankrupt ON "user"(is_bankrupt, bankrupt_reset_date);

-- ============================================
-- 2. 公司表
-- ============================================
CREATE TABLE IF NOT EXISTS company (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    industry VARCHAR(32) NOT NULL,
    description TEXT,
    market_cap DECIMAL(18,2),
    pe_ratio DECIMAL(10,2),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE company IS '公司表';
COMMENT ON COLUMN company.id IS '主键';
COMMENT ON COLUMN company.name IS '公司名称';
COMMENT ON COLUMN company.industry IS '行业';
COMMENT ON COLUMN company.description IS '公司简介';
COMMENT ON COLUMN company.market_cap IS '市值';
COMMENT ON COLUMN company.pe_ratio IS '市盈率';
COMMENT ON COLUMN company.created_at IS '创建时间';
COMMENT ON COLUMN company.updated_at IS '更新时间';

-- ============================================
-- 3. 股票表（静态数据，实时数据从Redis获取）
-- ============================================
CREATE TABLE IF NOT EXISTS stock (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL,
    code VARCHAR(10) NOT NULL UNIQUE,
    name VARCHAR(64) NOT NULL,
    prev_close DECIMAL(10,2),
    open DECIMAL(10,2),
    trend_list VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE stock IS '股票表（静态数据）';
COMMENT ON COLUMN stock.id IS '主键';
COMMENT ON COLUMN stock.company_id IS '公司ID';
COMMENT ON COLUMN stock.code IS '股票代码';
COMMENT ON COLUMN stock.name IS '股票名称';
COMMENT ON COLUMN stock.prev_close IS '昨收价';
COMMENT ON COLUMN stock.open IS '开盘价（AI预生成）';
COMMENT ON COLUMN stock.trend_list IS '近十日涨跌趋势';
COMMENT ON COLUMN stock.created_at IS '创建时间';
COMMENT ON COLUMN stock.updated_at IS '更新时间';

CREATE INDEX idx_stock_company_id ON stock(company_id);

-- ============================================
-- 4. 持仓表
-- ============================================
CREATE TABLE IF NOT EXISTS position (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    stock_id BIGINT NOT NULL,
    quantity INT NOT NULL DEFAULT 0,
    frozen_quantity INT NOT NULL DEFAULT 0,
    avg_cost DECIMAL(10,2) NOT NULL,
    total_discount DECIMAL(18,2) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_user_stock UNIQUE (user_id, stock_id)
);

COMMENT ON TABLE position IS '持仓表';
COMMENT ON COLUMN position.id IS '主键';
COMMENT ON COLUMN position.user_id IS '用户ID';
COMMENT ON COLUMN position.stock_id IS '股票ID';
COMMENT ON COLUMN position.quantity IS '可用数量';
COMMENT ON COLUMN position.frozen_quantity IS '冻结数量（限价卖单冻结）';
COMMENT ON COLUMN position.avg_cost IS '持仓成本（加权平均）';
COMMENT ON COLUMN position.created_at IS '创建时间';
COMMENT ON COLUMN position.updated_at IS '更新时间';

CREATE INDEX idx_position_user_id ON position(user_id);
CREATE INDEX idx_position_stock_id ON position(stock_id);

-- ============================================
-- 5. 订单表
-- ============================================
CREATE TABLE IF NOT EXISTS orders (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    stock_id BIGINT NOT NULL,
    order_side VARCHAR(10) NOT NULL,
    order_type VARCHAR(10) NOT NULL,
    quantity INT NOT NULL,
    limit_price DECIMAL(10,2),
    frozen_amount DECIMAL(18,2),
    filled_price DECIMAL(10,2),
    filled_amount DECIMAL(18,2),
    commission DECIMAL(18,2) DEFAULT 0,
    trigger_price DECIMAL(10,2),
    triggered_at TIMESTAMP,
    status VARCHAR(20) NOT NULL,
    discount_percent DECIMAL(5,2),
    client_timestamp BIGINT,
    expire_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE orders IS '订单表';
COMMENT ON COLUMN orders.id IS '主键';
COMMENT ON COLUMN orders.user_id IS '用户ID';
COMMENT ON COLUMN orders.stock_id IS '股票ID';
COMMENT ON COLUMN orders.order_side IS '订单方向：BUY/SELL';
COMMENT ON COLUMN orders.order_type IS '订单类型：MARKET/LIMIT';
COMMENT ON COLUMN orders.quantity IS '委托数量';
COMMENT ON COLUMN orders.limit_price IS '限价（限价单必填）';
COMMENT ON COLUMN orders.frozen_amount IS '冻结金额（限价买单冻结）';
COMMENT ON COLUMN orders.filled_price IS '成交价格';
COMMENT ON COLUMN orders.filled_amount IS '成交金额';
COMMENT ON COLUMN orders.commission IS '手续费';
COMMENT ON COLUMN orders.trigger_price IS '触发价格（限价单触发时的实际价格）';
COMMENT ON COLUMN orders.triggered_at IS '触发时间（限价单触发时间）';
COMMENT ON COLUMN orders.status IS '状态：PENDING/TRIGGERED/FILLED/CANCELLED/EXPIRED';
COMMENT ON COLUMN orders.client_timestamp IS '客户端时间戳（防作弊）';
COMMENT ON COLUMN orders.expire_at IS '过期时间（限价单有效期）';
COMMENT ON COLUMN orders.created_at IS '创建时间';
COMMENT ON COLUMN orders.updated_at IS '更新时间';

CREATE INDEX idx_orders_user_id ON orders(user_id);
CREATE INDEX idx_orders_status_type ON orders(status, order_type);
CREATE INDEX idx_orders_stock_status ON orders(stock_id, status);
CREATE INDEX idx_orders_expire_at ON orders(expire_at);

-- ============================================
-- 6. 分时行情表（按日聚合，索引即时间）
-- ============================================
CREATE TABLE IF NOT EXISTS price_tick_daily (
    id BIGSERIAL PRIMARY KEY,
    stock_id BIGINT NOT NULL,
    trade_date DATE NOT NULL,
    prices NUMERIC(10,2)[] NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_ptd_stock_date UNIQUE(stock_id, trade_date)
);

COMMENT ON TABLE price_tick_daily IS '分时行情表（按日聚合）';
COMMENT ON COLUMN price_tick_daily.stock_id IS '股票ID';
COMMENT ON COLUMN price_tick_daily.trade_date IS '交易日期';
COMMENT ON COLUMN price_tick_daily.prices IS '价格数组，索引对应时间，共1440个点';

-- ============================================
-- 7. 新闻表
-- ============================================
CREATE TABLE IF NOT EXISTS news (
    id BIGSERIAL PRIMARY KEY,
    stock_code VARCHAR(10),
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    news_type VARCHAR(32) NOT NULL,
    publish_time TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE news IS '新闻表';
COMMENT ON COLUMN news.id IS '主键';
COMMENT ON COLUMN news.stock_code IS '股票代码（null表示市场新闻）';
COMMENT ON COLUMN news.title IS '新闻标题';
COMMENT ON COLUMN news.content IS '新闻内容';
COMMENT ON COLUMN news.news_type IS '新闻类型';
COMMENT ON COLUMN news.publish_time IS '发布时间';
COMMENT ON COLUMN news.created_at IS '创建时间';

CREATE INDEX idx_news_stock_code ON news(stock_code);
CREATE INDEX idx_news_publish_time ON news(publish_time);

-- ============================================
-- 8. 资金结算表（T+1到账）
-- ============================================
CREATE TABLE IF NOT EXISTS settlement (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    settle_time TIMESTAMP NOT NULL,
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE settlement IS '资金结算表（T+1到账）';
COMMENT ON COLUMN settlement.id IS '主键';
COMMENT ON COLUMN settlement.user_id IS '用户ID';
COMMENT ON COLUMN settlement.order_id IS '关联订单ID';
COMMENT ON COLUMN settlement.amount IS '结算金额（卖出净额）';
COMMENT ON COLUMN settlement.settle_time IS '到账时间（卖出时刻+24小时）';
COMMENT ON COLUMN settlement.status IS '状态：PENDING/SETTLED';
COMMENT ON COLUMN settlement.created_at IS '创建时间';

CREATE INDEX idx_settlement_user_id ON settlement(user_id);
CREATE INDEX idx_settlement_status_time ON settlement(status, settle_time);

-- ============================================
-- 9. 期权合约表（每日生成的期权链）
-- ============================================
CREATE TABLE IF NOT EXISTS option_contract (
    id BIGSERIAL PRIMARY KEY,
    stock_id BIGINT NOT NULL,
    option_type VARCHAR(4) NOT NULL,
    strike DECIMAL(10,2) NOT NULL,
    expire_at TIMESTAMP NOT NULL,
    ref_price DECIMAL(10,2) NOT NULL,
    sigma DECIMAL(10,6) NOT NULL,
    status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE option_contract IS '期权合约表';
COMMENT ON COLUMN option_contract.id IS '主键';
COMMENT ON COLUMN option_contract.stock_id IS '标的股票ID';
COMMENT ON COLUMN option_contract.option_type IS '期权类型：CALL/PUT';
COMMENT ON COLUMN option_contract.strike IS '行权价';
COMMENT ON COLUMN option_contract.expire_at IS '到期时间（当天15:00）';
COMMENT ON COLUMN option_contract.ref_price IS '生成时的参考价（昨收）';
COMMENT ON COLUMN option_contract.sigma IS '年化波动率';
COMMENT ON COLUMN option_contract.status IS '状态：ACTIVE/SETTLED';
COMMENT ON COLUMN option_contract.created_at IS '创建时间';

CREATE INDEX idx_option_contract_expire ON option_contract(expire_at, status);
CREATE UNIQUE INDEX uk_option_contract ON option_contract(stock_id, option_type, strike, expire_at);

-- ============================================
-- 10. 期权持仓表
-- ============================================
CREATE TABLE IF NOT EXISTS option_position (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    contract_id BIGINT NOT NULL,
    quantity INT NOT NULL DEFAULT 0,
    frozen_quantity INT NOT NULL DEFAULT 0,
    avg_cost DECIMAL(10,4) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_user_contract UNIQUE (user_id, contract_id)
);

COMMENT ON TABLE option_position IS '期权持仓表';
COMMENT ON COLUMN option_position.id IS '主键';
COMMENT ON COLUMN option_position.user_id IS '用户ID';
COMMENT ON COLUMN option_position.contract_id IS '期权合约ID';
COMMENT ON COLUMN option_position.quantity IS '可用数量';
COMMENT ON COLUMN option_position.frozen_quantity IS '冻结数量（限价卖单冻结）';
COMMENT ON COLUMN option_position.avg_cost IS '持仓成本（加权平均权利金）';
COMMENT ON COLUMN option_position.created_at IS '创建时间';
COMMENT ON COLUMN option_position.updated_at IS '更新时间';

CREATE INDEX idx_option_position_user ON option_position(user_id);
CREATE INDEX idx_option_position_contract ON option_position(contract_id);

-- ============================================
-- 11. 期权订单表
-- ============================================
CREATE TABLE IF NOT EXISTS option_order (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    contract_id BIGINT NOT NULL,
    order_side VARCHAR(4) NOT NULL,
    order_type VARCHAR(10) NOT NULL,
    quantity INT NOT NULL,
    limit_price DECIMAL(10,4),
    frozen_amount DECIMAL(18,2),
    filled_price DECIMAL(10,4),
    filled_amount DECIMAL(18,2),
    commission DECIMAL(18,2) DEFAULT 0,
    underlying_price DECIMAL(10,2),
    status VARCHAR(20) NOT NULL,
    expire_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE option_order IS '期权订单表';
COMMENT ON COLUMN option_order.id IS '主键';
COMMENT ON COLUMN option_order.user_id IS '用户ID';
COMMENT ON COLUMN option_order.contract_id IS '期权合约ID';
COMMENT ON COLUMN option_order.order_side IS '订单方向：BTO(买入开仓)/STC(卖出平仓)';
COMMENT ON COLUMN option_order.order_type IS '订单类型：MARKET/LIMIT';
COMMENT ON COLUMN option_order.quantity IS '委托数量';
COMMENT ON COLUMN option_order.limit_price IS '限价（限价单必填）';
COMMENT ON COLUMN option_order.frozen_amount IS '冻结金额（买入冻结）';
COMMENT ON COLUMN option_order.filled_price IS '成交权利金';
COMMENT ON COLUMN option_order.filled_amount IS '成交金额';
COMMENT ON COLUMN option_order.commission IS '手续费';
COMMENT ON COLUMN option_order.underlying_price IS '成交时标的价格（用于复盘）';
COMMENT ON COLUMN option_order.status IS '状态：PENDING/FILLED/CANCELLED/EXPIRED';
COMMENT ON COLUMN option_order.expire_at IS '订单过期时间';
COMMENT ON COLUMN option_order.created_at IS '创建时间';
COMMENT ON COLUMN option_order.updated_at IS '更新时间';

CREATE INDEX idx_option_order_user ON option_order(user_id);
CREATE INDEX idx_option_order_contract ON option_order(contract_id);
CREATE INDEX idx_option_order_status ON option_order(status);

-- ============================================
-- 12. 期权结算记录表
-- ============================================
CREATE TABLE IF NOT EXISTS option_settlement (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    contract_id BIGINT NOT NULL,
    position_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    strike DECIMAL(10,2) NOT NULL,
    settlement_price DECIMAL(10,2) NOT NULL,
    intrinsic_value DECIMAL(10,4) NOT NULL,
    settlement_amount DECIMAL(18,2) NOT NULL,
    settled_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE option_settlement IS '期权结算记录表';
COMMENT ON COLUMN option_settlement.id IS '主键';
COMMENT ON COLUMN option_settlement.user_id IS '用户ID';
COMMENT ON COLUMN option_settlement.contract_id IS '期权合约ID';
COMMENT ON COLUMN option_settlement.position_id IS '持仓ID';
COMMENT ON COLUMN option_settlement.quantity IS '结算数量';
COMMENT ON COLUMN option_settlement.strike IS '行权价';
COMMENT ON COLUMN option_settlement.settlement_price IS '结算价（标的到期价格）';
COMMENT ON COLUMN option_settlement.intrinsic_value IS '内在价值';
COMMENT ON COLUMN option_settlement.settlement_amount IS '结算金额';
COMMENT ON COLUMN option_settlement.settled_at IS '结算时间';

CREATE INDEX idx_option_settlement_user ON option_settlement(user_id);
CREATE INDEX idx_option_settlement_contract ON option_settlement(contract_id);

-- ============================================
-- 13. 每日Buff表
-- ============================================
CREATE TABLE IF NOT EXISTS user_buff (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES "user"(id),
    buff_type VARCHAR(32) NOT NULL,
    buff_name VARCHAR(64) NOT NULL,
    rarity VARCHAR(16) NOT NULL,
    extra_data text,
    draw_date DATE NOT NULL,
    expire_at TIMESTAMP NOT NULL,
    is_used BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_user_draw_date UNIQUE (user_id, draw_date)
);

COMMENT ON TABLE user_buff IS '每日Buff表';
COMMENT ON COLUMN user_buff.id IS '主键';
COMMENT ON COLUMN user_buff.user_id IS '用户ID';
COMMENT ON COLUMN user_buff.buff_type IS 'Buff类型枚举';
COMMENT ON COLUMN user_buff.buff_name IS '显示名称';
COMMENT ON COLUMN user_buff.rarity IS '稀有度：COMMON/RARE/EPIC/LEGENDARY';
COMMENT ON COLUMN user_buff.extra_data IS '附加数据JSON';
COMMENT ON COLUMN user_buff.draw_date IS '抽奖日期';
COMMENT ON COLUMN user_buff.expire_at IS '过期时间';
COMMENT ON COLUMN user_buff.is_used IS '是否已使用（折扣类）';
COMMENT ON COLUMN user_buff.created_at IS '创建时间';

-- ============================================
-- 14. Blackjack积分账户表
-- ============================================
CREATE TABLE IF NOT EXISTS blackjack_account (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE REFERENCES "user"(id),
    chips BIGINT NOT NULL DEFAULT 20000,
    today_converted BIGINT NOT NULL DEFAULT 0,
    last_convert_date DATE,
    last_reset_date DATE,
    total_hands BIGINT NOT NULL DEFAULT 0,
    total_won BIGINT NOT NULL DEFAULT 0,
    total_lost BIGINT NOT NULL DEFAULT 0,
    biggest_win BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE blackjack_account IS 'Blackjack积分账户';
COMMENT ON COLUMN blackjack_account.id IS '主键';
COMMENT ON COLUMN blackjack_account.user_id IS '用户ID';
COMMENT ON COLUMN blackjack_account.chips IS '当前积分';
COMMENT ON COLUMN blackjack_account.today_converted IS '今日已转出';
COMMENT ON COLUMN blackjack_account.last_convert_date IS '上次转出日期';
COMMENT ON COLUMN blackjack_account.last_reset_date IS '上次积分重置日期';
COMMENT ON COLUMN blackjack_account.total_hands IS '总局数';
COMMENT ON COLUMN blackjack_account.total_won IS '总赢额';
COMMENT ON COLUMN blackjack_account.total_lost IS '总输额';
COMMENT ON COLUMN blackjack_account.biggest_win IS '单局最大赢额';
COMMENT ON COLUMN blackjack_account.created_at IS '创建时间';
COMMENT ON COLUMN blackjack_account.updated_at IS '更新时间';

-- ============================================
-- 14b. Blackjack 转出日志表
-- ============================================
CREATE TABLE IF NOT EXISTS blackjack_convert_log (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    amount BIGINT NOT NULL,
    chips_before BIGINT NOT NULL,
    chips_after BIGINT NOT NULL,
    balance_before DECIMAL(18,2) NOT NULL,
    balance_after DECIMAL(18,2) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_bj_convert_user ON blackjack_convert_log(user_id);

COMMENT ON TABLE blackjack_convert_log IS 'Blackjack积分转出日志';

-- ============================================
-- 15. 加密货币持仓表
-- ============================================
CREATE TABLE IF NOT EXISTS crypto_position (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    quantity DECIMAL(18,8) NOT NULL DEFAULT 0,
    frozen_quantity DECIMAL(18,8) NOT NULL DEFAULT 0,
    avg_cost DECIMAL(18,2) NOT NULL,
    total_discount DECIMAL(18,2) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_crypto_user_symbol UNIQUE (user_id, symbol)
);

COMMENT ON TABLE crypto_position IS '加密货币持仓表';
COMMENT ON COLUMN crypto_position.user_id IS '用户ID';
COMMENT ON COLUMN crypto_position.symbol IS '交易对（如BTCUSDT）';
COMMENT ON COLUMN crypto_position.quantity IS '可用数量';
COMMENT ON COLUMN crypto_position.frozen_quantity IS '冻结数量（限价卖单冻结）';
COMMENT ON COLUMN crypto_position.avg_cost IS '持仓成本（加权平均）';

-- ============================================
-- 16. 加密货币订单表
-- ============================================
CREATE TABLE IF NOT EXISTS crypto_order (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    order_side VARCHAR(10) NOT NULL,
    order_type VARCHAR(10) NOT NULL,
    quantity DECIMAL(18,8) NOT NULL,
    leverage INT NOT NULL DEFAULT 1,
    limit_price DECIMAL(18,2),
    frozen_amount DECIMAL(18,2),
    filled_price DECIMAL(18,2),
    filled_amount DECIMAL(18,2),
    commission DECIMAL(18,2) DEFAULT 0,
    trigger_price DECIMAL(18,2),
    triggered_at TIMESTAMP,
    status VARCHAR(20) NOT NULL,
    discount_percent DECIMAL(5,2),
    expire_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE crypto_order IS '加密货币订单表';
COMMENT ON COLUMN crypto_order.user_id IS '用户ID';
COMMENT ON COLUMN crypto_order.symbol IS '交易对（如BTCUSDT）';
COMMENT ON COLUMN crypto_order.order_side IS 'BUY/SELL';
COMMENT ON COLUMN crypto_order.order_type IS 'MARKET/LIMIT';
COMMENT ON COLUMN crypto_order.quantity IS '委托数量（支持小数）';
COMMENT ON COLUMN crypto_order.leverage IS '杠杆倍数（1-10）';
COMMENT ON COLUMN crypto_order.limit_price IS '限价';
COMMENT ON COLUMN crypto_order.frozen_amount IS '冻结金额（限价买单）';
COMMENT ON COLUMN crypto_order.filled_price IS '成交价格';
COMMENT ON COLUMN crypto_order.filled_amount IS '成交金额';
COMMENT ON COLUMN crypto_order.commission IS '手续费';
COMMENT ON COLUMN crypto_order.trigger_price IS '触发价格';
COMMENT ON COLUMN crypto_order.triggered_at IS '触发时间';
COMMENT ON COLUMN crypto_order.status IS 'PENDING/TRIGGERED/FILLED/CANCELLED/EXPIRED';
COMMENT ON COLUMN crypto_order.expire_at IS '过期时间';

CREATE INDEX idx_crypto_order_user ON crypto_order(user_id);
CREATE INDEX idx_crypto_order_status ON crypto_order(status, order_type);
CREATE INDEX idx_crypto_order_symbol ON crypto_order(symbol, status);
CREATE INDEX idx_crypto_order_expire ON crypto_order(expire_at);

-- ============================================
-- 17. 矿工游戏记录表
-- ============================================
CREATE TABLE IF NOT EXISTS mines_game (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    bet_amount DECIMAL(18,2) NOT NULL,
    fee DECIMAL(18,2) NOT NULL,
    mine_positions VARCHAR(32) NOT NULL,
    revealed_cells VARCHAR(128) NOT NULL DEFAULT '',
    multiplier DECIMAL(18,4) NOT NULL DEFAULT 1.0000,
    payout DECIMAL(18,2) NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'PLAYING',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE mines_game IS '矿工游戏记录';
COMMENT ON COLUMN mines_game.id IS '主键';
COMMENT ON COLUMN mines_game.user_id IS '用户ID';
COMMENT ON COLUMN mines_game.bet_amount IS '下注金额';
COMMENT ON COLUMN mines_game.fee IS '手续费(下注额×1%)';
COMMENT ON COLUMN mines_game.mine_positions IS '雷位置(逗号分隔,0-24)';
COMMENT ON COLUMN mines_game.revealed_cells IS '已翻开的安全格(逗号分隔)';
COMMENT ON COLUMN mines_game.multiplier IS '最终倍率';
COMMENT ON COLUMN mines_game.payout IS '实际支付金额';
COMMENT ON COLUMN mines_game.status IS 'PLAYING/CASHED_OUT/EXPLODED';
COMMENT ON COLUMN mines_game.created_at IS '创建时间';
COMMENT ON COLUMN mines_game.updated_at IS '更新时间';

CREATE INDEX idx_mines_game_user_status ON mines_game(user_id, status);

-- ============================================
-- 18. 永续合约仓位表
-- ============================================
CREATE TABLE IF NOT EXISTS futures_position (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    side VARCHAR(5) NOT NULL,
    leverage INT NOT NULL,
    quantity DECIMAL(18,8) NOT NULL,
    entry_price DECIMAL(18,2) NOT NULL,
    margin DECIMAL(18,2) NOT NULL,
    funding_fee_total DECIMAL(18,2) NOT NULL DEFAULT 0,
    stop_losses JSONB,
    take_profits JSONB,
    status VARCHAR(12) NOT NULL DEFAULT 'OPEN',
    closed_price DECIMAL(18,2),
    closed_pnl DECIMAL(18,2),
    memo VARCHAR(32),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE futures_position IS '永续合约仓位表';
COMMENT ON COLUMN futures_position.user_id IS '用户ID';
COMMENT ON COLUMN futures_position.symbol IS '交易对（如BTCUSDT）';
COMMENT ON COLUMN futures_position.side IS '方向：LONG/SHORT';
COMMENT ON COLUMN futures_position.leverage IS '杠杆倍数';
COMMENT ON COLUMN futures_position.quantity IS '数量';
COMMENT ON COLUMN futures_position.entry_price IS '开仓价';
COMMENT ON COLUMN futures_position.margin IS '逐仓保证金（动态变化）';
COMMENT ON COLUMN futures_position.funding_fee_total IS '累计资金费率';
COMMENT ON COLUMN futures_position.stop_losses IS '止损列表(JSONB)';
COMMENT ON COLUMN futures_position.take_profits IS '止盈列表(JSONB)';
COMMENT ON COLUMN futures_position.status IS '状态：OPEN/CLOSED/LIQUIDATED';
COMMENT ON COLUMN futures_position.closed_price IS '平仓价';
COMMENT ON COLUMN futures_position.closed_pnl IS '平仓盈亏';
COMMENT ON COLUMN futures_position.memo IS 'AI策略标签：TREND/MEAN_REVERSION/BREAKOUT';

CREATE INDEX idx_fp_user_status ON futures_position(user_id, status);
CREATE INDEX idx_fp_symbol_status ON futures_position(symbol, status);

-- ============================================
-- 19. 永续合约订单表
-- ============================================
CREATE TABLE IF NOT EXISTS futures_order (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    position_id BIGINT,
    symbol VARCHAR(20) NOT NULL,
    order_side VARCHAR(15) NOT NULL,
    order_type VARCHAR(10) NOT NULL DEFAULT 'MARKET',
    quantity DECIMAL(18,8) NOT NULL,
    leverage INT NOT NULL,
    limit_price DECIMAL(18,2),
    frozen_amount DECIMAL(18,2),
    filled_price DECIMAL(18,2),
    filled_amount DECIMAL(18,2),
    margin_amount DECIMAL(18,2),
    commission DECIMAL(18,2) DEFAULT 0,
    realized_pnl DECIMAL(18,2),
    stop_losses JSONB,
    take_profits JSONB,
    status VARCHAR(20) NOT NULL,
    expire_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE futures_order IS '永续合约订单表';
COMMENT ON COLUMN futures_order.user_id IS '用户ID';
COMMENT ON COLUMN futures_order.position_id IS '关联仓位ID';
COMMENT ON COLUMN futures_order.symbol IS '交易对';
COMMENT ON COLUMN futures_order.order_side IS '订单方向：OPEN_LONG/OPEN_SHORT/CLOSE_LONG/CLOSE_SHORT/INCREASE_LONG/INCREASE_SHORT';
COMMENT ON COLUMN futures_order.order_type IS '订单类型：MARKET/LIMIT';
COMMENT ON COLUMN futures_order.quantity IS '委托数量';
COMMENT ON COLUMN futures_order.leverage IS '杠杆倍数';
COMMENT ON COLUMN futures_order.limit_price IS '限价';
COMMENT ON COLUMN futures_order.frozen_amount IS '冻结金额（限价开仓冻结保证金+手续费）';
COMMENT ON COLUMN futures_order.filled_price IS '成交价格';
COMMENT ON COLUMN futures_order.filled_amount IS '成交金额';
COMMENT ON COLUMN futures_order.margin_amount IS '保证金金额';
COMMENT ON COLUMN futures_order.commission IS '手续费';
COMMENT ON COLUMN futures_order.realized_pnl IS '已实现盈亏';
COMMENT ON COLUMN futures_order.stop_losses IS '止损列表(JSONB)';
COMMENT ON COLUMN futures_order.take_profits IS '止盈列表(JSONB)';
COMMENT ON COLUMN futures_order.status IS '状态：PENDING/TRIGGERED/FILLED/CANCELLED/EXPIRED/LIQUIDATED';
COMMENT ON COLUMN futures_order.expire_at IS '过期时间';

CREATE INDEX idx_fo_user ON futures_order(user_id);
CREATE INDEX idx_fo_position ON futures_order(position_id);
CREATE INDEX idx_fo_symbol_status ON futures_order(symbol, status);

-- ============================================
-- 20. 视频扑克游戏记录表
-- ============================================
CREATE TABLE IF NOT EXISTS video_poker_game (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    bet_amount DECIMAL(18,2) NOT NULL,
    initial_cards VARCHAR(64) NOT NULL,
    held_positions VARCHAR(16) NOT NULL DEFAULT '',
    final_cards VARCHAR(64) NOT NULL DEFAULT '',
    hand_rank VARCHAR(32) NOT NULL DEFAULT '',
    multiplier DECIMAL(18,4) NOT NULL DEFAULT 0,
    payout DECIMAL(18,2) NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'DEALING',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE video_poker_game IS '视频扑克游戏记录';
COMMENT ON COLUMN video_poker_game.user_id IS '用户ID';
COMMENT ON COLUMN video_poker_game.bet_amount IS '下注金额';
COMMENT ON COLUMN video_poker_game.initial_cards IS '初始5张牌(逗号分隔)';
COMMENT ON COLUMN video_poker_game.held_positions IS 'HOLD的位置(逗号分隔,0-4)';
COMMENT ON COLUMN video_poker_game.final_cards IS '最终5张牌(逗号分隔)';
COMMENT ON COLUMN video_poker_game.hand_rank IS '牌型名称';
COMMENT ON COLUMN video_poker_game.multiplier IS '赔率倍数';
COMMENT ON COLUMN video_poker_game.payout IS '赔付金额';
COMMENT ON COLUMN video_poker_game.status IS 'DEALING/SETTLED';

CREATE INDEX idx_vp_game_user_status ON video_poker_game(user_id, status);

-- ============================================
-- 21. BTC 5min 涨跌预测回合表
-- ============================================
CREATE TABLE IF NOT EXISTS prediction_round (
    id BIGSERIAL PRIMARY KEY,
    window_start BIGINT NOT NULL UNIQUE,
    start_price NUMERIC(20, 8),
    end_price NUMERIC(20, 8),
    outcome VARCHAR(10),
    status VARCHAR(20) DEFAULT 'OPEN',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE prediction_round IS 'BTC 5min涨跌预测回合';
COMMENT ON COLUMN prediction_round.window_start IS '窗口起始时间戳(秒)';
COMMENT ON COLUMN prediction_round.start_price IS '起始BTC价格(Chainlink)';
COMMENT ON COLUMN prediction_round.end_price IS '结束BTC价格(Chainlink)';
COMMENT ON COLUMN prediction_round.outcome IS '结果：UP/DOWN/DRAW';
COMMENT ON COLUMN prediction_round.status IS '状态：OPEN/LOCKED/SETTLED';

-- ============================================
-- 22. BTC 5min 涨跌预测下注表
-- ============================================
CREATE TABLE IF NOT EXISTS prediction_bet (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    round_id BIGINT NOT NULL,
    side VARCHAR(10) NOT NULL,
    contracts NUMERIC(20, 4) NOT NULL,
    cost NUMERIC(20, 4) NOT NULL,
    avg_price NUMERIC(10, 4) NOT NULL,
    payout NUMERIC(20, 4),
    window_start BIGINT NOT NULL,
    status VARCHAR(20) DEFAULT 'ACTIVE',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE prediction_bet IS 'BTC 5min涨跌预测下注';
COMMENT ON COLUMN prediction_bet.user_id IS '用户ID';
COMMENT ON COLUMN prediction_bet.round_id IS '回合ID';
COMMENT ON COLUMN prediction_bet.side IS '方向：UP/DOWN';
COMMENT ON COLUMN prediction_bet.contracts IS '合约数量';
COMMENT ON COLUMN prediction_bet.cost IS '购买成本';
COMMENT ON COLUMN prediction_bet.avg_price IS '平均买入价';
COMMENT ON COLUMN prediction_bet.payout IS '结算赔付';
COMMENT ON COLUMN prediction_bet.window_start IS '窗口起始时间戳(秒)';
COMMENT ON COLUMN prediction_bet.status IS '状态：ACTIVE/WON/LOST/DRAW/SOLD';

CREATE INDEX idx_pred_bet_round ON prediction_bet(round_id, status);
CREATE INDEX idx_pred_bet_user ON prediction_bet(user_id, created_at DESC);

-- ============================================
-- 用户资产每日快照
-- ============================================
CREATE TABLE IF NOT EXISTS user_asset_snapshot (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    snapshot_date DATE NOT NULL,
    total_assets DECIMAL(18,2) NOT NULL,
    profit DECIMAL(18,2) NOT NULL,
    profit_pct DECIMAL(10,4) NOT NULL,
    stock_profit DECIMAL(18,2) NOT NULL DEFAULT 0,
    crypto_profit DECIMAL(18,2) NOT NULL DEFAULT 0,
    futures_profit DECIMAL(18,2) NOT NULL DEFAULT 0,
    option_profit DECIMAL(18,2) NOT NULL DEFAULT 0,
    prediction_profit DECIMAL(18,2) NOT NULL DEFAULT 0,
    game_profit DECIMAL(18,2) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_snapshot_user_date UNIQUE (user_id, snapshot_date)
);

COMMENT ON TABLE user_asset_snapshot IS '用户资产每日快照';

-- ============================================
-- 外部因子时间序列表（shadow 采集原值）
-- ============================================
CREATE TABLE IF NOT EXISTS factor_history (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(16) NOT NULL,
    factor_name VARCHAR(64) NOT NULL,
    factor_value DECIMAL(28,10),
    observed_at TIMESTAMP NOT NULL,
    metadata_json JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_factor_observation UNIQUE (symbol, factor_name, observed_at)
);

CREATE INDEX IF NOT EXISTS idx_factor_hist_symbol_factor_time
    ON factor_history(symbol, factor_name, observed_at DESC);

COMMENT ON TABLE factor_history IS '外部因子时间序列原值，shadow 采集供 B3 启用权重时计算分位';
COMMENT ON COLUMN factor_history.factor_value IS '外部因子原值，DECIMAL(28,10) 防 OI/资金流大额溢出';
COMMENT ON COLUMN factor_history.observed_at IS '数据观测时刻（非入库时刻，跨市场数据需考虑时区）';
COMMENT ON COLUMN factor_history.metadata_json IS '可放原始 API response、stale 标记、source 名等';

-- ============================================
-- 量化预测：预测周期主表
-- ============================================
CREATE TABLE IF NOT EXISTS quant_forecast_cycle (
    id BIGSERIAL PRIMARY KEY,
    cycle_id VARCHAR(64) NOT NULL UNIQUE,
    parent_cycle_id VARCHAR(64),
    symbol VARCHAR(20) NOT NULL,
    forecast_time TIMESTAMP NOT NULL,
    overall_decision VARCHAR(64) NOT NULL,
    risk_status VARCHAR(512) NOT NULL,
    snapshot_json JSONB,
    report_json JSONB,
    debate_json JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE quant_forecast_cycle IS '量化预测周期主表';
COMMENT ON COLUMN quant_forecast_cycle.cycle_id IS '周期唯一ID，格式 qf-yyyyMMdd-HHmmss-SYMBOL';
COMMENT ON COLUMN quant_forecast_cycle.parent_cycle_id IS '父重周期ID（轻周期挂载用，重周期为NULL）';
COMMENT ON COLUMN quant_forecast_cycle.symbol IS '交易对（如BTCUSDT）';
COMMENT ON COLUMN quant_forecast_cycle.forecast_time IS '预测时刻';
COMMENT ON COLUMN quant_forecast_cycle.overall_decision IS '总体决策（如PRIORITIZE_0_10_LONG）';
COMMENT ON COLUMN quant_forecast_cycle.risk_status IS '风控状态：NORMAL/CAUTIOUS/HIGH_DISAGREEMENT/ALL_NO_TRADE';
COMMENT ON COLUMN quant_forecast_cycle.snapshot_json IS '当时FeatureSnapshot快照';
COMMENT ON COLUMN quant_forecast_cycle.report_json IS 'LLM生成的报告';
COMMENT ON COLUMN quant_forecast_cycle.debate_json IS '辩论摘要';

CREATE INDEX idx_qfc_symbol_time ON quant_forecast_cycle(symbol, forecast_time DESC);
CREATE INDEX idx_qfc_parent ON quant_forecast_cycle(parent_cycle_id) WHERE parent_cycle_id IS NOT NULL;

-- ============================================
-- 量化预测：Agent投票表
-- ============================================
CREATE TABLE IF NOT EXISTS quant_agent_vote (
    id BIGSERIAL PRIMARY KEY,
    cycle_id VARCHAR(64) NOT NULL,
    agent VARCHAR(32) NOT NULL,
    horizon VARCHAR(8) NOT NULL,
    direction VARCHAR(10) NOT NULL,
    score DECIMAL(6,4) NOT NULL,
    confidence DECIMAL(6,4) NOT NULL,
    expected_move_bps INT NOT NULL DEFAULT 0,
    volatility_bps INT NOT NULL DEFAULT 0,
    reason_codes VARCHAR(512),
    risk_flags VARCHAR(256),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE quant_agent_vote IS '量化预测Agent投票明细';
COMMENT ON COLUMN quant_agent_vote.cycle_id IS '关联预测周期ID';
COMMENT ON COLUMN quant_agent_vote.agent IS 'Agent名称：microstructure/momentum/regime/volatility/news_event';
COMMENT ON COLUMN quant_agent_vote.horizon IS '区间：0_10/10_20/20_30';
COMMENT ON COLUMN quant_agent_vote.direction IS '方向：LONG/SHORT/NO_TRADE';
COMMENT ON COLUMN quant_agent_vote.score IS '方向强度 [-1,1]';
COMMENT ON COLUMN quant_agent_vote.confidence IS '置信度 [0,1]';
COMMENT ON COLUMN quant_agent_vote.expected_move_bps IS '预期波动(基点)';
COMMENT ON COLUMN quant_agent_vote.volatility_bps IS '风险估计(基点)';
COMMENT ON COLUMN quant_agent_vote.reason_codes IS '原因码(逗号分隔)';
COMMENT ON COLUMN quant_agent_vote.risk_flags IS '风险标志(逗号分隔)';

CREATE INDEX idx_qav_cycle ON quant_agent_vote(cycle_id);
CREATE INDEX idx_qav_agent_horizon ON quant_agent_vote(agent, horizon);

-- ============================================
-- 量化预测：区间裁决结果表
-- ============================================
CREATE TABLE IF NOT EXISTS quant_horizon_forecast (
    id BIGSERIAL PRIMARY KEY,
    cycle_id VARCHAR(64) NOT NULL,
    horizon VARCHAR(8) NOT NULL,
    direction VARCHAR(10) NOT NULL,
    confidence DECIMAL(6,4) NOT NULL,
    weighted_score DECIMAL(6,4) NOT NULL,
    disagreement DECIMAL(6,4) NOT NULL,
    entry_low DECIMAL(18,2),
    entry_high DECIMAL(18,2),
    invalidation_price DECIMAL(18,2),
    tp1 DECIMAL(18,2),
    tp2 DECIMAL(18,2),
    max_leverage INT NOT NULL DEFAULT 0,
    max_position_pct DECIMAL(6,4) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE quant_horizon_forecast IS '量化预测区间裁决结果';
COMMENT ON COLUMN quant_horizon_forecast.cycle_id IS '关联预测周期ID';
COMMENT ON COLUMN quant_horizon_forecast.horizon IS '区间：0_10/10_20/20_30';
COMMENT ON COLUMN quant_horizon_forecast.direction IS '方向：LONG/SHORT/NO_TRADE';
COMMENT ON COLUMN quant_horizon_forecast.confidence IS '置信度';
COMMENT ON COLUMN quant_horizon_forecast.weighted_score IS '加权得分';
COMMENT ON COLUMN quant_horizon_forecast.disagreement IS '分歧度';
COMMENT ON COLUMN quant_horizon_forecast.entry_low IS '入场区间下沿';
COMMENT ON COLUMN quant_horizon_forecast.entry_high IS '入场区间上沿';
COMMENT ON COLUMN quant_horizon_forecast.invalidation_price IS '失效价（止损）';
COMMENT ON COLUMN quant_horizon_forecast.tp1 IS '止盈目标1';
COMMENT ON COLUMN quant_horizon_forecast.tp2 IS '止盈目标2';
COMMENT ON COLUMN quant_horizon_forecast.max_leverage IS '最大杠杆';
COMMENT ON COLUMN quant_horizon_forecast.max_position_pct IS '最大仓位比例';

CREATE INDEX idx_qhf_cycle ON quant_horizon_forecast(cycle_id);

-- ============================================
-- 量化预测：风控后最终信号表
-- ============================================
CREATE TABLE IF NOT EXISTS quant_signal_decision (
    id BIGSERIAL PRIMARY KEY,
    cycle_id VARCHAR(64) NOT NULL,
    horizon VARCHAR(8) NOT NULL,
    direction VARCHAR(10) NOT NULL,
    confidence DECIMAL(6,4) NOT NULL,
    max_leverage INT NOT NULL DEFAULT 0,
    max_position_pct DECIMAL(6,4) NOT NULL DEFAULT 0,
    risk_status VARCHAR(512) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE quant_signal_decision IS '量化预测风控后最终信号';
COMMENT ON COLUMN quant_signal_decision.cycle_id IS '关联预测周期ID';
COMMENT ON COLUMN quant_signal_decision.horizon IS '区间：0_10/10_20/20_30';
COMMENT ON COLUMN quant_signal_decision.direction IS '风控后方向';
COMMENT ON COLUMN quant_signal_decision.confidence IS '风控后置信度';
COMMENT ON COLUMN quant_signal_decision.max_leverage IS '风控后杠杆上限';
COMMENT ON COLUMN quant_signal_decision.max_position_pct IS '风控后仓位上限';
COMMENT ON COLUMN quant_signal_decision.risk_status IS '风控状态';

CREATE INDEX idx_qsd_cycle ON quant_signal_decision(cycle_id);
CREATE INDEX idx_qsd_horizon_time ON quant_signal_decision(horizon, created_at DESC);

-- ============================================
-- 量化预测：预测验证表（事后对比实际价格）
-- ============================================
CREATE TABLE IF NOT EXISTS quant_forecast_verification (
    id BIGSERIAL PRIMARY KEY,
    cycle_id VARCHAR(64) NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    horizon VARCHAR(8) NOT NULL,
    predicted_direction VARCHAR(10) NOT NULL,
    predicted_confidence DECIMAL(6,4),
    actual_price_at_forecast DECIMAL(18,2),
    actual_price_after DECIMAL(18,2),
    actual_change_bps INT,
    max_favorable_bps INT,
    max_adverse_bps INT,
    tp1_hit_first BOOLEAN,
    prediction_correct BOOLEAN,
    trade_quality VARCHAR(10),
    result_summary VARCHAR(500),
    reversal_severity DECIMAL(6,4),
    verified_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE quant_forecast_verification IS '预测验证（事后对比实际价格）';
COMMENT ON COLUMN quant_forecast_verification.cycle_id IS '关联预测周期ID';
COMMENT ON COLUMN quant_forecast_verification.actual_change_bps IS '实际价格变化（基点）';
COMMENT ON COLUMN quant_forecast_verification.prediction_correct IS '预测方向是否正确';
COMMENT ON COLUMN quant_forecast_verification.reversal_severity IS '段内反转严重度[0,1]，NULL=NO_TRADE不适用';

CREATE INDEX idx_qfv_symbol_time ON quant_forecast_verification(symbol, verified_at DESC);
CREATE INDEX idx_qfv_cycle_id ON quant_forecast_verification(cycle_id);

-- ============================================
-- 量化预测：反思记忆表（LLM反思结论）
-- ============================================
CREATE TABLE IF NOT EXISTS quant_reflection_memory (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    cycle_id VARCHAR(64) NOT NULL,
    regime VARCHAR(20) NOT NULL,
    overall_decision VARCHAR(64),
    predicted_direction VARCHAR(10),
    actual_price_change_bps INT,
    prediction_correct BOOLEAN,
    reflection_text TEXT NOT NULL,
    lesson_tags VARCHAR(256),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE quant_reflection_memory IS '量化预测反思记忆（LLM生成的教训）';
COMMENT ON COLUMN quant_reflection_memory.regime IS '预测时的市场状态';
COMMENT ON COLUMN quant_reflection_memory.reflection_text IS 'LLM反思结论';
COMMENT ON COLUMN quant_reflection_memory.lesson_tags IS '结构化标签，如TREND_UP_OVERCONFIDENT';

CREATE INDEX idx_qrm_symbol_regime ON quant_reflection_memory(symbol, regime);
CREATE INDEX idx_qrm_symbol_time ON quant_reflection_memory(symbol, created_at DESC);

-- quant_forecast_cycle 增加辩论记录字段
ALTER TABLE quant_forecast_cycle ADD COLUMN IF NOT EXISTS debate_json JSONB;

-- ============================================
-- 量化预测：轻周期修正记录表（轻周期对父重周期 forecast 的逐次修正明细）
-- ============================================
CREATE TABLE IF NOT EXISTS quant_forecast_adjustment (
    id                      BIGSERIAL PRIMARY KEY,
    light_cycle_id          VARCHAR(64)  NOT NULL,
    heavy_cycle_id          VARCHAR(64)  NOT NULL,
    symbol                  VARCHAR(20)  NOT NULL,
    light_horizon           VARCHAR(8)   NOT NULL,
    heavy_horizon           VARCHAR(8)   NOT NULL,
    adjust_type             VARCHAR(32)  NOT NULL,
    light_direction         VARCHAR(10)  NOT NULL,
    light_confidence        NUMERIC(5,4) NOT NULL,
    prev_heavy_direction    VARCHAR(10)  NOT NULL,
    prev_heavy_confidence   NUMERIC(5,4) NOT NULL,
    new_heavy_direction     VARCHAR(10)  NOT NULL,
    new_heavy_confidence    NUMERIC(5,4) NOT NULL,
    vote_count_after        INT          NOT NULL DEFAULT 0,
    created_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE quant_forecast_adjustment IS '轻周期对父重周期 forecast 的修正明细（0~3 条/轻周期）';
COMMENT ON COLUMN quant_forecast_adjustment.light_cycle_id IS '触发修正的轻周期 cycleId（light- 前缀）';
COMMENT ON COLUMN quant_forecast_adjustment.heavy_cycle_id IS '被修正的父重周期 cycleId';
COMMENT ON COLUMN quant_forecast_adjustment.light_horizon IS '轻周期发出信号的 horizon';
COMMENT ON COLUMN quant_forecast_adjustment.heavy_horizon IS '按半小时墙钟窗口映射后落在的父重周期 horizon';
COMMENT ON COLUMN quant_forecast_adjustment.adjust_type IS 'SAME_DIR_BOOST 同向加成 / OPPO_WEAK_PENALTY 反向弱削弱 / OPPO_STRONG_PENALTY 反向强削弱 / LIGHT_VETO 轻周期否决 / FLIP 历史翻盘';
COMMENT ON COLUMN quant_forecast_adjustment.vote_count_after IS '本次写入后该 heavyHorizon 累计连续反转票数（否决旧方向或清零后为 0）';

CREATE INDEX idx_qfa_heavy_cycle ON quant_forecast_adjustment(heavy_cycle_id);
CREATE INDEX idx_qfa_symbol_time ON quant_forecast_adjustment(symbol, created_at DESC);

-- 爆仓记录（Binance WS @forceOrder 推送）
CREATE TABLE IF NOT EXISTS force_order (
    id              BIGSERIAL       PRIMARY KEY,
    symbol          VARCHAR(20)     NOT NULL,
    side            VARCHAR(10)     NOT NULL,
    price           DECIMAL(18,2)   NOT NULL,
    avg_price       DECIMAL(18,2)   NOT NULL,
    quantity        DECIMAL(18,8)   NOT NULL,
    amount          DECIMAL(18,2)   NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'FILLED',
    trade_time      TIMESTAMP       NOT NULL,
    created_at      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE force_order IS 'Binance合约强平记录';
COMMENT ON COLUMN force_order.side IS 'SELL=多头被强平, BUY=空头被强平';
COMMENT ON COLUMN force_order.price IS '强平委托价';
COMMENT ON COLUMN force_order.avg_price IS '成交均价';
COMMENT ON COLUMN force_order.amount IS '爆仓金额(avg_price * quantity)';

CREATE INDEX idx_fo_symbol_time ON force_order(symbol, trade_time DESC);

-- ============================================
-- AI 运行时配置表（API Key 管理，支持多条）
-- ============================================
CREATE TABLE IF NOT EXISTS ai_runtime_config (
    id BIGSERIAL PRIMARY KEY,
    config_name VARCHAR(32) NOT NULL,
    api_key VARCHAR(512) NOT NULL,
    base_url VARCHAR(512) NOT NULL,
    model VARCHAR(128),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE ai_runtime_config IS 'AI API Key配置（支持多个供应商）';
COMMENT ON COLUMN ai_runtime_config.config_name IS '配置名称，如 OpenAI、DeepSeek';
COMMENT ON COLUMN ai_runtime_config.api_key IS 'API Key';
COMMENT ON COLUMN ai_runtime_config.base_url IS 'OpenAI Compatible Base URL';
COMMENT ON COLUMN ai_runtime_config.model IS '默认模型名称（保留字段，实际模型由分配表决定）';
COMMENT ON COLUMN ai_runtime_config.enabled IS '是否启用';

-- ============================================
-- AI 模型分配表（5个功能独立选择 API Key + 模型）
-- ============================================
CREATE TABLE IF NOT EXISTS ai_model_assignment (
    id BIGSERIAL PRIMARY KEY,
    function_name VARCHAR(32) NOT NULL,
    config_id BIGINT NOT NULL,
    model VARCHAR(128) NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_ai_ma_function UNIQUE (function_name)
);

COMMENT ON TABLE ai_model_assignment IS 'AI模型分配（每个功能独立配置）';
COMMENT ON COLUMN ai_model_assignment.function_name IS '功能名称：behavior/quant/chat/trading/reflection';
COMMENT ON COLUMN ai_model_assignment.config_id IS '关联ai_runtime_config.id';
COMMENT ON COLUMN ai_model_assignment.model IS '使用的模型名称';

-- ============================================
-- AI 交易员决策记忆表
-- ============================================
CREATE TABLE IF NOT EXISTS ai_trading_decision (
    id BIGSERIAL PRIMARY KEY,
    cycle_no INT NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    action VARCHAR(32) NOT NULL,
    reasoning TEXT,
    market_context TEXT,
    position_snapshot TEXT,
    execution_result TEXT,
    balance_before DECIMAL(18,2),
    balance_after DECIMAL(18,2),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE ai_trading_decision IS 'AI交易员决策记忆';
COMMENT ON COLUMN ai_trading_decision.cycle_no IS '唤醒周期序号';
COMMENT ON COLUMN ai_trading_decision.symbol IS '交易对';
COMMENT ON COLUMN ai_trading_decision.action IS '决策动作：OPEN_LONG/OPEN_SHORT/CLOSE/INCREASE/ADD_MARGIN/HOLD/NO_ACTION';
COMMENT ON COLUMN ai_trading_decision.reasoning IS 'AI推理过程';
COMMENT ON COLUMN ai_trading_decision.market_context IS '注入的市场数据摘要';
COMMENT ON COLUMN ai_trading_decision.position_snapshot IS '当时持仓快照JSON';
COMMENT ON COLUMN ai_trading_decision.execution_result IS '执行结果JSON';
COMMENT ON COLUMN ai_trading_decision.balance_before IS '操作前余额';
COMMENT ON COLUMN ai_trading_decision.balance_after IS '操作后余额';

CREATE INDEX idx_atd_symbol_time ON ai_trading_decision(symbol, created_at DESC);
CREATE INDEX idx_atd_cycle ON ai_trading_decision(cycle_no);


-- ============================================
-- 交易归因表（真实平仓后写入）
-- ============================================
CREATE TABLE IF NOT EXISTS trade_attribution (
    id BIGSERIAL PRIMARY KEY,
    position_id BIGINT NOT NULL,
    symbol VARCHAR(16) NOT NULL,
    strategy_path VARCHAR(32) NOT NULL,
    entry_time TIMESTAMP NOT NULL,
    exit_time TIMESTAMP,
    entry_factors_json JSONB,
    pnl DECIMAL(18,8),
    holding_minutes INT,
    exit_reason VARCHAR(32),
    CONSTRAINT ck_trade_attr_strategy_path
        CHECK (strategy_path IN ('BREAKOUT', 'MR', 'LEGACY_TREND')),
    CONSTRAINT ck_trade_attr_exit_reason
        CHECK (exit_reason IS NULL OR exit_reason IN (
            'TP', 'TRAILING', 'SL', 'TIMEOUT', 'REVERSAL', 'MANUAL', 'UNKNOWN'
        ))
);

CREATE INDEX IF NOT EXISTS idx_trade_attr_path_time
    ON trade_attribution(strategy_path, entry_time);

CREATE INDEX IF NOT EXISTS idx_trade_attr_position
    ON trade_attribution(position_id);

COMMENT ON TABLE trade_attribution IS '交易归因，每平仓写一行；SHADOW_5OF7 不入此表';
COMMENT ON COLUMN trade_attribution.position_id IS '关联 futures_position.id，保持 NOT NULL';
COMMENT ON COLUMN trade_attribution.strategy_path IS '路径标签：BREAKOUT / MR / LEGACY_TREND';
COMMENT ON COLUMN trade_attribution.entry_factors_json IS '入场时关键因子快照，best-effort JSON';
COMMENT ON COLUMN trade_attribution.exit_reason IS 'best-effort 回填，自动触发场景允许 UNKNOWN';

-- ============================================
-- 策略路径状态表（自动禁用/人工解除持久化）
-- ============================================
CREATE TABLE IF NOT EXISTS strategy_path_status (
    path VARCHAR(32) PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    disabled_reason VARCHAR(256),
    disabled_at TIMESTAMP,
    consecutive_loss_count INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_strategy_path_status_path
        CHECK (path IN ('BREAKOUT', 'MR', 'LEGACY_TREND'))
);

COMMENT ON TABLE strategy_path_status IS '策略路径运行状态，自动禁用/人工解除均落库；不含 SHADOW_5OF7';
COMMENT ON COLUMN strategy_path_status.path IS '实盘路径：BREAKOUT / MR / LEGACY_TREND';
COMMENT ON COLUMN strategy_path_status.enabled IS 'false 表示该路径暂停开新仓';
COMMENT ON COLUMN strategy_path_status.disabled_reason IS '自动禁用或人工禁用原因';
COMMENT ON COLUMN strategy_path_status.consecutive_loss_count IS '路径级连续亏损计数';

INSERT INTO strategy_path_status (path, enabled) VALUES
    ('BREAKOUT', TRUE),
    ('MR', TRUE),
    ('LEGACY_TREND', TRUE)
ON CONFLICT (path) DO NOTHING;

-- ============================================
-- Phase 0A 每日观察指标（由 Phase0aStatsCollectionTask 自动聚合）
-- ============================================
CREATE TABLE IF NOT EXISTS phase0a_daily_stats (
    stat_date DATE PRIMARY KEY,
    user_id BIGINT NOT NULL,
    total_opens INT NOT NULL DEFAULT 0,
    opens_btc INT NOT NULL DEFAULT 0,
    opens_eth INT NOT NULL DEFAULT 0,
    total_closes INT NOT NULL DEFAULT 0,
    positions_closed INT NOT NULL DEFAULT 0,
    wins INT NOT NULL DEFAULT 0,
    losses INT NOT NULL DEFAULT 0,
    win_rate DECIMAL(5,2),
    realized_pnl DECIMAL(18,2) NOT NULL DEFAULT 0,
    avg_win DECIMAL(18,2),
    avg_loss DECIMAL(18,2),
    pnl_ratio DECIMAL(6,3),
    equity_start DECIMAL(18,2),
    equity_end DECIMAL(18,2),
    equity_high DECIMAL(18,2),
    equity_low DECIMAL(18,2),
    daily_drawdown_pct DECIMAL(6,2),
    daily_return_pct DECIMAL(6,2),
    avg_holding_minutes DECIMAL(10,2),
    daily_loss_blocks INT NOT NULL DEFAULT 0,
    strategy_breakdown JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE phase0a_daily_stats IS 'Phase 0A 观察期每日指标（自动聚合）';
COMMENT ON COLUMN phase0a_daily_stats.stat_date IS '统计自然日（服务器时区）';
COMMENT ON COLUMN phase0a_daily_stats.total_opens IS '开仓决策次数 ai_trading_decision.action LIKE OPEN%';
COMMENT ON COLUMN phase0a_daily_stats.total_closes IS '平仓决策次数 ai_trading_decision.action LIKE CLOSE%';
COMMENT ON COLUMN phase0a_daily_stats.positions_closed IS '当日实际平仓的 futures_position 数量';
COMMENT ON COLUMN phase0a_daily_stats.win_rate IS '胜率 wins/(wins+losses) × 100';
COMMENT ON COLUMN phase0a_daily_stats.pnl_ratio IS '平均盈亏比 avg_win / abs(avg_loss)';
COMMENT ON COLUMN phase0a_daily_stats.daily_drawdown_pct IS '日内最大回撤 (equity_high-equity_low)/equity_high × 100';
COMMENT ON COLUMN phase0a_daily_stats.daily_loss_blocks IS 'DAILY_LOSS_LIMIT 熔断触发次数（reasoning LIKE %日亏%）';
COMMENT ON COLUMN phase0a_daily_stats.strategy_breakdown IS '按 futures_position.memo 聚合的策略表现 JSONB';
