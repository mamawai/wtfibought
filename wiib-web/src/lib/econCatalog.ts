/**
 * 财经日历指标核对清单（TradingView High 级事件，按国家分）。
 * 一条 = 同一个指标：换过名、大小写不同的标题都写进 titles，画走势时一起查。
 * 发布阶段不同（初值 / 修正值 / 终值 / 不带后缀）是不同的数，各算一条。
 * 国家+标题查不到 = 没见过，界面打警告；日历里冒出新标题就补进来。
 */

/** 解读类别：决定展开后显示 calendar:kind.<kind> 哪段文案 */
export type EconKind =
  | 'cpi' | 'coreCpi' | 'pce' | 'ppi' | 'nfp' | 'unemployment' | 'jobOpenings' | 'gdp' | 'retail'
  | 'consumerConf' | 'businessConf' | 'pmi' | 'industrial' | 'durableGoods' | 'income' | 'spending'
  | 'trade' | 'housing' | 'rateDecision' | 'cbMeeting' | 'cbSpeech' | 'budget' | 'event';

export interface EconEntry {
  /** 同一个指标用过的全部标题（换名、大小写变体都写进来），画走势时一起查 */
  titles: string[];
  kind: EconKind;
  /** 中文名，不带国家 */
  zh: string;
}

/** 国家码 → 该国指标清单 */
export const ECON_CATALOG: Record<string, EconEntry[]> = {
  // 澳大利亚
  AU: [
    { titles: ['Inflation Rate YoY'], kind: 'cpi', zh: '季度 CPI 年率' },
    { titles: ['Monthly CPI Indicator'], kind: 'cpi', zh: '月度 CPI 年率' },
    { titles: ['Unemployment Rate'], kind: 'unemployment', zh: '失业率' },
    { titles: ['GDP Growth Rate QoQ'], kind: 'gdp', zh: 'GDP 季率' },
    { titles: ['Westpac Consumer Confidence Index'], kind: 'consumerConf', zh: 'Westpac 消费者信心指数' },
    { titles: ['Westpac Consumer Confidence Change'], kind: 'consumerConf', zh: 'Westpac 消费者信心指数月率' },
    { titles: ['NAB Business Confidence'], kind: 'businessConf', zh: 'NAB 商业信心指数' },
    { titles: ['Markit Composite PMI Flash'], kind: 'pmi', zh: '综合 PMI 初值（Markit）' },
    { titles: ['Balance of Trade'], kind: 'trade', zh: '贸易帐' },
    { titles: ['RBA Interest Rate Decision'], kind: 'rateDecision', zh: '澳洲联储利率决议' },
    { titles: ['RBA Meeting Minutes'], kind: 'cbMeeting', zh: '澳洲联储会议纪要' },
    { titles: ['RBA Statement on Monetary Policy'], kind: 'cbMeeting', zh: '澳洲联储货币政策声明' },
    { titles: ['RBA Chart Pack'], kind: 'cbMeeting', zh: '澳洲联储图表集' },
    { titles: ['RBA Bulletin'], kind: 'cbMeeting', zh: '澳洲联储季度公报' },
    { titles: ['RBA Financial Stability Review'], kind: 'cbMeeting', zh: '澳洲联储金融稳定报告' },
    { titles: ['RBA Gov Lowe Speech'], kind: 'cbSpeech', zh: '澳洲联储主席洛威讲话' },
  ],

  // 比利时
  BE: [
    { titles: ['Extraordinary NATO Summit'], kind: 'event', zh: '北约特别峰会' },
  ],

  // 加拿大
  CA: [
    { titles: ['Inflation Rate YoY'], kind: 'cpi', zh: 'CPI 年率' },
    { titles: ['Unemployment Rate'], kind: 'unemployment', zh: '失业率' },
    { titles: ['GDP Growth Rate QoQ'], kind: 'gdp', zh: 'GDP 季率' },
    { titles: ['GDP Growth Rate Annualized'], kind: 'gdp', zh: 'GDP 年化季率' },
    { titles: ['Ivey PMI s.a'], kind: 'pmi', zh: 'Ivey PMI' },
    { titles: ['Balance of Trade'], kind: 'trade', zh: '贸易帐' },
    { titles: ['BoC Interest Rate Decision'], kind: 'rateDecision', zh: '加拿大央行利率决议' },
    { titles: ['BoC Monetary Policy Report'], kind: 'cbMeeting', zh: '加拿大央行货币政策报告' },
  ],

  // 瑞士
  CH: [
    { titles: ['Consumer Confidence'], kind: 'consumerConf', zh: '消费者信心指数' },
  ],

  // 中国
  CN: [
    { titles: ['Inflation Rate YoY'], kind: 'cpi', zh: 'CPI 年率' },
    { titles: ['GDP Growth Rate YoY'], kind: 'gdp', zh: 'GDP 年率' },
    { titles: ['Retail Sales YoY'], kind: 'retail', zh: '社会消费品零售总额年率' },
    { titles: ['NBS Manufacturing PMI'], kind: 'pmi', zh: '国家统计局制造业 PMI' },
    // 冠名换了：2025-09 起财新换成 RatingDog，还是同一份标普中国制造业 PMI
    {
      titles: ['Caixin Manufacturing PMI', 'RatingDog Manufacturing PMI'],
      kind: 'pmi', zh: 'RatingDog 制造业 PMI（原财新）',
    },
    { titles: ['Industrial Production YoY'], kind: 'industrial', zh: '规模以上工业增加值年率' },
    { titles: ['Balance of Trade'], kind: 'trade', zh: '贸易帐' },
    { titles: ['Exports YoY'], kind: 'trade', zh: '出口年率' },
    { titles: ['Imports YoY'], kind: 'trade', zh: '进口年率' },
    { titles: ['Loan Prime Rate 1Y'], kind: 'rateDecision', zh: '一年期 LPR' },
    { titles: ['20th National Congress of the Chinese Communist Party'], kind: 'event', zh: '中共二十大' },
    { titles: ['National People’s Congress'], kind: 'event', zh: '全国人大会议' },
    { titles: ['Politburo Meeting'], kind: 'event', zh: '中央政治局会议' },
    { titles: ['National Development and Reform Commission Briefing'], kind: 'event', zh: '国家发改委新闻发布会' },
    { titles: ['President Trump and President Xi Summit'], kind: 'event', zh: '特朗普与习近平会晤' },
  ],

  // 德国
  DE: [
    { titles: ['Inflation Rate YoY Prel'], kind: 'cpi', zh: 'CPI 年率初值' },
    { titles: ['Unemployment Change'], kind: 'unemployment', zh: '季调后失业人数变化' },
    { titles: ['GDP Growth Rate QoQ Flash'], kind: 'gdp', zh: 'GDP 季率初值' },
    { titles: ['GDP Growth Rate YoY Flash'], kind: 'gdp', zh: 'GDP 年率初值' },
    { titles: ['Full Year GDP Growth'], kind: 'gdp', zh: '全年 GDP 年率' },
    { titles: ['GfK Consumer Confidence'], kind: 'consumerConf', zh: 'GfK 消费者信心指数' },
    { titles: ['Ifo Business Climate'], kind: 'businessConf', zh: 'Ifo 商业景气指数' },
    { titles: ['ZEW Economic Sentiment Index'], kind: 'businessConf', zh: 'ZEW 经济景气指数' },
    // 发布方/冠名换过：Markit → S&P Global，HCOB 冠名
    {
      titles: ['Markit Manufacturing PMI Flash', 'S&P Global Manufacturing PMI Flash', 'HCOB Manufacturing PMI Flash'],
      kind: 'pmi', zh: '制造业 PMI 初值（HCOB/S&P Global）',
    },
    { titles: ['Markit Composite PMI Flash'], kind: 'pmi', zh: '综合 PMI 初值（Markit）' },
    { titles: ['Balance of Trade'], kind: 'trade', zh: '贸易帐' },
    { titles: ['Federal Election'], kind: 'event', zh: '联邦议院选举' },
  ],

  // 西班牙
  ES: [
    { titles: ['GDP Growth Rate QoQ Flash'], kind: 'gdp', zh: 'GDP 季率初值' },
    { titles: ['GDP Growth Rate YoY Flash'], kind: 'gdp', zh: 'GDP 年率初值' },
  ],

  // 欧元区
  EU: [
    { titles: ['Inflation Rate YoY Flash'], kind: 'cpi', zh: 'CPI 年率初值' },
    { titles: ['Unemployment Rate'], kind: 'unemployment', zh: '失业率' },
    { titles: ['GDP Growth Rate QoQ Flash'], kind: 'gdp', zh: 'GDP 季率初值' },
    { titles: ['GDP Growth Rate YoY Flash'], kind: 'gdp', zh: 'GDP 年率初值' },
    { titles: ['Markit Composite PMI Flash'], kind: 'pmi', zh: '综合 PMI 初值（Markit）' },
    { titles: ['ECB Interest Rate Decision'], kind: 'rateDecision', zh: '欧洲央行利率决议' },
    { titles: ['Deposit Facility Rate'], kind: 'rateDecision', zh: '欧洲央行存款便利利率' },
    { titles: ['ECB Press Conference'], kind: 'cbMeeting', zh: '欧洲央行新闻发布会' },
    { titles: ['ECB Unscheduled Meeting to Discuss Markets'], kind: 'cbMeeting', zh: '欧洲央行临时会议' },
    { titles: ['ECB Forum on Central Banking'], kind: 'cbMeeting', zh: '欧洲央行论坛' },
    { titles: ['ECB President Lagarde Speech'], kind: 'cbSpeech', zh: '欧洲央行行长拉加德讲话' },
    { titles: ['European Commission Spring Forecasts'], kind: 'event', zh: '欧盟委员会春季经济预测' },
  ],

  // 法国
  FR: [
    { titles: ['Inflation Rate YoY Prel'], kind: 'cpi', zh: 'CPI 年率初值' },
    { titles: ['GDP Growth Rate QoQ Prel'], kind: 'gdp', zh: 'GDP 季率初值' },
    { titles: ['GDP Growth Rate YoY Prel'], kind: 'gdp', zh: 'GDP 年率初值' },
    { titles: ['Markit Composite PMI Flash'], kind: 'pmi', zh: '综合 PMI 初值（Markit）' },
  ],

  // 英国
  GB: [
    { titles: ['Inflation Rate YoY'], kind: 'cpi', zh: 'CPI 年率' },
    { titles: ['Unemployment Rate'], kind: 'unemployment', zh: '失业率' },
    { titles: ['Unemployment Rate - Adjusted'], kind: 'unemployment', zh: '调整后失业率' },
    { titles: ['Claimant Count Change'], kind: 'unemployment', zh: '季调后申领失业金人数变化' },
    { titles: ['GDP Growth Rate QoQ Prel'], kind: 'gdp', zh: 'GDP 季率初值' },
    { titles: ['GDP Growth Rate YoY Prel'], kind: 'gdp', zh: 'GDP 年率初值' },
    { titles: ['GDP MoM'], kind: 'gdp', zh: 'GDP 月率' },
    { titles: ['GDP YoY'], kind: 'gdp', zh: '月度 GDP 年率' },
    { titles: ['Retail Sales MoM'], kind: 'retail', zh: '零售销售月率' },
    // 只是大小写不同
    {
      titles: ['Gfk Consumer Confidence', 'GfK Consumer Confidence'],
      kind: 'consumerConf', zh: 'GfK 消费者信心指数',
    },
    // 冠名换过：Markit/CIPS → S&P Global/CIPS → S&P Global
    {
      titles: [
        'Markit/CIPS Manufacturing PMI Flash',
        'S&P Global/CIPS Manufacturing PMI Flash',
        'S&P Global Manufacturing PMI Flash',
      ],
      kind: 'pmi', zh: '制造业 PMI 初值（S&P Global/CIPS）',
    },
    // 冠名换过：Markit/CIPS → S&P Global/CIPS → S&P Global
    {
      titles: [
        'Markit/CIPS UK Services PMI Flash',
        'S&P Global/CIPS UK Services PMI Flash',
        'S&P Global Services PMI Flash',
      ],
      kind: 'pmi', zh: '服务业 PMI 初值（S&P Global/CIPS）',
    },
    { titles: ['S&P Global/CIPS UK Services PMI Final'], kind: 'pmi', zh: '服务业 PMI 终值（S&P Global/CIPS）' },
    { titles: ['Markit/CIPS Composite PMI Flash'], kind: 'pmi', zh: '综合 PMI 初值（Markit/CIPS）' },
    { titles: ['Balance of Trade'], kind: 'trade', zh: '贸易帐' },
    { titles: ['BoE Interest Rate Decision'], kind: 'rateDecision', zh: '英国央行利率决议' },
    { titles: ['BoE Gov Bailey Speech'], kind: 'cbSpeech', zh: '英国央行行长贝利讲话' },
    { titles: ['BoE Pill Speech'], kind: 'cbSpeech', zh: '英国央行首席经济学家皮尔讲话' },
    { titles: ['UK Autumn Statement'], kind: 'budget', zh: '秋季财政声明' },
    { titles: ['Autumn Statement'], kind: 'budget', zh: '秋季财政声明' },
    { titles: ['Autumn Budget 2025'], kind: 'budget', zh: '2025 年秋季预算案' },
    { titles: ['Chancellor Jeremy Hunt Statement'], kind: 'budget', zh: '财政大臣亨特声明' },
    { titles: ['PM Liz Truss Speech'], kind: 'event', zh: '首相特拉斯讲话' },
    { titles: ['UK General Election'], kind: 'event', zh: '英国大选' },
    { titles: ['Local Elections'], kind: 'event', zh: '地方选举' },
  ],

  // 印度
  IN: [
    { titles: ['GDP Growth Rate YoY'], kind: 'gdp', zh: 'GDP 年率' },
    { titles: ['RBI Interest Rate Decision'], kind: 'rateDecision', zh: '印度央行利率决议' },
    { titles: ['India Union Budget 2025'], kind: 'budget', zh: '2025 年联邦预算案' },
    { titles: ['India Union Budget 2026'], kind: 'budget', zh: '2026 年联邦预算案' },
  ],

  // 意大利
  IT: [
    { titles: ['Inflation Rate YoY Prel'], kind: 'cpi', zh: 'CPI 年率初值' },
    { titles: ['Unemployment Rate'], kind: 'unemployment', zh: '失业率' },
    { titles: ['GDP Growth Rate QoQ Adv'], kind: 'gdp', zh: 'GDP 季率初值' },
    { titles: ['GDP Growth Rate YoY Adv'], kind: 'gdp', zh: 'GDP 年率初值' },
    { titles: ['Full Year GDP Growth'], kind: 'gdp', zh: '全年 GDP 年率' },
    { titles: ['Government Budget'], kind: 'budget', zh: '政府预算占 GDP 比重' },
  ],

  // 日本
  JP: [
    { titles: ['Inflation Rate YoY'], kind: 'cpi', zh: 'CPI 年率' },
    { titles: ['GDP Growth Rate QoQ Prel'], kind: 'gdp', zh: 'GDP 季率初值' },
    { titles: ['GDP Growth Annualized Prel'], kind: 'gdp', zh: 'GDP 年化季率初值' },
    { titles: ['Consumer Confidence'], kind: 'consumerConf', zh: '消费者信心指数' },
    { titles: ['Tankan Large Manufacturers Index'], kind: 'businessConf', zh: '短观大型制造业指数' },
    { titles: ['Jibun Bank Composite PMI Flash'], kind: 'pmi', zh: '综合 PMI 初值（Jibun Bank）' },
    { titles: ['Balance of Trade'], kind: 'trade', zh: '贸易帐' },
    { titles: ['BoJ Interest Rate Decision'], kind: 'rateDecision', zh: '日本央行利率决议' },
  ],

  // 韩国
  KR: [
    { titles: ['BoK Monetary Policy Board Extraordinary Meeting'], kind: 'cbMeeting', zh: '韩国央行货币政策委员会临时会议' },
    { titles: ['US President Trump-China President Xi Meeting'], kind: 'event', zh: '特朗普与习近平会晤' },
  ],

  // 美国
  US: [
    { titles: ['Inflation Rate YoY'], kind: 'cpi', zh: 'CPI 年率' },
    { titles: ['Inflation Rate MoM'], kind: 'cpi', zh: 'CPI 月率' },
    { titles: ['Core Inflation Rate YoY'], kind: 'coreCpi', zh: '核心 CPI 年率' },
    { titles: ['Core Inflation Rate MoM'], kind: 'coreCpi', zh: '核心 CPI 月率' },
    { titles: ['Core PCE Price Index MoM'], kind: 'pce', zh: '核心 PCE 物价指数月率' },
    { titles: ['PPI MoM'], kind: 'ppi', zh: 'PPI 月率' },
    { titles: ['Non Farm Payrolls'], kind: 'nfp', zh: '非农就业人数' },
    // 改名：2026 起标题加 Prel
    {
      titles: ['Non Farm Payrolls Annual Revision', 'Non Farm Payrolls Annual Revision Prel'],
      kind: 'nfp', zh: '非农就业人数年度基准修正初值',
    },
    { titles: ['Unemployment Rate'], kind: 'unemployment', zh: '失业率' },
    { titles: ['JOLTs Job Openings'], kind: 'jobOpenings', zh: 'JOLTs 职位空缺' },
    // 美国公布的是年化季率
    { titles: ['GDP Growth Rate QoQ Adv'], kind: 'gdp', zh: '实际 GDP 年化季率初值' },
    { titles: ['GDP Growth Rate QoQ 2nd Est'], kind: 'gdp', zh: '实际 GDP 年化季率修正值' },
    { titles: ['GDP Growth Rate QoQ Final'], kind: 'gdp', zh: '实际 GDP 年化季率终值' },
    { titles: ['GDP Growth Rate QoQ'], kind: 'gdp', zh: '实际 GDP 年化季率' },
    { titles: ['Retail Sales MoM'], kind: 'retail', zh: '零售销售月率' },
    { titles: ['Michigan Consumer Sentiment Prel'], kind: 'consumerConf', zh: '密歇根大学消费者信心指数初值' },
    { titles: ['ISM Manufacturing PMI'], kind: 'pmi', zh: 'ISM 制造业 PMI' },
    // 改名：2023-05 起 Non-Manufacturing 改叫 Services
    {
      titles: ['ISM Non-Manufacturing PMI', 'ISM Services PMI'],
      kind: 'pmi', zh: 'ISM 服务业 PMI（原非制造业）',
    },
    { titles: ['Markit Composite PMI Flash'], kind: 'pmi', zh: '综合 PMI 初值（Markit）' },
    { titles: ['Durable Goods Orders MoM'], kind: 'durableGoods', zh: '耐用品订单月率' },
    { titles: ['Personal Income MoM'], kind: 'income', zh: '个人收入月率' },
    { titles: ['Personal Spending MoM'], kind: 'spending', zh: '个人支出月率' },
    { titles: ['Balance of Trade'], kind: 'trade', zh: '贸易帐' },
    // 改名：2022-11 起标题加了 Prel
    {
      titles: ['Building Permits', 'Building Permits Prel'],
      kind: 'housing', zh: '营建许可总数初值',
    },
    { titles: ['Housing Starts'], kind: 'housing', zh: '新屋开工总数' },
    { titles: ['Existing Home Sales'], kind: 'housing', zh: '成屋销售总数' },
    { titles: ['Fed Interest Rate Decision'], kind: 'rateDecision', zh: '美联储利率决议' },
    { titles: ['Fed Press Conference'], kind: 'cbMeeting', zh: '美联储新闻发布会' },
    { titles: ['FOMC Minutes'], kind: 'cbMeeting', zh: 'FOMC 会议纪要' },
    { titles: ['FOMC Economic Projections'], kind: 'cbMeeting', zh: 'FOMC 经济预期' },
    { titles: ['Closed-door Fed Emergency Meeting'], kind: 'cbMeeting', zh: '美联储闭门紧急会议' },
    // 主席换人：鲍威尔 → 沃什
    {
      titles: ['Fed Chair Powell Speech', 'Fed Chair Warsh Speech'],
      kind: 'cbSpeech', zh: '美联储主席讲话',
    },
    // 主席换人：鲍威尔 → 沃什
    {
      titles: ['Fed Chair Powell Testimony', 'Fed Chair Warsh Testimony'],
      kind: 'cbSpeech', zh: '美联储主席国会证词',
    },
    { titles: ['Presidential Election'], kind: 'event', zh: '美国总统大选' },
    { titles: ['Inauguration Day'], kind: 'event', zh: '特朗普就职日' },
    { titles: ['Reciprocal Tariff Plan Announcement'], kind: 'event', zh: '对等关税计划公布' },
    { titles: ['President Trump State of the Union Speech'], kind: 'event', zh: '特朗普国情咨文演讲' },
    { titles: ['US President Trump Speech'], kind: 'event', zh: '特朗普讲话' },
    { titles: ['President Biden Speech on Banking System'], kind: 'event', zh: '拜登就银行体系发表讲话' },
  ],
};

// 键 `${country}|${title}`，模块加载时建一次
const BY_KEY = new Map<string, EconEntry>();
for (const [country, entries] of Object.entries(ECON_CATALOG)) {
  for (const entry of entries) {
    for (const title of entry.titles) BY_KEY.set(`${country}|${title}`, entry);
  }
}

/** 国家+标题 → 清单条目；查不到 null（= 没见过，界面打警告） */
export function findEntry(country: string, title: string): EconEntry | null {
  return BY_KEY.get(`${country}|${title}`) ?? null;
}
