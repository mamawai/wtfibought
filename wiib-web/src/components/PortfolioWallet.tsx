import { useMemo } from 'react';
import styled from 'styled-components';

export interface WalletAsset {
  name: string;
  count: { label: string; value: number }[];
  value: number;
  profit: number;
  bg: string;
}

interface Props {
  totalAssets: number;
  balance: number;
  username: string;
  assets: WalletAsset[];
  ready?: boolean;
}

function fmt(n: number) {
  return n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function compact(n: number): string {
  const abs = Math.abs(n);
  if (abs >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (abs >= 10_000) return `${Math.round(n / 1_000)}K`;
  if (abs >= 1_000) return `${(n / 1_000).toFixed(1)}K`;
  return n.toFixed(0);
}

export function PortfolioWallet({ totalAssets, balance, username, assets, ready = true }: Props) {
  const cards = useMemo(() => {
    type C = {
      key: string; name: string; label: string;
      detail: string | { label: string; value: number }[];
      masked: string; full: string; sub?: string;
      bg: string; isDark?: boolean;
    };

    const balanceCard: C = {
      key: 'balance',
      name: username,
      label: 'Balance',
      detail: 'USDT',
      masked: `≈ ${compact(balance)}`,
      full: fmt(balance),
      bg: '#ffffff',
      isDark: true,
    };

    const assetCards: C[] = assets.slice(0, 1).map(a => ({
      key: a.name,
      name: a.name,
      label: '总持仓',
      detail: a.count,
      masked: `≈ ${compact(a.value)}`,
      full: fmt(a.value),
      sub: `${a.profit >= 0 ? '+' : ''}${fmt(a.profit)}`,
      bg: a.bg,
    }));

    return [...assetCards, balanceCard];
  }, [assets, balance, username]);

  const count = cards.length;
  const stackGap = Math.min(25, Math.max(15, Math.floor(75 / Math.max(count - 1, 1))));
  const spreadGap = Math.max(35, Math.floor(140 / Math.max(count, 1)));
  const walletH = Math.max(250, 160 + 40 + (count - 1) * stackGap);
  const backH = walletH - 30;

  return (
    <Wrapper
      style={{ '--wallet-h': `${walletH}px`, '--back-h': `${backH}px` } as React.CSSProperties}
    >
      <div className="wallet">
        <div className="wallet-back" />
        {cards.map((c, i) => {
          const fromFront = count - 1 - i;
          const bottom = 40 + fromFront * stackGap;
          const spreadY = -(10 + fromFront * spreadGap);
          const rotate = i === count - 1 ? 0 : (i % 2 === 0 ? -3 : 2);
          const isBalance = c.key === 'balance';
          const shouldAnimate = isBalance || ready;
          const animDelay = isBalance ? 0.1 : 0.2 + i * 0.12;

          return (
            <div
              key={c.key}
              className="card"
              style={{
                bottom,
                zIndex: 10 + i * 10,
                animationDelay: `${animDelay}s`,
                background: c.bg,
                color: c.isDark ? '#1e293b' : '#fff',
                '--spread-y': `${spreadY}px`,
                '--spread-r': `${rotate}deg`,
                ...(shouldAnimate ? {} : { animationDuration: '0s', animationIterationCount: '0', opacity: 0 }),
              } as React.CSSProperties}
            >
              <div className="card-inner">
                <div className="card-top">
                  <span>{c.name}</span>
                  <div
                    className="chip"
                    style={c.isDark ? { background: 'rgba(0,0,0,0.05)', borderColor: 'rgba(0,0,0,0.1)' } : undefined}
                  />
                </div>
                <div className="card-bottom">
                  <div className="card-info" style={Array.isArray(c.detail) ? { width: '100%' } : undefined}>
                    <span className="label" style={c.isDark ? { color: '#64748b' } : undefined}>{c.label}</span>
                    {Array.isArray(c.detail) ? (
                      <div style={{ display: 'flex', flexDirection: 'column', gap: 4, marginTop: 2 }}>
                        {c.detail.map(item => (
                          <div key={item.label} style={{ display: 'flex', justifyContent: 'space-between', fontSize: 11, opacity: 0.9 }}>
                            <span style={{ opacity: 0.7 }}>{item.label}</span>
                            <span style={{ fontWeight: 600, fontFamily: 'monospace' }}>{fmt(item.value)}</span>
                          </div>
                        ))}
                      </div>
                    ) : (
                      <span className="value">{c.detail}</span>
                    )}
                  </div>
                  {!Array.isArray(c.detail) && (
                    <div className="card-number-wrapper">
                      <span className="hidden-stars">{c.masked}</span>
                      <span className="card-number">
                        {c.full}
                        {c.sub && (<><br /><span className="card-sub">{c.sub}</span></>)}
                      </span>
                    </div>
                  )}
                </div>
              </div>
            </div>
          );
        })}
        <div className="pocket">
          <svg className="pocket-svg" viewBox="0 0 280 160" fill="none">
            <path
              d="M 0 20 C 0 10, 5 10, 10 10 C 20 10, 25 25, 40 25 L 240 25 C 255 25, 260 10, 270 10 C 275 10, 280 10, 280 20 L 280 120 C 280 155, 260 160, 240 160 L 40 160 C 20 160, 0 155, 0 120 Z"
              fill="#1e341e"
            />
            <path
              d="M 8 22 C 8 16, 12 16, 15 16 C 23 16, 27 29, 40 29 L 240 29 C 253 29, 257 16, 265 16 C 268 16, 272 16, 272 22 L 272 120 C 272 150, 255 152, 240 152 L 40 152 C 25 152, 8 152, 8 120 Z"
              stroke="#3d5635"
              strokeWidth="1.5"
              strokeDasharray="6 4"
            />
          </svg>
          <div className="pocket-content">
            <div style={{ position: 'relative', height: 24, width: '100%' }}>
              <div className="balance-stars">******</div>
              <div className="balance-real">{fmt(totalAssets)}</div>
            </div>
            <div style={{ color: '#698263', fontSize: 12, fontWeight: 500 }}>总资产</div>
            <div className="eye-icon-wrapper">
              <svg
                className="eye-icon eye-slash"
                width={20} height={20} viewBox="0 0 24 24"
                fill="none" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round"
              >
                <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" />
                <circle cx={12} cy={12} r={3} />
                <line x1={3} y1={3} x2={21} y2={21} />
              </svg>
              <svg
                className="eye-icon eye-open"
                style={{ opacity: 0 }}
                width={20} height={20} viewBox="0 0 24 24"
                fill="none" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round"
              >
                <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" />
                <circle cx={12} cy={12} r={3} />
              </svg>
            </div>
          </div>
        </div>
      </div>
    </Wrapper>
  );
}

const Wrapper = styled.div`
  display: flex;
  justify-content: center;

  .wallet {
    position: relative;
    width: 280px;
    height: var(--wallet-h, 230px);
    cursor: pointer;
    perspective: 1000px;
    display: flex;
    justify-content: center;
    align-items: flex-end;
    transition: transform 0.4s ease;
  }

  @keyframes slideIntoPocket {
    0% { transform: translateY(-100px); opacity: 0; }
    100% { transform: translateY(0); opacity: 1; }
  }

  .wallet-back {
    position: absolute;
    bottom: 0;
    width: 280px;
    height: var(--back-h, 200px);
    background: #1e341e;
    border-radius: 22px 22px 60px 60px;
    z-index: 5;
    box-shadow:
      inset 0 25px 35px rgba(0, 0, 0, 0.4),
      inset 0 5px 15px rgba(0, 0, 0, 0.5);
  }

  .card {
    position: absolute;
    width: 260px;
    height: 160px;
    left: 10px;
    border-radius: 16px;
    padding: 18px;
    color: white;
    box-shadow:
      inset 0 1px 1px rgba(255, 255, 255, 0.3),
      0 -4px 15px rgba(0, 0, 0, 0.1);
    transition:
      transform 0.6s cubic-bezier(0.34, 1.56, 0.64, 1),
      z-index 0s;
    animation: slideIntoPocket 0.8s cubic-bezier(0.2, 0.8, 0.2, 1) backwards;
  }

  .card-inner { display: flex; flex-direction: column; justify-content: space-between; height: 100%; }
  .card-top { display: flex; justify-content: space-between; align-items: center; font-size: 14px; text-transform: uppercase; letter-spacing: 1px; }
  .chip { width: 32px; height: 24px; background: rgba(255, 255, 255, 0.2); border-radius: 4px; border: 1px solid rgba(255, 255, 255, 0.1); }
  .card-bottom { display: flex; justify-content: space-between; align-items: flex-end; }
  .label { font-size: 8px; opacity: 0.7; text-transform: uppercase; margin-bottom: 2px; display: block; }
  .value { font-size: 10px; font-weight: 500; }
  .card-number-wrapper { text-align: right; }
  .hidden-stars { font-size: 15px; letter-spacing: 2px; font-weight: 600; }
  .card-number { display: none; font-size: 14px; letter-spacing: 1px; font-family: monospace; }
  .card-sub { font-size: 10px; opacity: 0.7; letter-spacing: 0; }

  .pocket {
    position: absolute;
    bottom: 0;
    width: 280px;
    height: 160px;
    z-index: 40;
    filter: drop-shadow(0 15px 25px rgba(20, 40, 20, 0.4));
  }

  .pocket-content {
    position: absolute;
    top: 45px;
    width: 100%;
    text-align: center;
    z-index: 50;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 8px;
  }

  .balance-stars { color: #839e7b; font-size: 24px; letter-spacing: 4px; transition: 0.3s; }
  .balance-real {
    color: #a7c59e; font-size: 22px; font-weight: 600;
    opacity: 0; position: absolute; top: 0; left: 50%;
    transform: translate(-50%, 10px); transition: 0.3s;
  }

  .eye-icon-wrapper { margin-top: 8px; height: 20px; width: 20px; position: relative; opacity: 0.3; transition: 0.3s; }
  .eye-icon { position: absolute; top: 0; left: 0; stroke: #3be60b; transition: 0.3s; }

  .wallet:hover { transform: translateY(-5px); }
  .wallet:hover .eye-icon-wrapper { opacity: 1; }

  .wallet:hover .card {
    transform: translateY(var(--spread-y)) rotate(var(--spread-r));
  }

  .card:hover { z-index: 100 !important; transition-delay: 0s !important; }
  .wallet:hover .card:hover {
    transform: translateY(var(--spread-y)) scale(1.05) rotate(0deg);
  }

  .card:hover .hidden-stars { display: none; }
  .card:hover .card-number { display: block; }

  .wallet:hover .balance-stars { opacity: 0; }
  .wallet:hover .balance-real { opacity: 1; transform: translate(-50%, 0); }
  .wallet:hover .eye-slash { opacity: 0; transform: scale(0.5); }
  .wallet:hover .eye-open { opacity: 1; transform: scale(1.1); }
`;
