import { useQuery } from '@tanstack/react-query'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { z } from 'zod'
import { Search, ArrowUpRight } from 'lucide-react'
import { get, params } from '../api/client'
import { experimentSchema, experimentStates, executionSchema, executionStates, pageOf, scenarioSchema, targetSchema, type Execution } from '../api/contracts'
import { Badge, Heading, Panel, QueryState, Empty, Pagination, Fields, Id, date } from '../components/ui'

export function useFilters() {
  const [search, setSearch] = useSearchParams()
  const raw = Number(search.get('page') ?? 0)
  const page = Number.isInteger(raw) && raw >= 0 && raw <= 10000 ? raw : 0
  const update = (key: string, value: string) => setSearch(prev => { const next = new URLSearchParams(prev); if (value) next.set(key, value); else next.delete(key); if (key !== 'page') next.delete('page'); return next })
  return { search, page, update }
}
export function ExecutionTable({ items }: { items: Execution[] }) {
  if (!items.length) return <Empty text="暂无执行记录"/>
  return <div className="table-scroll"><table><thead><tr><th>Execution / Attempt</th><th>状态</th><th>创建时间</th><th>完成时间</th><th><span className="sr-only">详情</span></th></tr></thead><tbody>{items.map(e => <tr key={e.id}><td><Link className="table-title mono" to={`/executions/${e.id}`}>{e.id}</Link><small className="muted">第 {e.attempt} 次执行</small></td><td><Badge value={e.status}/></td><td>{date(e.createdAt)}</td><td>{date(e.finishedAt)}</td><td><Link className="icon-button" aria-label={`查看执行 ${e.id}`} to={`/executions/${e.id}`}><ArrowUpRight size={17}/></Link></td></tr>)}</tbody></table></div>
}
export function Experiments() {
  const { search, page, update } = useFilters()
  const text = search.get('search') ?? '', status = search.get('status') ?? ''
  const query = useQuery({ queryKey: ['experiments', page, text, status], queryFn: ({ signal }) => get(`/api/v1/console/experiments${params({ page, size: 20, search: text, status })}`, pageOf(experimentSchema), signal) })
  return <><Heading eyebrow="EXPERIMENT LIBRARY" title="实验管理" description="查看实验设计与执行历史。此工作区不提供创建、编辑或故障派发。"/>
    <Panel><div className="filters"><label className="search-input"><Search size={17}/><input aria-label="按实验名称搜索" maxLength={100} placeholder="搜索实验名称…" value={text} onChange={e => update('search', e.target.value)}/></label><label>状态<select aria-label="实验状态" value={status} onChange={e => update('status', e.target.value)}><option value="">全部状态</option>{experimentStates.map(s => <option key={s}>{s}</option>)}</select></label></div>
      <QueryState query={query}>{data => <>{data.items.length ? <div className="table-scroll"><table><thead><tr><th>实验 / ID</th><th>假设</th><th>时长</th><th>状态</th><th><span className="sr-only">详情</span></th></tr></thead><tbody>{data.items.map(e => <tr key={e.id}><td><Link className="table-title" to={`/experiments/${e.id}`}>{e.name}</Link><small className="muted mono">{e.id}</small></td><td className="truncate">{e.hypothesis}</td><td>{e.durationSeconds}s</td><td><Badge value={e.status}/></td><td><Link className="icon-button" aria-label={`查看 ${e.name}`} to={`/experiments/${e.id}`}><ArrowUpRight size={17}/></Link></td></tr>)}</tbody></table></div> : <Empty text={text || status ? '没有匹配的实验' : '暂无实验'}/>}<Pagination {...data} onPage={p => update('page', String(p))}/></>}</QueryState>
    </Panel></>
}
export function Executions() {
  const { search, page, update } = useFilters()
  const status = search.get('status') ?? '', experimentId = search.get('experimentId') ?? ''
  const query = useQuery({ queryKey: ['executions', page, status, experimentId], queryFn: ({ signal }) => get(`/api/v1/console/executions${params({ page, size: 20, status, experimentId })}`, pageOf(executionSchema), signal) })
  return <><Heading eyebrow="EXECUTION HISTORY" title="执行记录" description="保留完整执行状态，包括 CREATE_UNCERTAIN 和 ROLLBACK_FAILED。"/>
    <Panel><div className="filters"><label>执行状态<select value={status} onChange={e => update('status', e.target.value)}><option value="">全部状态</option>{executionStates.map(s => <option key={s}>{s}</option>)}</select></label>{experimentId && <span className="muted small">当前实验：<Id value={experimentId}/><button className="subtle-link" onClick={() => update('experimentId', '')}>清除筛选</button></span>}</div><QueryState query={query}>{data => <><ExecutionTable items={data.items}/><Pagination {...data} onPage={p => update('page', String(p))}/></>}</QueryState></Panel></>
}
export function ExperimentDetail() {
  const { id = '' } = useParams()
  const { page, update } = useFilters()
  const query = useQuery({ queryKey: ['experiment', id], queryFn: ({ signal }) => get(`/api/v1/console/experiments/${id}`, experimentSchema, signal) })
  const executions = useQuery({ queryKey: ['executions', id, page], queryFn: ({ signal }) => get(`/api/v1/console/executions${params({ experimentId: id, page, size: 10 })}`, pageOf(executionSchema), signal) })
  const targets = useQuery({ queryKey: ['targets'], queryFn: ({ signal }) => get('/api/v1/targets', z.array(targetSchema), signal) })
  const scenarios = useQuery({ queryKey: ['scenarios'], queryFn: ({ signal }) => get('/api/v1/scenarios', z.array(scenarioSchema), signal) })
  return <><Link className="back-link" to="/experiments">← 返回实验列表</Link><QueryState query={query}>{e => <><Heading eyebrow="EXPERIMENT DETAILS" title={e.name} description={e.hypothesis} action={<Badge value={e.status}/>}/><Panel title="实验设计"><Fields values={[
    ['Experiment ID', <Id value={e.id}/>], ['目标', <>{targets.data?.find(t => t.id === e.targetId)?.name ?? '名称暂不可用'}<br/><Id value={e.targetId}/></>],
    ['场景', <>{scenarios.data?.find(s => s.id === e.scenarioId)?.code ?? '名称暂不可用'}<br/><Id value={e.scenarioId}/></>], ['计划时长', `${e.durationSeconds} 秒`], ['版本', e.version], ['公开参数', <code>{JSON.stringify(e.parameters)}</code>],
  ]}/><Link className="subtle-link" to={`/audit?experimentId=${e.id}`}>查看此实验审计 →</Link></Panel><Panel title="关联执行" subtitle="按执行创建时间倒序"><QueryState query={executions}>{data => <><ExecutionTable items={data.items}/><Pagination {...data} onPage={p => update('page', String(p))}/></>}</QueryState></Panel></>}</QueryState></>
}
