import { createRoot } from 'react-dom/client'
// 字体本地打包（国内不走 Google CDN）：Geist 可变字重 100~900，family 名带 "Variable" 后缀
import '@fontsource-variable/geist'
import '@fontsource-variable/jetbrains-mono'
import './index.css'
// 国际化初始化：必须排在 App 之前，组件首次渲染时词表就得在位
import './i18n'
import App from './App.tsx'
import { ToastProvider } from './components/ui/toast.tsx'

createRoot(document.getElementById('root')!).render(
  <ToastProvider>
    <App />
  </ToastProvider>,
)
