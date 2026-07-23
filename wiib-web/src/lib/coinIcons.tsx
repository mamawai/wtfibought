import type { LucideProps } from 'lucide-react';

// 自定义币种图标（lucide 没有的）：单独成文件，coinConfig 才能满足 react-refresh 的
// "组件与常量导出不同文件" 约束。React 19 直接函数组件，无需 forwardRef。

export const Eth = ({ className, ...rest }: LucideProps) => (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor"
         strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className} {...rest}>
      <path d="M12 2L4.5 12.5 12 16.5l7.5-4L12 2z" />
      <path d="M4.5 12.5L12 22l7.5-9.5L12 16.5 4.5 12.5z" />
    </svg>
);

// DOGE 用真实柴犬+金币 PNG（dogecoin.com 官方 logo），不响应 currentColor
export const Doge = ({ className, ...rest }: LucideProps) => (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" className={className} {...rest}>
      <image href="/coin-icons/doge.png" x="0" y="0" width="24" height="24" preserveAspectRatio="xMidYMid meet" />
    </svg>
);

// SOL 三条错位斜杠（Solana 官方 logo 的描边简化版）
export const Sol = ({ className, ...rest }: LucideProps) => (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor"
         strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className} {...rest}>
      <path d="M7.5 4H21l-3.5 3.5H4L7.5 4z" />
      <path d="M4 10.25h13.5l3.5 3.5H7.5l-3.5-3.5z" />
      <path d="M7.5 16.5H21L17.5 20H4l3.5-3.5z" />
    </svg>
);

// BNB 五枚菱形：中心一枚 + 上下左右各一枚（Binance 官方 logo 的描边简化版）
export const Bnb = ({ className, ...rest }: LucideProps) => (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor"
         strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className} {...rest}>
      <path d="M12 2.8L14.2 5 12 7.2 9.8 5 12 2.8z" />
      <path d="M5 9.8L7.2 12 5 14.2 2.8 12 5 9.8z" />
      <path d="M12 9.8L14.2 12 12 14.2 9.8 12 12 9.8z" />
      <path d="M19 9.8L21.2 12 19 14.2 16.8 12 19 9.8z" />
      <path d="M12 16.8L14.2 19 12 21.2 9.8 19 12 16.8z" />
    </svg>
);

// XRP 上下两道喇叭口曲线组成的 X（Ripple 官方 logo 的描边简化版）
export const Xrp = ({ className, ...rest }: LucideProps) => (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor"
         strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className} {...rest}>
      <path d="M4 4.5h2c1.7 0 3.3.7 4.5 1.9L12 7.8l1.5-1.4C14.7 5.2 16.3 4.5 18 4.5h2" />
      <path d="M4 19.5h2c1.7 0 3.3-.7 4.5-1.9L12 16.2l1.5 1.4c1.2 1.2 2.8 1.9 4.5 1.9h2" />
    </svg>
);
