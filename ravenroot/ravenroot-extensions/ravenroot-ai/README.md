# Ravenroot AI node bundle

Two node types against an operator-configured OpenAI-compatible endpoint:

- **`llm-prompt`** sends the incoming payload following a prompt and continues with the model's
  answer. **One call**, and no second turn is reachable from it.
- **`agent`** runs a **bounded loop**: it sends an objective together with the tools it exposes,
  the model either answers or asks for a tool, the tool answers, and the loop repeats until the model
  answers or a budget runs out.

The second is not a mode of the first. They share this bundle, the model profile, the managed channel
and the provenance marking, and share no behaviour — which is why the request document, the reader and
the failure vocabulary are separate types rather than widened ones.

**Never supplied with the product jar or with its default image.** Model-invoking nodes travel through
this bundle only: compiled
with `./plugin.sh build ai`, installed with `./plugin.sh install`, named in
`RAVENROOT_ENABLED_PLUGINS`, and included in an image the operator builds. `./plugin.sh build --all`
skips this extension and says so; `./plugin.sh check-published` refuses it in any directory destined
for publication.

The operator guide — the four environment variables, the profile document, the service grant, the
reserved-network exception, a graph that uses the node, and what to do when it does not answer —
is covered by the public [model, agent, and program integration guide](../../../docs/integrator-guide/ai-programs.md).

## What is inside

| Class | Role |
|---|---|
| `AiNodePackage` | the SDK /2 package; contributes both behaviors |
| `LlmPromptNodeBehavior` | the node type: descriptor (declaring the capability `ai`), refusals, admission, the call |
| `OpenAiCompatibleChat` | **the embedded adapter**: the chat-completions request and response document |
| `LlmProfile` / `LlmProfileResolver` / `EnvironmentLlmProfileResolver` | the operator-owned endpoint, model and credential binding |
| `PromptTemplate` | `{{payload}}` rendering, carried over from the departed core node |
| `LlmPromptException` | the closed failure vocabulary; no payload, prompt, response body or credential ever travels in it |
| `AgentNodeBehavior` | the `agent` node type: descriptor (declaring `ai` **and** `agentic`), the loop, the three budgets, admission held across turns |
| `AgentTurn` | **the agent's own wire document**: immutable operator policy in the system role, untrusted graph content in user roles, tool declarations, and the reader that can tell an answer from a tool call |
| `ModelInputProvenance` | per-invocation source-kind and SHA-256 evidence for untrusted inputs and model outputs, without retaining their content |
| `AgentTool` / `LoadSkillTool` | what an agent may call; `load_skill` hands over one skill body per run, on request |
| `AgentSkill` | the author-declared skill: the numbered slot properties, the reader, and every declaration this bundle refuses to build a node from |
| `AgentException` | the agent's closed failure vocabulary, separate because a loop can exhaust turns and tokens and one call cannot |
| `AgentSkillException` | a skill declaration the bundle can never serve, refused while the graph is composed. An `IllegalArgumentException`, which is what makes the refusal answerable rather than merely early — see its Javadoc for the author-facing diagnostic gap |

## Two properties worth knowing before reading the code

**The credential is never in this process's reach.** Both behaviors require `OUTBOUND_HTTP` and
deliberately not `CREDENTIAL_RESOLUTION`; the agent additionally requires `TOOL_AUTHORIZATION`. A
profile names an `OutboundCredentialBinding`; the runtime resolves it and places it on the request.
There is no code path here that could return a secret.

**A skill's body is not in the prompt until it is asked for.** An untrusted author turn lists a declared
skill's name and description; `LoadSkillTool` hands the body over when the model calls for it, once
per run. That is the whole difference between a skill and more text in `instructions` — an unused
skill costs one line. Keeping that disclosure boundary in `load_skill` also keeps the agent loop
independent of how skills are declared. The shape of the slot properties, and the measurement behind choosing three numbered properties over one
JSON document, are on `AgentSkill`.

**Only operator policy receives the system role.** Graph instructions, objectives, payloads, skill
metadata, retrieved tool content, and model output are all structurally untrusted. The invocation
records bounded source-kind/digest provenance for each of those inputs; it never stores their raw
content in the provenance record.

**A model request is not authority.** In addition to `OUTBOUND_HTTP`, the `agent` behavior requires
the `TOOL_AUTHORIZATION` package capability. The runtime parses and canonicalizes each requested
argument object under fixed bounds, evaluates the deployment's tool policy with the trusted tenant
identity immediately before the effect, and emits correlated payload-free audit records. Missing or
malformed authorization denies by default. `REQUIRE_APPROVAL` is a no-effect refusal in this release;
approval redemption belongs to the approval lifecycle.

**A tool answers; a budget terminates.** An `agent` tool never fails the node: a wrong argument, an
invented tool name or a broken tool comes back as a tool *result* the model can read and correct. A
non-deterministic component must not be able to end a traversal by mistyping. What stops a model that
cannot correct itself is `maxTurns`, which is finite and defaults to 8.

**Nothing here writes the synthetic-provenance marker.** The descriptors declare `ai` — and `agentic`
for the agent —
and the runtime marks a completion by reading the registered catalogue descriptor — the same
mechanism that marked the node when it lived in the core. That is also why a
refusal is always a failed future and never a `NodeResult`: a successful result returned from a
refusal would be stamped as model-generated content that no model generated.

## Building and testing this module alone

```sh
JAVA_HOME=/path/to/jdk21 mvn -f ../../pom.xml -pl ravenroot-extensions/ravenroot-ai -am test
```

`AiBundleEndToEndTest` runs the node through the real managed channel against a loopback
OpenAI-compatible endpoint, and is the executable form of this bundle's documented behavior.
