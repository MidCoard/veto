/** Browser UI shipped by the secret plugin. React and scoped actions come from the host. */
export function activate(host) {
  const { createElement: h, useEffect, useRef, useState } = host.React;
  function SecretReference({ reference, context }) {
    const [value, setValue] = useState(null);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState(null);
    const request = useRef(null);
    const zh = context.locale === 'zh-CN';
    function reset() {
      request.current?.abort();
      setValue(null); setBusy(false); setError(null);
    }
    useEffect(() => () => request.current?.abort(), []);
    useEffect(() => {
      const hide = () => { if (document.visibilityState === 'hidden') reset(); };
      document.addEventListener('visibilitychange', hide);
      hide();
      return () => document.removeEventListener('visibilitychange', hide);
    }, []);
    useEffect(() => {
      if (value === null) return;
      if (document.visibilityState === 'hidden') { reset(); return; }
      const timer = setTimeout(reset, 30000);
      return () => clearTimeout(timer);
    }, [value]);
    async function show() {
      if (busy) return;
      const controller = new AbortController(); request.current = controller;
      setBusy(true); setError(null);
      try {
        const result = await context.invoke('show', { reference }, controller.signal);
        if (!controller.signal.aborted) {
          if (typeof result === 'string') setValue(result);
          else setError(zh ? '已过期或不可用' : 'Expired or unavailable');
        }
      } catch {
        if (!controller.signal.aborted) setError(zh ? '无法加载，请重试。' : 'Could not load. Try again.');
      } finally { if (!controller.signal.aborted) setBusy(false); }
    }
    return h('span', {
      'aria-busy': busy,
      'data-plugin-view': 'secret-reference',
      className: 'veto-plugin-inline',
      onKeyDown: event => { if (event.key === 'Escape') reset(); },
    },
      h('span', { className: 'veto-plugin-text' }, value ?? '••••••••'),
      h('button', { type: 'button', className: 'ui-button veto-plugin-action', disabled: busy, onClick: value === null ? show : reset }, value === null ? (zh ? '显示' : 'Show') : (zh ? '隐藏' : 'Hide')),
      busy && h('span', { role: 'status', className: 'sr-only' }, zh ? '加载中…' : 'Loading…'),
      error && h('span', { role: 'alert', className: 'text-verdict' }, error));
  }
  host.registerReferenceRenderer('SECRET_REF', SecretReference);
}
