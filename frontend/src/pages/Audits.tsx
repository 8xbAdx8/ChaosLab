import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { get, params } from '../api/client'
import { pageOf, auditSchema, operations } from '../api/contracts'
import { Badge, Heading, Panel, QueryState, Empty, Pagination, date, Id } from '../components/ui'
import { useFilters } from './Experiments'

export function Audits() {
  const { search, page, update } = useFilters()
  const experimentId = search.get('experimentId') ?? '', operation = search.get('operation') ?? '', result = search.get('result') ?? ''
  const from = search.get('from') ?? '', to = search.get('to') ?? ''
  const query = useQuery({ queryKey: ['audits', page, experimentId, operation, result, from, to], queryFn: ({ signal }) => get(`/api/v1/console/audit-logs${params({ page, size: 20, experimentId, operation, result, from: from ? new Date(from).toISOString() : '', to: to ? new Date(to).toISOString() : '' })}`, pageOf(auditSchema), signal) })
  return <><Heading eyebrow="AUDIT TRAIL" title="审计日志" description="查询公开审计字段。原始 root 输出、路径、任意参数和来源 IP 不在此界面暴露。"/>
    <Panel><div className="filters audit-filters"><label>Experiment ID<input aria-label="审计实验 ID" placeholder="完整 UUID" value={experimentId} onChange={e => update('experimentId', e.target.value)}/></label><label>操作<select value={operation} onChange={e => update('operation', e.target.value)}><option value="">全部操作</option>{operations.map(op => <option key={op}>{op}</option>)}</select></label><label>结果<select value={result} onChange={e => update('result', e.target.value)}><option value="">全部结果</option>{['SUCCESS', 'REJECTED', 'FAILED'].map(r => <option key={r}>{r}</option>)}</select></label><label>起始时间（本地）<input type="datetime-local" value={from} onChange={e => update('from', e.target.value)}/></label><label>结束时间（本地）<input type="datetime-local" value={to} onChange={e => update('to', e.target.value)}/></label><button className="button" onClick={() => { for (const key of ['experimentId', 'operation', 'result', 'from', 'to']) update(key, '') }}>清空筛选</button></div>
      <QueryState query={query}>{data => <>{!data.items.length ? <Empty text="暂无匹配的审计记录"/> : <div className="audit-list">{data.items.map(a => <article className="audit-item" key={a.id}><div className="audit-header"><div><span className="mono">{a.operation}</span><small className="muted">{date(a.occurredAt)} · {a.actor}</small></div><Badge value={a.result}/></div><div className="audit-identity"><span>Audit <Id value={a.id}/></span>{a.experimentId && <Link className="subtle-link" to={`/experiments/${a.experimentId}`}>实验详情 →</Link>}{a.executionId && <Link className="subtle-link" to={`/executions/${a.executionId}`}>执行详情 →</Link>}</div>{a.failureCode && <p className="diagnostic">{a.failureCode}</p>}<details><summary>公开字段</summary><pre>{JSON.stringify(a.parameters, null, 2)}</pre></details></article>)}</div>}<Pagination {...data} onPage={p => update('page', String(p))}/></>}</QueryState>
    </Panel></>
}
