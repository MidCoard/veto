import type * as ReactTypes from 'react';
import type { FrontendHost } from '../../../veto-api/frontend';
import { en, zh } from './translations';
let React: typeof ReactTypes;
let host: FrontendHost;
let Locale: ReactTypes.Context<string>;
export { React as default };
export type Translate = (key: string, values?: Record<string, string | number>) => string;
export function configure(value: FrontendHost) { host=value; React=value.React; Locale=React.createContext('en'); }
export function useI18n() {
 const lang=React.useContext(Locale);const dictionary=lang.startsWith('zh')?zh:en;
 const t:Translate=(key,values={}) => (dictionary[key]??en[key]??key).replace(/\{(\w+)\}/g,(_match:string,name:string)=>String(values[name]??`{${name}}`));
 return {t,lang};
}
export function LocaleScope({locale,children}:{locale:string;children:ReactTypes.ReactNode}) {return <Locale.Provider value={locale}>{children}</Locale.Provider>;}
export function CodeHighlight(props: {code:string;language?:string;className?:string;showLineNumbers?:boolean}) { return <host.components.CodeBlock {...props}/>; }
export function StreamingMarkdown(props:{content:string;isStreaming?:boolean;className?:string}) {return <host.components.Markdown {...props}/>;}
