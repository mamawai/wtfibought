import { createRoot } from 'react-dom/client'
// 字体本地打包（国内不走 Google CDN）：变量字重全档，family 名带 "Variable" 后缀
import '@fontsource-variable/plus-jakarta-sans'
import '@fontsource-variable/jetbrains-mono'
import './index.css'
import App from './App.tsx'
import { ToastProvider } from './components/ui/toast.tsx'

createRoot(document.getElementById('root')!).render(
  <ToastProvider>
    <App />
  </ToastProvider>,
)
