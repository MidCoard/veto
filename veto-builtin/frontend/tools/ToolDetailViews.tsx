import { toolFieldLabel, toolValueLabel } from './toolLabels';
import React from './runtime';
import { useI18n } from './runtime';

const surface = 'rounded-lg border border-rule bg-panel p-3 text-xs space-y-2 max-h-80 overflow-auto';
const pre = 'whitespace-pre-wrap break-words font-mono text-xs';

export function ResultText({ text }: { text: string }) {
  return <pre className={`${surface} ${pre} text-dim`}>{text}</pre>;
}

/** Only HTTP(S) results become links; tool output remains untrusted text. */
export function SourceLink({ url, children }: { url: string; children?: React.ReactNode }) {
  let safe = false;
  try {
    const parsed = new URL(url);
    safe = ['http:', 'https:'].includes(parsed.protocol) && !parsed.username && !parsed.password;
  } catch { /* Invalid destinations remain visible as text. */ }
  return safe
    ? <a className="text-accent break-all hover:underline" href={url} target="_blank" rel="noopener noreferrer">{children ?? url}</a>
    : <span className="break-all">{children ?? url}</span>;
}

function LegacyWebFetchResult({ text }: { text: string }) {
  const match = /^\[(\d{3})\] (\S+)\r?\n\r?\n([\s\S]*)$/.exec(text);
  if (!match) return <ResultText text={text} />;
  return <div className={surface}>
    <div className="flex gap-2"><span className="font-mono">{match[1]}</span><SourceLink url={match[2]} /></div>
    <pre className={pre}>{match[3]}</pre>
  </div>;
}

export function WebSearchResult({ text }: { text: string }) {
  const { t } = useI18n();
  if (text === '(no results)') return <p className="text-xs text-dim">{t('tool.noResults')}</p>;
  const header = /^Found (\d+) results:\r?\n\r?\n/.exec(text);
  const sourceAt = text.lastIndexOf('\nSources:\n');
  if (!header || sourceAt < 0) return <ResultText text={text} />;
  const body = text.slice(header[0].length, sourceAt);
  const matches = [...body.matchAll(/^(\d+)\. (.+)\n   (\S+)\n([\s\S]*?)(?=^\d+\. |$(?![\s\S]))/gm)];
  if (matches.length !== Number(header[1]) || matches.map(m => m[0]).join('') !== body) return <ResultText text={text} />;
  return <div className={surface}>
    <p className="text-dim">{t('tool.resultCount', { count: matches.length })}</p>
    <ol className="space-y-3">{matches.map((m, index) => <li key={index}>
      <SourceLink url={m[3]}>{m[1]}. {m[2]}</SourceLink>
      <div className="text-dim break-all">{m[3]}</div>
      <p className="whitespace-pre-wrap">{m[4].trim()}</p>
    </li>)}</ol>
    <details><summary className="cursor-pointer text-dim">{t('tool.rawResult')}</summary><pre className={pre}>{text}</pre></details>
  </div>;
}

function object(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

interface WebReadEvidence {
  url: string;
  section: string;
  quote: string;
}

function webReadEvidence(value: unknown): value is WebReadEvidence {
  return object(value) && typeof value.url === 'string' && typeof value.section === 'string' && typeof value.quote === 'string';
}

export function WebFetchResult({ text }: { text: string }) {
  const { t } = useI18n();
  let value: unknown;
  try {
    value = JSON.parse(text);
    if (object(value) && value.status === 'success' && value.format === 'json'
      && typeof value.content === 'string') {
      value = JSON.parse(value.content);
    }
  } catch { return <LegacyWebFetchResult text={text} />; }
  if (!object(value) || !['complete', 'partial', 'not_found'].includes(String(value.outcome))
    || typeof value.answer !== 'string' || !Array.isArray(value.evidence) || !value.evidence.every(webReadEvidence)
    || !Array.isArray(value.limitations) || !value.limitations.every((item): item is string => typeof item === 'string')) {
    return <ResultText text={text} />;
  }
  const outcome = value.outcome as 'complete' | 'partial' | 'not_found';
  return <div className={surface}>
    <span className="inline-block rounded border border-rule px-2 py-0.5 text-dim">{t(`tool.webRead.${outcome}`)}</span>
    <p className="whitespace-pre-wrap break-words text-paper">{value.answer}</p>
    {value.evidence.length > 0 && <section className="space-y-2">
      <h4 className="text-dim">{t('tool.webRead.evidence')}</h4>
      {value.evidence.map((item, index) => <figure key={index} className="space-y-1">
        <figcaption><SourceLink url={item.url}>{item.section || item.url}</SourceLink><div className="text-dim break-all">{item.url}</div></figcaption>
        <blockquote className="border-l-2 border-accent/40 pl-3 whitespace-pre-wrap break-words">{item.quote}</blockquote>
      </figure>)}
    </section>}
    {value.limitations.length > 0 && <section>
      <h4 className="text-dim">{t('tool.webRead.limitations')}</h4>
      <ul className="list-disc pl-4 space-y-1">{value.limitations.map((item, index) => <li className="whitespace-pre-wrap break-words" key={index}>{item}</li>)}</ul>
    </section>}
    <details><summary className="cursor-pointer text-dim">{t('tool.rawResult')}</summary><pre className={pre}>{text}</pre></details>
  </div>;
}

export function Fields({ values, literal = false }: { values: Record<string, unknown>; literal?: boolean }) {
  const { t } = useI18n();
  return <dl className="space-y-2">{Object.entries(values).map(([key, value]) => <div key={key}>
    <dt className="font-mono text-dim">{literal ? key : toolFieldLabel(t, key)}</dt>
    <dd className="whitespace-pre-wrap break-words text-paper">{value === null ? '—' : typeof value === 'boolean' ? t(value ? 'tool.yes' : 'tool.no') : Array.isArray(value) ? <ul className="space-y-1">{value.map((item, index) => <li key={index}>{object(item) ? <Fields values={item} literal={literal} /> : String(item)}</li>)}</ul> : object(value) ? <Fields values={value} literal={literal} /> : literal ? String(value) : toolValueLabel(t, key, String(value))}</dd>
  </div>)}</dl>;
}

/** JSON output is validated before selecting a semantic view; malformed bodies stay readable. */
export function StructuredToolResult({ toolName, text }: { toolName: string; text: string }) {
  const { t } = useI18n();
  let value: unknown;
  try { value = JSON.parse(text); } catch { return <ResultText text={text} />; }
  if (!object(value)) return <ResultText text={text} />;
  if (toolName === 'find_files') {
    if (typeof value.base !== 'string' || typeof value.pattern !== 'string' || !Array.isArray(value.matches) || !value.matches.every(v => typeof v === 'string')) return <ResultText text={text} />;
    const { matches, ...metadata } = value;
    return <div className={surface}>
      <Fields values={metadata} />
      <p className="text-dim">{t('tool.resultCount', { count: matches.length })}</p>
      <ul className="font-mono space-y-1">{matches.map((path, i) => <li className="break-all" key={i}>{path}</li>)}</ul>
    </div>;
  }
  return <div className={surface}><Fields values={value} /></div>;
}

export function DetailCallCard({ toolName, args }: { toolName: string; args: Record<string, unknown> | undefined }) {
  const { t } = useI18n();
  return <div className={surface}>
    <div className="font-mono text-accent">{toolName}</div>
    {args && Object.keys(args).length > 0 ? <Fields values={args} /> : <p className="text-dim">{t('tool.noParameters')}</p>}
  </div>;
}
