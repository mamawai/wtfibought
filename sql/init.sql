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
    linux_do_id VARCHAR(64) UNIQUE,
    username VARCHAR(64) NOT NULL UNIQUE,
    avatar VARCHAR(256),
    password_hash VARCHAR(60),
    invite_code_id BIGINT,
    balance DECIMAL(18,2) NOT NULL DEFAULT 10000.00,
    frozen_balance DECIMAL(18,2) NOT NULL DEFAULT 0,
    game_balance DECIMAL(18,2) NOT NULL DEFAULT 0,
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
COMMENT ON COLUMN "user".linux_do_id IS 'LinuxDo用户ID，OAuth登录标识（本地注册用户为空）';
COMMENT ON COLUMN "user".username IS '用户名（全局唯一，密码登录按此查人）';
COMMENT ON COLUMN "user".avatar IS '头像URL';
COMMENT ON COLUMN "user".password_hash IS 'BCrypt密码哈希（定长60，OAuth用户为空）';
COMMENT ON COLUMN "user".invite_code_id IS '注册用的邀请码ID（可追溯，OAuth用户为空）';
COMMENT ON COLUMN "user".balance IS '余额钱包（交易：现货/B股/合约/杠杆，全仓保证金池）';
COMMENT ON COLUMN "user".frozen_balance IS '冻结余额（限价买单冻结，属余额钱包）';
COMMENT ON COLUMN "user".game_balance IS '游戏钱包（Mines/扑克/21点/预测市场，与全仓风险隔离）';
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
-- 1b. 邀请码表（邀请码注册模式：有码才能注册本地账号）
-- ============================================
CREATE TABLE IF NOT EXISTS invite_code (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(32) NOT NULL UNIQUE,
    max_uses INT NOT NULL DEFAULT 1,
    used_count INT NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE invite_code IS '邀请码表';
COMMENT ON COLUMN invite_code.code IS '邀请码（唯一）';
COMMENT ON COLUMN invite_code.max_uses IS '最大可用次数';
COMMENT ON COLUMN invite_code.used_count IS '已用次数（注册时原子+1，防并发超用）';
COMMENT ON COLUMN invite_code.enabled IS '是否可用（作废置 FALSE）';

-- ============================================
-- 1c. 钱包划转流水表（余额钱包 ↔ 游戏钱包）
-- ============================================
CREATE TABLE IF NOT EXISTS wallet_transfer (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    direction VARCHAR(12) NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    fee DECIMAL(18,2) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE wallet_transfer IS '钱包划转流水（审计用）';
COMMENT ON COLUMN wallet_transfer.direction IS '方向：TO_GAME=余额→游戏 TO_BALANCE=游戏→余额';
COMMENT ON COLUMN wallet_transfer.amount IS '划转金额（恒为正，转出方全额扣）';
COMMENT ON COLUMN wallet_transfer.fee IS '手续费（1%，到账=amount-fee，费即销毁）';

CREATE INDEX IF NOT EXISTS idx_wt_user ON wallet_transfer(user_id, created_at DESC);

-- ============================================
-- 13. 每日Buff表
-- ============================================
CREATE TABLE IF NOT EXISTS user_buff (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES "user"(id),
    buff_type VARCHAR(32) NOT NULL,
    buff_name VARCHAR(64) NOT NULL,
    rarity VARCHAR(16) NOT NULL,
    extra_data jsonb,
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
    avg_cost DECIMAL(20,8) NOT NULL,
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
    limit_price DECIMAL(20,8),
    frozen_amount DECIMAL(18,2),
    filled_price DECIMAL(20,8),
    filled_amount DECIMAL(18,2),
    commission DECIMAL(18,2) DEFAULT 0,
    trigger_price DECIMAL(20,8),
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
    margin_mode VARCHAR(8) NOT NULL DEFAULT 'ISOLATED',
    leverage INT NOT NULL,
    quantity DECIMAL(18,8) NOT NULL,
    entry_price DECIMAL(20,8) NOT NULL,
    margin DECIMAL(18,2) NOT NULL,
    funding_fee_total DECIMAL(18,2) NOT NULL DEFAULT 0,
    stop_losses JSONB,
    take_profits JSONB,
    status VARCHAR(12) NOT NULL DEFAULT 'OPEN',
    closed_price DECIMAL(20,8),
    closed_pnl DECIMAL(18,2),
    memo VARCHAR(32),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE futures_position IS '永续合约仓位表';
COMMENT ON COLUMN futures_position.user_id IS '用户ID';
COMMENT ON COLUMN futures_position.symbol IS '交易对（如BTCUSDT）';
COMMENT ON COLUMN futures_position.side IS '方向：LONG/SHORT';
COMMENT ON COLUMN futures_position.margin_mode IS '保证金模式：ISOLATED逐仓(margin=划扣的仓位保证金) CROSS全仓(margin=占用的起始保证金,钱仍在余额钱包)';
COMMENT ON COLUMN futures_position.leverage IS '杠杆倍数';
COMMENT ON COLUMN futures_position.quantity IS '数量';
COMMENT ON COLUMN futures_position.entry_price IS '开仓价';
COMMENT ON COLUMN futures_position.margin IS '保证金：逐仓=实划金额 全仓=起始保证金占用额';
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
    margin_mode VARCHAR(8) NOT NULL DEFAULT 'ISOLATED',
    quantity DECIMAL(18,8) NOT NULL,
    leverage INT NOT NULL,
    limit_price DECIMAL(20,8),
    frozen_amount DECIMAL(18,2),
    filled_price DECIMAL(20,8),
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
COMMENT ON COLUMN futures_order.margin_mode IS '保证金模式：ISOLATED逐仓(限价单物理冻结) CROSS全仓(不冻结,计入挂单占用)';
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
COMMENT ON COLUMN prediction_bet.status IS '状态：ACTIVE/WON/LOST/DRAW/SOLD/CANCELLED';

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
    -- 五分类盈亏：bStock / crypto(现货+合约) / 大宗商品(金油) / 预测 / 游戏
    bstock_profit DECIMAL(18,2) NOT NULL DEFAULT 0,
    crypto_profit DECIMAL(18,2) NOT NULL DEFAULT 0,
    commodity_profit DECIMAL(18,2) NOT NULL DEFAULT 0,
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

-- 爆仓记录（Binance WS @forceOrder 推送）
CREATE TABLE IF NOT EXISTS force_order (
    id              BIGSERIAL       PRIMARY KEY,
    symbol          VARCHAR(20)     NOT NULL,
    side            VARCHAR(10)     NOT NULL,
    price           DECIMAL(20,8)   NOT NULL,
    avg_price       DECIMAL(20,8)   NOT NULL,
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

-- 策略运行时信号记录（实盘信号复盘）
CREATE TABLE IF NOT EXISTS strategy_signal (
    id                BIGSERIAL       PRIMARY KEY,
    strategy_id       VARCHAR(32)     NOT NULL,
    symbol            VARCHAR(20)     NOT NULL,
    side              VARCHAR(8)      NOT NULL,
    mode              VARCHAR(8)      NOT NULL DEFAULT 'LIVE',
    entry_ref_price   DECIMAL(20,8)   NOT NULL,
    stop_loss         DECIMAL(20,8)   NOT NULL,
    take_profit       DECIMAL(20,8),
    score             DECIMAL(10,4),
    reason            VARCHAR(512),
    leg_tags          VARCHAR(512),
    bar_close_time    BIGINT          NOT NULL,
    created_at        TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_strategy_signal UNIQUE (strategy_id, symbol, bar_close_time)
);
COMMENT ON TABLE strategy_signal IS '策略实盘信号记录';
COMMENT ON COLUMN strategy_signal.mode IS 'LIVE；保留字段用于兼容与审计';
COMMENT ON COLUMN strategy_signal.entry_ref_price IS '信号参考价(确认bar收盘)';
COMMENT ON COLUMN strategy_signal.leg_tags IS 'live确认腿判定，如liq_cascade=PASS';
COMMENT ON COLUMN strategy_signal.take_profit IS '固定止盈价；TURTLE类通道出场策略无固定TP，为NULL';

CREATE INDEX idx_strategy_signal_symbol_time ON strategy_signal(symbol, bar_close_time DESC);

-- ============================================
-- AI 运行时配置表（API Key 管理，支持多条）
-- ============================================
CREATE TABLE IF NOT EXISTS ai_runtime_config (
    id BIGSERIAL PRIMARY KEY,
    config_name VARCHAR(32) NOT NULL,
    api_key VARCHAR(512) NOT NULL,
    base_url VARCHAR(512) NOT NULL,
    model VARCHAR(128),
    reasoning_effort VARCHAR(16),
    api_protocol VARCHAR(16) NOT NULL DEFAULT 'openai',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE ai_runtime_config IS 'LLM配置（一条=一个具体LLM：key+baseUrl+model+思考档位，支持多条）';
COMMENT ON COLUMN ai_runtime_config.config_name IS '配置名称，如 OpenAI、DeepSeek';
COMMENT ON COLUMN ai_runtime_config.api_key IS 'API Key';
COMMENT ON COLUMN ai_runtime_config.base_url IS 'OpenAI Compatible Base URL（不含/v1后缀，quant/sim 均自拼 /v1/chat/completions）';
COMMENT ON COLUMN ai_runtime_config.model IS '该LLM的模型名（功能位切到此配置即用此模型）';
COMMENT ON COLUMN ai_runtime_config.reasoning_effort IS '思考档位 none/low/medium/high，NULL=不传走模型默认；同模型要深浅两档就建两条配置分给不同功能位';
COMMENT ON COLUMN ai_runtime_config.api_protocol IS '上游协议：openai=/v1/chat/completions（DeepSeek等通用），responses=/v1/responses（CPA/OpenAI官方/xAI，思考模型优先）';
COMMENT ON COLUMN ai_runtime_config.enabled IS '是否启用';

-- ============================================
-- 功能位分配表（功能位→LLM配置的指针，更换LLM=改config_id）
-- ============================================
CREATE TABLE IF NOT EXISTS ai_model_assignment (
    id BIGSERIAL PRIMARY KEY,
    function_name VARCHAR(32) NOT NULL,
    config_id BIGINT NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_ai_ma_function UNIQUE (function_name)
);

COMMENT ON TABLE ai_model_assignment IS '功能位→LLM配置指针（模型名归属ai_runtime_config）';
COMMENT ON COLUMN ai_model_assignment.function_name IS '功能名称：behavior/quant/quant-light/chat/sim（sim=wiib-sim行情/新闻生成，自读DB）';
COMMENT ON COLUMN ai_model_assignment.config_id IS '关联ai_runtime_config.id';

-- ============ kline_history：回测/评估用 5m 基础 K 线落库（research，可复现） ============
CREATE TABLE IF NOT EXISTS kline_history (
    id            BIGSERIAL PRIMARY KEY,
    symbol        VARCHAR(32)   NOT NULL,
    interval_code VARCHAR(8)    NOT NULL,
    open_time     BIGINT        NOT NULL,
    close_time    BIGINT        NOT NULL,
    open          NUMERIC(20,8) NOT NULL,
    high          NUMERIC(20,8) NOT NULL,
    low           NUMERIC(20,8) NOT NULL,
    close         NUMERIC(20,8) NOT NULL,
    volume        NUMERIC(30,8) NOT NULL,
    created_at    TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_kline_symbol_interval_opentime UNIQUE (symbol, interval_code, open_time)
);
CREATE INDEX IF NOT EXISTS idx_kline_symbol_time ON kline_history (symbol, interval_code, open_time);

-- （Slice3 融合：research 链下序列已并入 factor_history 表，不再单建 market_series_history）

-- ============ quant_snapshot：数值快照（P2a，每5m零LLM预测点，vol_legs_json 含 PIT 档界） ============
CREATE TABLE IF NOT EXISTS quant_snapshot (
    id                  BIGSERIAL PRIMARY KEY,
    symbol              VARCHAR(20) NOT NULL,
    close_time          BIGINT NOT NULL,
    last_price          NUMERIC(20, 8),
    vol_legs_json       JSONB NOT NULL,
    regime              VARCHAR(20),
    regime_confidence   DOUBLE PRECISION,
    fragility_score     INT,
    fragility_level     VARCHAR(12),
    fragility_direction VARCHAR(10),
    fragility_headline  TEXT,
    signal_panel_json   JSONB,
    quality_flags_json  JSONB,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_quant_snapshot UNIQUE (symbol, close_time)
);
CREATE INDEX IF NOT EXISTS idx_quant_snapshot_query ON quant_snapshot (symbol, close_time DESC);
COMMENT ON TABLE quant_snapshot IS '量化数值快照:每5m零LLM,vol三腿(PIT档界)+regime+脆弱度+信号面板(P2a)';
COMMENT ON COLUMN quant_snapshot.vol_legs_json IS '三腿预测JSON,lowCut/highCut为预测时点档界,验证侧禁止重算';

-- ============ quant_deep_analysis：深研判（P2b，1h定频+哨兵插队门控，Bull∥Bear→Judge 产物） ============
CREATE TABLE IF NOT EXISTS quant_deep_analysis (
    id              BIGSERIAL PRIMARY KEY,
    symbol          VARCHAR(20) NOT NULL,
    close_time      BIGINT NOT NULL,
    trigger_source  VARCHAR(20),
    snapshot_id     BIGINT,
    narrative       TEXT,
    scenarios_json  JSONB,
    no_direction    BOOLEAN DEFAULT FALSE,
    invalidation    TEXT,
    bull_argument   TEXT,
    bear_argument   TEXT,
    judge_reasoning TEXT,
    news_context    TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_quant_deep_analysis_query ON quant_deep_analysis (symbol, close_time DESC);
COMMENT ON TABLE quant_deep_analysis IS '深研判(P2b):研判叙事+情景分布+失效条件+无方向态,debate升格为研判生产者不再改写方向数字';

-- ============ quant_vol_verification：vol预测验证（P3，QLIKE vs naive基准 + vol-state命中，PIT档界） ============
CREATE TABLE IF NOT EXISTS quant_vol_verification (
    id                  BIGSERIAL PRIMARY KEY,
    snapshot_id         BIGINT NOT NULL,
    symbol              VARCHAR(20) NOT NULL,
    close_time          BIGINT NOT NULL,
    horizon             VARCHAR(4) NOT NULL,
    forecast_sigma_bps  INT,
    baseline_sigma_bps  INT,
    realized_return_bps INT,
    qlike               DOUBLE PRECISION,
    baseline_qlike      DOUBLE PRECISION,
    vol_state_predicted VARCHAR(8),
    vol_state_actual    VARCHAR(8),
    vol_state_hit       BOOLEAN,
    verified_at         TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_quant_vol_verification UNIQUE (snapshot_id, horizon)
);
CREATE INDEX IF NOT EXISTS idx_quant_vol_verification_query ON quant_vol_verification (symbol, close_time DESC);
COMMENT ON TABLE quant_vol_verification IS 'vol预测验证(P3):QLIKE对照naive基准+vol-state命中(用快照PIT档界,禁止重算);regime刻意不验证(research实证无skill)';

-- ============ quant_narrative_verification：叙事对账（Judge三情景概率到期对答案，档界来自挂靠快照PIT） ============
CREATE TABLE IF NOT EXISTS quant_narrative_verification (
    id                  BIGSERIAL PRIMARY KEY,
    analysis_id         BIGINT NOT NULL,
    symbol              VARCHAR(20) NOT NULL,
    close_time          BIGINT NOT NULL,
    horizon             VARCHAR(4) NOT NULL,
    bull_pct            INT,
    range_pct           INT,
    bear_pct            INT,
    no_direction        BOOLEAN,
    range_cut_bps       INT,
    realized_return_bps INT,
    actual_scenario     VARCHAR(8),
    predicted_scenario  VARCHAR(8),
    scenario_hit        BOOLEAN,
    brier               DOUBLE PRECISION,
    status              VARCHAR(16) NOT NULL,
    verified_at         TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_quant_narrative_verification UNIQUE (analysis_id)
);
CREATE INDEX IF NOT EXISTS idx_quant_narrative_verification_query ON quant_narrative_verification (symbol, close_time DESC);
COMMENT ON TABLE quant_narrative_verification IS '叙事对账:Judge三情景概率12h到期后对真实走势打分(Brier,均匀基线2/3)——快照轨有记分卡,叙事轨同样要战绩';
COMMENT ON COLUMN quant_narrative_verification.range_cut_bps IS '实际情景判定界=挂靠快照H12腿lowCut(90天|收益|下三分位,基率≈1/3均分),PIT禁止重算';
COMMENT ON COLUMN quant_narrative_verification.status IS 'VERIFIED=已对账/SKIPPED=不可对账(缺档界或情景损坏或K线缺口超宽限)';

-- ============ workbench_chat_message：工作台对话历史（展示用；续聊上下文走 graphcheckpoint） ============
CREATE TABLE IF NOT EXISTS workbench_chat_message (
    id          BIGSERIAL PRIMARY KEY,
    session_id  VARCHAR(80) NOT NULL,
    user_id     BIGINT NOT NULL,
    role        VARCHAR(10) NOT NULL,
    content     TEXT NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_wb_chat_session ON workbench_chat_message (session_id, id);
CREATE INDEX IF NOT EXISTS idx_wb_chat_user ON workbench_chat_message (user_id, id DESC);
COMMENT ON TABLE workbench_chat_message IS '工作台对话历史(展示用):user/assistant按会话落库,quant启动时幂等自建(ChatHistoryService)';

-- ============================================
-- 27. 留言板评论（全站唯一，无附着实体）
-- ============================================
CREATE TABLE IF NOT EXISTS comment (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    root_id BIGINT,
    reply_to_user_id BIGINT,
    content VARCHAR(500) NOT NULL,
    like_count INT NOT NULL DEFAULT 0,
    dislike_count INT NOT NULL DEFAULT 0,
    status SMALLINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE comment IS '留言板评论（全站唯一，无附着实体）';
COMMENT ON COLUMN comment.root_id IS 'NULL=根评论；非NULL=所属根评论ID。只有两层：回复子评论时root_id仍指向根';
COMMENT ON COLUMN comment.reply_to_user_id IS '子评论回复的目标用户，用于展示"回复 @xxx"';
COMMENT ON COLUMN comment.like_count IS '赞数。只存计数，投票去重靠Redis Set，不落记录表';
COMMENT ON COLUMN comment.status IS '1=正常 0=已删（软删；根评论被删时级联软删其子评论）';

CREATE INDEX IF NOT EXISTS idx_comment_child ON comment(root_id, created_at DESC) WHERE status = 1;
CREATE INDEX IF NOT EXISTS idx_comment_root ON comment(created_at DESC) WHERE root_id IS NULL AND status = 1;

-- ============================================
-- 28. 评论通知（赞/回复，踩不通知）
-- ============================================
CREATE TABLE IF NOT EXISTS comment_notification (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    type SMALLINT NOT NULL,
    comment_id BIGINT NOT NULL,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE comment_notification IS '评论通知（赞/回复，踩不通知；自己操作自己不通知）';
COMMENT ON COLUMN comment_notification.user_id IS '接收者';
COMMENT ON COLUMN comment_notification.actor_id IS '触发者（谁赞的/谁回复的）';
COMMENT ON COLUMN comment_notification.type IS '1=赞 2=回复';
COMMENT ON COLUMN comment_notification.comment_id IS '点击后要定位的评论：赞=自己被赞的那条，回复=对方那条回复';

CREATE INDEX IF NOT EXISTS idx_notif_unread ON comment_notification(user_id, is_read, created_at DESC);

-- 禁言（评论区管理用）。重置账户刻意不清此列，否则被禁言者可靠重置逃避处罚
ALTER TABLE "user" ADD COLUMN IF NOT EXISTS muted_until TIMESTAMP;
COMMENT ON COLUMN "user".muted_until IS '禁言到期时间，NULL或已过期=未禁言；永久禁言存2099年。到期自动解禁，无需定时任务';
