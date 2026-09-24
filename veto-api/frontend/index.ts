import type * as React from 'react';

export type Json = null | boolean | number | string | Json[] | { [key: string]: Json };
export interface FrontendModule { id: string; pluginId: string; apiVersion: number; source: string; tools?: Record<string, string> }
export interface PluginContext {
  session: string;
  agent: string;
  locale: string;
  connected: boolean;
  /** Available in host surfaces that support opening a session Agent. */
  openAgent?: (agentId: string) => Promise<void>;
  subscribe(resource: string, listener: () => void): () => void;
  /** Requests are also cancelled when the session's frontend plugin is disposed. */
  invoke<T extends Json = Json>(action: string, arguments_: Record<string, Json>, signal?: AbortSignal): Promise<T>;
}
export interface ReferenceProps { reference: string; context: PluginContext }
export interface PanelProps { context: PluginContext; onCount?: (count: number | null) => void }
export interface ToolIdentity { toolName?: string; pluginId?: string; localId?: string }
export interface ToolPresentationProps extends ToolIdentity {
  context: PluginContext;
  args?: Record<string, unknown>;
  text?: string;
  success?: boolean;
  result?: { text: string; success?: boolean };
}
export interface ToolRenderer {
  call?: React.ComponentType<ToolPresentationProps>;
  result?: React.ComponentType<ToolPresentationProps>;
  conversation?: React.ComponentType<ToolPresentationProps>;
  headerTarget?: (args: Record<string, unknown>) => string | undefined;
}
export interface FrontendHost {
  apiVersion: 1;
  React: typeof React;
  signal: AbortSignal;
  components: {
    CodeBlock: React.ComponentType<{ code: string; language?: string; className?: string; showLineNumbers?: boolean }>;
    Markdown: React.ComponentType<{ content: string; className?: string; isStreaming?: boolean }>;
  };
  registerToolRenderer(localToolId: string, renderer: ToolRenderer): void;
  registerInspector(id: string, labels: Record<string, string>, component: React.ComponentType<PanelProps>): void;
  registerReferenceRenderer(tokenType: string, component: React.ComponentType<ReferenceProps>): void;
  registerPanel(id: string, slot: 'conversation.footer', component: React.ComponentType<PanelProps>): void;
}
/** Plugins compile TS/JSX to self-contained ESM, obtaining the host's React instance here. */
export interface FrontendPlugin { activate(host: FrontendHost): void | (() => void) }
