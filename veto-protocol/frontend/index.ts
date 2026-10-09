import { frameSchemas, protocolVersion } from './schema';

export { protocolVersion };

export type JsonValue = null | boolean | number | string | JsonValue[] | { [key: string]: JsonValue };

type Shape = string
  | { readonly nullable: Shape }
  | { readonly enum: readonly string[] }
  | { readonly array: Shape }
  | { readonly map: Shape }
  | { readonly object: Readonly<Record<string, Shape>> };

type Value<S> = S extends { readonly nullable: infer T } ? Value<T> | null
  : S extends { readonly enum: readonly (infer E)[] } ? E
    : S extends { readonly array: infer T } ? Value<T>[]
      : S extends { readonly map: infer T } ? Record<string, Value<T>>
        : S extends { readonly object: infer T } ? { [K in keyof T]: Value<T[K]> }
          : S extends 'string' ? string
            : S extends 'integer' | 'number' ? number
              : S extends 'boolean' ? boolean : JsonValue;

type Schemas = typeof frameSchemas;
export type Frame = {
  [K in keyof Schemas]: { type: K } & Value<{ object: Schemas[K] }>
}[keyof Schemas];
export type EventFrame = Extract<Frame, { type: 'event' }>;
export type EventKind = EventFrame['kind'];
export type SessionEventFrame = EventFrame & { sessionId: string };
export type ControlFrame = Exclude<Frame, EventFrame> & Record<string, unknown>;

function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function json(value: unknown, ancestors: Set<object>): boolean {
  if (value === null || typeof value === 'string' || typeof value === 'boolean') return true;
  if (typeof value === 'number') return Number.isFinite(value);
  if (typeof value !== 'object') return false;
  if (!Array.isArray(value) && Object.getPrototypeOf(value) !== Object.prototype
    && Object.getPrototypeOf(value) !== null) return false;
  if (ancestors.has(value) || Object.getOwnPropertySymbols(value).length !== 0) return false;
  ancestors.add(value);
  try {
    const entries = Array.isArray(value) ? Array.from(value) : Object.values(value);
    return entries.every(entry => json(entry, ancestors));
  } finally {
    ancestors.delete(value);
  }
}

function matches(shape: Shape, value: unknown): boolean {
  if (typeof shape === 'string') {
    switch (shape) {
      case 'string': return typeof value === 'string';
      case 'boolean': return typeof value === 'boolean';
      case 'integer': return typeof value === 'number' && Number.isSafeInteger(value);
      case 'number': return typeof value === 'number' && Number.isFinite(value);
      case 'json': return value !== undefined;
      default: return false;
    }
  }
  if ('nullable' in shape) return value === null || matches(shape.nullable, value);
  if ('enum' in shape) return typeof value === 'string' && shape.enum.includes(value);
  if ('array' in shape) return Array.isArray(value) && value.every(item => matches(shape.array, item));
  if ('map' in shape) return object(value) && Object.values(value).every(item => matches(shape.map, item));
  return object(value) && Object.entries(shape.object).every(([key, field]) => matches(field, value[key]));
}

/** Validates the one application-frame hierarchy after a transport has decoded its payload. */
export function decodeFrame(value: unknown): Frame | null {
  if (!json(value, new Set()) || !object(value) || typeof value.type !== 'string'
    || !Object.prototype.hasOwnProperty.call(frameSchemas, value.type)) return null;
  const fields = frameSchemas[value.type as keyof Schemas];
  return matches({ object: fields }, value) ? value as Frame : null;
}

/** Encodes a validated application frame; socket framing belongs to the selected transport. */
export function encodeFrame(frame: Frame): string {
  if (decodeFrame(frame) === null) throw new TypeError('Invalid protocol frame');
  return JSON.stringify(frame);
}
