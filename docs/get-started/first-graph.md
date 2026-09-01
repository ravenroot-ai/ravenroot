# Build your first graph

This tutorial creates the smallest useful executable document: one entry, one template behavior, one successful terminal, and one failure terminal.

## Create the document

From the source-checkout root, enter the installation directory created on the previous page:

```bash
cd ravenroot-quickstart/ravenroot
```

Save the following as `hello.graphml` in that directory:

```xml
<graphml xmlns="http://graphml.graphdrawing.org/xmlns">
  <key id="kind" for="node" attr.name="kind" attr.type="string"/>
  <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
  <key id="template" for="node" attr.name="template" attr.type="string"/>
  <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
  <graph id="ravenroot-minimal" edgedefault="directed">
    <node id="start"><data key="kind">START</data></node>
    <node id="greet">
      <data key="kind">BEHAVIOR</data>
      <data key="behavior">template</data>
      <data key="template">Hello, {{payload}}! Ravenroot received your request.</data>
    </node>
    <node id="end"><data key="kind">END</data></node>
    <node id="error"><data key="kind">ERROR</data></node>
    <edge id="e1" source="start" target="greet"><data key="outcome">continue</data></edge>
    <edge id="e2" source="greet" target="end"><data key="outcome">continue</data></edge>
    <edge id="e3" source="greet" target="error"/>
  </graph>
</graphml>
```

The document declares keys before the graph, uses the GraphML namespace, and contains one directed top-level graph. `START` selects the entry, `BEHAVIOR` invokes the named `template` behavior, `END` terminates success, and `ERROR` receives unhandled failure.

## Validate before execution

Still in `ravenroot-quickstart/ravenroot`, use the distribution CLI:

```bash
bin/ravenroot validate hello.graphml
bin/ravenroot inspect hello.graphml
```

Validation exits 0 when accepted, 1 when the document is refused or invalid, and 2 for command misuse. Inspection resolves the graph without executing behavior. The server equivalent is `POST /v1/graphs/inspect`.

## Recreate it in the workspace

Open `http://127.0.0.1:8080`, enable **Modify**, add START, BEHAVIOR, END, and ERROR nodes, and set the behavior and template properties in the Inspector. Connect the successful route with outcome `continue`; leave the failure edge without an outcome so it is the undeclared failure route.

The UI palette comes from `GET /v1/node-types`. Graph structure never grants credentials, tools, network access, or adapter installation.

## Preserve the source

Original GraphML bytes remain authoritative. Unknown extensions survive an unmodified import/export round trip. After a structural mutation, Ravenroot refuses an export that would falsely claim byte preservation.

See the exact [GraphML profile](../reference/graphml.md) and [workspace authoring controls](../user-guide/workspace-authoring.md).
