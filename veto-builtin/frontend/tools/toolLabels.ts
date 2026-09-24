import { en } from './translations';
import type { Translate } from './runtime';
const lookup = (t: Translate, key: string, fallback: string) => Object.prototype.hasOwnProperty.call(en,key) ? t(key) : fallback;
export const toolFieldLabel = (t: Translate, field: string) => lookup(t, `tool.field.${field}`, field);
export const toolValueLabel = (t: Translate, field: string, value: string) => lookup(t, `tool.enum.${field}.${value}`, value);
