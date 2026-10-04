import type { ComponentType } from 'react';
import type { LucideProps } from 'lucide-react';
import { Mu, Skhynix, Sndk, Soxl, Spcx } from './stockIcons';

/**
 * TradFi 永续合约的图标：公司官方 logo + 右上角一颗圆形 PF 角标（PF = 永续合约）。
 * 角标画在同一个 svg 里，图标走到哪（行情行、交易页页头、点行过渡、持仓）角标就跟到哪。
 * 角标填墨、字用底色，外圈一道底色描边把它跟 logo 隔开，亮暗主题自动反过来
 */
function PerpIcon({ logo: Logo, className, ...rest }: LucideProps & { logo: ComponentType<LucideProps> }) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" className={className} {...rest}>
      {/* logo 缩到 21 靠左下，右上角让给角标，角标压一点在 logo 上 */}
      <Logo x="0" y="3" width="21" height="21" />
      <circle cx="18.6" cy="5.4" r="5.4" fill="var(--color-foreground)" stroke="var(--color-background)" strokeWidth="1.1" />
      <text x="18.6" y="5.6" textAnchor="middle" dominantBaseline="central" fill="var(--color-background)"
            fontSize="5.4" fontWeight="800" letterSpacing="-.2" style={{ fontFamily: 'var(--font-sans)' }}>PF</text>
    </svg>
  );
}

export const SndkPerp = (p: LucideProps) => <PerpIcon logo={Sndk} {...p} />;
export const SoxlPerp = (p: LucideProps) => <PerpIcon logo={Soxl} {...p} />;
export const SkhynixPerp = (p: LucideProps) => <PerpIcon logo={Skhynix} {...p} />;
export const MuPerp = (p: LucideProps) => <PerpIcon logo={Mu} {...p} />;
// KORU（韩国 3 倍做多 ETF）跟 SOXL 同是 Direxion 发行，用同一个 Direxion 的 x
export const KoruPerp = (p: LucideProps) => <PerpIcon logo={Soxl} {...p} />;
export const SpcxPerp = (p: LucideProps) => <PerpIcon logo={Spcx} {...p} />;
