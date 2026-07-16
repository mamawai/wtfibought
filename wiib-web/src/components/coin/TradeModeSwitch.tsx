import { Link } from 'react-router-dom';
import { Flame } from 'lucide-react';

/** 合约/现货模式切换 + 爆仓入口（现货/合约两个交易面板头部共用） */
export function TradeModeSwitch({ mode, futuresOnly, onModeChange }: {
  mode: 'spot' | 'futures';
  futuresOnly?: boolean;
  onModeChange: (m: 'spot' | 'futures') => void;
}) {
  return (
    <>
      <div className="flex rounded-xl bg-card overflow-hidden neu-raised">
        <button onClick={() => onModeChange('futures')} className={`px-5 py-2.5 text-xs font-bold ${futuresOnly ? '' : 'border-r border-border'} transition-colors ${mode === 'futures' ? 'bg-foreground text-background' : 'bg-card text-foreground hover:bg-surface-hover'}`}>合约</button>
        {/* 纯合约标的（黄金/原油）无现货，藏掉现货切换 */}
        {!futuresOnly && (
          <button onClick={() => onModeChange('spot')} className={`px-5 py-2.5 text-xs font-bold transition-colors ${mode === 'spot' ? 'bg-foreground text-background' : 'bg-card text-foreground hover:bg-surface-hover'}`}>现货</button>
        )}
      </div>
      <Link
        to="/force-orders"
        className="px-3 sm:px-4 py-2 sm:py-2.5 rounded-xl bg-red-500/8 neu-raised text-xs font-black text-red-500 border border-red-500/15 hover:bg-red-500/12 transition-colors flex items-center gap-1.5"
      >
        <Flame className="w-3.5 h-3.5" />
        爆仓
      </Link>
    </>
  );
}
