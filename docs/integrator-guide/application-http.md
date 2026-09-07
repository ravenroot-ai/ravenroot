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

`ravenroot events decode` consumes an SSE response body from standard input. It is a local decoder, like local GraphML validation: it starts no engine, contacts no server and resolves no credentials. Global `--server` and `--token-file` options are parsed but ignored by this local command before token resolution or backend construction. Use a captured response body or pipe one from an HTTP client whose credentials you manage separately:

```sh
ravenroot events decode < capture.sse
```

Each complete execution frame produces a JSON line shaped as `{event, id, data}`: the SSE event name, exact string ID and decoded JSON object. Legacy fallback from `type` to canonical `eventType` applies only to unversioned input, which retains its aliases without a fabricated schema version. Version 1 requires the original canonical `eventType`; either source may additionally carry an equal `type` alias. Legacy source inference requires unambiguous native cursor fields. Unknown JSON members are tolerated; unknown SSE fields and unknown named events are skipped. Output is decoded input, not proof that an archive came from an authenticated server.

The decoder bounds raw frames at 65536 bytes before allocating an unbounded line. Decoded JSON also uses the shared structural limits: 32 nested levels, 1000 members per collection, 10000 values and 256 UTF-16 code units per key; text values are bounded at 65536 code units within the byte-limited frame. It accepts LF, CR and CRLF, an initial UTF-8 BOM, and multiline `data`; malformed UTF-8 is rejected. IDs use exact signed-long decimal arithmetic, including values beyond JavaScript's safe integer range. Conflicting aliases, IDs or source fields and unsupported versions are errors.

Keepalive comments produce no output. `stream-truncated` and `stream-overrun` are emitted as separately named JSON lines and then terminate with exit code 3, so a pipeline does not silently apply them as execution history. A control's `id` is the last SSE ID, or null if none has been set. EOF after complete frames succeeds; pending incomplete data is discarded and reports `INCOMPLETE_EVENT_STREAM` with exit code 1. Malformed input is reported with a safe error classification, never a raw input or exception dump. A successful decode does not establish origin, authentication, cross-stream continuity or execution completion.

## Linked contracts

- [Primary interface](../reference/api-cli.md)
- [Operational or security model](../reference/execution-events.md)
