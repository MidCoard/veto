// Builtin owns monitor presentation, translations, reads, controls and cleanup.
export function activate(host) {
  const { createElement: h, useEffect, useState, useCallback, useRef } = host.React;
  const words = {
    en: { title: 'Monitors', empty: 'No monitors in this session.', loading: 'Loading monitors…', error: 'Could not refresh monitors.', stale: 'Waiting for connection', retry: 'Retry', pause: 'Pause', resume: 'Resume', cancel: 'Cancel', working: 'Updating…', details: 'Notifications', previous: 'Previous', next: 'Next', truncated: 'Read more', hint: 'Controls affect future delivery; they do not stop work already running.', group: 'Group task outcomes' },
    zh: { title: '监控', empty: '此会话暂无监控。', loading: '正在加载监控…', error: '无法刷新监控。', stale: '等待连接', retry: '重试', pause: '暂停', resume: '恢复', cancel: '取消', working: '正在更新…', details: '通知记录', previous: '上一页', next: '下一页', truncated: '继续阅读', hint: '操作影响后续投递，不会停止已经运行的工作。', group: '协作任务结果' },
  };
  const labels = {"en": {"interrupted": "Source needs recovery", "title": "Monitors", "cancelled": "Cancelled", "paused": "Paused", "pending": "Awaiting delivery", "triggered": "Triggered", "activation.APPENDED": "Added to context · Awaiting processing", "activation.RUNNING": "Agent processing", "activation.COMPLETED": "Agent processing completed", "activation.FAILED": "Agent processing failed", "activation.CANCELLED": "Request cancelled · No automatic processing", "activation.INTERRUPTED": "Processing interrupted · Outcome unknown", "activation.UNKNOWN": "Added to context · Processing state unknown", "activation.interruptedHint": "No task was replayed. Check the original task before deciding what to do next.", "delivered": "Added to Agent context", "active": "Watching", "error": "Could not load Monitors", "empty": "No Monitors in this session", "time": "Scheduled wake-up", "group": "Group events", "groupPurpose": "Receive collaborator outcomes", "pause": "Pause reminder", "resume": "Resume reminder", "cancel": "Cancel reminder", "updating": "Updating…", "controlHint": "These controls affect pending reminders. They do not stop Agent or tool execution already in progress.", "controlError": "Could not update this reminder. Its state is unconfirmed; refresh before trying again.", "details": "Details"}, "zh": {"interrupted": "监测来源待恢复", "title": "监测与提醒", "cancelled": "已取消", "paused": "已暂停", "pending": "待交付", "triggered": "已触发", "activation.APPENDED": "已加入上下文 · 待处理", "activation.RUNNING": "Agent 处理中", "activation.COMPLETED": "Agent 处理已完成", "activation.FAILED": "Agent 处理失败", "activation.CANCELLED": "请求已取消 · 不再自动处理", "activation.INTERRUPTED": "处理已中断 · 结果未知", "activation.UNKNOWN": "已加入上下文 · 处理状态未知", "activation.interruptedHint": "未重放任务。请先核查原任务，再决定下一步。", "delivered": "已加入 Agent 上下文", "active": "监测中", "error": "无法加载监测记录", "empty": "此会话暂无监测或提醒", "time": "定时唤醒", "group": "团队事件", "groupPurpose": "接收协作者的执行结果", "pause": "暂停提醒", "resume": "恢复提醒", "cancel": "取消提醒", "updating": "正在更新…", "controlHint": "这些操作只影响待处理提醒，不会停止已开始的 Agent 或工具执行。", "controlError": "无法更新提醒，状态尚未确认；请刷新后再尝试。", "details": "详情"}};
  const button = 'ui-button rounded border border-rule px-2 py-1.5 hover:bg-paper/5 focus-visible:outline focus-visible:outline-2 focus-visible:outline-paper disabled:opacity-50';
  function Panel({ context, onCount }) {
    const locale = context.locale.startsWith('zh') ? 'zh' : 'en';
    const t = { ...words[locale], ...labels[locale] };
    const [page, setPage] = useState(0);
    const [data, setData] = useState(null);
    const [failed, setFailed] = useState(false);
    const [revision, setRevision] = useState(0);
    const [working, setWorking] = useState(null);
    const [controlError, setControlError] = useState(false);
    const [loading, setLoading] = useState(true);
    const write = useRef(null);
    useEffect(() => () => write.current?.abort(), []);
    const refresh = useCallback(() => setRevision(value => value + 1), []);
    useEffect(() => context.subscribe('monitors', refresh), [context.subscribe, refresh]);
    useEffect(() => {
      const controller = new AbortController();
      if (!context.connected) return () => controller.abort();
      setFailed(false); setLoading(true);
      context.invoke('list', { offset: page * 20 }, controller.signal).then(value => {
        if (controller.signal.aborted) return;
        if (page > 0 && value.items.length === 0) { setPage(0); return; }
        setData(value); setLoading(false);
      }).catch(() => { if (!controller.signal.aborted) { setFailed(true); setLoading(false); } });
      return () => controller.abort();
    }, [context.invoke, context.connected, page, revision]);
    useEffect(() => { onCount?.(failed || !context.connected ? null : data?.total ?? null); }, [data, failed, context.connected, onCount]);
    const control = async (id, action) => {
      if (write.current || !context.connected || loading || failed) return;
      const controller = new AbortController(); write.current = controller;
      setWorking(id); setControlError(false);
      try { await context.invoke(action, { id }, controller.signal); }
      catch { if (!controller.signal.aborted && !host.signal.aborted) setControlError(true); }
      finally { if (!controller.signal.aborted && !host.signal.aborted) { write.current = null; setLoading(true); setWorking(null); refresh(); } }
    };
    const unavailable = !context.connected || failed || loading;
    return h('div', { className: 'min-h-full p-3 space-y-2' },
      !context.connected && h('p', { role: 'status', className: 'text-xs text-dim' }, t.stale),
      (failed || controlError) && h('p', { role: 'alert', className: 'text-xs text-verdict' }, (controlError ? t.controlError : t.error), ' ', h('button', { type: 'button', className: button, onClick: refresh, disabled: !context.connected }, t.retry)),
      !data && !failed && context.connected && h('p', { role: 'status', className: 'text-xs text-dim' }, t.loading),
      data?.total === 0 && !unavailable && h('p', { className: 'text-xs text-dim' }, t.empty),
      ...(data?.items ?? []).map(item => h('article', { key: item.id, className: 'rounded-xl border border-rule bg-ink/40 p-3 text-xs' },
        h('div', { className: 'flex flex-wrap justify-between gap-2 text-dim' }, h('span', null, item.kind === 'TIME_ONCE' ? t.time : item.kind === 'RESOURCE_EVENT' ? t.group : (context.locale.startsWith('zh') ? '后台任务' : 'Background tasks')), h('span', null, item.state === 'INTERRUPTED' ? t.interrupted : item.state === 'CANCELLED' ? t.cancelled : item.state === 'PAUSED' ? t.paused : item.pending > 0 ? t.pending : item.state === 'COMPLETED' ? t.triggered : t.active)),
        h('div', { className: 'mt-2 text-paper whitespace-pre-wrap break-words' }, item.kind === 'RESOURCE_EVENT' ? t.group : h(Content, { context, item, event: {content: item.purpose, truncated: item.purposeTruncated}, t, action: 'purpose' })),
        item.dueAt && h('time', { className: 'mt-2 block text-dim', dateTime: item.dueAt }, new Date(item.dueAt).toLocaleString(context.locale)),
        item.kind === 'TIME_ONCE' && (['ACTIVE', 'PAUSED'].includes(item.state) || item.state === 'COMPLETED' && item.controllable) && h('div', { className: 'mt-3 space-y-2' },
          h('div', { className: 'flex flex-wrap gap-2' },
            h('button', { type: 'button', className: button, disabled: !!working || unavailable, onClick: () => control(item.id, item.state === 'PAUSED' ? 'resume' : 'pause') }, item.state === 'PAUSED' ? t.resume : t.pause),
            h('button', { type: 'button', className: button, disabled: !!working || unavailable, onClick: () => control(item.id, 'cancel') }, t.cancel)),
          h('p', { className: 'text-dim' }, working === item.id ? t.working : t.hint)),
        h(Details, { context, item, t, revision }))),
      data && data.total > 20 && h('nav', { className: 'flex items-center justify-between gap-2', 'aria-label': t.title },
        h('button', { type: 'button', className: button, disabled: page === 0, onClick: () => { setData(null); setPage(page - 1); } }, t.previous),
        h('span', null, page + 1, ' / ', Math.ceil(data.total / 20)),
        h('button', { type: 'button', className: button, disabled: (page + 1) * 20 >= data.total, onClick: () => { setData(null); setPage(page + 1); } }, t.next)));
  }
  function Details({ context, item, t, revision }) {
    const [open, setOpen] = useState(false);
    const [page, setPage] = useState(0);
    const [data, setData] = useState(null);
    const [failed, setFailed] = useState(false);
    useEffect(() => {
      if (!open || !context.connected) return;
      const controller = new AbortController();
      setFailed(false);
      context.invoke('details', { id: item.id, offset: page * 10 }, controller.signal)
        .then(value => { if (!controller.signal.aborted) setData(value); })
        .catch(() => { if (!controller.signal.aborted) setFailed(true); });
      return () => controller.abort();
    }, [open, page, context.invoke, context.connected, item.id, revision]);
    return h('details', { className: 'mt-2 text-dim', onToggle: event => setOpen(event.currentTarget.open) },
      h('summary', { className: 'cursor-pointer' }, t.details),
      h('p', { className: 'mt-2 break-all' }, 'Agent: ', item.agentId),
      failed && h('p', { role: 'alert', className: 'text-verdict' }, t.error),
      open && !data && !failed && h('p', { role: 'status' }, t.loading),
      ...(data?.items ?? []).map(event => h('div', { key: event.id, className: 'mt-2 whitespace-pre-wrap break-words' },
        h('p', null, t['activation.' + event.state] ?? t['activation.UNKNOWN']), event.state === 'INTERRUPTED' && h('p', null, t['activation.interruptedHint']), h(Content, { context, item, event, t }))),
      data && data.total > 10 && h('div', { className: 'mt-2 flex gap-2' },
        h('button', { type: 'button', className: button, disabled: page === 0, onClick: () => { setData(null); setPage(page - 1); } }, t.previous),
        h('button', { type: 'button', className: button, disabled: (page + 1) * 10 >= data.total, onClick: () => { setData(null); setPage(page + 1); } }, t.next)));
  }
  function Content({ context, item, event, t, action = 'content' }) {
    const [content, setContent] = useState(event.content);
    const [more, setMore] = useState(event.truncated);
    const [busy, setBusy] = useState(false);
    const [failed, setFailed] = useState(false);
    const request = useRef(null);
    useEffect(() => () => request.current?.abort(), []);
    const load = async () => {
      if (request.current || !context.connected) return;
      const controller = new AbortController(); request.current = controller;
      setBusy(true); setFailed(false);
      try {
        const value = await context.invoke(action, { id: item.id, ...(event.id ? { eventId: event.id } : {}), offset: content.length }, controller.signal);
        if (!controller.signal.aborted) { setContent(content + value.content); setMore(content.length + value.content.length < value.total); }
      } catch { if (!controller.signal.aborted) setFailed(true); }
      finally { if (!controller.signal.aborted) { request.current = null; setBusy(false); } }
    };
    return h('div', null, h('p', null, content), failed && h('p', { role: 'alert' }, t.error),
      more && h('button', { type: 'button', className: button, disabled: busy || !context.connected, onClick: load }, busy ? t.loading : failed ? t.retry : t.truncated));
  }
  host.registerInspector('monitors', { en: words.en.title, zh: words.zh.title }, Panel);
}
