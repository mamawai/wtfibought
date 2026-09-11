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
    lang VARCHAR(8),
    muted_until TIMESTAMP,
    profile_public BOOLEAN NOT NULL DEFAULT TRUE,
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
COMMENT ON COLUMN "user".lang IS 'agent提示词语言 zh/en（AgentLang.code），NULL=跟随中文。建号时取当时的界面语言，之后只在配置页改；界面语言在前端localStorage(wiib-lang)，两边互不影响';
COMMENT ON COLUMN "user".muted_until IS '禁言到期时间，NULL或已过期=未禁言；永久禁言存2099年。到期自动解禁，无需定时任务。重置账户不清此列，否则被禁言者可靠重置逃避处罚';
COMMENT ON COLUMN "user".profile_public IS '是否允许别人查看自己的持仓与交易历史。关掉只挡详情页，仍照常上排行榜（榜上只有总资产/收益率）';
COMMENT ON COLUMN "user".created_at IS '创建时间';
COMMENT ON COLUMN "user".updated_at IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_user_bankrupt ON "user"(is_bankrupt, bankrupt_reset_date);

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

-- 1c. 无钱包划转流水表：划转记 user_ledger 的 WALLET_TRANSFER_OUT/IN 两条，差额即销毁的手续费

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
    chips BIGINT NOT NULL DEFAULT 200,
    today_converted BIGINT NOT NULL DEFAULT 0,
    last_convert_date DATE,
    last_reset_date DATE,
    total_hands BIGINT NOT NULL DEFAULT 0,
    total_won BIGINT NOT NULL DEFAULT 0,
    total_lost BIGINT NOT NULL DEFAULT 0,
    biggest_win BIGINT NOT NULL DEFAULT 0,
    session_json TEXT,
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
COMMENT ON COLUMN blackjack_account.session_json IS '进行中那一局的完整快照(牌靴/各手牌/庄家牌/保险)，NULL=无牌局；与筹码同行同一笔update，钱和牌不会分叉';
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

CREATE INDEX IF NOT EXISTS idx_bj_convert_user ON blackjack_convert_log(user_id);

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
COMMENT ON COLUMN crypto_order.status IS 'PENDING/TRIGGERED/FILLED/CANCELLED';

CREATE INDEX IF NOT EXISTS idx_crypto_order_user ON crypto_order(user_id);
CREATE INDEX IF NOT EXISTS idx_crypto_order_status ON crypto_order(status, order_type);
CREATE INDEX IF NOT EXISTS idx_crypto_order_symbol ON crypto_order(symbol, status);

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

CREATE INDEX IF NOT EXISTS idx_mines_game_user_status ON mines_game(user_id, status);

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

CREATE INDEX IF NOT EXISTS idx_fp_user_status ON futures_position(user_id, status);
CREATE INDEX IF NOT EXISTS idx_fp_symbol_status ON futures_position(symbol, status);
-- 对齐Binance双向持仓：同用户同币同向最多一张OPEN仓位（多空各一张），开仓合并的并发兜底
CREATE UNIQUE INDEX IF NOT EXISTS uq_fp_user_symbol_side_open ON futures_position(user_id, symbol, side) WHERE status = 'OPEN';

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
COMMENT ON COLUMN futures_order.status IS '状态：PENDING/TRIGGERED/FILLED/CANCELLED/LIQUIDATED';

CREATE INDEX IF NOT EXISTS idx_fo_user ON futures_order(user_id);
CREATE INDEX IF NOT EXISTS idx_fo_position ON futures_order(position_id);
CREATE INDEX IF NOT EXISTS idx_fo_symbol_status ON futures_order(symbol, status);

-- ============================================
-- 20. 视频扑克游戏记录表
-- ============================================
CREATE TABLE IF NOT EXISTS video_poker_game (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    bet_amount DECIMAL(18,2) NOT NULL,
    initial_cards VARCHAR(64) NOT NULL,
    deck TEXT,
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
COMMENT ON COLUMN video_poker_game.deck IS '本局洗好的整副52张(逗号分隔)，前5张即initial_cards，draw从第6张起补牌';
COMMENT ON COLUMN video_poker_game.held_positions IS 'HOLD的位置(逗号分隔,0-4)';
COMMENT ON COLUMN video_poker_game.final_cards IS '最终5张牌(逗号分隔)';
COMMENT ON COLUMN video_poker_game.hand_rank IS '牌型名称';
COMMENT ON COLUMN video_poker_game.multiplier IS '赔率倍数';
COMMENT ON COLUMN video_poker_game.payout IS '赔付金额';
COMMENT ON COLUMN video_poker_game.status IS 'DEALING/SETTLED';

CREATE INDEX IF NOT EXISTS idx_vp_game_user_status ON video_poker_game(user_id, status);

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
COMMENT ON COLUMN prediction_round.outcome IS '结果：UP/DOWN/DRAW/VOID（VOID=取不到收盘价作废，注单退本金）';
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

CREATE INDEX IF NOT EXISTS idx_pred_bet_round ON prediction_bet(round_id, status);
CREATE INDEX IF NOT EXISTS idx_pred_bet_user ON prediction_bet(user_id, created_at DESC);

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

CREATE INDEX IF NOT EXISTS idx_fo_symbol_time ON force_order(symbol, trade_time DESC);
-- 首页"最新一条强平"卡片直取首行，代价与表大小无关
CREATE INDEX IF NOT EXISTS idx_fo_time ON force_order(trade_time DESC);

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
    reason            TEXT,
    leg_tags          TEXT,
    bar_close_time    BIGINT          NOT NULL,
    created_at        TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_strategy_signal UNIQUE (strategy_id, symbol, bar_close_time)
);
COMMENT ON TABLE strategy_signal IS '策略实盘信号记录';
COMMENT ON COLUMN strategy_signal.mode IS 'LIVE；保留字段用于兼容与审计';
COMMENT ON COLUMN strategy_signal.entry_ref_price IS '信号参考价(确认bar收盘)';
COMMENT ON COLUMN strategy_signal.leg_tags IS 'live确认腿判定，如liq_cascade=PASS';
COMMENT ON COLUMN strategy_signal.take_profit IS '固定止盈价；TURTLE类通道出场策略无固定TP，为NULL';

CREATE INDEX IF NOT EXISTS idx_strategy_signal_symbol_time ON strategy_signal(symbol, bar_close_time DESC);

-- 旧库放开列宽（新库的 CREATE 里已是 TEXT）：PG 的 varchar(n) 与 text 存储实现相同，
-- 封顶换不来好处，只会让超长的那行整条写不进去
ALTER TABLE strategy_signal ALTER COLUMN reason   TYPE TEXT;
ALTER TABLE strategy_signal ALTER COLUMN leg_tags TYPE TEXT;

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
COMMENT ON COLUMN ai_runtime_config.reasoning_effort IS '思考档位，任意上游认的值（none/low/medium/high/xhigh…），NULL=不传走模型默认；同模型要深浅两档就建两条配置分给不同功能位';
COMMENT ON COLUMN ai_runtime_config.api_protocol IS '上游协议：openai=/v1/chat/completions，responses=/v1/responses，anthropic=/v1/messages，gemini=/v1beta/models/{model}:streamGenerateContent';
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
COMMENT ON COLUMN ai_model_assignment.function_name IS '功能名称，白名单见AiFunctions，现只有news-translation（快讯后台批量译英文）；面向用户的功能位已全量BYOK，behavior等残行是孤儿不影响使用';
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

-- （research 链下序列统一存 factor_history 表，不单建序列表）

-- ============ quant_deep_analysis：深研判（工作台对话触发，Bull∥Bear→Judge 产物） ============
CREATE TABLE IF NOT EXISTS quant_deep_analysis (
    id              BIGSERIAL PRIMARY KEY,
    symbol          VARCHAR(20) NOT NULL,
    close_time      BIGINT NOT NULL,
    trigger_source  VARCHAR(20),
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
COMMENT ON TABLE quant_deep_analysis IS '深研判:研判叙事+情景分布+失效条件+无方向态;定时轨下线后唯一入口=工作台对话(HITL确认后跑)';

-- ============ quant_narrative_verification：叙事对账（Judge三情景概率到期对答案，判定界对账时现算） ============
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
COMMENT ON TABLE quant_narrative_verification IS '叙事对账:Judge三情景概率12h到期后对真实走势打分(Brier,均匀基线2/3)——研判有战绩才知道准不准';
COMMENT ON COLUMN quant_narrative_verification.range_cut_bps IS '实际情景判定界=研判时点前90天|H12收益|下三分位(基率≈1/3均分);对账时从K线现算,只用closeTime前数据保PIT';
COMMENT ON COLUMN quant_narrative_verification.status IS 'VERIFIED=已对账/SKIPPED=不可对账(缺档界或情景损坏或K线缺口超宽限)';

-- ============ workbench_chat_message：工作台对话历史（展示用；续聊上下文走 workbench_chat_context） ============
CREATE TABLE IF NOT EXISTS workbench_chat_message (
    id          BIGSERIAL PRIMARY KEY,
    session_id  VARCHAR(80) NOT NULL,
    user_id     BIGINT NOT NULL,
    role        VARCHAR(10) NOT NULL,
    content     TEXT NOT NULL,
    -- 下面 6 列只有 assistant 行有值：这一轮用的端点、烧的 token、花的时间，随答案一起落库
    -- 200 是按上界算的：端点名上限 32 + 分隔符 3 + user_llm_endpoint.model 的 128。
    -- 装不下不是丢一列而是丢一整条答案——插入抛异常被 ChatHistoryService 的 catch 吞掉
    model_label VARCHAR(200),
    model_calls INT,
    prompt_tokens BIGINT,
    completion_tokens BIGINT,
    total_tokens BIGINT,
    latency_ms  INT,
    -- 这一轮联网搜索的来源 [{url,title}]，只有搜过的 assistant 行有值
    sources     JSONB,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- 旧库补列（新库的 CREATE 里已有），可反复执行
ALTER TABLE workbench_chat_message ADD COLUMN IF NOT EXISTS sources JSONB;

CREATE INDEX IF NOT EXISTS idx_wb_chat_session ON workbench_chat_message (session_id, id);
CREATE INDEX IF NOT EXISTS idx_wb_chat_user ON workbench_chat_message (user_id, id DESC);
COMMENT ON TABLE workbench_chat_message IS '工作台对话历史(展示用):user/assistant按会话落库,session_id与workbench_chat_context同值';
COMMENT ON COLUMN workbench_chat_message.model_label IS '这一轮用的对话主模型:端点名 · 模型名(与LlmEndpointSelect展示口径一致)';
COMMENT ON COLUMN workbench_chat_message.total_tokens IS '本轮全部模型调用(路由+专家+汇总+压缩+深研判)的token合计;NULL=上游端点没返回usage或本轮账不可信(有别轮的在途专家仍在记账),不是0';
COMMENT ON COLUMN workbench_chat_message.latency_ms IS '本轮墙钟耗时:从controller接手这一轮起算,不含准入/建叶子/让位握手;比[TurnMetrics]日志多一帧session与user行落库';
COMMENT ON COLUMN workbench_chat_message.sources IS '这一轮联网搜索搜到/引用的来源 [{url,title}],按url去重;答案底部展示;没搜过或老数据为NULL';

-- ============ workbench_chat_context：工作台会话模型侧上下文（续聊主链；一会话一行整体替换） ============
-- 替代 langgraph4j PostgresSaver 的 lg4j* 表：那套图每走一步存一行完整快照（一轮 8 行、同一份历史重复存），
-- 而续聊只消费最新一份。这里只存那一份：每轮对话结束用 summarizer 的最终 state 整体覆盖
CREATE TABLE IF NOT EXISTS workbench_chat_context (
    session_id  VARCHAR(80) PRIMARY KEY,
    user_id     BIGINT NOT NULL,
    state       BYTEA NOT NULL,
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE workbench_chat_context IS '工作台会话模型侧上下文:完整消息历史(含专家结论/压缩摘要/工具配对),每轮结束整体替换;删会话随展示表一并清';
COMMENT ON COLUMN workbench_chat_context.state IS '裸JSON {"messages":[...]}(fastjson2,ChatContextCodec写),保Spring AI Message多态与tool_call配对往返无损;老行是Java对象流包JSON,读时兼容,下一轮整体覆盖后自然换成新格式';

-- 工作台跨会话记忆表 workbench_memory 已删：召回段对答案质量没有可观测贡献，链路整条拆掉。旧库执行：
--     DROP TABLE IF EXISTS workbench_memory;

-- ============ news_event：快讯存档（首页快讯卡 + 事件研究数据积累） ============
-- 采集轨独立于 NewsCache 懒加载：定时经缓存拉 BlockBeats（共享额度窗），新条目轻模型译成英文后落库。
-- BlockBeats 免费额度一次性不回血，采集节奏见 application.yml 的 news.collect
CREATE TABLE IF NOT EXISTS news_event (
    id               BIGSERIAL PRIMARY KEY,
    source_id        BIGINT NOT NULL UNIQUE,
    title            TEXT NOT NULL,
    content          TEXT,
    title_en         TEXT,
    content_en       TEXT,
    url              TEXT,
    published_at     BIGINT NOT NULL,
    translated_model VARCHAR(128),
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- 快讯停止打标（2026-09）：K线图标改挂财经日历，tags 列删掉；模型只做译文，列名跟着改；功能位改名
ALTER TABLE news_event DROP COLUMN IF EXISTS tags;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'news_event' AND column_name = 'tagged_model') THEN
        ALTER TABLE news_event RENAME COLUMN tagged_model TO translated_model;
    END IF;
END $$;
UPDATE ai_model_assignment SET function_name = 'news-translation'
 WHERE function_name = 'news-tagging'
   AND NOT EXISTS (SELECT 1 FROM ai_model_assignment WHERE function_name = 'news-translation');
CREATE INDEX IF NOT EXISTS idx_news_event_published ON news_event (published_at DESC);
COMMENT ON TABLE news_event IS '快讯存档:BlockBeats重要快讯+轻模型英文译文;首页快讯卡数据源,未来做事件研究';
COMMENT ON COLUMN news_event.source_id IS 'BlockBeats快讯id,增量去重键';
COMMENT ON COLUMN news_event.published_at IS '发稿时刻epoch毫秒(BlockBeats create_time按北京时间解析)';
COMMENT ON COLUMN news_event.translated_model IS '译文用的模型名,追责用';
COMMENT ON COLUMN news_event.title_en IS '标题英文译文;NULL=没译成(模型没给/正文超长/老行):模型侧回落中文原文,英文界面不展示这条——不许拿原文冒充译文';
COMMENT ON COLUMN news_event.content_en IS '正文英文译文;NULL 同 title_en。正文超过打标输入上限的那条不留译文:半截译文比原文更糟';

-- ============ econ_calendar_event：财经日历（TradingView 日历接口只收 High 级，唤醒开场白注入 + BTC K线标记） ============
-- 采集轨 EconCalendarCollector 每 4h 同步 [now-3d, now+7d]，按 TradingView 事件 id upsert（改期改时刻、公布填实际值、
-- 前值修正落同一行），窗口内不在回包里的行删掉（改期出窗/取消）；公布时刻等待闸 EconCalendarGate 窄窗口轮询补 actual。
-- EconCalendarAssembler 注入"刚公布 / 过去3天已公布 / 今天剩余即将公布"
CREATE TABLE IF NOT EXISTS econ_calendar_event (
    id         BIGSERIAL    PRIMARY KEY,
    source_id  VARCHAR(32)  NOT NULL,
    event_time BIGINT       NOT NULL,
    country    VARCHAR(8)   NOT NULL,
    currency   VARCHAR(8)   NOT NULL,
    title      VARCHAR(200) NOT NULL,
    actual     VARCHAR(32),
    forecast   VARCHAR(32),
    previous   VARCHAR(32),
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- 换源 TradingView（2026-09）：存量库补列、清 ForexFactory 旧行（旧行没有 source_id），全部幂等可重跑
ALTER TABLE econ_calendar_event ADD COLUMN IF NOT EXISTS source_id VARCHAR(32);
ALTER TABLE econ_calendar_event ADD COLUMN IF NOT EXISTS country   VARCHAR(8);
ALTER TABLE econ_calendar_event ADD COLUMN IF NOT EXISTS actual    VARCHAR(32);
ALTER TABLE econ_calendar_event DROP COLUMN IF EXISTS impact;
DELETE FROM econ_calendar_event WHERE source_id IS NULL;
ALTER TABLE econ_calendar_event ALTER COLUMN source_id SET NOT NULL, ALTER COLUMN country SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_econ_calendar_source ON econ_calendar_event (source_id);
CREATE INDEX IF NOT EXISTS idx_econ_calendar_time ON econ_calendar_event (event_time);
COMMENT ON TABLE econ_calendar_event IS '财经日历:TradingView日历接口只收High级,按事件id upsert;唤醒注入过去3天+当天剩余,BTC K线挂日历标记';
COMMENT ON COLUMN econ_calendar_event.source_id IS 'TradingView事件id,幂等键';
COMMENT ON COLUMN econ_calendar_event.event_time IS '公布/开始时刻epoch毫秒(接口的UTC ISO时间换算)';
COMMENT ON COLUMN econ_calendar_event.country IS 'ISO国家码(US/EU/GB/DE…),前端配国旗';
COMMENT ON COLUMN econ_calendar_event.currency IS '事件影响的货币代码(德国CPI国家DE货币EUR)';
COMMENT ON COLUMN econ_calendar_event.actual IS '实际值显示文本(0.2%/206K/1.443M,数字+K/M/B+%拼成);NULL=未公布或无数值(讲话/会议类)';
COMMENT ON COLUMN econ_calendar_event.forecast IS '共识预测值显示文本;NULL=无数值(讲话/会议类)';
COMMENT ON COLUMN econ_calendar_event.previous IS '前值显示文本,接口给的已是修正后的值';

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
    self_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

COMMENT ON TABLE comment IS '留言板评论（全站唯一，无附着实体）';
COMMENT ON COLUMN comment.root_id IS 'NULL=根评论；非NULL=所属根评论ID。只有两层：回复子评论时root_id仍指向根';
COMMENT ON COLUMN comment.reply_to_user_id IS '子评论回复的目标用户，用于展示"回复 @xxx"';
COMMENT ON COLUMN comment.like_count IS '赞数。只存计数，投票去重靠Redis Set，不落记录表';
COMMENT ON COLUMN comment.status IS '1=正常 0=已删（软删；根评论被删时级联软删其子评论）';
-- updated_at/self_deleted 都不参与过滤，下面两个部分索引与所有读查询的 status=1 与它们无关
COMMENT ON COLUMN comment.updated_at IS 'NULL=从未编辑过；非空=最后一次编辑时刻，前端据此显示"已编辑"。自删刻意不写此列';
COMMENT ON COLUMN comment.self_deleted IS '用户自删：内容已被占位文案覆盖（原文不可恢复）。仍算正常评论，照常可赞可回复，只是不能再编辑';

CREATE INDEX IF NOT EXISTS idx_comment_child ON comment(root_id, created_at DESC) WHERE status = 1;
CREATE INDEX IF NOT EXISTS idx_comment_root ON comment(created_at DESC) WHERE root_id IS NULL AND status = 1;

-- ============================================
-- 28. 通知（评论赞/回复 + 交易事件）
-- ============================================
CREATE TABLE IF NOT EXISTS notification (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    type SMALLINT NOT NULL,
    -- 评论类才有：交易通知是系统触发的，没有 actor，也不指向任何评论
    actor_id BIGINT,
    comment_id BIGINT,
    -- 交易类才有
    symbol VARCHAR(20),
    side VARCHAR(8),
    quantity NUMERIC(28,10),
    price NUMERIC(28,10),
    pnl NUMERIC(28,10),
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE notification IS '通知：评论赞/回复 + 交易事件（强平/止损/止盈/全仓爆仓）';
COMMENT ON COLUMN notification.user_id IS '接收者';
COMMENT ON COLUMN notification.type IS '1=赞 2=回复 3=逐仓强平 4=止损触发 5=止盈触发 6=全仓爆仓';
COMMENT ON COLUMN notification.actor_id IS '触发者（谁赞的/谁回复的）；交易类为空';
COMMENT ON COLUMN notification.comment_id IS '点击后要定位的评论：赞=自己被赞的那条，回复=对方那条回复；交易类为空';
COMMENT ON COLUMN notification.symbol IS '交易对；全仓爆仓跨多个币种，为空';
COMMENT ON COLUMN notification.side IS 'LONG/SHORT；全仓爆仓为空';
COMMENT ON COLUMN notification.quantity IS '平掉的数量；type=6 时复用为"爆掉的仓位数"';
COMMENT ON COLUMN notification.price IS '触发价；全仓爆仓为空';
COMMENT ON COLUMN notification.pnl IS '已实现盈亏；type=6 时是全部仓位的净结算额';

CREATE INDEX IF NOT EXISTS idx_notif_unread ON notification(user_id, is_read, created_at DESC);

-- ============================================
-- 30. 用户资金流水账本
-- ============================================
CREATE TABLE IF NOT EXISTS user_ledger (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT NOT NULL,
    wallet        VARCHAR(32) NOT NULL,
    biz_type      VARCHAR(32) NOT NULL,
    delta         DECIMAL(18,2) NOT NULL,
    balance_after DECIMAL(18,2) NOT NULL,
    fee           DECIMAL(18,2),
    ref_type      VARCHAR(16),
    ref_id        BIGINT,
    symbol        VARCHAR(32),
    remark        VARCHAR(128),
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE user_ledger IS '用户资金流水账本';
COMMENT ON COLUMN user_ledger.wallet IS '钱包：BALANCE/FROZEN/GAME/LOAN_PRINCIPAL/LOAN_INTEREST/POSITION_MARGIN';
COMMENT ON COLUMN user_ledger.biz_type IS '业务类型，见 LedgerBizType 枚举';
COMMENT ON COLUMN user_ledger.delta IS '变动额，有符号，正入负出';
COMMENT ON COLUMN user_ledger.balance_after IS '该钱包变动后余额，取自同条 UPDATE 的 RETURNING，不重查';
COMMENT ON COLUMN user_ledger.fee IS 'delta 中含的手续费；仅费与本金同条 SQL 时用（现货买卖、划转）。纯注释字段，不参与求和校验';
COMMENT ON COLUMN user_ledger.ref_id IS '关联单号，账单可点进对应订单';

-- 账单分页：按用户倒序翻页
CREATE INDEX IF NOT EXISTS idx_ledger_user_time ON user_ledger(user_id, id DESC);
-- 按类型筛选
CREATE INDEX IF NOT EXISTS idx_ledger_user_biz ON user_ledger(user_id, biz_type, id DESC);

-- ============ AI Trader：用户BYOK自主交易代理（2026-08，公开竞技场） ============
CREATE TABLE IF NOT EXISTS ai_trader (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL UNIQUE,
    name            VARCHAR(32) NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'PAUSED',
    paused_reason   TEXT,
    symbols         VARCHAR(255) NOT NULL,
    interval_code   VARCHAR(8) NOT NULL DEFAULT '1h',
    custom_prompt   TEXT,
    use_default_prompt BOOLEAN NOT NULL DEFAULT TRUE,
    memory          TEXT,
    learning_notes  TEXT,
    owner_note      TEXT,
    owner_note_rounds INT NOT NULL DEFAULT 0,
    sim_user_id     BIGINT,
    round_no        INT NOT NULL DEFAULT 1,
    consecutive_failures INT NOT NULL DEFAULT 0,
    leverage_min    INT NOT NULL DEFAULT 3,
    leverage_max    INT NOT NULL DEFAULT 20,
    margin_pct_min  NUMERIC(5,2) NOT NULL DEFAULT 5,
    margin_pct_max  NUMERIC(5,2) NOT NULL DEFAULT 20,
    allow_multi_position BOOLEAN NOT NULL DEFAULT TRUE,
    allow_hedge     BOOLEAN NOT NULL DEFAULT FALSE,
    review_enabled  BOOLEAN NOT NULL DEFAULT TRUE,
    learning_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    alert_enabled   BOOLEAN NOT NULL DEFAULT TRUE,
    alert_threshold_mult NUMERIC(4,2) NOT NULL DEFAULT 1.0,
    wake_window     VARCHAR(11),
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE ai_trader IS 'AI Trader：用户BYOK自主交易代理（每用户1个，独立sim子账户，公开竞技场）';
COMMENT ON COLUMN ai_trader.status IS 'PAUSED/RUNNING/LIQUIDATED';
COMMENT ON COLUMN ai_trader.symbols IS '交易币种白名单子集，逗号分隔（须在binance.symbols范围内）';
COMMENT ON COLUMN ai_trader.interval_code IS '唤醒K线级别 5m/15m/1h/4h（1d已下线；5m烧token快，适合短期测试）';
COMMENT ON COLUMN ai_trader.review_enabled IS '每日复盘开关：reviewer日线边界复盘写REVIEW决策行并整理memory；关掉只停复盘，已有笔记照常注入';
COMMENT ON COLUMN ai_trader.learning_enabled IS '同侪学习开关：learning agent在全体复盘完成后向同侪学习写LEARN行并整理learning_notes；关掉只停学习，已有笔记照常注入';
COMMENT ON COLUMN ai_trader.alert_enabled IS '波动哨兵警报开关（仅1h/4h档生效）：5分钟振幅超过 币基准阈值×灵敏度系数 且持有该币仓位/挂单时临时唤醒';
COMMENT ON COLUMN ai_trader.alert_threshold_mult IS '警报灵敏度系数≥1.0只能调高：生效阈值=每币基准(BTC0.6/ETH0.8/XRP0.8/SOL0.9/DOGE1.0%)×本系数，180天历史校准见VolatilitySentinel';
COMMENT ON COLUMN ai_trader.wake_window IS '唤醒时段（北京时间，HH:mm-HH:mm，5分钟粒度，两端含，可跨午夜），NULL=全天。只管例行唤醒与波动警报；手动唤醒不受限；每日复盘/学习仍在08:00照常。时段外静默不唤醒不写SKIPPED行';
COMMENT ON COLUMN ai_trader.use_default_prompt IS '是否使用平台系统提示词（默认true）；false=自定义提示词成为唯一指令来源（护栏仍硬校验）';
COMMENT ON COLUMN ai_trader.memory IS '复盘笔记：reviewer每日复盘整理写入（限长文本，≤2000字覆盖写），每次唤醒注入提示词——trader侧只读只注入，本列即记忆学习的接口';
COMMENT ON COLUMN ai_trader.learning_notes IS '学习笔记：learning agent向同侪学习后整理写入（≤2000字覆盖写），每次唤醒与复盘笔记并列注入；与memory分开存——来源分开模型才分得清"自己的教训"与"从别人学的"';
COMMENT ON COLUMN ai_trader.owner_note IS '主人留言：trader动作面板写入，随提示词注入，每注入一次owner_note_rounds减1，减到0连同本列一起清空（注入与递减在TraderPromptAssembler同一处）。与memory的分工：memory是复盘沉淀的长期笔记，本列是主人阶段性交代的一句话';
COMMENT ON COLUMN ai_trader.owner_note_rounds IS '留言剩余注入轮次，0=无待读留言。1即"念一次就清"，上限24（15m档≈6小时）。递减走条件SQL(正文匹配作前置+原子递减)：唤醒读的是调度时刻的快照，以正文匹配保证只递减自己注入的那条';
COMMENT ON COLUMN ai_trader.sim_user_id IS '当前局sim子账户userId，每局独立，重置开新账户';
COMMENT ON COLUMN ai_trader.round_no IS '局数：爆仓/手动重置+1开新局，历史留档';
COMMENT ON COLUMN ai_trader.leverage_min IS '杠杆区间下界：模型必须从[min,max]里选，越界护栏拒（不截断——悄悄改值会让模型的止损计算失真）';
COMMENT ON COLUMN ai_trader.leverage_max IS '杠杆区间上界，1~125；实际可用还受sim按名义价值分档限制，超档由sim拒并回传拒因';
COMMENT ON COLUMN ai_trader.margin_pct_min IS '单笔保证金占权益%下界；只约束开新仓，加仓量由模型自己斟酌';
COMMENT ON COLUMN ai_trader.margin_pct_max IS '单笔保证金占权益%上界，0.1~100';
COMMENT ON COLUMN ai_trader.allow_multi_position IS '允许同时持有多个仓位；false=全账户至多一仓（挂单一并计数，否则挂几单就能绕过）';
COMMENT ON COLUMN ai_trader.allow_hedge IS '允许同币多空双开；仅在allow_multi_position=true时有意义（双开天然占两个仓位）';
-- 自主加/减仓开关 allow_self_add / allow_self_reduce 已删：agentic trading 里调仓不等人点头，模型始终自主。旧库执行：
--     ALTER TABLE ai_trader DROP COLUMN IF EXISTS allow_self_add, DROP COLUMN IF EXISTS allow_self_reduce;

CREATE TABLE IF NOT EXISTS ai_trader_decision (
    id              BIGSERIAL PRIMARY KEY,
    trader_id       BIGINT NOT NULL,
    round_no        INT NOT NULL,
    wake_time       BIGINT NOT NULL,
    interval_code   VARCHAR(8) NOT NULL,
    kind            VARCHAR(8) NOT NULL DEFAULT 'TRADE',
    status          VARCHAR(16) NOT NULL,
    equity          NUMERIC(20,8),
    reasoning       TEXT,
    actions_json    TEXT,
    tool_calls      INT NOT NULL DEFAULT 0,
    model_calls     INT,
    prompt_tokens   BIGINT,
    completion_tokens BIGINT,
    total_tokens    BIGINT,
    latency_ms      INT,
    error           TEXT,
    memory_after    TEXT,
    trace_json      TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE ai_trader_decision ADD COLUMN IF NOT EXISTS trace_json TEXT;

CREATE INDEX IF NOT EXISTS idx_atd_trader_time ON ai_trader_decision(trader_id, wake_time DESC);
COMMENT ON TABLE ai_trader_decision IS 'AI Trader每次唤醒一行：推理全文+动作(含play_type论点标签)+权益快照——竞技场决策时间线与净值曲线数据源';
COMMENT ON COLUMN ai_trader_decision.status IS 'OK/ERROR/SKIPPED（上一唤醒未完被跳过）';
COMMENT ON COLUMN ai_trader_decision.kind IS 'TRADE=例行K线唤醒 ALERT=波动哨兵警报唤醒（wake_time=触发时刻非边界） MANUAL=主人手动唤醒（对话轨wake_trader，回路同例行） REVIEW=reviewer复盘（reasoning=复盘全文，无equity） LEARN=learning agent向同侪学习（reasoning=学习全文，无equity）';
COMMENT ON COLUMN ai_trader_decision.memory_after IS '仅REVIEW行：本期学习完的记忆快照存档（学习演进史,append-only）；ai_trader.memory是滚动覆盖的生效版本,历史版本只在这里';
COMMENT ON COLUMN ai_trader_decision.equity IS '本轮动作落地后的账户权益USDT';
COMMENT ON COLUMN ai_trader_decision.model_calls IS '本轮模型调用次数：ReAct是循环，一次唤醒会调很多次（上限见ModelCallLimiter）';
COMMENT ON COLUMN ai_trader_decision.total_tokens IS '本轮全部模型调用的token合计；NULL=上游端点没返回usage（BYOK网关各不相同），不是0';
COMMENT ON COLUMN ai_trader_decision.trace_json IS '唤醒过程轨迹JSON（提示词/每次模型调用的正文与工具调用/回执预览/收尾），形状见WakeTrace.toJson；仅TRADE/ALERT/MANUAL行，NULL=老行或begin之前就失败';

CREATE TABLE IF NOT EXISTS ai_trader_plan (
    id              BIGSERIAL PRIMARY KEY,
    trader_id       BIGINT NOT NULL,
    round_no        INT NOT NULL,
    symbol          VARCHAR(20) NOT NULL,
    side            VARCHAR(8) NOT NULL,
    play_type       VARCHAR(20),
    signals_used    TEXT,
    invalidation_condition TEXT NOT NULL,
    entry_price     NUMERIC(20,8),
    stop_loss_price NUMERIC(20,8),
    take_profit_price NUMERIC(20,8),
    opened_wake_time BIGINT NOT NULL,
    revisions_json  TEXT,
    status          VARCHAR(8) NOT NULL DEFAULT 'LIVE',
    closed_wake_time BIGINT,
    stale           BOOLEAN NOT NULL DEFAULT FALSE,
    position_id     BIGINT,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE ai_trader_plan ADD COLUMN IF NOT EXISTS stale BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE ai_trader_plan ADD COLUMN IF NOT EXISTS position_id BIGINT;
-- 同键同一时刻至多一份存活计划；归档行不占键——同轮重开/跨轮重开都能再立新计划
CREATE UNIQUE INDEX IF NOT EXISTS uq_atp_live ON ai_trader_plan (trader_id, round_no, symbol, side)
    WHERE status = 'LIVE';
COMMENT ON TABLE ai_trader_plan IS 'AI Trader持仓交易计划：开仓立的论点/失效条件/止损止盈快照，每次唤醒原样回注提示词（nof1式plan reinjection）——退出纪律的记忆载体。键(symbol,side)：sim同向开仓自动并入，任意时刻至多一仓，加仓=新论点覆盖';
COMMENT ON COLUMN ai_trader_plan.invalidation_condition IS '失效条件：什么市场状况证明论点错了（市场条件而非盈亏数字），触发才允许主动平仓';
COMMENT ON COLUMN ai_trader_plan.stop_loss_price IS '原始止损快照；当前生效止损以sim仓位为准（可能已上移锁盈）';
COMMENT ON COLUMN ai_trader_plan.opened_wake_time IS '开仓所在唤醒边界(ms)，回注时计算已持有时长';
COMMENT ON COLUMN ai_trader_plan.revisions_json IS '修订历史追加式JSON [{time,type,change,reason}]：加仓覆盖/移动止盈/移动止损/补立——修改必须留痕带理由，计划本体价格字段永远是原始快照';
COMMENT ON COLUMN ai_trader_plan.status IS 'LIVE=仓位/挂单存活 CLOSED=已了结归档。归档不删：论点→结局的配对数据是reviewer每日复盘的原料（结局按symbol/side/时间窗join sim已平仓位）';
COMMENT ON COLUMN ai_trader_plan.closed_wake_time IS '归档时刻(ms)：懒清理发现仓位已了结的唤醒边界/重置时刻，与opened_wake_time围出计划生命期';
COMMENT ON COLUMN ai_trader_plan.stale IS '主人标记忽略:true=本笔不进论点战绩统计与复盘教材(配对表/了结统计行);权益/排行榜/同侪学习照常。仅CLOSED可标,可随时取消';
COMMENT ON COLUMN ai_trader_plan.position_id IS 'sim仓位id:市价开仓/加仓从下单响应落盘,限价单成交后唤醒懒清理趟补绑;计划↔仓位配对的精确键,NULL(历史行/未成交挂单)走bestMatch时间就近兜底';

-- 加仓/减仓待主人确认表 ai_trader_request 已删：agentic trading 里调仓不该等人点头，审批链路整条拆掉。旧库执行：
--     DROP TABLE IF EXISTS ai_trader_request;

-- 旧库放开这几列的列宽（新库的 CREATE 里已是 TEXT）。装的是模型自由文本与上游异常串，
-- 长度封顶换不来任何好处：PG 的 varchar(n) 与 text 存储实现相同，超长不截断而是整行拒收——
-- 模型多写一句，一整份交易计划就没了。varchar→text 二进制兼容，只改 catalog 不重写表，可反复执行
ALTER TABLE ai_trader          ALTER COLUMN paused_reason          TYPE TEXT;
ALTER TABLE ai_trader_decision ALTER COLUMN error                  TYPE TEXT;
ALTER TABLE ai_trader_plan     ALTER COLUMN signals_used           TYPE TEXT;
ALTER TABLE ai_trader_plan     ALTER COLUMN invalidation_condition TYPE TEXT;

-- ============ user_llm_endpoint / user_llm_binding：用户 BYOK 端点库（2026-08 重构） ============
-- 全站 BYOK 总配置：一人多条端点（协议+URL+key+模型+思考档位），对话/交易员/复盘教练只做选择；
-- 用途绑定表按 purpose 指到某条端点，没绑的用途落到 is_default 那条（一人恰一条默认，只配一条时它就是全局配置）。
-- 取代原 user_llm_config（对话一人一行）与 ai_trader 里的四列 BYOK——两处各填一套表单的时代结束。
CREATE TABLE IF NOT EXISTS user_llm_endpoint (
    id               BIGSERIAL     PRIMARY KEY,
    user_id          BIGINT        NOT NULL,
    name             VARCHAR(32)   NOT NULL,
    api_protocol     VARCHAR(16)   NOT NULL DEFAULT 'openai',
    base_url         VARCHAR(255)  NOT NULL,
    model            VARCHAR(128)  NOT NULL,
    reasoning_effort VARCHAR(16),
    web_search       BOOLEAN       NOT NULL DEFAULT FALSE,
    api_key_enc      VARCHAR(1024) NOT NULL,
    is_default       BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_user_llm_endpoint_user ON user_llm_endpoint(user_id);
-- 一人恰一条默认。旧库若同一用户有多条默认只留最早一条，再建部分唯一索引：并发新增/设默认时另一方直接失败
UPDATE user_llm_endpoint e SET is_default = FALSE
 WHERE e.is_default AND e.id <> (SELECT min(d.id) FROM user_llm_endpoint d WHERE d.user_id = e.user_id AND d.is_default);
CREATE UNIQUE INDEX IF NOT EXISTS uq_user_llm_endpoint_default ON user_llm_endpoint(user_id) WHERE is_default;
-- 旧库补列（新库的 CREATE 里已有），可反复执行
ALTER TABLE user_llm_endpoint ADD COLUMN IF NOT EXISTS web_search BOOLEAN NOT NULL DEFAULT FALSE;
COMMENT ON TABLE  user_llm_endpoint IS '用户 BYOK 端点库：一条=协议+URL+key+模型(+思考档位)，一人多条；对话/交易员/复盘教练从中选';
COMMENT ON COLUMN user_llm_endpoint.reasoning_effort IS '思考档位，任意上游认的值（none/low/medium/high/xhigh…），NULL=不传走模型默认；模型支不支持查不到，由用户自选';
COMMENT ON COLUMN user_llm_endpoint.web_search IS '服务端联网搜索：请求里声明该协议的服务端搜索工具才搜(opt-in)，上游拒收自动退回不搜；responses/anthropic/gemini协议可勾(openai归一false)，端点支不支持由用户自己勾；当前只有对话summarizer用';
COMMENT ON COLUMN user_llm_endpoint.api_key_enc IS 'AES-256-GCM 密文，密钥来自 WIIB_TRADER_KEY_SECRET';
COMMENT ON COLUMN user_llm_endpoint.is_default IS '默认端点：没按用途绑定的地方都用它；一人恰一条（首条自动、删默认时最早的顶上）';

CREATE TABLE IF NOT EXISTS user_llm_binding (
    id          BIGSERIAL   PRIMARY KEY,
    user_id     BIGINT      NOT NULL,
    purpose     VARCHAR(16) NOT NULL,
    endpoint_id BIGINT      NOT NULL,
    UNIQUE (user_id, purpose)
);
COMMENT ON TABLE  user_llm_binding IS '用途→端点绑定：CHAT_MAIN 对话主模型 / CHAT_LIGHT 对话轻模型 / TRADER 交易员；无行=跟随默认端点。端点删除时其绑定连带删';
