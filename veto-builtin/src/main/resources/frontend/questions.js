const strings = {
    en: { title: 'Input needed', waiting: 'The agent is waiting for your choice.', other: 'Other', answer: 'Answer: ', placeholder: 'Enter your answer', tooLong: 'Answer must be 500 characters or fewer.', limit: 'Up to 500 characters.', sending: 'Sending…', continue: 'Continue', cancel: 'Cancel', error: 'Unable to reach the backend.', retry: 'Retry', recommended: '(Recommended)', offline: 'Connection lost. Answers are saved here; reconnect to submit.', loading: 'Loading questions…' },
    zh: { title: '需要你的回答', waiting: 'Agent 正在等待你的选择。', other: '其他', answer: '回答：', placeholder: '请输入你的回答', tooLong: '回答不能超过 500 个字符。', limit: '最多 500 个字符。', sending: '正在发送…', continue: '继续', cancel: '取消', error: '无法连接后端。', retry: '重试', recommended: '（推荐）', offline: '连接已断开。已保留当前回答，请重新连接后提交。', loading: '正在加载问题…' }
};
const css = `.veto-questions{color:rgb(var(--paper));font-size:14px;min-width:0}.veto-questions section{border:1px solid rgb(var(--accent)/.35);background:rgb(var(--panel));border-radius:12px;margin:12px 0;overflow:hidden}.veto-questions header{padding:12px 16px;border-bottom:1px solid rgb(var(--rule));background:rgb(var(--accent)/.05)}.veto-questions h3{font-size:12px;color:rgb(var(--accent));font-weight:600}.veto-questions p{margin:4px 0;white-space:pre-wrap;overflow-wrap:anywhere}.veto-questions .q-body{padding:16px;display:grid;gap:20px}.veto-questions fieldset{min-width:0;border:0;padding:0}.veto-questions legend{width:100%;font-size:14px;margin-bottom:8px}.veto-questions small{display:block;color:rgb(var(--dim));font-size:11px}.veto-questions .q-options{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}.veto-questions button{min-height:36px;border:1px solid rgb(var(--rule));border-radius:7px;padding:8px 12px;background:rgb(var(--raised));color:rgb(var(--paper));text-align:left;white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px;cursor:pointer}.veto-questions button[aria-pressed=true]{border-color:rgb(var(--accent));background:rgb(var(--accent)/.1)}.veto-questions button:disabled{opacity:.5;cursor:default}.veto-questions button:focus-visible,.veto-questions input:focus-visible{outline:2px solid rgb(var(--accent));outline-offset:2px}.veto-questions input{box-sizing:border-box;width:100%;margin-top:8px;padding:8px 12px;border:1px solid rgb(var(--rule));border-radius:7px;background:rgb(var(--raised));color:rgb(var(--paper))}.veto-questions footer{display:flex;gap:8px;align-items:center;flex-wrap:wrap;border-top:1px solid rgb(var(--rule));padding-top:12px}.veto-questions .q-submit{background:rgb(var(--accent));color:rgb(var(--onaccent))}.veto-questions [role=alert]{color:rgb(var(--verdict));font-size:12px}.veto-questions .q-status{color:rgb(var(--dim));font-size:12px}@media(max-width:480px){.veto-questions .q-options{grid-template-columns:1fr}}`;
export function activate(host) {
    const React = host.React;
    const { useState, useRef, useEffect } = React;
    const OTHER = Symbol('other');
    function Card({ batch, context, refresh }) {
        const t = strings[context.locale.startsWith('zh') ? 'zh' : 'en'];
        const [choices, setChoices] = useState({});
        const [other, setOther] = useState({});
        const pending = useRef(false);
        const [submitting, setSubmitting] = useState(false);
        const [error, setError] = useState(null);
        const controller = useRef();
        useEffect(() => () => controller.current?.abort(), []);
        const answers = {};
        for (const question of batch.questions) {
            const choice = choices[question.id];
            if (choice === OTHER)
                answers[question.id] = (other[question.id] ?? '').trim();
            else if (choice !== undefined)
                answers[question.id] = choice;
        }
        const complete = batch.questions.length > 0 && batch.questions.every(q => answers[q.id] && Array.from(answers[q.id]).length <= 500);
        async function run(action) {
            if (pending.current || !context.connected || (action === 'answer' && !complete))
                return;
            pending.current = true;
            setSubmitting(true);
            setError(null);
            const request = new AbortController();
            controller.current = request;
            try {
                await context.invoke(action, { callId: batch.callId, ...(action === 'answer' ? { answers } : {}) }, request.signal);
                if (!request.signal.aborted)
                    refresh(batch.callId);
            }
            catch (caught) {
                if (!request.signal.aborted) {
                    setError({ message: caught?.name === 'ApiError' ? caught.message : null });
                    pending.current = false;
                    setSubmitting(false);
                }
            }
        }
        return React.createElement("section", null,
            React.createElement("header", null,
                React.createElement("h3", null, t.title),
                React.createElement("p", null, t.waiting)),
            React.createElement("div", { className: "q-body" },
                batch.questions.map(question => React.createElement("fieldset", { key: question.id, disabled: submitting || !context.connected },
                    React.createElement("legend", null,
                        React.createElement("small", null, question.header),
                        React.createElement("p", null, question.question)),
                    React.createElement("div", { className: "q-options" },
                        question.options.map((option, index) => React.createElement("button", { type: "button", key: option.label, "aria-pressed": choices[question.id] === option.label, onClick: () => setChoices(previous => ({ ...previous, [question.id]: option.label })) },
                            option.label.replace(/\s*\(Recommended\)\s*$/i, '').trim(),
                            index === 0 ? ` ${t.recommended}` : '',
                            React.createElement("small", null, option.description))),
                        React.createElement("button", { type: "button", "aria-pressed": choices[question.id] === OTHER, onClick: () => setChoices(previous => ({ ...previous, [question.id]: OTHER })) }, t.other)),
                    choices[question.id] === OTHER && React.createElement(React.Fragment, null,
                        React.createElement("input", { autoFocus: true, "aria-label": t.answer + question.question, "aria-invalid": Array.from(answers[question.id] ?? '').length > 500, "aria-describedby": `q-limit-${batch.callId}-${question.id}`, value: other[question.id] ?? '', onChange: event => setOther(previous => ({ ...previous, [question.id]: event.target.value })), placeholder: t.placeholder }),
                        React.createElement("p", { className: "q-status", id: `q-limit-${batch.callId}-${question.id}`, "aria-live": "polite" }, Array.from(answers[question.id] ?? '').length > 500 ? t.tooLong : t.limit)))),
                React.createElement("footer", null,
                    React.createElement("button", { type: "button", className: "q-submit", disabled: !complete || submitting || !context.connected, onClick: () => void run('answer') }, submitting ? t.sending : t.continue),
                    React.createElement("button", { type: "button", disabled: submitting || !context.connected, onClick: () => void run('cancel') }, t.cancel),
                    error && React.createElement("span", { role: "alert" }, error.message ?? t.error))));
    }
    function Panel({ context }) {
        const t = strings[context.locale.startsWith('zh') ? 'zh' : 'en'];
        const identity = `${context.session}/${context.agent}`;
        const [snapshot, setSnapshot] = useState({ identity, items: [] });
        const [error, setError] = useState(false);
        const [loading, setLoading] = useState(true);
        const [revision, setRevision] = useState(0);
        useEffect(() => {
            const controller = new AbortController();
            let generation = 0;
            const load = async () => { const request = ++generation; try {
                const data = await context.invoke('list', {}, controller.signal);
                if (!controller.signal.aborted && request === generation) {
                    setSnapshot({ identity, items: data.items });
                    setError(false);
                    setLoading(false);
                }
            }
            catch {
                if (!controller.signal.aborted && request === generation) {
                    setError(true);
                    setLoading(false);
                }
            } };
            const unsubscribe = context.subscribe('interactions', () => void load());
            if (context.connected)
                void load();
            return () => { controller.abort(); unsubscribe(); };
        }, [identity, context.connected, context.invoke, context.subscribe, revision]);
        const items = snapshot.identity === identity ? snapshot.items : [];
        const refresh = (callId) => { setSnapshot(previous => ({ ...previous, items: previous.items.filter(item => item.callId !== callId) })); setRevision(value => value + 1); };
        return React.createElement("div", { className: "veto-questions" },
            React.createElement("style", null, css),
            !context.connected && items.length > 0 && React.createElement("p", { className: "q-status", role: "status" }, t.offline),
            loading && context.connected && React.createElement("p", { className: "q-status", role: "status" }, t.loading),
            error && React.createElement("p", { role: "alert" },
                t.error,
                " ",
                React.createElement("button", { type: "button", disabled: !context.connected, onClick: () => setRevision(value => value + 1) }, t.retry)),
            items.map(batch => React.createElement(Card, { key: `${identity}/${batch.callId}`, batch: batch, context: context, refresh: refresh })));
    }
    host.registerPanel('questions', 'conversation.footer', Panel);
}
