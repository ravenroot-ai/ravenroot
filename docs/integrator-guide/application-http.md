# Application, HTTP, SSE, and CLI integration

Choose the narrowest transport while preserving the same execution and authority contract.

## Integration sequence

1. Use the transport-neutral application API when Ravenroot shares the host process; do not introduce HTTP solely for internal composition.
2. For remote use, inspect GraphML first, submit with explicit `mode`, retain the HTTP 202 execution ID, and poll the execution resource to terminal state.
3. Open SSE for live changes and retain the exact string ID of the last processed execution frame. Resume with `Last-Event-ID` only within the same authenticated tenant, source and continuity domain; reconcile declared retention gaps from authoritative state. Recent-event polling is a separate legacy projection.
4. Use the CLI for operator and shell workflows, not as a way around authentication or ownership.

## Authority boundary

The host decides how caller identity maps to the application or HTTP boundary. Ravenroot remains authoritative for resource ownership, mode semantics, and state transitions.

## Decode an event stream in the CLI

`ravenroot events decode` consumes an SSE response body from standard input. It is a local decoder, like local GraphML validation: it starts no engine, contacts no server and resolves no credentials. Global remote options do not turn decoding into an HTTP request. Use a captured response body or pipe one from an HTTP client whose credentials you manage separately:

```sh
ravenroot events decode < capture.sse
```

Each complete execution frame produces a JSON line containing its SSE event name, exact string ID and decoded data. Legacy unversioned input retains its aliases and gains canonical `eventType` without a fabricated schema version. Source inference requires unambiguous native cursor fields. Unknown JSON members are tolerated; unknown SSE fields or event names never become execution records. Output is decoded input, not proof that an archive came from an authenticated server.

The decoder bounds raw frames at 65536 bytes before allocating an unbounded line, and applies finite JSON structure limits. It accepts LF, CR and CRLF, an initial UTF-8 BOM, and multiline `data`; malformed UTF-8 is rejected. IDs use exact signed-long decimal arithmetic, including values beyond JavaScript's safe integer range. Conflicting aliases, IDs or source fields and unsupported versions are errors.

Keepalive comments produce no execution output. Named retention and slow-consumer controls are surfaced separately and terminate with a nonzero result, so a pipeline does not silently apply them as execution history. EOF after a complete frame succeeds; pending incomplete data is discarded and reports `INCOMPLETE_EVENT_STREAM`. Malformed input is reported with a safe error classification, never a raw input or exception dump. A successful decode does not establish cross-stream continuity or execution completion.

## Linked contracts

- [Primary interface](../reference/api-cli.md)
- [Operational or security model](../reference/execution-events.md)
