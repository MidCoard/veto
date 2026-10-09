# @veto/protocol

Transport-neutral TypeScript contracts and JSON validation for Veto frontends.
This package has no runtime dependencies and requires no DOM, React, Node, or socket APIs.
Java clients use the `veto-protocol` JVM module, including its ZeroMQ transport.
The TypeScript package has no dependency on that JVM transport. Terminal interaction,
rendering and logging belong to `veto-terminal`.

```ts
import { decodeFrame, encodeFrame, type Frame } from '@veto/protocol';

const heartbeat: Frame = { type: 'heartbeat', seq: 1 };
const json = encodeFrame(heartbeat);
const received = decodeFrame(JSON.parse(json));
```

Every application frame uses one `type` discriminator. Streaming events use
`type: "event"` with `kind`, `text`, `attrs`, session identity and sequence.
Transports add their own framing, authentication and delivery rules around these values.
Consumers select presentation behavior without defining another wire envelope.

`schema.ts` and `fixtures.json` are generated from the Java wire contracts by
`:veto-protocol:generateFrontendBindings`. Protocol tests reject stale generated files;
frontend tests decode the actual Java fixtures. Edit the Java contracts first, regenerate,
and update all consumers together. Protocol version 2 has no legacy frame fallback.
