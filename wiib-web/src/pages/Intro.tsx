import { useNavigate } from 'react-router-dom';
import { useTranslation, Trans } from 'react-i18next';
import { Card, CardContent } from '../components/ui/card';
import { Button } from '../components/ui/button';
import { Bell } from 'lucide-react';

const HIDE_NOTICE_KEY = 'wiib-notice-hide-date';
const NOTICE_SEEN_KEY = 'wiib-notice-seen';
function hideNoticeToday() { localStorage.setItem(HIDE_NOTICE_KEY, new Date().toDateString()); }
// 「我知道了」= 看过了，以后不再自动拦。必须落标记，否则回首页又被 Home 弹回来，死循环
function markNoticeSeen() { localStorage.setItem(NOTICE_SEEN_KEY, '1'); }

/**
 * 「术语：说明」式条目：术语加粗，整句连同分隔符都留在词表里。
 * 拆成 t('术语') + '：' + t('说明') 会把中文全角冒号钉死在英文界面上。
 */
function TermItem({ i18nKey }: { i18nKey: string }) {
  return <li><Trans ns="home" i18nKey={i18nKey} components={[<strong key="term" />]} /></li>;
}

export function Intro() {
  const navigate = useNavigate();
  const { t } = useTranslation('home');
  const goHome = () => navigate('/', { replace: true });

  return (
    <div className="page-shell px-4 md:px-6 py-6 pb-36 lg:pb-24 space-y-5">

      <h1 className="text-2xl font-extrabold flex items-center gap-2">
        <Bell className="w-6 h-6 text-primary" />
        {t('intro.title')}
      </h1>

      {/* 欢迎 + 风险提示 */}
      <Card>
        <CardContent className="pt-5 space-y-3 text-sm leading-relaxed">
          <h2 className="font-bold text-base text-primary">{t('intro.welcomeTitle')}</h2>
          <p className="text-muted-foreground">{t('intro.welcomeDesc')}</p>
          <div className="bg-primary/8 p-3 rounded-xl border-2 border-primary/20">
            <p className="text-primary/80 text-xs font-medium">{t('intro.welcomeWarn')}</p>
          </div>
        </CardContent>
      </Card>

      {/* 桌面双列：左交易（重点），右预测；移动端自然单列 */}
      <div className="grid md:grid-cols-2 gap-5 items-start">
      {/* 交易规则 */}
      <Card>
        <CardContent className="pt-5 space-y-4 text-sm leading-relaxed">
          <h2 className="font-bold text-base text-primary">{t('intro.rulesTitle')}</h2>

          <section>
            <h3 className="font-bold mb-1">{t('intro.bstockTitle')}</h3>
            <ul className="list-disc list-inside text-muted-foreground space-y-1">
              <li>{t('intro.bstock1')}</li>
              <li>{t('intro.bstock2')}</li>
              <li>{t('intro.bstock3')}</li>
            </ul>
          </section>

          <section>
            <h3 className="font-bold mb-1">{t('intro.cryptoTitle')}</h3>
            <ul className="list-disc list-inside text-muted-foreground space-y-1">
              <li>{t('intro.crypto1')}</li>
              <li>{t('intro.crypto2')}</li>
              <li>{t('intro.crypto3')}</li>
            </ul>
          </section>

          <section>
            <h3 className="font-bold mb-1">{t('intro.futuresTitle')}</h3>
            <ul className="list-disc list-inside text-muted-foreground space-y-1">
              <TermItem i18nKey="intro.futures1" />
              <TermItem i18nKey="intro.futures2" />
              <TermItem i18nKey="intro.futures3" />
              <TermItem i18nKey="intro.futures4" />
              <TermItem i18nKey="intro.futures5" />
              <li>{t('intro.futures6')}</li>
            </ul>
          </section>

          <section>
            <h3 className="font-bold mb-1">{t('intro.walletTitle')}</h3>
            <p className="text-muted-foreground">{t('intro.walletDesc')}</p>
          </section>

          <section>
            <h3 className="font-bold mb-1">{t('intro.quantTitle')}</h3>
            <p className="text-muted-foreground">{t('intro.quantDesc')}</p>
          </section>
        </CardContent>
      </Card>

      {/* 预测市场 */}
      <Card>
        <CardContent className="pt-5 space-y-3 text-sm leading-relaxed">
          <h2 className="font-bold text-base text-primary">{t('intro.perksTitle')}</h2>
          <p className="text-muted-foreground">
            <Trans ns="home" i18nKey="intro.perk1" components={[<strong key="term" />]} />
          </p>
        </CardContent>
      </Card>
      </div>

      {/* 风险声明 */}
      <div className="bg-red-500/10 border-2 border-red-500/20 rounded-2xl p-4 text-xs text-red-500 dark:text-red-400 font-medium leading-relaxed">
        {t('intro.riskNotice')}
      </div>

      {/* sticky 底部按钮；手机上叠在底部悬浮 Tab 上方，横屏时左右让开灵动岛 */}
      <div className="fixed left-[env(safe-area-inset-left)] right-[env(safe-area-inset-right)] bottom-[calc(var(--tabbar-space)+0.75rem)] lg:bottom-6 px-4 md:px-6 z-50">
        <div className="max-w-2xl mx-auto flex gap-3">
          <Button variant="outline" className="flex-1" onClick={() => { hideNoticeToday(); goHome(); }}>{t('intro.hideToday')}</Button>
          <Button className="flex-1" onClick={() => { markNoticeSeen(); goHome(); }}>{t('intro.gotIt')}</Button>
        </div>
      </div>
    </div>
  );
}
