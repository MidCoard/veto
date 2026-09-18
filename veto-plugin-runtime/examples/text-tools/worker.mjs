import { createInterface } from 'node:readline';

// One JSON-RPC response per line. stdout is reserved for protocol messages.
for await (const line of createInterface({ input: process.stdin })) {
  const request = JSON.parse(line);
  let result;
  if (request.method === 'initialize' && request.params.protocolVersion === 1) {
    result = { protocolVersion: 1 };
  } else if (request.method === 'invoke' && request.params.handler === 'text.length') {
    result = [...request.params.arguments.text].length;
  } else {
    process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id: request.id, error: { code: -32601, message: 'Unsupported method' } }) + '\n');
    continue;
  }
  process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id: request.id, result }) + '\n');
}
