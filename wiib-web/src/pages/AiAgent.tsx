import { useTranslation } from 'react-i18next';
import { AgentLangSetting } from '../components/AgentLangSetting';
import { ModelConfig } from '../components/ModelConfig';
import './AiAgent.css';

/**
 * 「配置」页（样式见 AiAgent.css）：agent 提示词语言、模型端点、谁用哪条、后台子 agent。
 * 电脑上铺满两栏（左边端点库，右边另外三节），窄屏居中一列按上面的顺序排。
 * 行为分析在对话里（analyze_my_behavior 工具，见 workbench/BehaviorReportCard）。
 */
export function AiAgent() {
  const { t } = useTranslation('ai');
  return (
    <div className="page-shell">
      <div className="p-ai">
        <div className="pg-h"><h1>{t('tab.config')}</h1></div>
        {/* agent 提示词语言：只在这里改，不随顶栏界面语言变 */}
        <AgentLangSetting />
        <ModelConfig />
      </div>
    </div>
  );
}
