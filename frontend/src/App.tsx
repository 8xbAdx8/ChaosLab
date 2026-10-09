import { useState } from 'react'
import { Link, NavLink, Route, Routes, useLocation } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import { Activity, LayoutDashboard, FlaskConical, ListChecks, ScrollText, FileBarChart, Sun, Moon, RefreshCw, LockKeyhole, Menu, X } from 'lucide-react'
import { Dashboard } from './pages/Dashboard'
import { Experiments, ExperimentDetail, Executions } from './pages/Experiments'
import { ExecutionDetail } from './pages/ExecutionDetail'
import { Audits } from './pages/Audits'
import { Reports, ReportDetail } from './pages/Reports'
import { Empty } from './components/ui'

const navigation = [
  { to: '/', text: '概览', icon: LayoutDashboard, end: true },
  { to: '/experiments', text: '实验管理', icon: FlaskConical },
  { to: '/executions', text: '执行记录', icon: ListChecks },
  { to: '/audit', text: '审计日志', icon: ScrollText },
  { to: '/reports', text: '实验报告', icon: FileBarChart },
]
export function App() {
  const queryClient = useQueryClient()
  const [theme, setTheme] = useState(() => {
    const stored = localStorage.getItem('chaoslab-theme')
    return stored === 'light' || stored === 'dark' ? stored : window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark'
  })
  const [menu, setMenu] = useState(false)
  const location = useLocation()
  const toggleTheme = () => { const next = theme === 'dark' ? 'light' : 'dark'; setTheme(next); localStorage.setItem('chaoslab-theme', next) }
  return <div className={`app ${theme}`}>
    <a className="skip-link" href="#main">跳转到主要内容</a>
    <aside className={`sidebar ${menu ? 'open' : ''}`}>
      <Link to="/" className="brand" onClick={() => setMenu(false)}><span className="brand-symbol"><Activity size={22}/></span><span>ChaosLab<small>ENGINEERING CONSOLE</small></span></Link>
      <div className="workspace-label"><span className="status-dot"/>READ-ONLY WORKSPACE</div>
      <nav aria-label="主导航">{navigation.map(item => <NavLink key={item.to} to={item.to} end={item.end} onClick={() => setMenu(false)} className={({ isActive }) => `nav-item ${isActive ? 'selected' : ''}`}><item.icon size={18}/>{item.text}</NavLink>)}</nav>
      <div className="sidebar-footer"><LockKeyhole size={18}/><div>观测，不改变现场<p>不开放故障执行或权限操作</p></div></div>
    </aside>
    {menu && <button aria-label="关闭导航遮罩" className="nav-backdrop" onClick={() => setMenu(false)}/>}
    <div className="workspace">
      <div className="topbar"><div className="topbar-left"><button className="icon-button mobile-toggle" aria-label={menu ? '关闭导航' : '打开导航'} onClick={() => setMenu(!menu)}>{menu ? <X size={20}/> : <Menu size={20}/>}</button><span className="muted">Workspace</span><span className="divider">/</span><span>{navigation.find(n => n.to === '/' ? location.pathname === '/' : location.pathname.startsWith(n.to))?.text ?? '详情'}</span></div><div className="topbar-actions"><span className="read-only"><LockKeyhole size={13}/>只读控制台</span><button className="icon-button" aria-label="刷新当前数据" onClick={() => void queryClient.invalidateQueries()}><RefreshCw size={17}/></button><button className="icon-button" aria-label={`切换${theme === 'dark' ? '浅色' : '深色'}主题`} onClick={toggleTheme}>{theme === 'dark' ? <Sun size={18}/> : <Moon size={18}/>}</button></div></div>
      <main id="main"><Routes>
        <Route path="/" element={<Dashboard/>}/><Route path="/experiments" element={<Experiments/>}/><Route path="/experiments/:id" element={<ExperimentDetail/>}/>
        <Route path="/executions" element={<Executions/>}/><Route path="/executions/:id" element={<ExecutionDetail/>}/><Route path="/audit" element={<Audits/>}/>
        <Route path="/reports" element={<Reports/>}/><Route path="/reports/:id" element={<ReportDetail/>}/><Route path="*" element={<Empty text="页面不存在" detail="请通过左侧导航返回控制台。"/>}/>
      </Routes></main>
      <footer className="workspace-footer"><span>CHAOSLAB / OBSERVABILITY FIRST</span><span>历史证据只读 · 不声明恢复因果归属</span></footer>
    </div>
  </div>
}
