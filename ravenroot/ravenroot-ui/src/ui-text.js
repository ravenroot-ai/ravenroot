import { productError } from './product-error.js';
import { STATIC_ENGLISH_MESSAGES } from './ui-static-text.js';
import { DYNAMIC_ENGLISH_MESSAGES } from './ui-dynamic-text.js';

export const ENGLISH_MESSAGES = Object.freeze({
  'correlation.preview.title': 'Application correlation preview',
  'correlation.preview.description': 'Test the current selectors against an explicit sample of the input this node receives. This reads no execution content and creates no history.',
  'correlation.preview.sample': 'Sample input (JSON)',
  'correlation.preview.bound': 'Sample editor limit: {maximum} UTF-8 bytes. The service also applies its configured input and selector limits.',
  'correlation.preview.run': 'Preview application context',
  'correlation.preview.invalidSample': 'Enter valid JSON within the sample editor byte limit.',
  'correlation.preview.loading': 'Evaluating sample…',
  'correlation.preview.refused': 'Sample refused: {reason}. Selector: {selector}.',
  'correlation.preview.requiresExisting': 'Valid selection. Actual admission additionally requires an existing open history in the authorized deployment and version.',
  'correlation.preview.generated': 'This sample needs a new identity. The zero UUID below is an illustrative preview placeholder.',
  'correlation.preview.selected': 'Valid sample selection. Actual admission still applies deployment authority and lifecycle checks.',
  'correlation.preview.failed': 'Preview unavailable. Retry against the connected service.',
  'correlation.entry.title': 'START application context',
  'history.details': 'Record details',
  'history.facts.applicationContext': 'Application context',
  'history.facts.closureSignal': 'Requested closure and original cause',
  'history.facts.derivation': 'Original derivation cause',
  'history.contentPolicy': 'Captured content policy: {policy}. Retention gap: {gap}.',
  'history.refreshCoverage': 'Refresh coverage',
  'history.tooltip.visits': 'Total visits',
  'history.tooltip.attempts': 'Total attempts',
  'history.coverage.edgeNotLoaded': 'Edge coverage is not yet loaded. Its traversal count is unknown.',
  'history.unknown': 'unavailable',
  'history.row.business': '{reference} · Business {id} · {state} · {lifecycle}',
  'history.row.traversal': 'Traversal {id} · Process {process} · Entry {entry} · {time} · {status}',
  'history.row.visit': 'Visit {id} · Node {node} · Position {position} · {time} · {status}',
  'history.row.attempt': 'Attempt {ordinal} · {id} · {time} · {status}',
  'history.row.join': 'Node {node} · Round {round} · {received}/{expected} contributions · {phase}',
  'history.return': 'Return to graph',
  'history.choose': 'Choose a business history or an uncorrelated traversal',
  'history.draftChanged': 'Your draft differs from this deployment. Monitoring shows the original deployed graph; these edits have not been deployed or run.',
  'history.draftMatches': 'Monitoring shows the pinned deployment. Later draft edits require a separate deployment.',
  'history.deployedOnly': 'Read-only deployed graph. Monitoring uses this exact pinned version.',
  'history.retry': 'Retry history',
  'history.retryView': 'Retry deployment view',
  'history.nodeTotals': 'Total visits {visits} · Attempts {attempts} · Active {active} · Failed visits {failed}',
  'history.title': 'Deployment history',
  'history.business': 'Business histories',
  'history.uncorrelated': 'Uncorrelated traversals',
  'history.traversals': 'Actual traversals',
  'history.previous': 'Previous page',
  'history.next': 'Next page',
  'history.refresh': 'Refresh page',
  'history.close': 'Close preview',
  'history.inspect': 'Inspect node',
  'history.visits': 'Visits',
  'history.attempts': 'Attempts',
  'history.parents': 'Causal parents',
  'history.activity': 'Load captured content',
  'history.joins': 'Join rounds',
  'history.members': 'Contributions',
  'history.member.received': 'Contribution {id} · Received',
  'history.member.missing': 'Contribution {id} · Expected and missing',
  'history.moreNodes': 'Load more node coverage',
  'history.moreEdges': 'Load more edge coverage',
  'history.coverage.nodeNotLoaded': 'Node coverage is not yet loaded. Its visit count is unknown.',
  'history.coverage.partial': 'Partial coverage: {nodes} node rows and {edges} edge rows loaded. Omitted activity is unknown.',
  'history.coverage.complete': 'Complete coverage: {nodes} visited nodes and {edges} traversed edges.',
  'history.coverage.unavailable': 'Coverage unavailable: nodes {nodes}, edges {edges}.',
  'history.empty': 'No records in this page.',
  'history.loading': 'Loading bounded history page…',
  'history.failed': 'History read failed. Retry this page.',
  'history.pinned': 'Pinned deployment {deploymentId} · {version}',
  'history.limit': 'Page limit {limit}. Records and previews load only when requested.',
  'history.previewLimit': 'Close a preview before opening another. At most two graphs are mounted.',
  'history.selectNode': 'Select a graph node to inspect its actual visits.',
  'history.unavailable': 'Availability: {availability}',

  'workspace.run.persistenceRequired': 'Run needs a saved workspace association before creating a deployment. Restore workspace storage, then retry.',
  'workspace.run.confirmDeployment': 'Run “{name}” as one pinned deployment? Its sources start listening and its initial START runs once. Later Run actions reconcile this same deployment.',
  'workspace.run.attached': 'Deployment {deploymentId} · Pinned {version}',
  'workspace.run.draftSeparate': 'Monitoring uses the pinned deployment. Local draft edits remain separate.',
  'workspace.run.reconciling': 'Reconciling the saved deployment and its original initial START.',
  'workspace.run.ready': 'Deployment ready. The initial traversal identity is retained across retry and reload.',
  'workspace.run.failed': 'Deployment Run could not be reconciled. Retry uses the same saved deployment identity.',

  'workspace.run.associationChanged': 'The deployment association or workspace authority changed. Your saved deployment identity is retained. Reconcile it before retrying.',

  ...STATIC_ENGLISH_MESSAGES,
  ...DYNAMIC_ENGLISH_MESSAGES,
  'repository.open.preserved': 'This source is already open. Your local graph and undo history were kept.',
  'repository.open.saveFailed': 'The previous save is still refused: {reason}',
  'repository.error.operationChanged': 'The source revision or local work changed while this operation was pending. Your edits are intact. Reopen the current managed source before retrying.',
  'repository.error.sourceBinding': 'This open document belongs to an earlier or different repository connection. Your edits are intact. Download GraphML to protect your work, then open the current managed source and reapply your edits.',
  "repository.picker.title": "Open from repository",
  "repository.picker.search": "Search document names or paths",
  "repository.picker.filter": "Source filter",
  "repository.picker.all": "All sources",
  "repository.picker.draft": "Editable drafts",
  "repository.picker.release": "Read-only releases",
  "repository.picker.cancel": "Cancel",
  "repository.picker.draftItem": "{path} · Editable draft v{version} · Released v{released} · {publication}",
  "repository.picker.releaseItem": "{path} · Read-only release v{version} · {publication}",
  "repository.picker.published": "Published",
  "repository.picker.pending": "Publication pending or failed",
  "repository.picker.loading": "Loading repository documents…",
  "repository.picker.failed": "Could not load the next page. The inventory is incomplete. Retry to continue.",
  "repository.picker.partial": "More documents are available. Load more to search the complete inventory.",
  "repository.picker.empty": "This repository has no documents in your workspace.",
  "repository.picker.noMatch": "No loaded documents match this search.",
  "repository.picker.complete": "All repository documents loaded.",
  "repository.picker.retry": "Retry",
  "repository.picker.more": "Load more",
  "repository.launch.invalid": "Invalid repository launch: provide one repositoryDocument and source=draft or source=release, without file.",
  "repository.launch.unavailable": "The managed document could not be opened. Authenticate and connect to a runtime with repository authoring, then reopen this link.",
  "repository.error.importConflict": "Save refused: this filename already exists or its source state changed. Your edits are intact. Choose a new filename, or open the managed document and reapply your edits.",
  "repository.error.conflict": "Save or version creation refused because repository state changed, a successor exists, or this released version is immutable. Your edits are intact. Reopen current sources, then open an existing successor or create one from the release. Back up divergent edits before reconciling.",
  "repository.error.authorization": "Repository operation refused. Your edits are intact. Authenticate with permission for this document, then retry.",
  "repository.error.failed": "Repository operation failed. Your edits are intact. {reason}",
  "repository.version.confirm": "Create editable draft v{next} from immutable release v{current}? Existing draft work will be preserved.",
  "repository.version.open": "Open existing draft",
  "repository.version.create": "Create next version",
  'repository.version.draftExplanation': 'Drafts keep their assigned version. Open the read-only release to create its successor.',
  'repository.error.connectionChanged': 'The service or authenticated workspace changed. Reopen the managed document on the current connection before continuing.',
  'repository.version.reconcile': 'This draft differs from its release without a valid successor version. Back up all work before reconciling the draft; no version was created.',
  'catalog.empty.connecting': 'Connecting to the service to load programmable nodes…',
  'catalog.empty.disconnected': 'Connect to the service to load programmable nodes.',
  'catalog.empty.available': 'The service answered with an empty node catalog. Custom and unknown behaviors remain editable.',
  'catalog.empty.authenticationRequired': 'The service replied that authentication is required (HTTP 401). Paste an access token to load the node catalog. Custom and unknown behaviors remain editable.',
  'catalog.empty.accessRevoked': 'The service refused the node catalog for this token (HTTP 403). Access has been revoked. Custom and unknown behaviors remain editable.',
  'catalog.empty.unreachable': 'Node catalog unreachable: {reason}. Custom and unknown behaviors remain editable.',
  'catalog.empty.requestFailed': 'the request failed',
  'legend.filterNodeType': 'Filter node type: {label}',
  'legend.filterEdgeType': 'Filter edge type: {label}',
  'nodeVisualType.start': 'Start',
  'nodeVisualType.end': 'End',
  'nodeVisualType.terminal': 'Terminal',
  'nodeVisualType.error': 'Error',
  'nodeVisualType.consumer': 'Consumer',
  'nodeVisualType.handler': 'Handler',
  'nodeVisualType.agent': 'Agent',
  'nodeVisualType.flow': 'Flow',
  'nodeVisualType.actor': 'Actor',
  'nodeVisualType.system': 'System',
  'nodeVisualType.trace': 'Trace',
  'nodeVisualType.human-task': 'Human task',
  'nodeVisualType.workspace': 'Workspace',
  'edgeVisualType.failed': 'FAILED',
  'edgeVisualType.failure': 'FAILURE ROUTE',
  'edgeVisualType.completed': 'COMPLETED',
  'edgeVisualType.continue': 'CONTINUE',
  'edgeVisualType.continueP': 'CONTINUE (parallel)',
  'edgeVisualType.validate': 'VALIDATE',
  'edgeVisualType.ping': 'PING',
  'edgeVisualType.outcome': '*_OUTCOME',
  'edgeVisualType.callback': 'CALLBACK_*',
  'edgeVisualType.undefined': 'UNDEFINED',
  'assistant.request.failed': 'The assistant request failed.',
  'assistant.context.degraded': 'Some context is unavailable. Check the chips below before relying on the answer.',
  'assistant.draft.unavailable': 'The assistant is not available, so nothing can be sent.',
  'assistant.draft.empty': 'Type a question before sending.',
  'humanTasks.context.required': 'Run or select an authoritative deployment context to see Human Tasks.',
  'humanTasks.capability.unavailable': 'This runtime does not expose embedded Human Task presentation.',
  'humanTasks.refresh.failed': 'Human Tasks could not be refreshed: {reason}',
  'humanTasks.validation.failed': 'The Human Task response failed client validation.',
  'runtimeClient.validation.failed': 'The service response or client request failed client validation.',
  'startupImport.url.unsupported': 'The graph URL must use HTTP or HTTPS.',
  'startupImport.cancelled': 'The graph import was cancelled.',
  'startupImport.failed': 'The graph could not be imported. Check the file and try again.',
  'executionOutcome.title': 'execution outcome',
  'executionOutcome.handled': { one: '{status} with one handled node failure: {nodes}', other: '{status} with {count} handled node failures: {nodes}' },
  'executionOutcome.handledUnavailable': { one: '{status} with one handled node failure; node list unavailable', other: '{status} with {count} handled node failures; node list unavailable' },
  'executionOutcome.degraded': { one: '{status}, degraded: one node ran as an unresolved default: {nodes}', other: '{status}, degraded: {count} nodes ran as unresolved defaults: {nodes}' },
  'executionOutcome.degradedUnavailable': { one: '{status}, degraded: one node ran as an unresolved default; node list unavailable', other: '{status}, degraded: {count} nodes ran as unresolved defaults; node list unavailable' },
  'executionOutcome.cancelled': 'CANCELLED (stopped on request, stored as FAILED)',
  'executionOutcome.failed': 'FAILED',
  'executionOutcome.clean': '{status}, no handled failures and no defaulted nodes',
  'executionOutcome.bypassed': { one: 'One node bypassed — behaviour not invoked, traversal continued past it: {nodes}. Cause is reported per node, in the NODE_BYPASSED entries.', other: '{count} nodes bypassed — behaviour not invoked, traversal continued past them: {nodes}. Cause is reported per node, in the NODE_BYPASSED entries.' },
  'common.yes': 'yes',
  'common.no': 'no',
  'common.absent': 'absent',
  'common.unavailable': 'unavailable',
  'common.unknown': 'unknown',
  'common.transient': 'transient',
  'common.enabled': 'enabled',
  'common.disabled': 'disabled',
  'common.pending': 'pending',
  'common.engine': 'engine',
  'workspace.sourceSession.state': '{state}',
  'workspace.sourceSession.retrying': ' · status unavailable, retrying',
  'workspace.layout.column': 'Column',
  'workspace.layout.row': 'Row',
  'workspace.property.secretHelp': 'Enter an opaque secret reference. Existing references are redacted and never copied into this form.',
  'workspace.property.batchHelp': 'Changing this value updates every selected node.',
  'workspace.edge.failure': 'failure',
  'workspace.bypass.help': 'The node does not run its behaviour. The traversal continues past it with the payload it received, unchanged. Everything downstream executes normally.',
  'workspace.nature.help': 'Runtime nature controls this node\'s lifecycle — when a runtime instance exists and how many may be authoritative. It does not control concurrency or replica count.',
  'workspace.nature.declared': 'Declared on this node.',
  'workspace.nature.inherited': 'Inherited default ({value}).',
  'workspace.concurrency.declared': 'Declared ({value}).',
  'workspace.concurrency.invalid': 'Declared value is invalid and will be refused.',
  'workspace.concurrency.help': 'Positive, per node and per traversal. The trusted catalog ceiling is {ceiling}; stricter plugin or profile limits still apply.',
  'workspace.concurrency.placeholder': 'Inherit default ({value})',
  'workspace.join.effectiveNone': 'Effective now: no join — each arrival runs independently.',
  'workspace.join.effectiveAll': 'Effective now: waits for all {count} branches.',
  'workspace.join.effectiveFirst': 'Effective now: first arrival wins, the rest are discarded.',
  'workspace.join.effectiveQuorum': 'Effective now: waits for {quorum} of {count} branches.',
  'workspace.join.help': 'No join writes nothing to the document. A declared choice writes exactly one property. A join needs at least two distinct incoming edges; START arrivals are always external.',
  'workspace.join.notStart': 'Not a join · START receives externally.',
  'workspace.join.notEnough': { one: 'Not a join · one incoming edge; 2 required.', other: 'Not a join · {count} incoming edges; 2 required.' },
  'workspace.program.outputTruncated': '{output}… (output truncated)',
  'workspace.minimap.document': 'document',
  'workspace.minimap.monitoring': 'Monitoring',
  'workspace.minimap.design': 'Design',
  'workspace.minimap.overview': 'Overview — {name}',
  'workspace.edge.selectedSource': '{name} is selected and moves in Editing. Move to an unselected node to start an edge.',
  'workspace.edge.cannotConnect': 'Cannot connect: {reason}.',
  'workspace.edge.cannotAddConnected': 'Cannot add and connect node: {reason}.',
  'workspace.sourceSession.localOnly': { one: 'One source node · local process only', other: '{count} source nodes · local process only' },
  'workspace.refusal.node': 'node {id}',
  'workspace.refusal.property': 'property {name}',
  'workspace.refusal.incident': ' · incident {id}',
  'workspace.refusal.detail': '{phase} · {reason}{location}. {remediation}{incident}',
  'workspace.refusal.start': 'Start refused',
  'workspace.refusal.sourceSession': 'Source session refused',
  'workspace.refusal.graphAdmission': 'Graph admission refused',
  'workspace.refusal.invalidStructure': 'Correct the graph structure and retry.',
  'workspace.refusal.requiredProperty': 'Provide the required property and retry.',
  'workspace.refusal.propertyType': 'Use the property type declared by the trusted catalog.',
  'workspace.refusal.propertyValue': 'Choose a value allowed by the trusted catalog.',
  'workspace.refusal.propertyRange': 'Choose a value inside the catalog bounds.',
  'workspace.refusal.propertySize': 'Reduce the property to the catalog size bound.',
  'workspace.refusal.propertyName': 'Correct the property spelling to match the trusted catalog.',
  'workspace.refusal.sourceRequired': 'Add and configure an inbound source before starting a listener session.',
  'workspace.refusal.sourceCapability': 'Install or enable the trusted source package for this node.',
  'workspace.refusal.startupFailed': 'Quote the incident handle to an operator, then retry after the runtime issue is resolved.',
  'workspace.program.artifactInactive': '{node}: {detail}',
  'workspace.program.artifactInactiveFallback': 'artifact is not ACTIVE',
  'workspace.program.artifactInactiveAny': 'A program artifact is not ACTIVE',
  'workspace.run.catalogUnavailable': 'The trusted node catalog is unavailable, so Run cannot safely decide whether this graph requires a listener session.',
  'workspace.run.catalogLoading': 'The trusted node catalog is still loading. Wait for it before running this graph.',
  'workspace.assistant.waiting': 'Waiting for the assistant…',
  'workspace.assistant.ready': 'Ready. Graph proposals require confirmation.',
  'workspace.panel.section': 'section',
  'workspace.panel.collapse': 'Collapse {section}',
  'workspace.panel.expand': 'Expand {section}',
  'workspace.panel.resize': 'Resize {before} and {after} panels',
  'workspace.panel.leftToolbox': 'left toolbox',
  'workspace.panel.rightSidebar': 'right sidebar',
  'workspace.panel.noOpen': 'No open panels in the {owner}; use Panels to reopen one',
  'workspace.catalog.categoryDetail': ', category {category}',
  'workspace.deployment.inboundSources': { one: 'one inbound source node', other: '{count} inbound source nodes' },
  'workspace.deployment.noInboundSources': 'no inbound source',
  'embed.tooltip.value': '{label}: {value}',
  'embed.tooltip.unavailable': '{label}: Unavailable',
  'embed.tooltip.id': 'ID',
  'embed.tooltip.state': 'State',
  'embed.tooltip.activeInstances': 'Active instances',
  'embed.tooltip.inFlightArrivals': 'In-flight arrivals',
  'embed.tooltip.lastEvent': 'Last event',
  'embed.tooltip.lastEventTime': 'Last event time',
  'embed.tooltip.processingDuration': 'Processing duration',
  'embed.tooltip.fallback': 'Fallback',
  'embed.tooltip.bypassed': 'Bypassed',
  'embed.tooltip.recentActivity': 'Recent activity: {count}',
  'embed.tooltip.traversals': 'Traversals: {count}',
  'embed.tooltip.lastTraversal': 'Last traversal',
  'embed.tooltip.lastTraversalTime': 'Last traversal time',
  'embed.tooltip.configuredWeight': 'Configured weight',
  'assistant.context.graph': 'Open graph',
  'assistant.context.catalog': 'Node catalog',
  'assistant.context.validation': 'Validation errors',
  'assistant.context.execution': 'Last execution',
  'assistant.context.logs': 'Logs',
  'assistant.context.events': 'Event stream',
  'assistant.context.attached': '{label}: attached',
  'assistant.context.attachedDetail': '{label}: attached, {detail}',
  'assistant.context.unavailable': '{label}: unavailable',
  'assistant.context.unavailableDetail': '{label}: unavailable, {detail}',
  'activity.event.nodeTitle': '{type} · {nodeId}',
  'activity.event.title': '{type}',
  'activity.event.counts': ' · instances={instances} inFlight={inFlight}',
  'activity.event.diagnostic': ' · {value}{flags}',
  'activity.event.output': ' · output={value}{flags}',
  'activity.flag.redacted': 'redacted',
  'activity.flag.truncated': 'truncated',
  'assistant.inert.serviceUnavailable': 'This deployment does not provide the assistant service. Nothing is configured to answer, so nothing is sent.',
  'assistant.inert.noProfile': 'No model provider is configured. An operator registers a provider profile before this panel can be used.',
  'assistant.inert.insecureRefused': 'The configured provider uses plaintext outside the credential-free local exception. Only an operator can replace it with HTTPS or explicitly permit an eligible local endpoint.',
  'assistant.inert.hostNotAllowlisted': 'The configured provider is not permitted by this deployment’s outbound policy. Only an operator can widen it.',
  'assistant.inert.notSignedIn': 'Your Ravenroot session is not authenticated, so the assistant cannot be reached. Sign in to Ravenroot and try again.',
  'assistant.inert.notLinked': 'This deployment expects each author to connect to the model provider themselves, and this account is not connected yet. You can start that from here.',
  'assistant.failure.providerRefused': 'The model declined to answer this request. Rephrasing the question may help — nothing is wrong with the graph or with Ravenroot.',
  'assistant.failure.providerUnreadable': 'The model provider returned a response this build cannot read, so nothing was answered. Trying again may succeed.',
  'assistant.failure.providerRejected': 'The model provider rejected this request. That is a configuration problem an operator has to resolve, so retrying will not clear it.',
  'assistant.failure.providerUnavailable': 'The model provider could not be reached, or did not answer in time. Trying again may succeed.',
  'assistant.failure.egressRefused': 'This deployment’s outbound policy refused the provider destination. Only an operator can change that, so retrying will not help.',
  'assistant.failure.toolLoopExhausted': 'The model kept requesting context without reaching an answer, so the turn was stopped. A narrower question usually resolves this.',
  'assistant.failure.invalidTurn': 'The message could not be read, so nothing was sent to the model. Sending it again unchanged will fail the same way — try rewording it.',
  'assistant.failure.modelProposalInvalid': 'The request reached the model, but its graph proposal was still invalid after Ravenroot asked it to correct the proposal. Nothing was applied. Retrying will not help unless the request changes — rephrase it or add more graph detail.',
  'assistant.failure.adapterDefect': 'Ravenroot’s assistant connection failed because of a defect in Ravenroot itself, not in the provider. Retrying will do the same thing. This needs a fix in Ravenroot — please report it.',
  'assistant.connection.authorizationPending': 'Waiting for you to finish on the provider’s page. This panel will notice on its own — nothing else is needed here.',
  'assistant.connection.slowDown': 'The provider asked for fewer checks, so this panel is now asking less often. Nothing has gone wrong and there is nothing to retry.',
  'assistant.connection.accessDenied': 'The request was declined on the provider’s page, so no connection was made. Nothing further will happen unless you start again.',
  'assistant.connection.expiredToken': 'The code ran out before it was used, so this attempt is over. Starting again issues a new code.',
  'assistant.connection.unavailable': 'The provider could not be reached, or answered something this build cannot read, so the attempt stopped. Starting again may succeed.',
  'catalog.node.unnamed': 'Unnamed node',
  'catalog.origin.core': 'Core nodes',
  'catalog.origin.bundle': 'Installed bundles',
  'catalog.category.other': 'Other',
  'visualGroup.header': '▦ {name} · {count}',
  'visualGroup.summary': { one: '▦\n{name}\nOne member', other: '▦\n{name}\n{count} members' },
  'graphField.name': 'Name',
  'graphField.kind': 'Ravenroot kind',
  'graphField.behavior': 'Behavior',
  'graphField.classname': 'Class name',
  'graphField.classification': 'Visual classification',
  'graphField.description': 'Description',
  'graphField.outcome': 'Outcome',
  'graphField.command': 'Command',
  'graphField.status': 'Status',
  'graphField.parallel': 'Parallel',
  'graphField.trafficWeight': 'Traffic weight',
  'joinKind.none': 'No join — each arrival runs independently',
  'joinKind.all': 'Wait for all branches',
  'joinKind.first': 'First arrival wins',
  'joinKind.quorum': 'K of N branches',
  'executionEvent.unknown': 'Execution activity was reported.',
  'executionEvent.nodeCompletedSuccess': 'Node completed successfully.',
  'executionEvent.nodeCompletedOutcome': 'Node completed and routed its "{reason}" outcome.',
  'executionEvent.nodeFailedReason': 'Node failed with {reason}. Protected diagnostics may contain more detail.',
  'executionEvent.executionFailedReason': 'Execution failed with {reason}. Protected diagnostics may contain more detail.',
  'executionEvent.joinFailedReason': 'Join conditions could not be satisfied: {reason}.',
  'executionEvent.retryReason': 'Node attempt failed as "{reason}" and another attempt was scheduled.',
  'executionEvent.bypassedAuthored': 'Node was bypassed: the graph author switched this node off.',
  'executionEvent.bypassedCommand': 'Node was bypassed: the traversal was not executing node behaviours.',
  'executionEvent.executionStarted': 'Execution started.',
  'executionEvent.nodeStarted': 'Node execution started.',
  'executionEvent.nodeBypassed': 'Node was bypassed.',
  'executionEvent.nodeDefaulted': 'Node used its configured fallback.',
  'executionEvent.nodeCompleted': 'Node completed.',
  'executionEvent.sourceBoundary': 'Traversal stopped at a source boundary; the listener remains active.',
  'executionEvent.edgeTraversed': 'Edge was traversed.',
  'executionEvent.nodeFailed': 'Node failed. Protected diagnostics may contain more detail.',
  'executionEvent.retryScheduled': 'Node attempt failed and another attempt was scheduled.',
  'executionEvent.joinSatisfied': 'Join conditions were satisfied.',
  'executionEvent.joinBacklog': 'A join is holding state for several iterations.',
  'executionEvent.joinDiscarded': 'A duplicate or late join arrival was ignored.',
  'executionEvent.joinFailed': 'Join conditions could not be satisfied.',
  'executionEvent.executionPaused': 'Execution was paused and is holding before its next node.',
  'executionEvent.executionResumed': 'Execution was resumed and is running again.',
  'executionEvent.executionCompleted': 'Execution completed successfully.',
  'executionEvent.executionFailed': 'Execution failed. Protected diagnostics may contain more detail.',
  'executionEvent.executionCancelled': 'Execution was cancelled before it produced a result.',
  'executionEvent.handlerRegistered': 'A handler was registered and the process is waiting for it.',
  'executionEvent.handlerEscalated': 'A waiting handler was escalated and can still be resolved.',
  'executionEvent.handlerExpired': "A handler's wait ended without a trigger.",
  'executionEvent.handlerDenied': 'A handler was denied and the process continued.',
  'executionEvent.handlerResolved': 'A handler was resolved and the process re-entered.',
  'commands.edit.groupSelection.label': 'Group selection',
  'commands.edit.groupSelection.help': 'Create a named visual group from two or more selected real nodes',
  'commands.view.toggleGroup.label': 'Expand / collapse group',
  'commands.view.toggleGroup.help': 'Toggle the selected visual group without changing execution or zoom',
  'commands.edit.renameGroup.label': 'Rename group',
  'commands.edit.replaceGroupMembers.label': 'Replace members with selection',
  'commands.edit.ungroup.label': 'Ungroup',
  'commands.edit.removeGroupMetadata.label': 'Remove unsupported visual group metadata',
  'commands.file.new.label': 'New Document',
  'commands.file.open.label': 'Open…',
  'commands.file.localOpen.label': 'Open local file…',
  'commands.file.localOpen.help': 'Open a GraphML or Graphify file from this device',
  'commands.file.replaceActive.label': 'Replace Active…',
  'commands.file.fork.label': 'Fork as Draft',
  'commands.file.fork.help': 'Create a new editable draft from this immutable graph snapshot.',
  'commands.file.save.label': 'Save GraphML',
  'commands.file.save.help': 'Save the workflow as GraphML',
  'commands.file.localSave.label': 'Download GraphML',
  'commands.file.localSave.help': 'Download this workflow as a local GraphML file',
  'commands.file.repositoryOpen.label': 'Open from repository…',
  'commands.file.repositoryOpen.help': 'Open a tenant graph from the configured Git repository',
  'commands.file.repositorySave.label': 'Save draft to repository',
  'commands.file.repositorySave.help': 'Save this GraphML draft with conflict protection',
  'commands.file.repositoryHistory.label': 'Repository history…',
  'commands.file.repositoryHistory.help': 'View, compare, or restore prior source revisions',
  'commands.file.repositoryDiscard.label': 'Discard draft changes',
  'commands.file.repositoryDiscard.help': 'Restore the reviewed release into the draft',
  'commands.file.repositoryDelete.label': 'Delete repository draft',
  'commands.file.repositoryDelete.help': 'Delete the draft while retaining Git history',
  'commands.file.repositoryRelease.label': 'Propose release review',
  'commands.file.repositoryRelease.help': 'Open a review proposal in the graph repository',
  'commands.file.publishedArtifacts.label': 'Published versions…',
  'commands.file.publishedArtifacts.help': 'Deploy or roll back using verified immutable publications',
  'commands.file.close.label': 'Close Document',
  'commands.edit.undo.label': 'Undo',
  'commands.edit.undo.help': 'Undo the last change',
  'commands.edit.undo.named': 'Undo {name}',
  'commands.edit.undo.empty': 'Nothing to undo',
  'commands.edit.redo.label': 'Redo',
  'commands.edit.redo.help': 'Redo the last undone change',
  'commands.edit.redo.named': 'Redo {name}',
  'commands.edit.redo.empty': 'Nothing to redo',
  'commands.edit.modify.label': 'Modify',
  'commands.edit.autosave.label': 'Autosave',
  'commands.edit.autosave.help': 'Save valid Inspector changes automatically',
  'commands.view.navigation.label': 'Navigation',
  'commands.view.navigation.help': 'Pan the graph with a hand cursor',
  'commands.edit.connect.label': 'Connect',
  'commands.edit.addNode.label': 'Add Node',
  'commands.edit.addEdge.label': 'Add Edge',
  'commands.edit.duplicateNode.label': 'Duplicate Node',
  'commands.edit.deleteSelection.label': 'Delete Selection',
  'commands.edit.deleteSelection.help': 'Delete selected elements in Modify mode',
  'nodeActions.trace': 'Trace full path from {name}',
  'nodeActions.duplicate': 'Duplicate {name}',
  'nodeActions.delete': 'Delete {name}',
  'commands.edit.migrateJoinSemantics.label': 'Migrate Join Semantics…',
  'commands.edit.migrateJoinSemantics.help':
    'Declare join.semantics on this document and materialise its currently-inferred join policies',
  'commands.view.fit.label': 'Fit Graph',
  'commands.view.fit.help': 'Fit graph to view',
  'commands.view.zoomIn.label': 'Zoom In',
  'commands.view.zoomIn.help': 'Zoom in',
  'commands.view.zoomOut.label': 'Zoom Out',
  'commands.view.zoomOut.help': 'Zoom out',
  'controls.nodeActionScale.label': 'Node minibar size',
  'controls.nodeActionScale.help': 'Resize node action minibars',
  'commands.view.graphs.label': 'Graphs…',
  'commands.view.graphs.help': 'List open documents and switch between them',
  'commands.view.closeAllDocuments.label': 'Close All Documents',
  'commands.view.closeAllDocuments.help': 'Close every document that is currently open',
  'commands.view.panels.label': 'Panels…',
  'commands.view.leftPanels.label': 'Left Panels',
  'commands.view.rightInspector.label': 'Right Inspector',
  'commands.view.themeDark.label': 'Dark theme',
  'commands.view.themeLight.label': 'Light theme',
  'commands.view.keyboardShortcuts.label': 'Keyboard Shortcuts',
  'commands.view.keyboardShortcuts.help': 'Toggle this help',
  'commands.file.open.repositoryLabel': 'Open from repository…',
  'commands.file.open.repositoryHelp': 'Open a tenant graph from the configured Git repository',
  'commands.file.save.repositoryLabel': 'Save draft to repository',
  'commands.file.save.repositoryHelp': 'Save this GraphML draft to the configured Git repository with conflict protection',
  'commands.file.fork.repositoryLabel': 'Open repository draft',
  'commands.workspace.horizontal.label': 'Horizontal panes',
  'commands.workspace.horizontal.help': 'Horizontal panes workspace panes',
  'commands.workspace.vertical.label': 'Vertical panes',
  'commands.workspace.vertical.help': 'Vertical panes workspace panes',
  'commands.workspace.grid.label': 'Grid panes',
  'commands.workspace.grid.help': 'Grid panes workspace panes',
  'commands.workspace.single.label': 'Single pane',
  'commands.workspace.single.help': 'Single pane workspace panes',
  'commands.workspace.reset.label': 'Reset workspace layout',
  'commands.workspace.reset.help': 'Reset workspace layout and pane sizes',
  'commands.layout.design.label': 'Design',
  'commands.layout.design.help': 'Restore the saved Design view without arranging it',
  'commands.layout.monitoring.label': 'Monitoring',
  'commands.layout.monitoring.help': 'Restore the saved Monitoring view without reheating it',
  'commands.layout.render.label': 'Render',
  'commands.layout.render.help': 'Recompute only the active graph view',
  'commands.layout.arrange.hierarchical.label': 'Arrange — Hierarchical',
  'commands.layout.arrange.hierarchical.help': 'Arrange left to right by workflow dependency',
  'commands.layout.arrange.flow.label': 'Arrange — Flow',
  'commands.layout.arrange.flow.help': 'Arrange as a compact directional flow',
  'commands.layout.arrange.organic.label': 'Arrange — Organic',
  'commands.layout.arrange.organic.help': 'Arrange related nodes with balanced organic spacing',
  'commands.layout.arrange.keep.label': 'Keep positions',
  'commands.layout.arrange.keep.help': 'Keep every node where it is and fit the graph in view',
  'commands.layout.arrange.hierarchical-new.label': 'Arrange — Hierarchical (new)',
  'commands.layout.arrange.hierarchical-new.help': 'Arrange on layers with orthogonal, label-aware edge channels',
  'commands.layout.arrange.layered-down.label': 'Arrange — Layered (top-down)',
  'commands.layout.arrange.layered-down.help': 'Arrange on layers from top to bottom, with the node name beside the card',
  'commands.run.play.label': 'Test',
  'commands.run.play.help': 'Test this graph with pass-through execution; node side effects are bypassed',
  'commands.run.start.label': 'Run',
  'commands.run.start.help': 'Execute real node behavior once, or start a process-local source session when this graph declares an inbound source',
  'commands.run.pause.label': 'Pause',
  'commands.run.pause.help': 'Pause this execution after its current node reaches a safe boundary',
  'commands.run.resume.label': 'Resume',
  'commands.run.resume.help': 'Resume this paused execution',
  'commands.run.cancel.label': 'Cancel',
  'commands.run.cancel.help': 'Cooperatively cancel this execution',
  'commands.run.stop.label': 'Stop',
  'commands.run.stop.help': 'Stop this document’s process-local source session; closing the document does not stop it',
  'commands.run.stopDeployment.label': 'Stop Deployment',
  'commands.run.stopDeployment.help': 'Unavailable: this runtime does not advertise the versioned deployment Stop API',
  'commands.run.shutdown.label': 'Shutdown Service',
  'commands.run.shutdown.help': 'Unavailable: this runtime does not advertise the authorized global Shutdown API',
  // These two act on THE COMMAND BAR'S SERVICE TOKEN — the sign-in to the
  // author's own Ravenroot service — and never on a credential a node uses. Their verbs were already
  // accurate and are deliberately unchanged: what was ambiguous was the FIELD's label ("Access
  // token", next to a Service URL, reads as a provider key box), and that label lives in
  // `index.html`, not here. It now reads "Service token".
  'commands.run.authenticate.label': 'Authenticate',
  'commands.run.forgetToken.label': 'Forget Token',
  // The ellipsis is the product's existing spelling for "this opens something", as in `Open…`,
  // `Replace Active…`, `Graphs…` and `Panels…`.
  'commands.run.credentials.label': 'Credentials…',
  'commands.run.runners.label': 'Agents and runners…',
  'commands.run.deployments.label': 'Deployments…',
  'commands.canvas.cursor.label': 'Move graph cursor',
  'commands.canvas.cursor.help': 'Move the graph cursor between nodes',
  'commands.canvas.edges.label': 'Step through connected edges',
  'commands.canvas.edges.help': "Step through the cursor node's edges",
  'commands.canvas.confirm.label': 'Confirm graph action',
  'commands.canvas.confirm.help': 'Select a node or confirm an edge',
  'commands.canvas.startEdge.label': 'Start edge',
  'commands.canvas.startEdge.help': 'Start an edge from the cursor node',
  'commands.canvas.reconnect.label': 'Reconnect edge',
  'commands.canvas.reconnect.help': "Reconnect the selected edge's target or source",
  'commands.canvas.cancel.label': 'Cancel graph gesture',
  'commands.canvas.cancel.help': 'Cancel the current graph gesture',
  'commands.ui.dismiss.label': 'Dismiss transient interface',
  'commands.ui.dismiss.help': 'Dismiss a transient menu, drag, filter, or trace',
  'inspector.unsaved.title': 'Save node changes?',
  'inspector.unsaved.description': 'This node has Inspector changes that have not been saved.',
  'inspector.unsaved.invalidDescription':
    'This node has invalid Inspector changes. Correct them, or discard them before changing selection.',
  'inspector.unsaved.save': 'Save',
  'inspector.unsaved.discard': 'Discard',
  'inspector.unsaved.cancel': 'Cancel',
  'inspector.history.pending': 'Save or discard the current Inspector changes before using Undo or Redo.',
  'closeAll.dirty.title': 'Save changes before closing all documents?',
  'closeAll.dirty.description.one':
    'One modified document has changes that have not been downloaded.',
  'closeAll.dirty.description.many':
    '{count} modified documents have changes that have not been downloaded.',
  'closeAll.dirty.save': 'Save All and Continue',
  'closeAll.dirty.discard': 'Discard All and Continue',
  'closeAll.sessions.title': 'Close documents with active local sessions?',
  'closeAll.sessions.description.one':
    'One document has an active local source session. Keeping it running detaches this browser only.',
  'closeAll.sessions.description.many':
    '{count} documents have active local source sessions. Keeping them running detaches this browser only.',
  'closeAll.sessions.item.one': '{name} — one inbound source node · scope LOCAL_PROCESS',
  'closeAll.sessions.item.many': '{name} — {count} inbound source nodes · scope LOCAL_PROCESS',
  'closeAll.sessions.keep': 'Keep Running and Close',
  'closeAll.sessions.stop': 'Stop and Close All',
  'closeAll.working.title': 'Closing documents…',
  'closeAll.working.description': 'Ravenroot is completing the selected save and stop actions before closing documents.',
  'closeAll.failure.title': 'Could not close all documents',
  'closeAll.failure.description':
    'The targeted documents remain open. Review the problem below, then retry or cancel.',
  'closeAll.failure.save': 'One or more modified documents could not be prepared or downloaded.',
  'closeAll.failure.stop': 'One or more local source sessions did not reach the stopped state.',
  'closeAll.retry': 'Retry',
  'closeAll.cancel': 'Cancel',
  'commands.view.language.label': 'Language…',
  'commands.view.language.help': 'Choose the interface language or import a custom catalog',
  'language.title': 'Interface language',
  'language.description': 'Choose a configured or imported language. Ravenroot applies the change immediately.',
  'language.selection.label': 'Preferred language',
  'language.automatic': 'Automatic (configured, browser, then English)',
  'language.source.built-in': 'Built in',
  'language.source.configured': 'Configured by this deployment',
  'language.source.imported': 'Imported in this browser',
  'language.source.fallback': 'English fallback',
  'language.import': 'Import catalog…',
  'language.remove': 'Remove imported catalog',
  'language.close': 'Close',
  'language.import.success': 'Imported {locale}.',
  'language.import.failure': 'The catalog could not be imported. Check the file and try again.',
  'language.remove.failure': 'The catalog could not be removed. Try again.',
  'language.catalogFile.label': 'UI catalog JSON file',
  'language.currentSource': 'Source: {source}',
  'language.currentResolution': 'Resolved language: {locale} · {source}',
  'embed.document.title': 'Ravenroot embedded graph',
  'embed.state.idle': '',
  'embed.state.loading': 'Loading graph…',
  'embed.state.ready': 'Graph ready.',
  'embed.state.empty': 'This graph is empty.',
  'embed.state.error': 'The graph could not be displayed.',
  'embed.state.expired': 'This viewing session has expired.',
  'embed.state.offline': 'The graph service is unavailable.',
  'embed.state.incompatible': 'This graph requires a newer viewer.',
  'embed.state.destroyed': 'Viewer closed.',
  'embed.kind.START': 'Start',
  'embed.kind.PASSTHROUGH': 'Step',
  'embed.kind.BEHAVIOR': 'Behavior',
  'embed.kind.END': 'End',
  'embed.kind.ERROR': 'Error',
  'embed.alternative.node': '{kind} node {id}',
  'embed.alternative.empty': 'No nodes in this graph.',
  'embed.alternative.connects': 'Connects to {id}',
  'embed.alternative.receives': 'Receives from {id}',
  'embed.alternative.relationships': 'Relationships for {id}',
  'embed.boundary.start': 'Start of embedded graph',
  'embed.boundary.end': 'End of embedded graph',
  'embed.skip': 'Skip to graph',
  'embed.title': 'Graph',
  'embed.controls.label': 'Graph view controls',
  'embed.controls.view': 'View',
  'embed.mode.design': 'Design',
  'embed.mode.monitoring': 'Monitoring',
  'embed.mode.cyto': 'Cyto',
  'embed.mode.n8n': 'N8N',
  'embed.mode.elastic': 'Elastic',
  'embed.controls.render': 'Render',
  'embed.controls.render.title': 'Render layout',
  'embed.controls.run': 'Run',
  'embed.controls.liveRun': 'Live run',
  'embed.runs.none': 'No runs',
  'embed.runs.noneAuthorized': 'No authorized runs.',
  'embed.runs.choose': 'Choose a run',
  'embed.runs.connecting': 'Connecting to selected run.',
  'embed.runs.unavailable': 'Run list is temporarily unavailable.',
  'embed.runs.noLongerAuthorized': 'The selected run is no longer authorized.',
  'embed.controls.startExecution': 'Start execution',
  'embed.controls.zoom': 'Zoom controls',
  'embed.controls.zoomOut': 'Zoom out',
  'embed.controls.fit': 'Fit',
  'embed.controls.fit.title': 'Fit graph',
  'embed.controls.zoomIn': 'Zoom in',
  'embed.controls.maximize': 'Maximize embedded graph',
  'embed.controls.restore': 'Restore embedded graph',
  'embed.canvas.label': 'Read-only graph. Arrow keys pan, plus and minus zoom, and zero fits the graph.',
  'embed.minimap.label': 'Graph overview. Arrow keys pan, Home fits, Escape returns to graph.',
  'embed.minimap.fallback': 'Graph overview',
  'embed.contents.label': 'Graph contents',
  'embed.view.rendered': '{mode} view rendered.',
  'embed.view.ready': '{mode} view ready.',
  'embed.view.elasticUnavailable': 'Elastic view is unavailable for large graphs.',
  'embed.metadata.version': 'Version {version}',
  'embed.observation.gap': 'Live observation has a gap. Reattach to reconcile the deployment.',
  'embed.observation.versionMismatch': 'The deployment version changed. This view is no longer live.',
  'embed.observation.authorizationEnded': 'Live observation authorization ended.',
  'embed.observation.deployment': 'Deployment {state}.',
  'embed.selection': { one: 'One node selected.', other: '{count} nodes selected.' },
  'embed.inspect.group': {
    one: '{action} visual group {name}, one member',
    other: '{action} visual group {name}, {count} members',
  },
  'embed.inspect.expand': 'Expand',
  'embed.inspect.collapse': 'Collapse',
  'embed.inspect.node': 'Inspect node {name}',
});

export const UI_TEXT_CATALOGS = Object.freeze({ en: ENGLISH_MESSAGES });

export const UI_CATALOG_SCHEMA = 'ravenroot.ui-catalog/v1';
export const UI_LOCALES_SCHEMA = 'ravenroot.ui-locales/v1';
export const UI_LOCALE_STORAGE_KEY = 'ravenroot.ui.locale.v1';

const MAX_CATALOG_KEYS = 4_096;
const MAX_MESSAGE_LENGTH = 8_192;
const MAX_LOCALE_LENGTH = 64;
const PLURAL_CATEGORIES = new Set(['zero', 'one', 'two', 'few', 'many', 'other']);
// Compatibility fallback for engines without Intl.Locale text-direction metadata.
const RTL_SCRIPTS = new Set(['Adlm', 'Arab', 'Hebr', 'Nkoo', 'Rohg', 'Syrc', 'Thaa']);

function plainObject(value) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return false;
  const prototype = Object.getPrototypeOf(value);
  return prototype === Object.prototype || prototype === null;
}

export function normalizeUiLocale(locale, fallback = 'en') {
  const input = String(locale || fallback).replaceAll('_', '-');
  if (input.length > MAX_LOCALE_LENGTH) throw productError("workspace.error.ui.text.ui.locale.is.too.long", new TypeError('UI locale is too long.'));
  try {
    const [normalized] = Intl.getCanonicalLocales(input);
    if (!normalized) throw productError("workspace.error.ui.text.ui.locale.is.empty", new TypeError('UI locale is empty.'));
    return normalized;
  } catch (failure) {
    throw productError("workspace.error.ui.text.invalid.ui.locale", new TypeError(`Invalid UI locale "${input}".`, { cause: failure }), { value1: input });
  }
}

export function uiLocaleDirection(locale, declaredDirection) {
  if (declaredDirection !== undefined && declaredDirection !== 'ltr' && declaredDirection !== 'rtl') {
    throw productError("workspace.error.ui.text.ui.catalog.direction.must.be.ltr.or.rtl", new TypeError('UI catalog direction must be "ltr" or "rtl".'));
  }
  if (declaredDirection) return declaredDirection;
  const parsed = new Intl.Locale(normalizeUiLocale(locale));
  const textInfo = typeof parsed.getTextInfo === 'function' ? parsed.getTextInfo() : parsed.textInfo;
  if (textInfo?.direction === 'ltr' || textInfo?.direction === 'rtl') return textInfo.direction;
  const script = parsed.script || parsed.maximize().script;
  return RTL_SCRIPTS.has(script) ? 'rtl' : 'ltr';
}

export function hasUiText(key) {
  return Object.hasOwn(ENGLISH_MESSAGES, key);
}

export function uiLocaleCandidates(locale) {
  const normalized = normalizeUiLocale(locale);
  const language = new Intl.Locale(normalized).language;
  return [...new Set([normalized, language, 'en'])];
}

function placeholders(template) {
  return [...String(template).matchAll(/\{([A-Za-z][A-Za-z0-9_]*)\}/g)].map(match => match[1]);
}

function messageVariants(message, key) {
  if (typeof message === 'string') return { other: message };
  if (!plainObject(message)) throw productError("workspace.error.ui.text.ui.text.must.be.a.string.or.plural.object", new TypeError(`UI text "${key}" must be a string or plural object.`), { value1: key });
  const entries = Object.entries(message);
  if (!entries.length || !Object.hasOwn(message, 'other')) {
    throw productError("workspace.error.ui.text.plural.ui.text.must.define.other", new TypeError(`Plural UI text "${key}" must define "other".`), { value1: key });
  }
  for (const [category, value] of entries) {
    if (!PLURAL_CATEGORIES.has(category) || typeof value !== 'string') {
      throw productError("workspace.error.ui.text.invalid.plural.category.for.ui.text", new TypeError(`Invalid plural category "${category}" for UI text "${key}".`), { value1: category, value2: key });
    }
  }
  return message;
}

function validateMessage(key, message, sourceMessage) {
  const variants = messageVariants(message, key);
  const sourceVariants = sourceMessage === undefined ? null : messageVariants(sourceMessage, key);
  for (const [category, template] of Object.entries(variants)) {
    // An empty canonical status means no announcement (currently embed.state.idle).
    // It does not authorize blank labels, accessibility text, or plural variants.
    const intentionallyEmpty = sourceMessage === '' && typeof message === 'string';
    if ((!template.length && !intentionallyEmpty) || template.length > MAX_MESSAGE_LENGTH) {
      throw productError("workspace.error.ui.text.ui.text.has.an.invalid.length", new TypeError(`UI text "${key}" has an invalid length.`), { value1: key });
    }
    const sourceTemplate = sourceVariants?.[category] ?? sourceVariants?.other;
    if (sourceTemplate !== undefined) {
      const actual = [...new Set(placeholders(template))].sort();
      const expected = [...new Set(placeholders(sourceTemplate))].sort();
      if (actual.join('\0') !== expected.join('\0')) {
        throw productError("workspace.error.ui.text.ui.text.has.incompatible.named.parameters", new TypeError(`UI text "${key}" has incompatible named parameters.`), { value1: key });
      }
    }
  }
}

export function validateUiCatalog(input, { sourceMessages = ENGLISH_MESSAGES } = {}) {
  if (!plainObject(input) || input.schema !== UI_CATALOG_SCHEMA) {
    throw productError("workspace.error.ui.text.ui.catalog.schema.must.be", new TypeError(`UI catalog schema must be "${UI_CATALOG_SCHEMA}".`), { value1: UI_CATALOG_SCHEMA });
  }
  const locale = normalizeUiLocale(input.locale);
  uiLocaleDirection(locale, input.direction);
  if (!plainObject(input.messages)) throw productError("workspace.error.ui.text.ui.catalog.messages.must.be.an.object", new TypeError('UI catalog messages must be an object.'));
  const entries = Object.entries(input.messages);
  if (entries.length > MAX_CATALOG_KEYS) throw productError("workspace.error.ui.text.ui.catalog.has.too.many.messages", new RangeError('UI catalog has too many messages.'));
  const messages = Object.create(null);
  for (const [key, message] of entries) {
    if (!/^[A-Za-z][A-Za-z0-9]*(?:[.-][A-Za-z0-9]+)+$/.test(key)) {
      throw productError("workspace.error.ui.text.invalid.ui.text.key", new TypeError(`Invalid UI text key "${key}".`), { value1: key });
    }
    if (!Object.hasOwn(sourceMessages, key)) throw productError("workspace.error.ui.text.unknown.ui.text.key", new TypeError(`Unknown UI text key "${key}".`), { value1: key });
    validateMessage(key, message, sourceMessages[key]);
    messages[key] = typeof message === 'string' ? message : Object.freeze({ ...message });
  }
  return Object.freeze({ schema: UI_CATALOG_SCHEMA, locale,
    ...(input.direction === undefined ? {} : { direction: input.direction }),
    messages: Object.freeze(messages) });
}

function interpolate(template, params, key, numberFormat) {
  return template.replace(/\{([A-Za-z][A-Za-z0-9_]*)\}/g, (_, name) => {
    if (!Object.hasOwn(params, name)) throw productError("workspace.error.ui.text.missing.ui.text.parameter.for", new Error(`Missing UI text parameter "${name}" for "${key}"`), { value1: name, value2: key });
    return typeof params[name] === 'number' ? numberFormat.format(params[name]) : String(params[name]);
  });
}

function catalogMessages(value, locale) {
  if (value?.schema === UI_CATALOG_SCHEMA) return validateUiCatalog(value).messages;
  if (!plainObject(value)) throw productError("workspace.error.ui.text.ui.catalog.for.must.be.an.object", new TypeError(`UI catalog for "${locale}" must be an object.`), { value1: locale });
  const validated = validateUiCatalog({ schema: UI_CATALOG_SCHEMA, locale, messages: value });
  return validated.messages;
}

export function createUiText({ locale = 'en', catalogs = {}, onDiagnostic = () => {} } = {}) {
  const normalizedLocale = normalizeUiLocale(locale);
  const supplied = Object.fromEntries(Object.entries(catalogs)
    .map(([catalogLocale, messages]) => {
      const normalized = normalizeUiLocale(catalogLocale);
      return [normalized, catalogMessages(messages, normalized)];
    }));
  const available = { ...UI_TEXT_CATALOGS, ...supplied };
  available.en = { ...ENGLISH_MESSAGES, ...(supplied.en || {}) };
  const candidates = uiLocaleCandidates(normalizedLocale);
  const numberFormat = new Intl.NumberFormat(normalizedLocale);
  const pluralRules = new Intl.PluralRules(normalizedLocale);

  const text = (key, params = {}) => {
    const catalog = candidates.map(candidate => available[candidate])
      .find(item => item && Object.hasOwn(item, key));
    if (!catalog) {
      onDiagnostic(Object.freeze({ code: 'MISSING_SOURCE_MESSAGE', key, locale: normalizedLocale }));
      return 'Unavailable';
    }
    try {
      const variants = messageVariants(catalog[key], key);
      const count = Number(params.count);
      const category = Number.isFinite(count) ? pluralRules.select(count) : 'other';
      return interpolate(variants[category] ?? variants.other, params, key, numberFormat);
    } catch (failure) {
      onDiagnostic(Object.freeze({ code: 'INVALID_MESSAGE_PARAMETERS', key,
        locale: normalizedLocale, reason: failure.message }));
      const fallback = messageVariants(ENGLISH_MESSAGES[key] ?? 'Unavailable', key).other;
      try { return interpolate(fallback, params, key, new Intl.NumberFormat('en')); }
      catch { return 'Unavailable'; }
    }
  };
  Object.defineProperties(text, {
    locale: { value: normalizedLocale },
    direction: { value: uiLocaleDirection(normalizedLocale) },
    number: { value: (value, options) => new Intl.NumberFormat(normalizedLocale, options).format(value) },
    dateTime: { value: (value, options) => new Intl.DateTimeFormat(normalizedLocale, options).format(value) },
    duration: { value: (milliseconds) => formatUiDuration(milliseconds, normalizedLocale) },
  });
  return text;
}

let sharedUiText = createUiText();
let sharedUiLocalization = null;
/** Stable delegate used by workspace modules; the main application swaps its backing locale live. */
export const uiText = (key, params) => sharedUiText(key, params);
export const uiErrorReason = error => error?.uiMessageKey
  ? uiText(error.uiMessageKey, typeof error.uiMessageParams === 'function' ? error.uiMessageParams() : error.uiMessageParams || {})
  : String(error?.message || error || uiText('common.unavailable'));
Object.defineProperty(uiText, 'locale', { get: () => sharedUiLocalization?.locale ?? 'en' });
export function useUiLocalization(localization) {
  if (!localization || typeof localization.text !== 'function') {
    throw productError("workspace.error.ui.text.ui.localization.context.is.required", new TypeError('UI localization context is required.'));
  }
  sharedUiText = (key, params) => localization.text(key, params);
  sharedUiLocalization = localization;
}
export const uiNumber = (value, options) => sharedUiLocalization?.number(value, options)
  ?? new Intl.NumberFormat('en', options).format(value);
export const uiDateTime = (value, options) => sharedUiLocalization?.dateTime(value, options)
  ?? new Intl.DateTimeFormat('en', options).format(value);
export const uiDuration = value => sharedUiLocalization?.duration(value) ?? formatUiDuration(value, 'en');

export function formatUiDuration(milliseconds, locale = 'en') {
  const value = Number(milliseconds);
  if (!Number.isFinite(value)) return 'Unavailable';
  const absoluteSeconds = Math.max(0, Math.round(Math.abs(value) / 1_000));
  const units = absoluteSeconds >= 3_600
    ? [['hour', Math.floor(absoluteSeconds / 3_600)], ['minute', Math.floor(absoluteSeconds % 3_600 / 60)]]
    : absoluteSeconds >= 60
      ? [['minute', Math.floor(absoluteSeconds / 60)], ['second', absoluteSeconds % 60]]
      : [['second', absoluteSeconds]];
  return units.filter(([, amount], index) => amount > 0 || index === 0).map(([unit, amount]) =>
    new Intl.NumberFormat(normalizeUiLocale(locale), { style: 'unit', unit, unitDisplay: 'short' }).format(amount))
    .join(' ');
}

export function createUiLocalization({ locale = 'en', catalogs = {}, onDiagnostic = () => {} } = {}) {
  let activeLocale = normalizeUiLocale(locale);
  const available = new Map();
  const listeners = new Set();
  for (const [catalogLocale, messages] of Object.entries(catalogs)) {
    const normalized = normalizeUiLocale(catalogLocale);
    const catalog = messages?.schema === UI_CATALOG_SCHEMA ? validateUiCatalog(messages)
      : Object.freeze({ schema: UI_CATALOG_SCHEMA, locale: normalized,
        messages: catalogMessages(messages, normalized) });
    available.set(normalized, catalog);
  }
  const textCatalogs = () => Object.fromEntries([...available]
    .map(([catalogLocale, catalog]) => [catalogLocale, catalog.messages]));
  let text = createUiText({ locale: activeLocale, catalogs: textCatalogs(), onDiagnostic });
  const notify = () => listeners.forEach(listener => listener(context));
  const rebuild = () => {
    text = createUiText({ locale: activeLocale, catalogs: textCatalogs(), onDiagnostic });
  };
  const context = Object.freeze({
    get locale() { return activeLocale; },
    get direction() {
      const language = new Intl.Locale(activeLocale).language;
      const catalog = uiLocaleCandidates(activeLocale)
        .filter(candidate => candidate !== 'en' || language === 'en')
        .map(candidate => available.get(candidate)).find(Boolean);
      return uiLocaleDirection(activeLocale, catalog?.direction);
    },
    text: (key, params) => text(key, params),
    number: (value, options) => text.number(value, options),
    dateTime: (value, options) => text.dateTime(value, options),
    duration: value => text.duration(value),
    setLocale(nextLocale) {
      const normalized = normalizeUiLocale(nextLocale);
      if (normalized === activeLocale) return false;
      activeLocale = normalized;
      rebuild();
      notify();
      return true;
    },
    registerCatalog(catalog) {
      const validated = validateUiCatalog(catalog);
      available.set(validated.locale, validated);
      rebuild();
      notify();
      return validated.locale;
    },
    removeCatalog(locale_) {
      const normalized = normalizeUiLocale(locale_);
      if (normalized === 'en') return false;
      const removed = available.delete(normalized);
      if (!removed) return false;
      if (activeLocale === normalized) activeLocale = 'en';
      rebuild();
      notify();
      return true;
    },
    availableLocales: () => Object.freeze(['en', ...[...available.keys()].filter(item => item !== 'en')]),
    subscribe(listener) {
      if (typeof listener !== 'function') throw productError("workspace.error.ui.text.ui.locale.listener.must.be.a.function", new TypeError('UI locale listener must be a function.'));
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  });
  return context;
}
