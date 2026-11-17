import { useNavigate } from 'react-router-dom';
import { useTranslation, Trans } from 'react-i18next';
import { Layers, Target, TriangleAlert, Wallet } from 'lucide-react';
import './Intro.css';

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
  return <li><Trans ns="home" i18nKey={i18nKey} components={[<b key="term" />]} /></li>;
}

/** 玩法说明：三种交易各一块，底下是双钱包、量化策略、预测市场，最后风险声明；两颗按钮浮在底边 */
export function Intro() {
  const navigate = useNavigate();
  const { t } = useTranslation('home');
  const goHome = () => navigate('/', { replace: true });

  return (
    <div className="page-shell p-in">
      <div className="pg-h">
        <div>
          <h1>{t('intro.title')}</h1>
          <p className="lead">{t('intro.welcomeDesc')}</p>
          <div className="warnl"><TriangleAlert className="ic" />{t('intro.welcomeWarn')}</div>
        </div>
      </div>

      <section className="sec">
        <div className="sec-h"><h2>{t('intro.rulesTitle')}</h2></div>
        <div className="rules">
          <div className="blk rule">
            <span className="n nf">01</span>
            <h3>{t('intro.bstockTitle')}</h3>
            <ul>
              <li>{t('intro.bstock1')}</li>
              <li>{t('intro.bstock2')}</li>
              <li>{t('intro.bstock3')}</li>
            </ul>
          </div>
          <div className="blk rule">
            <span className="n nf">02</span>
            <h3>{t('intro.cryptoTitle')}</h3>
            <ul>
              <li>{t('intro.crypto1')}</li>
              <li>{t('intro.crypto2')}</li>
              <li>{t('intro.crypto3')}</li>
            </ul>
          </div>
          <div className="blk rule">
            <span className="n nf">03</span>
            <h3>{t('intro.futuresTitle')}</h3>
            <ul>
              <TermItem i18nKey="intro.futures1" />
              <TermItem i18nKey="intro.futures2" />
              <TermItem i18nKey="intro.futures3" />
              <TermItem i18nKey="intro.futures4" />
              <TermItem i18nKey="intro.futures5" />
              <li>{t('intro.futures6')}</li>
            </ul>
          </div>
        </div>
      </section>

      <section className="sec">
        <div className="more">
          <div><h3><Wallet className="ic" />{t('intro.walletTitle')}</h3><p>{t('intro.walletDesc')}</p></div>
          <div><h3><Layers className="ic" />{t('intro.quantTitle')}</h3><p>{t('intro.quantDesc')}</p></div>
          <div>
            <h3><Target className="ic" />{t('intro.perksTitle')}</h3>
            <p><Trans ns="home" i18nKey="intro.perk1" components={[<b key="term" />]} /></p>
          </div>
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
