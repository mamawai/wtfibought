-- ============================================================
-- bStock 模块：Binance 代币化美股（真实标的，独立于虚拟 stock/company）
-- 一 token 一公司，扁平单表。价格/K线走 feed→Redis，本表只存标的身份 + 公司基本面。
-- 数据来源：Binance RWA 接口（meta 公司信息 + dynamic 基本面）+ 现货 exchangeInfo。
-- 既有库上线：直接执行本文件即可，不影响 stock/company 虚拟盘。
-- ============================================================

CREATE TABLE IF NOT EXISTS bstock (
    id             BIGSERIAL PRIMARY KEY,
    symbol         VARCHAR(20) NOT NULL UNIQUE,   -- Binance 现货符号，如 NVDABUSDT
    ticker         VARCHAR(10) NOT NULL,          -- 真实股票代码，如 NVDA
    name           VARCHAR(64) NOT NULL,          -- 中文名
    name_en        VARCHAR(64),                   -- 英文名
    industry       VARCHAR(32),                   -- 行业
    description    TEXT,                           -- 中文简介
    ceo            VARCHAR(64),
    homepage       VARCHAR(255),
    market_cap     DECIMAL(20,2),                 -- 市值(USD)
    pe_ratio       DECIMAL(12,2),                 -- 市盈率
    dividend_yield DECIMAL(8,4),                  -- 股息率
    multiplier     DECIMAL(24,18),                -- bStock 乘数（信息用，现货价已含分红再投）
    week52_high    DECIMAL(12,2),
    week52_low     DECIMAL(12,2),
    enabled        BOOLEAN NOT NULL DEFAULT TRUE, -- 是否上架
    sort           INT NOT NULL DEFAULT 0,        -- 展示排序
    created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE  bstock IS 'bStock 代币化美股（静态信息，独立于虚拟 stock/company）';
COMMENT ON COLUMN bstock.symbol     IS 'Binance 现货符号，如 NVDABUSDT（查价/下单）';
COMMENT ON COLUMN bstock.ticker     IS '真实股票代码，如 NVDA（展示）';
COMMENT ON COLUMN bstock.multiplier IS 'bStock 乘数（信息用，现货价已含分红再投）';

INSERT INTO bstock (symbol, ticker, name, name_en, industry, description, ceo, homepage, market_cap, pe_ratio, dividend_yield, multiplier, week52_high, week52_low, sort) VALUES
('NVDABUSDT','NVDA','英伟达','Nvidia Corp','半导体','英伟达是图形处理单元的领先开发商。传统上，GPU用于提升计算平台的体验，最显著的是在个人电脑的游戏应用中。GPU的使用案例随后发展成为人工智能中运行大型语言模型的重要半导体。英伟达不仅提供人工智能GPU，还提供用于人工智能模型开发和训练的软件平台Cuda。英伟达还在扩展其数据中心网络解决方案，帮助将GPU连接起来以处理复杂的工作负载。','Jen-Hsun Huang','https://www.nvidia.com',5109298845000,32.01,0.02,1.000932054247057497,236.54,162.02,1),
('TSLABUSDT','TSLA','特斯拉','Tesla, Inc.','电动汽车','特斯拉是一家垂直整合的电池电动汽车制造商和现实世界人工智能软件开发商，涵盖自动驾驶和类人机器人。该公司拥有多款车型，包括豪华和中型轿车、跨界SUV、一款轻型卡车和一款半挂卡车。特斯拉还计划开始销售一款跑车并提供机器人出租车服务。2024年全球交付量略低于180万辆。公司销售用于住宅和商业物业（包括公用事业）的固定储能电池，以及用于能源生成的太阳能电池板和太阳能屋顶。特斯拉还拥有一个快速充电网络和汽车保险业务。','Elon R. Musk','https://www.tesla.com',1531548026973,396.54,0,1,498.83,297.82,2),
('MUBUSDT','MU','美光科技','Micron Technology, Inc.','半导体','美光是世界上最大的半导体公司之一，专注于存储器和存储芯片。其主要收入来源于动态随机存取存储器（DRAM），同时也有少量涉及非门或NAND闪存芯片。美光服务全球客户群，向数据中心、手机、消费电子以及工业和汽车应用销售芯片。该公司实行垂直整合。','Sanjay Mehrotra','https://www.micron.com',1105845303802,21.91,0.05,1.001121237525844732,1255,103.38,3),
('SNDKBUSDT','SNDK','闪迪','SanDisk','半导体','闪迪是一家基于NAND闪存技术的数据存储企业，产品涵盖固态硬盘、嵌入式存储、存储卡和闪存盘，服务于消费者、企业及汽车市场。','David V. Goeckeler','https://www.sandisk.com',283764707318,62.95,0,1,2354.39,40.10,4),
('CRCLBUSDT','CRCL','Circle','Circle Internet Group, Inc.','金融科技','Circle Internet Group Inc是一家金融科技公司，专注于数字货币和用于支付、商业及金融应用的公共区块链。该公司是美元硬币（USDC）的发行方。','Jeremy D. Allaire','https://www.circle.com',16437199109,NULL,NULL,1,262.97,49.90,5),
('MSTRBUSDT','MSTR','Strategy','Strategy Inc','比特币金库','StrategyInc是一家比特币国库公司和商业智能服务提供商。虽然其传统业务是提供企业分析软件，但公司的核心战略是通过发行证券和利用现金流大规模持有比特币。它为投资者提供了一种在美股市场获得比特币经济敞口的独特证券化工具。','Phong Le','https://www.strategy.com',33852605316,NULL,0.88,1,457.22,81.81,6),
('AMDBUSDT','AMD','AMD','Advanced Micro Devices','半导体','超威半导体设计各种数字半导体，应用于个人电脑、游戏主机、数据中心（包括人工智能）、工业和汽车等市场。AMD的传统优势在于用于个人电脑和数据中心的中央处理器和图形处理器。然而，AMD正逐渐成为人工智能GPU及相关硬件领域的重要参与者。此外，该公司还供应索尼PlayStation和微软Xbox等知名游戏主机中的芯片。','Lisa T. Su','https://www.amd.com',909231612300,181.61,0,1,584.73,141.90,7),
('SPCXBUSDT','SPCX','SpaceX','SpaceX','航空航天','美国航天与科技公司，成立于 2002 年，由 Elon Musk 创立。公司专注于火箭、航天器、卫星互联网和人工智能基础设施的研发与运营，业务涵盖卫星发射、载人航天、全球宽带通信以及下一代太空技术。其核心产品包括 Falcon 系列火箭、Starship 飞船以及 Starlink 卫星网络。SpaceX 目前是全球最大的商业航天企业之一，并在全球轨道发射市场占据领先地位。','Elon Musk','https://www.spacex.com',NULL,NULL,0,1,225.64,135.00,8),
('QQQBUSDT','QQQ','纳指100ETF','Invesco QQQ Trust','ETF','QQQ是一只追踪纳斯达克100指数的明星基金。它包含了纳斯达克市值最大的100家非金融公司（主要是科技、生物医药和消费巨头）。作为全球科技创新的代名词，QQQ是投资者捕捉科技巨头成长红利、进行流动性管理的核心工具。',NULL,'https://invesco.com',486064702200,NULL,NULL,1.003352854155083837,748.65,551.56,9),
('SOXLBUSDT','SOXL','半导体3x做多ETF','Direxion Daily Semiconductor Bull 3X Shares','ETF','Direxion 每日半导体三倍做多 ETF：以每日再平衡方式提供半导体行业指数约 3 倍的杠杆多头敞口，是放大押注半导体板块的短线交易工具。含杠杆与每日复利损耗，波动极大，仅适合短期交易而非长期持有。',NULL,'https://www.direxion.com',25479423525,NULL,NULL,1,302.00,22.57,10);
