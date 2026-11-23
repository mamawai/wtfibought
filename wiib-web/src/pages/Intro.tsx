import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Flame, Scale, TriangleAlert, Wallet } from 'lucide-react';
import { useLangToggle } from '../hooks/useLangToggle';
import './Intro.css';

const HIDE_NOTICE_KEY = 'wiib-notice-hide-date';
const NOTICE_SEEN_KEY = 'wiib-notice-seen';
function hideNoticeToday() { localStorage.setItem(HIDE_NOTICE_KEY, new Date().toDateString()); }
// 「我知道了」= 看过了，以后不再自动拦。必须落标记，否则回首页又被 Home 弹回来，死循环
function markNoticeSeen() { localStorage.setItem(NOTICE_SEEN_KEY, '1'); }

/** 引言：一句话说这是什么，能做什么三块，先知道的三件事，最后风险声明；两颗按钮浮在底边。手机上整屏盖住，一屏看完 */
export function Intro() {
  const navigate = useNavigate();
  const { t } = useTranslation('home');
  const { toggle: toggleLang } = useLangToggle();
  const goHome = () => navigate('/', { replace: true });

  return (
    <div className="page-shell p-in">
      {/* 手机上顶栏被盖住，语言钮搬到这里；桌面不显示 */}
      <button type="button" className="lang" onClick={toggleLang} aria-label={t('common:language.switch')}>中 / EN</button>
      <div className="pg-h">
        <div>
          <h1>{t('intro.title')}</h1>
          <p className="lead">{t('intro.lead')}</p>
          <div className="warnl"><TriangleAlert className="ic" />{t('intro.warn')}</div>
        </div>
      </div>

      <section className="sec">
        <div className="sec-h"><h2>{t('intro.doTitle')}</h2></div>
        <div className="rules">
          <div className="blk rule">
            <span className="n nf">01</span>
            <h3>{t('intro.manualTitle')}</h3>
            <p>{t('intro.manualDesc')}</p>
          </div>
          <div className="blk rule">
            <span className="n nf">02</span>
            <h3>{t('intro.aiTitle')}</h3>
            <p>{t('intro.aiDesc')}</p>
          </div>
          <div className="blk rule">
            <span className="n nf">03</span>
            <h3>{t('intro.learnTitle')}</h3>
            <p>{t('intro.learnDesc')}</p>
          </div>
        </div>
      </section>

      <section className="sec">
        <div className="sec-h"><h2>{t('intro.knowTitle')}</h2></div>
        <div className="more">
          <div><h3><Wallet className="ic" />{t('intro.walletTitle')}</h3><p>{t('intro.walletDesc')}</p></div>
          <div><h3><Flame className="ic" />{t('intro.liqTitle')}</h3><p>{t('intro.liqDesc')}</p></div>
          <div><h3><Scale className="ic" />{t('intro.realTitle')}</h3><p>{t('intro.realDesc')}</p></div>
        </div>
      </section>

      <div className="ok">
        <button type="button" className="btn" onClick={() => { hideNoticeToday(); goHome(); }}>{t('intro.hideToday')}</button>
        <button type="button" className="btn ink" onClick={() => { markNoticeSeen(); goHome(); }}>{t('intro.gotIt')}</button>
      </div>
      <p className="risk">{t('intro.riskNotice')}</p>
    </div>
  );
}
