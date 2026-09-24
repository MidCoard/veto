// Delivered with builtin. The host knows only inspector and action contracts.
export function activate(host) {
  const { createElement: h, useState, useEffect, useCallback } = host.React;
  const translations = {
    en: { title: 'Group tasks', loading: 'Loading…', error: 'Could not refresh group records.', retry: 'Retry', offline: 'Waiting for connection; showing saved records.', empty: 'No group tasks in this session.', previous: 'Previous', next: 'Next', more: 'Read more', brief: 'Task objective', tasks: 'Tasks', history: 'State history', description: 'Description', report: 'Report', mate: 'View Mate', dependencies: 'Dependencies', independent: 'None; independent', legacy: 'Recovered from legacy records; some transitions were not saved.', inactive: 'Execution is offline; showing the last saved state.', unknown: 'Not recorded', navigationError: 'This Agent could not be opened. Refresh and try again.', request: 'Request', dispatch: 'Dispatch', retries: 'Retries', completedHint: 'A received report is not independent verification.', states: { COMPLETED: 'Completed', VERIFIED: 'Report received', REPORTED: 'Report received', RUNNING: 'Running', PENDING: 'Waiting', FAILED: 'Failed', CANCEL_REQUESTED: 'Cancellation requested · awaiting stop confirmation', CANCELLED: 'Cancelled', STALE: 'Removed', DISBANDED: 'Disbanded', ACTIVE: 'Active', INTERRUPTED: 'Interrupted · not automatically replayed' } },
    zh: { title: '协作任务', loading: '正在加载…', error: '无法刷新协作记录。', retry: '重试', offline: '等待连接；当前显示已保存记录。', empty: '此会话暂无协作任务。', previous: '上一页', next: '下一页', more: '继续阅读', brief: '任务目标', tasks: '任务', history: '状态历史', description: '任务说明', report: '报告', mate: '查看 Mate', dependencies: '依赖', independent: '无，可独立执行', legacy: '从旧会话记录恢复；部分状态变化未保存。', inactive: '执行进程已结束；以下为最后保存的状态。', unknown: '状态未记录', navigationError: '无法打开此 Agent，请刷新后重试。', request: '请求', dispatch: '派发', retries: '重试次数', completedHint: '收到报告不代表结论已独立核验。', states: { COMPLETED: '已完成', VERIFIED: '已提交报告', REPORTED: '已提交报告', RUNNING: '执行中', PENDING: '等待依赖', FAILED: '失败', CANCEL_REQUESTED: '已请求取消 · 等待停止确认', CANCELLED: '已取消', STALE: '已移除', DISBANDED: '已解散', ACTIVE: '进行中', INTERRUPTED: '已中断 · 未自动重派' } },
  };
  const css = `.builtin-groups{padding:12px;color:rgb(var(--paper));font-size:12px;line-height:1.65;min-width:0}.builtin-groups *{box-sizing:border-box}.builtin-groups article,.builtin-groups section{border:1px solid rgb(var(--rule));border-radius:10px;padding:12px;margin:8px 0;min-width:0}.builtin-groups section{background:rgb(var(--panel))}.builtin-groups article{background:rgb(var(--ink)/.4)}.builtin-groups p{white-space:pre-wrap;overflow-wrap:anywhere;margin:8px 0}.builtin-groups header,.builtin-groups nav{display:flex;align-items:center;justify-content:space-between;gap:8px;flex-wrap:wrap}.builtin-groups button{font:inherit;border:1px solid rgb(var(--rule));border-radius:6px;background:transparent;color:inherit;padding:5px 8px;cursor:pointer}.builtin-groups button:hover:not(:disabled){background:rgb(var(--paper)/.08)}.builtin-groups button:active:not(:disabled){background:rgb(var(--paper)/.14)}.builtin-groups button:disabled{opacity:.5;cursor:default}.builtin-groups button:focus-visible,.builtin-groups summary:focus-visible{outline:2px solid rgb(var(--accent));outline-offset:2px}.builtin-groups summary{cursor:pointer;padding:6px 0}.builtin-groups summary:hover{color:rgb(var(--accent))}.builtin-groups .bg-muted{color:rgb(var(--dim))}.builtin-groups [role=alert]{color:rgb(var(--verdict))}.builtin-groups code{font-family:inherit;overflow-wrap:anywhere}.builtin-groups details{margin-top:8px}.builtin-groups time{color:rgb(var(--dim))}`;
  const status = (t, value) => t.states[value] ?? t.unknown;
  function Disclosure({ label, children }) {
    const [open, setOpen] = useState(false);
    return h('details', { onToggle: event => setOpen(event.currentTarget.open) }, h('summary', null, label), open && children);
  }
  function Page({ context, action, args, t, revision, renderItem, onCount, empty = t.empty }) {
    const [page, setPage] = useState(0);
    const [data, setData] = useState(null);
    const [failed, setFailed] = useState(false);
    const [loading, setLoading] = useState(true);
    const [retry, setRetry] = useState(0);
    const key = JSON.stringify(args);
    useEffect(() => {
      if (!context.connected) return;
      const controller = new AbortController();
      setLoading(true); setFailed(false);
      context.invoke(action, { ...JSON.parse(key), offset: page * 20, limit: 20 }, controller.signal).then(value => {
        if (controller.signal.aborted) return;
        if (page > 0 && value.items.length === 0) { setPage(0); setData(null); return; }
        setData(value); setLoading(false);
      }).catch(() => { if (!controller.signal.aborted) { setFailed(true); setLoading(false); } });
      return () => controller.abort();
    }, [context.invoke, context.connected, action, key, page, revision, retry]);
    useEffect(() => { onCount?.(!context.connected || failed || !data ? null : action === 'list' ? data.totalNodeCount : data.total); }, [onCount, data, context.connected, failed, action]);
    return h('div', { 'aria-busy': loading && context.connected },
      failed && h('p', { role: 'alert' }, t.error, ' ', h('button', { type: 'button', onClick: () => setRetry(value => value + 1), disabled: !context.connected }, t.retry)),
      loading && context.connected && h('p', { role: 'status' }, t.loading),
      data?.total === 0 && !loading && !failed && h('p', null, empty),
      ...(data?.items ?? []).map((item, index) => renderItem(item, page * 20 + index)),
      data && data.total > 20 && h('nav', { 'aria-label': `${t.title} · ${action}` },
        h('button', { type: 'button', disabled: page === 0 || loading || !context.connected, onClick: () => { setData(null); setPage(value => value - 1); } }, t.previous),
        h('span', null, `${page + 1} / ${Math.ceil(data.total / 20)}`),
        h('button', { type: 'button', disabled: (page + 1) * 20 >= data.total || loading || !context.connected, onClick: () => { setData(null); setPage(value => value + 1); } }, t.next)));
  }
  function Text({ context, args, t, revision }) {
    const [content, setContent] = useState('');
    const [offset, setOffset] = useState(0);
    const [data, setData] = useState(null);
    const [failed, setFailed] = useState(false);
    const [loading, setLoading] = useState(true);
    const [retry, setRetry] = useState(0);
    const key = JSON.stringify(args);
    useEffect(() => { setOffset(0); setContent(''); setData(null); }, [key, revision]);
    useEffect(() => {
      if (!context.connected) return;
      const controller = new AbortController(); setLoading(true); setFailed(false);
      context.invoke('text', { ...JSON.parse(key), offset, limit: 8192 }, controller.signal).then(value => {
        if (controller.signal.aborted) return;
        setContent(previous => offset === 0 ? value.text : previous.slice(0, offset) + value.text);
        setData(value); setLoading(false);
      }).catch(() => { if (!controller.signal.aborted) { setFailed(true); setLoading(false); } });
      return () => controller.abort();
    }, [context.invoke, context.connected, key, offset, revision, retry]);
    return h('div', null, h('p', null, content),
      loading && context.connected && h('p', { role: 'status' }, t.loading),
      failed && h('p', { role: 'alert' }, t.error, ' ', h('button', { type: 'button', disabled: !context.connected, onClick: () => setRetry(value => value + 1) }, t.retry)),
      data && Number.isInteger(data.nextOffset) && data.nextOffset > offset && data.nextOffset < data.total && h('button', { type: 'button', disabled: loading || !context.connected, onClick: () => setOffset(data.nextOffset) }, t.more));
  }
  function Node({ node, context, args, t, revision }) {
    const [failed, setFailed] = useState(false);
    const [opening, setOpening] = useState(false);
    const open = async () => { if (opening) return; setOpening(true); setFailed(false); try { await context.openAgent(node.mateId); } catch { if (!host.signal.aborted) setFailed(true); } finally { if (!host.signal.aborted) setOpening(false); } };
    return h('article', null,
      h('header', null, h('code', null, node.id), h('span', null, status(t, node.state))),
      h(Disclosure, { label: t.description }, h(Text, { context, t, revision, args: { ...args, nodeId: node.id, field: 'description' } })),
      h('p', { className: 'bg-muted' }, `${t.dependencies}: ${(node.dependsOn ?? []).join(', ') || t.independent}`),
      node.mateId && context.openAgent && h('button', { type: 'button', onClick: open, disabled: opening || !context.connected }, `${t.mate} · ${node.mateId}`),
      failed && h('p', { role: 'alert' }, t.navigationError),
      h('p', { className: 'bg-muted' }, `${t.retries}: ${node.retries ?? 0}`),
      node.requestId && h('p', { className: 'bg-muted' }, `${t.request}: ${node.requestId}`),
      node.dispatchId && h('p', { className: 'bg-muted' }, `${t.dispatch}: ${node.dispatchId}`),
      node.reportLength > 0 && h(Disclosure, { label: t.report }, h('p', { className: 'bg-muted' }, t.completedHint), h(Text, { context, t, revision, args: { ...args, nodeId: node.id, field: 'report' } })));
  }
  function Group({ group, context, t, revision }) {
    const args = { groupId: group.id };
    return h('section', null,
      h('header', null, h('strong', null, t.title), h('span', null, status(t, group.state))),
      h('p', { className: 'bg-muted' }, group.id),
      group.historical && h('p', { className: 'bg-muted' }, t.legacy),
      !group.live && h('p', { className: 'bg-muted' }, t.inactive),
      h(Disclosure, { label: t.brief }, h(Text, { context, t, revision, args: { ...args, field: 'brief' } })),
      h(Page, { context, t, revision, action: 'nodes', args, empty: t.unknown, renderItem: node => h(Node, { key: node.id, node, context, args, t, revision }) }),
      group.changeCount > 0 && h(Disclosure, { label: `${t.history} (${group.changeCount})` },
        h(Page, { context, t, revision, action: 'changes', args, renderItem: change => h(Disclosure, { key: change.index, label: `${new Date(change.at).toLocaleString(context.locale)} · ${status(t, change.state)} · ${change.nodeCount}` },
          h(Page, { context, t, revision, action: 'changeNodes', args: { ...args, changeIndex: change.index }, renderItem: node => h(Node, { key: node.id, node, context, t, revision, args: { ...args, changeIndex: change.index } }) })) })));
  }
  function Panel({ context, onCount }) {
    const t = translations[context.locale.startsWith('zh') ? 'zh' : 'en'];
    const [revision, setRevision] = useState(0);
    const refresh = useCallback(() => setRevision(value => value + 1), []);
    useEffect(() => context.subscribe('groups', refresh), [context.subscribe, refresh]);
    return h('div', { className: 'builtin-groups' }, h('style', null, css),
      !context.connected && h('p', { role: 'status' }, t.offline),
      h(Page, { key: `${context.session}:${context.agent}`, context, t, revision, action: 'list', args: {}, onCount, renderItem: group => h(Group, { key: group.id, group, context, t, revision }) }));
  }
  host.registerInspector('groups', { en: translations.en.title, zh: translations.zh.title }, Panel);
}
