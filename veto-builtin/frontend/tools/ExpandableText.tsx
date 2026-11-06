import React, { useI18n } from './runtime';

export const TEXT_PREVIEW_CHARACTERS = 32 * 1024;

/** Keep full text available while bounding text, highlighting and line DOM until expanded. */
export function ExpandableText({ text, className = 'text-xs font-mono text-dim whitespace-pre-wrap break-words bg-panel border border-rule rounded-lg p-2 max-h-64 overflow-y-auto', children }: {
  text: string;
  className?: string;
  children?: (visible: string) => React.ReactNode;
}) {
  const { t } = useI18n();
  const [limit, setLimit] = React.useState(TEXT_PREVIEW_CHARACTERS);
  let end = Math.min(limit, text.length);
  const last = text.charCodeAt(end - 1);
  if (end < text.length && last >= 0xd800 && last <= 0xdbff) end -= 1;
  const visible = text.slice(0, end);
  return <div className="min-w-0 space-y-2">
    {children?.(visible) ?? <pre className={className}>{visible}</pre>}
    {end < text.length && <button type="button"
      className="cursor-pointer rounded border border-rule px-3 py-1 text-xs text-dim hover:text-paper focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent"
      onClick={() => setLimit(value => value + TEXT_PREVIEW_CHARACTERS)}>
      {t('tool.showMoreResult', { count: text.length - end })}
    </button>}
  </div>;
}
