import { useTranslation } from 'react-i18next';
import type { CategoryAverages } from '../types';

interface Props {
  userData: CategoryAverages;
}

// 四分类能力轴：与后端 CategoryAveragesDTO 一一对应。表里存词表 key，渲染时现查——
// 存成文案会在模块加载那一刻定死，切语言不跟着变
const AXES: { labelKey: string; key: keyof CategoryAverages }[] = [
  { labelKey: 'radar.crypto', key: 'cryptoProfit' },
  { labelKey: 'radar.commodity', key: 'commodityProfit' },
  { labelKey: 'radar.bstock', key: 'bstockProfit' },
  { labelKey: 'radar.prediction', key: 'predictionProfit' },
];

/** 画布 340×280，中心和半径；四根轴朝上、右、下、左 */
const CX = 170;
const CY = 140;
const R = 104;
const DIRS = [[0, -1], [1, 0], [0, 1], [-1, 0]];
/** 轴名的对齐方式和离轴端的偏移 */
const ANCHOR = ['middle', 'start', 'middle', 'end'] as const;
const OFF = [[0, -12], [10, 4], [0, 20], [-10, 4]];

const poly = (k: number) => DIRS.map(([dx, dy]) => `${CX + dx * R * k},${CY + dy * R * k}`).join(' ');

/**
 * 持仓页「能力」面板：四根轴的雷达 + 右边四项读数（样式见 pages/Portfolio.css .rad）。
 * 值是这一类胜过百分之多少的其他用户。
 */
export function RadarChart({ userData }: Props) {
  const { t } = useTranslation('strategy');
  const values = AXES.map(a => Number(userData[a.key] || 0));
  const avg = Math.round(values.reduce((s, v) => s + v, 0) / values.length);
  const pts = values.map((v, i) => [CX + DIRS[i][0] * R * v / 100, CY + DIRS[i][1] * R * v / 100]);

  return (
    <div className="rad">
      <svg viewBox="0 0 340 280" role="img" aria-label={t('radar.betterThan', { pct: avg })}>
        {/* 一圈浅灰一圈白，从外往里叠 */}
        {[1, 0.8, 0.6, 0.4, 0.2].map((k, i) => <polygon key={k} className={i % 2 ? 'rg' : 'rg a'} points={poly(k)} />)}
        {DIRS.map(([dx, dy], i) => <line key={i} className="ax" x1={CX} y1={CY} x2={CX + dx * R} y2={CY + dy * R} />)}
        <polygon className="shape" points={pts.map(p => p.join(',')).join(' ')} />
        {pts.map(([px, py], i) => <circle key={i} className="pt" cx={px} cy={py} r={3.5} />)}
        {AXES.map((a, i) => (
          <text key={a.key} x={CX + DIRS[i][0] * R + OFF[i][0]} y={CY + DIRS[i][1] * R + OFF[i][1]} textAnchor={ANCHOR[i]}>
            {t(a.labelKey)}
          </text>
        ))}
      </svg>
      <div>
        <div className="sum"><b>{avg}%</b>{t('radar.betterThan', { pct: avg })}</div>
        <div className="kv">
          {AXES.map((a, i) => (
            <div key={a.key}>
              <span>{t(a.labelKey)}</span>
              <b className={values[i] >= 50 ? 'up' : 'mute'}>{values[i].toFixed(0)}<small>%</small></b>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
