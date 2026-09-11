#!/usr/bin/env python3
"""Inventory fixed operational candidates and reject unreviewed source drift.

The scanner is deliberately lexical. It finds a stable, reviewable set of candidates
within its documented patterns and leaves each semantic decision in
``scripts/operational-configuration-inventory.json``. The inventory and generated
report are the source of the issue's counts.
"""

from __future__ import annotations

import argparse
from bisect import bisect_right
import hashlib
import json
import re
import subprocess
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass, replace
from functools import lru_cache
from pathlib import Path
from typing import Iterable

try:
    from check_product_version import SEMVER as PRODUCT_SEMVER, helm_errors as product_helm_errors
except ModuleNotFoundError:  # Imported as scripts.audit_operational_configuration.
    from scripts.check_product_version import (
        SEMVER as PRODUCT_SEMVER,
        helm_errors as product_helm_errors,
    )


ROOT = Path(__file__).resolve().parents[1]
INVENTORY = ROOT / "scripts" / "operational-configuration-inventory.json"
REPORT = ROOT / "docs" / "architecture" / "operational-configuration-audit.md"

SCHEMA_VERSION = 5
CLASSIFICATIONS = {
    "operator-configurable",
    "security-ceiling-or-default",
    "protocol-or-format-invariant",
    "published-contract-description",
    "presentation-text",
    "derived",
    "test-fixture",
}
STATUSES = {
    "pending-review",
    "already-centralized",
    "confirmed-hardcoded",
    "converted",
    "retained",
    "deferred",
    "duplicate-removed",
}
CLASSIFICATION_STATUSES = {
    "operator-configurable": {"already-centralized", "confirmed-hardcoded", "converted", "deferred"},
    "security-ceiling-or-default": {"retained", "deferred"},
    "protocol-or-format-invariant": {"retained", "deferred"},
    "published-contract-description": {"retained", "deferred"},
    "presentation-text": {"retained"},
    "derived": {"retained", "deferred"},
    "test-fixture": {"retained"},
}
TESTKIT_MODULES = {
    "ravenroot-api-testkit",
    "ravenroot-engine-testkit",
    "ravenroot-persistence-testkit",
    "ravenroot-sandbox-supervisor-testkit",
}
EXCLUDED_PARTS = {"target", "node_modules", "dist", ".git"}
SOURCE_SUFFIXES = {".java", ".js", ".mjs", ".ts", ".py", ".sh", ".yaml", ".yml", ".json"}

OPERATIONAL_WORD = re.compile(
    r"(?i)(timeout|deadline|interval|poll|retry|attempt|capacity|queue|limit|max|min|retention|ttl|"
    r"lifetime|grace|delay|period|expiry|expire|workers|threads|batch|burst|rate|bytes|size|count|"
    r"lease|drain|age|port|path|directory|dir|memory|heap|cpu|replica|probe|health|cumulative)"
)
NUMBER = re.compile(r"(?<![\w.])(?:0[xX][0-9a-fA-F_]+|\d[\d_]*(?:\.\d+)?)(?:[lLdDfF])?(?![\w.])")
QUOTED = re.compile(r'''(?s)(?:"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')''')
FIXED = re.compile(
    r"(?s)(?<![\w.])(?:0[xX][0-9a-fA-F_]+|\d[\d_]*(?:\.\d+)?)(?:[lLdDfF])?(?![\w.])|"
    r'''"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|Duration\.of(?:Nanos|Millis|Seconds|Minutes|Hours|Days)\s*\('''
)
FIXED_ATOM = re.compile(
    r'''(?<![\w.])(?:0[xX][0-9a-fA-F_]+|\d[\d_]*(?:\.\d+)?)(?:[lLdDfF])?(?![\w.])|'''
    r'''"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*' '''.rstrip()
)
KNOWN_OPERATIONAL_CALL = re.compile(
    r"(?i)(Duration\.of(?:Nanos|Millis|Seconds|Minutes|Hours|Days)|new\s+(?:PayloadLimits|GraphExecutionLimits|GraphMlLimits|Limits|RetryPolicy|RetryBackoff|AgentBudgetVector|Semaphore|"
    r"ArrayBlockingQueue|ThreadPoolExecutor)|newScheduledThreadPool|newFixedThreadPool|withStash|"
    r"orTimeout|completeOnTimeout|readNBytes|sleep|setTimeout|setInterval|getOrDefault)\s*\("
)
# This is intentionally a bounded lexical contract rather than Java receiver-type inference. The
# explicit TimeUnit argument separates timed Future/latch/process/executor/lock/semaphore overloads
# from ordinary get()/await()/tryAcquire() calls. Dynamic and static-imported units are outside the
# supported pattern and remain visible only through their own fixed declarations, when present.
TIME_UNIT_OPERATIONAL_CALL = re.compile(
    r"\.(get|await|tryAcquire|tryLock|waitFor|awaitTermination)\s*\("
)
TIME_UNIT_ARGUMENT = re.compile(
    r"(?:java\.util\.concurrent\.)?TimeUnit\."
    r"(?:NANOSECONDS|MICROSECONDS|MILLISECONDS|SECONDS|MINUTES|HOURS|DAYS)"
)
ASSIGNMENT_NAME = re.compile(r"(?s)\b([A-Za-z_$][\w$]*)\s*=\s*[^=]")
STATIC_FINAL = re.compile(r"\bstatic\s+final\b")
JS_DECLARATION = re.compile(r"\b(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=")
PY_ASSIGNMENT = re.compile(r"^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(?![=])")
YAML_SCALAR = re.compile(r'''^\s*["']?([A-Za-z_][A-Za-z0-9_.-]*)["']?\s*:\s*(\S.*)$''')
ENVIRONMENT_BINDING = re.compile(r"RAVENROOT_[A-Z0-9_]+")
PROPERTY_BINDING = re.compile(r'''"(ravenroot\.[A-Za-z0-9_.-]*)"''')
SYSTEM_PROPERTY_READ = re.compile(r"\bSystem\.getProperty\s*\(\s*([^,)]+)")
RESOLVER_TEST_ROLES = {
    "propertyPrecedence",
    "blankPropertyEnvironmentFallback",
    "blankSourcesTypedDefault",
    "malformedNonblankRefusal",
}
RESOLVER_COMPOSITION_METHOD_ROLES = {
    "typedDefaultsFactory",
    "nestedDefaultsFactory",
}
RESOLVER_COMPOSITION_LINK_ROLES = {
    "typedDefaultsInitializer",
    "nestedDefaultsAccessor",
}
ENVIRONMENT_RESOLVER_TEST_ROLES = {
    "bindingEnumeration",
    "blankTypedDefault",
    "asciiTrimContract",
    "malformedOverflowRefusal",
    "nonPositiveRefusal",
    "documentedBoundaryAcceptance",
    "relationalConstraintRefusal",
}
DEPLOYMENT_ENVIRONMENT_CARRIER_PATHS = {
    "compose": frozenset({"compose.yaml"}),
    "helm": frozenset({
        "deploy/helm/ravenroot/values.yaml",
        "deploy/helm/ravenroot/values.schema.json",
        "deploy/helm/ravenroot/templates/deployment.yaml",
    }),
    "rawKubernetes": frozenset({"deploy/kubernetes/ravenroot.yaml"}),
}
HELM_AUTHORITY_ID = "ravenroot-helm-values-v1"
HELM_CHART_PATH = "deploy/helm/ravenroot/Chart.yaml"
HELM_RELEASE_CONTRACT_PATH = "scripts/check_product_version.py"
HELM_VALUES_PATH = "deploy/helm/ravenroot/values.yaml"
HELM_SCHEMA_PATH = "deploy/helm/ravenroot/values.schema.json"
HELM_TEMPLATE_PATHS = (
    "deploy/helm/ravenroot/templates/_helpers.tpl",
    "deploy/helm/ravenroot/templates/deployment.yaml",
    "deploy/helm/ravenroot/templates/pvc.yaml",
    "deploy/helm/ravenroot/templates/service.yaml",
)
HELM_TEST_ROLES = {
    "scripts/tests/test_helm_values_contract.sh": (
        "closed-schema", "default-render", "nondefault-render", "tag-only-render", "refusal"),
    "scripts/tests/test_program_timeout_helm_contract.sh": (
        "timeout-default", "timeout-nondefault", "timeout-blank", "timeout-refusal"),
    "scripts/tests/test_execution_manifest_pin_helm_contract.sh": (
        "unsupported-setting-refusal",),
}

# This is the complete chart-owned operator surface. Blank Java-default carriers are intentionally
# absent: their typed authorities remain in Java and this Helm proof only verifies their transport.
# Entries are value path, setting, exact serialized default, schema rule, projection rule.
HELM_OPERATOR_VALUE_CONTRACTS = (
    ("image.repository", "deployment.image.repository", "ravenroot", "nonempty-string", "image-helper"),
    ("image.tag", "deployment.image.tag", "local", "string", "image-helper"),
    ("image.digest", "deployment.image.digest", '""', "image-digest", "image-helper"),
    ("image.pullPolicy", "deployment.image.pull-policy", "IfNotPresent", "image-pull-policy", "image-pull-policy"),
    ("service.type", "deployment.service.type", "ClusterIP", "service-type", "service-type"),
    ("service.port", "deployment.service.port", "8080", "service-port", "service-port"),
    ("auth.issuer", "deployment.auth.issuer", '""', "required-auth", "auth-required"),
    ("auth.audience", "deployment.auth.audience", '""', "required-auth", "auth-required"),
    ("auth.jwksUri", "deployment.auth.jwks-uri", '""', "required-auth", "auth-required"),
    ("programTimeoutMs", "deployment.program-timeout-ms", "15000", "program-timeout", "program-timeout"),
    ("resources.requests.cpu", "deployment.resources.requests.cpu", "100m", "quantity", "resources"),
    ("resources.requests.memory", "deployment.resources.requests.memory", "256Mi", "quantity", "resources"),
    ("resources.limits.cpu", "deployment.resources.limits.cpu", '"1"', "quantity", "resources"),
    ("resources.limits.memory", "deployment.resources.limits.memory", "1Gi", "quantity", "resources"),
    ("podSecurityContext.fsGroup", "deployment.pod-security.fs-group", "10001", "positive-id", "pod-security"),
    ("podSecurityContext.fsGroupChangePolicy", "deployment.pod-security.fs-group-change-policy", "OnRootMismatch", "fs-group-policy", "pod-security"),
    ("securityContext.runAsUser", "deployment.container-security.run-as-user", "10001", "positive-id", "container-security"),
    ("securityContext.runAsGroup", "deployment.container-security.run-as-group", "10001", "positive-id", "container-security"),
    ("probes.readiness.initialDelaySeconds", "deployment.probe.readiness-initial-delay-seconds", "3", "probe-initial", "readiness-probe"),
    ("probes.readiness.periodSeconds", "deployment.probe.readiness-period-seconds", "5", "probe-period", "readiness-probe"),
    ("probes.liveness.initialDelaySeconds", "deployment.probe.liveness-initial-delay-seconds", "15", "probe-initial", "liveness-probe"),
    ("probes.liveness.periodSeconds", "deployment.probe.liveness-period-seconds", "10", "probe-period", "liveness-probe"),
    ("tmpfs.sizeLimit", "deployment.tmpfs.size-limit", "64Mi", "positive-quantity", "tmpfs"),
    ("persistence.size", "deployment.persistence.size", "1Gi", "positive-quantity", "persistence-size"),
    ("persistence.storageClass", "deployment.persistence.storage-class", '""', "string", "persistence-storage-class"),
)
HELM_FIXED_VALUE_CONTRACTS = {
    "replicaCount": "1",
    "engine": "pekko",
    "podSecurityContext.runAsNonRoot": "true",
    "podSecurityContext.seccompProfile.type": "RuntimeDefault",
    "securityContext.allowPrivilegeEscalation": "false",
    "securityContext.readOnlyRootFilesystem": "true",
}
HELM_FIXED_LIST_CONTRACTS = {
    "securityContext.capabilities.drop.0": "ALL",
    "persistence.accessModes.0": "ReadWriteOnce",
}
HELM_JAVA_CARRIER_PREFIXES = (
    "executionRuntime.", "graph.", "humanTask.", "assistant.", "rateLimit.",
)
GRAPH_LIMIT_FAMILY_ID = "graph-execution-environment-v1"
GRAPH_EXECUTION_LIMITS_PATH = Path(
    "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/runtime/GraphExecutionLimits.java")
GRAPH_ML_LIMITS_PATH = Path(
    "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/graph/GraphMlLimits.java")
PAYLOAD_LIMITS_PATH = Path(
    "ravenroot/ravenroot-application-api/src/main/java/ai/ravenroot/api/payload/PayloadLimits.java")
GRAPH_DEFINITION_STORE_PATH = Path(
    "ravenroot/ravenroot-application-api/src/main/java/ai/ravenroot/api/persistence/GraphDefinitionStore.java")
GRAPH_LIMIT_TYPED_AUTHORITIES = frozenset({
    ("graph.execution.max-amplified-deliveries", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/runtime/GraphExecutionLimits.java#GraphExecutionLimits", "maxAmplifiedDeliveries", "RAVENROOT_GRAPH_MAX_AMPLIFIED_DELIVERIES"),
    ("graph.execution.max-cumulative-payload-bytes", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/runtime/GraphExecutionLimits.java#GraphExecutionLimits", "maxCumulativePayloadBytes", "RAVENROOT_GRAPH_MAX_CUMULATIVE_PAYLOAD_BYTES"),
    ("graph.execution.max-fan-out", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/runtime/GraphExecutionLimits.java#GraphExecutionLimits", "maxFanOut", "RAVENROOT_GRAPH_MAX_FAN_OUT"),
    ("graph.execution.max-in-flight-hops-per-traversal", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/runtime/GraphExecutionLimits.java#GraphExecutionLimits", "maxInFlightHopsPerTraversal", "RAVENROOT_GRAPH_MAX_IN_FLIGHT_HOPS"),
    ("graph.execution.max-live-actors-per-traversal", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/runtime/GraphExecutionLimits.java#GraphExecutionLimits", "maxLiveActorsPerTraversal", "RAVENROOT_GRAPH_MAX_LIVE_ACTORS_PER_TRAVERSAL"),
    ("graph.execution.max-queued-admissions-per-node", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/runtime/GraphExecutionLimits.java#GraphExecutionLimits", "maxQueuedAdmissionsPerNode", "RAVENROOT_GRAPH_MAX_QUEUED_ADMISSIONS_PER_NODE"),
    ("graph.execution.max-recovery-deliveries-per-attempt", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/runtime/GraphExecutionLimits.java#GraphExecutionLimits", "maxRecoveryDeliveriesPerAttempt", "RAVENROOT_GRAPH_MAX_RECOVERY_DELIVERIES_PER_ATTEMPT"),
    ("graph.execution.max-resident-actors", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/runtime/GraphExecutionLimits.java#GraphExecutionLimits", "maxResidentActors", "RAVENROOT_GRAPH_MAX_RESIDENT_ACTORS"),
    ("graph.execution.max-traversal-steps", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/runtime/GraphExecutionLimits.java#GraphExecutionLimits", "maxTraversalSteps", "RAVENROOT_GRAPH_MAX_TRAVERSAL_STEPS"),
    ("graph.graphml.max-attributes", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/graph/GraphMlLimits.java#GraphMlLimits", "maxAttributes", "RAVENROOT_GRAPHML_MAX_ATTRIBUTES"),
    ("graph.graphml.max-bytes", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/graph/GraphMlLimits.java#GraphMlLimits", "maxBytes", "RAVENROOT_GRAPHML_MAX_BYTES"),
    ("graph.graphml.max-depth", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/graph/GraphMlLimits.java#GraphMlLimits", "maxDepth", "RAVENROOT_GRAPHML_MAX_DEPTH"),
    ("graph.graphml.max-edges", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/graph/GraphMlLimits.java#GraphMlLimits", "maxEdges", "RAVENROOT_GRAPH_MAX_EDGES"),
    ("graph.graphml.max-elements", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/graph/GraphMlLimits.java#GraphMlLimits", "maxElements", "RAVENROOT_GRAPHML_MAX_ELEMENTS"),
    ("graph.graphml.max-keys", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/graph/GraphMlLimits.java#GraphMlLimits", "maxKeys", "RAVENROOT_GRAPHML_MAX_KEYS"),
    ("graph.graphml.max-namespace-declarations", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/graph/GraphMlLimits.java#GraphMlLimits", "maxNamespaceDeclarations", "RAVENROOT_GRAPHML_MAX_NAMESPACE_DECLARATIONS"),
    ("graph.graphml.max-nodes", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/graph/GraphMlLimits.java#GraphMlLimits", "maxNodes", "RAVENROOT_GRAPH_MAX_NODES"),
    ("graph.graphml.max-properties", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/graph/GraphMlLimits.java#GraphMlLimits", "maxProperties", "RAVENROOT_GRAPH_MAX_PROPERTIES"),
    ("graph.graphml.max-string-length", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/graph/GraphMlLimits.java#GraphMlLimits", "maxStringLength", "RAVENROOT_GRAPHML_MAX_STRING_LENGTH"),
    ("graph.payload.max-collection-size", "ravenroot/ravenroot-application-api/src/main/java/ai/ravenroot/api/payload/PayloadLimits.java#PayloadLimits", "maxCollectionSize", "RAVENROOT_GRAPH_MAX_PAYLOAD_COLLECTION_SIZE"),
    ("graph.payload.max-depth", "ravenroot/ravenroot-application-api/src/main/java/ai/ravenroot/api/payload/PayloadLimits.java#PayloadLimits", "maxDepth", "RAVENROOT_GRAPH_MAX_PAYLOAD_DEPTH"),
    ("graph.payload.max-encoded-bytes", "ravenroot/ravenroot-application-api/src/main/java/ai/ravenroot/api/payload/PayloadLimits.java#PayloadLimits", "maxEncodedBytes", "RAVENROOT_GRAPH_MAX_PAYLOAD_BYTES"),
    ("graph.payload.max-key-length", "ravenroot/ravenroot-application-api/src/main/java/ai/ravenroot/api/payload/PayloadLimits.java#PayloadLimits", "maxKeyLength", "RAVENROOT_GRAPH_MAX_PAYLOAD_KEY_LENGTH"),
    ("graph.payload.max-text-length", "ravenroot/ravenroot-application-api/src/main/java/ai/ravenroot/api/payload/PayloadLimits.java#PayloadLimits", "maxTextLength", "RAVENROOT_GRAPH_MAX_PAYLOAD_TEXT_LENGTH"),
    ("graph.payload.max-value-count", "ravenroot/ravenroot-application-api/src/main/java/ai/ravenroot/api/payload/PayloadLimits.java#PayloadLimits", "maxValueCount", "RAVENROOT_GRAPH_MAX_PAYLOAD_VALUE_COUNT"),
})

GRAPH_LIMIT_SOURCE_CONTRACTS = (
    # setting, target constructor, root component, environment symbol, helper, fallback, ceiling
    ("graph.graphml.max-bytes", "GraphMlLimits", "graphMl", "MAX_GRAPHML_BYTES_VARIABLE",
     "integer", "graphMl.maxBytes()", "GraphDefinitionStore.HARD_MAX_DEFINITION_BYTES"),
    ("graph.graphml.max-nodes", "GraphMlLimits", "graphMl", "MAX_NODES_VARIABLE",
     "integer", "graphMl.maxNodes()", "GraphMlLimits.HARD_MAX_NODES"),
    ("graph.graphml.max-edges", "GraphMlLimits", "graphMl", "MAX_EDGES_VARIABLE",
     "integer", "graphMl.maxEdges()", "GraphMlLimits.HARD_MAX_EDGES"),
    ("graph.graphml.max-properties", "GraphMlLimits", "graphMl", "MAX_PROPERTIES_VARIABLE",
     "integer", "graphMl.maxProperties()", "GraphMlLimits.HARD_MAX_PROPERTIES"),
    ("graph.graphml.max-depth", "GraphMlLimits", "graphMl", "MAX_GRAPHML_DEPTH_VARIABLE",
     "integer", "graphMl.maxDepth()", "GraphMlLimits.HARD_MAX_DEPTH"),
    ("graph.graphml.max-string-length", "GraphMlLimits", "graphMl",
     "MAX_GRAPHML_STRING_LENGTH_VARIABLE", "integer", "graphMl.maxStringLength()",
     "GraphMlLimits.HARD_MAX_STRING_LENGTH"),
    ("graph.graphml.max-keys", "GraphMlLimits", "graphMl", "MAX_GRAPHML_KEYS_VARIABLE",
     "integer", "graphMl.maxKeys()", "GraphMlLimits.HARD_MAX_KEYS"),
    ("graph.graphml.max-elements", "GraphMlLimits", "graphMl", "MAX_GRAPHML_ELEMENTS_VARIABLE",
     "integer", "graphMl.maxElements()", "GraphMlLimits.HARD_MAX_ELEMENTS"),
    ("graph.graphml.max-attributes", "GraphMlLimits", "graphMl",
     "MAX_GRAPHML_ATTRIBUTES_VARIABLE", "integer", "graphMl.maxAttributes()",
     "GraphMlLimits.HARD_MAX_ATTRIBUTES"),
    ("graph.graphml.max-namespace-declarations", "GraphMlLimits", "graphMl",
     "MAX_GRAPHML_NAMESPACE_DECLARATIONS_VARIABLE", "integer",
     "graphMl.maxNamespaceDeclarations()", "GraphMlLimits.HARD_MAX_NAMESPACE_DECLARATIONS"),
    ("graph.payload.max-encoded-bytes", "PayloadLimits", "payload", "MAX_PAYLOAD_BYTES_VARIABLE",
     "integer", "payload.maxEncodedBytes()", "PayloadLimits.HARD_MAX_ENCODED_BYTES"),
    ("graph.payload.max-depth", "PayloadLimits", "payload", "MAX_PAYLOAD_DEPTH_VARIABLE",
     "integer", "payload.maxDepth()", "PayloadLimits.HARD_MAX_DEPTH"),
    ("graph.payload.max-collection-size", "PayloadLimits", "payload",
     "MAX_PAYLOAD_COLLECTION_SIZE_VARIABLE", "integer", "payload.maxCollectionSize()",
     "PayloadLimits.HARD_MAX_COLLECTION_SIZE"),
    ("graph.payload.max-value-count", "PayloadLimits", "payload",
     "MAX_PAYLOAD_VALUE_COUNT_VARIABLE", "integer", "payload.maxValueCount()",
     "PayloadLimits.HARD_MAX_VALUE_COUNT"),
    ("graph.payload.max-text-length", "PayloadLimits", "payload",
     "MAX_PAYLOAD_TEXT_LENGTH_VARIABLE", "integer", "payload.maxTextLength()",
     "PayloadLimits.HARD_MAX_TEXT_LENGTH"),
    ("graph.payload.max-key-length", "PayloadLimits", "payload",
     "MAX_PAYLOAD_KEY_LENGTH_VARIABLE", "integer", "payload.maxKeyLength()",
     "PayloadLimits.HARD_MAX_KEY_LENGTH"),
    ("graph.execution.max-fan-out", "GraphExecutionLimits", "maxFanOut",
     "MAX_FAN_OUT_VARIABLE", "integer", "defaults.maxFanOut", "HARD_MAX_FAN_OUT"),
    ("graph.execution.max-resident-actors", "GraphExecutionLimits", "maxResidentActors",
     "MAX_RESIDENT_ACTORS_VARIABLE", "integer", "defaults.maxResidentActors",
     "HARD_MAX_RESIDENT_ACTORS"),
    ("graph.execution.max-live-actors-per-traversal", "GraphExecutionLimits",
     "maxLiveActorsPerTraversal", "MAX_LIVE_ACTORS_VARIABLE", "integer",
     "defaults.maxLiveActorsPerTraversal", "HARD_MAX_LIVE_ACTORS"),
    ("graph.execution.max-in-flight-hops-per-traversal", "GraphExecutionLimits",
     "maxInFlightHopsPerTraversal", "MAX_IN_FLIGHT_HOPS_VARIABLE", "integer",
     "defaults.maxInFlightHopsPerTraversal", "HARD_MAX_IN_FLIGHT_HOPS"),
    ("graph.execution.max-queued-admissions-per-node", "GraphExecutionLimits",
     "maxQueuedAdmissionsPerNode", "MAX_QUEUED_ADMISSIONS_VARIABLE", "integer",
     "defaults.maxQueuedAdmissionsPerNode", "HARD_MAX_QUEUED_ADMISSIONS"),
    ("graph.execution.max-traversal-steps", "GraphExecutionLimits", "maxTraversalSteps",
     "MAX_TRAVERSAL_STEPS_VARIABLE", "longInteger", "defaults.maxTraversalSteps",
     "HARD_MAX_TRAVERSAL_STEPS"),
    ("graph.execution.max-amplified-deliveries", "GraphExecutionLimits",
     "maxAmplifiedDeliveries", "MAX_AMPLIFIED_DELIVERIES_VARIABLE", "longInteger",
     "defaults.maxAmplifiedDeliveries", "HARD_MAX_AMPLIFIED_DELIVERIES"),
    ("graph.execution.max-cumulative-payload-bytes", "GraphExecutionLimits",
     "maxCumulativePayloadBytes", "MAX_CUMULATIVE_PAYLOAD_BYTES_VARIABLE", "longInteger",
     "defaults.maxCumulativePayloadBytes", "HARD_MAX_CUMULATIVE_PAYLOAD_BYTES"),
    ("graph.execution.max-recovery-deliveries-per-attempt", "GraphExecutionLimits",
     "maxRecoveryDeliveriesPerAttempt", "MAX_RECOVERY_DELIVERIES_VARIABLE", "integer",
     "defaults.maxRecoveryDeliveriesPerAttempt", "HARD_MAX_RECOVERY_DELIVERIES"),
)
GRAPH_LIMIT_AUTHORITY_BY_SETTING = {
    setting: {"typedOwner": owner, "field": field, "environment": environment}
    for setting, owner, field, environment in GRAPH_LIMIT_TYPED_AUTHORITIES
}
GRAPH_LIMIT_SOURCE_BY_SETTING = {
    setting: {
        "targetConstructor": target, "rootComponent": root_component,
        "environmentSymbol": environment_symbol, "helper": helper,
        "fallbackAccessor": fallback, "ceilingAccessor": ceiling,
    }
    for setting, target, root_component, environment_symbol, helper, fallback, ceiling
    in GRAPH_LIMIT_SOURCE_CONTRACTS
}
GRAPH_LIMIT_DEFAULT_CONTRACTS = {
    "graph.graphml.max-bytes": ("GraphDefinitionStore.DEFAULT_MAX_DEFINITION_BYTES", "bytes"),
    "graph.graphml.max-nodes": ("10_000", "nodes"),
    "graph.graphml.max-edges": ("25_000", "edges"),
    "graph.graphml.max-properties": ("100_000", "properties"),
    "graph.graphml.max-depth": ("64", "nesting levels"),
    "graph.graphml.max-string-length": ("1024 * 1024", "UTF-16 code units"),
    "graph.graphml.max-keys": ("4_096", "distinct keys"),
    "graph.graphml.max-elements": ("250_000", "XML elements"),
    "graph.graphml.max-attributes": ("500_000", "XML attributes"),
    "graph.graphml.max-namespace-declarations": ("10_000", "namespace declarations"),
    "graph.payload.max-encoded-bytes": ("256 * 1024", "bytes"),
    "graph.payload.max-depth": ("32", "nesting levels"),
    "graph.payload.max-collection-size": ("1_000", "members per collection"),
    "graph.payload.max-value-count": ("10_000", "values"),
    "graph.payload.max-text-length": ("32 * 1024", "UTF-16 code units"),
    "graph.payload.max-key-length": ("256", "UTF-16 code units"),
    "graph.execution.max-fan-out": ("64", "targets"),
    "graph.execution.max-resident-actors": ("256", "actors"),
    "graph.execution.max-live-actors-per-traversal": ("256", "actors"),
    "graph.execution.max-in-flight-hops-per-traversal": ("1_024", "messages"),
    "graph.execution.max-queued-admissions-per-node": ("1_024", "messages"),
    "graph.execution.max-traversal-steps": ("100_000", "deliveries"),
    "graph.execution.max-amplified-deliveries": ("100_000", "deliveries"),
    "graph.execution.max-cumulative-payload-bytes": ("64L * 1024 * 1024", "bytes"),
    "graph.execution.max-recovery-deliveries-per-attempt": ("8", "delivery claims"),
}
GRAPH_NUMERIC_CONSTANT_EXPRESSIONS = {
    "GraphDefinitionStore.DEFAULT_MAX_DEFINITION_BYTES": "10 * 1024 * 1024",
    "GraphDefinitionStore.HARD_MAX_DEFINITION_BYTES": "256 * 1024 * 1024",
    "GraphMlLimits.HARD_MAX_NODES": "1_000_000",
    "GraphMlLimits.HARD_MAX_EDGES": "5_000_000",
    "GraphMlLimits.HARD_MAX_PROPERTIES": "10_000_000",
    "GraphMlLimits.HARD_MAX_DEPTH": "1_024",
    "GraphMlLimits.HARD_MAX_STRING_LENGTH": "64 * 1024 * 1024",
    "GraphMlLimits.HARD_MAX_KEYS": "100_000",
    "GraphMlLimits.HARD_MAX_ELEMENTS": "10_000_000",
    "GraphMlLimits.HARD_MAX_ATTRIBUTES": "20_000_000",
    "GraphMlLimits.HARD_MAX_NAMESPACE_DECLARATIONS": "1_000_000",
    "PayloadLimits.HARD_MAX_ENCODED_BYTES": "64 * 1024 * 1024",
    "PayloadLimits.HARD_MAX_DEPTH": "256",
    "PayloadLimits.HARD_MAX_COLLECTION_SIZE": "1_000_000",
    "PayloadLimits.HARD_MAX_VALUE_COUNT": "5_000_000",
    "PayloadLimits.HARD_MAX_TEXT_LENGTH": "64 * 1024 * 1024",
    "PayloadLimits.HARD_MAX_KEY_LENGTH": "4_096",
    "GraphExecutionLimits.HARD_MAX_FAN_OUT": "256",
    "GraphExecutionLimits.HARD_MAX_RESIDENT_ACTORS": "4_096",
    "GraphExecutionLimits.HARD_MAX_LIVE_ACTORS": "1_024",
    "GraphExecutionLimits.HARD_MAX_IN_FLIGHT_HOPS": "4_096",
    "GraphExecutionLimits.HARD_MAX_QUEUED_ADMISSIONS": "4_096",
    "GraphExecutionLimits.HARD_MAX_TRAVERSAL_STEPS": "1_000_000L",
    "GraphExecutionLimits.HARD_MAX_AMPLIFIED_DELIVERIES": "1_000_000L",
    "GraphExecutionLimits.HARD_MAX_CUMULATIVE_PAYLOAD_BYTES": "256L * 1024 * 1024",
    "GraphExecutionLimits.HARD_MAX_RECOVERY_DELIVERIES": "64",
}


@dataclass(frozen=True)
class Candidate:
    id: str
    path: str
    line: int
    symbol: str
    kind: str
    role: str
    expression: str
    expression_digest: str
    evidence: str
    evidence_digest: str
    surface: str
    fixture: bool = False

    def inventory_entry(self) -> dict[str, object]:
        if self.fixture:
            return {
                **self.source_fields(),
                "status": "retained",
                "classification": "test-fixture",
                "rationale": "A testkit module ships reusable test fixtures under src/main; it is not production runtime configuration.",
            }
        return {
            **self.source_fields(),
            "status": "pending-review",
            "classification": None,
        }

    def source_fields(self) -> dict[str, object]:
        return {
            "id": self.id,
            "path": self.path,
            "line": self.line,
            "symbol": self.symbol,
            "kind": self.kind,
            "role": self.role,
            "expression": self.expression,
            "expressionDigest": self.expression_digest,
            "evidenceDigest": self.evidence_digest,
            "surface": self.surface,
        }


def tracked_files(root: Path) -> tuple[Path, ...]:
    listing = subprocess.run(
        ["git", "ls-files", "-co", "--exclude-standard", "-z"],
        cwd=root,
        check=True,
        capture_output=True,
    ).stdout.decode("utf-8").split("\0")
    return tuple(sorted(Path(item) for item in listing if item and (root / item).is_file()))


def surface(relative: Path) -> str | None:
    parts = relative.parts
    text = relative.as_posix()
    if any(part in EXCLUDED_PARTS for part in parts):
        return None
    if relative.name in {"package-lock.json", "npm-shrinkwrap.json", "pnpm-lock.yaml", "yarn.lock"}:
        return None
    if text in {
        "scripts/audit_operational_configuration.py",
        "scripts/operational-configuration-inventory.json",
    }:
        # The audit implementation and inventory describe the scan. Including either would make
        # checker maintenance create candidates about the checker rather than runtime policy.
        return None
    if relative.name in {"Dockerfile", "Dockerfile.ci"}:
        return "deployment"
    if text == "compose.yaml" or text.startswith("deploy/"):
        return "deployment"
    if relative.suffix == ".sh":
        if text.startswith("scripts/tests/") or "/e2e/" in text or "/src/test/" in text \
                or text == "scripts/verify-source-session-editor-activity.sh":
            return "test-fixture"
        return "script"
    # Documented runnable configuration is a deployment surface, not ordinary prose.
    if text in {
        "docs/examples/assistant/compose.override.yaml",
        "docs/examples/plugins/compose.openapi-client.override.yaml",
    }:
        return "deployment-example"
    if text.startswith("ravenroot/ravenroot-ui/src/") or text.startswith("ravenroot/ravenroot-ui/public/"):
        return "ui"
    if text.startswith("scripts/"):
        if text.startswith("scripts/tests/") or text.startswith("scripts/fixtures/"):
            return "test-fixture"
        return "script"
    if "/src/test/" in text or "/e2e/" in text or "/test/" in text:
        return None
    if "/src/main/" in text:
        module = next((part for part in parts if part.startswith("ravenroot-") and part != "ravenroot"), "")
        return "test-fixture" if module in TESTKIT_MODULES else "java"
    return None


def strip_c_comments(text: str) -> str:
    """Remove // and /* */ comments while preserving strings and newlines."""
    out: list[str] = []
    index = 0
    state = "code"
    quote = ""
    while index < len(text):
        char = text[index]
        following = text[index + 1] if index + 1 < len(text) else ""
        if state == "line":
            if char == "\n":
                state = "code"
                out.append(char)
            else:
                out.append(" ")
        elif state == "block":
            if char == "*" and following == "/":
                out.extend((" ", " "))
                index += 1
                state = "code"
            else:
                out.append("\n" if char == "\n" else " ")
        elif state == "textblock":
            if text.startswith('"""', index):
                out.extend(('"', '"', '"'))
                index += 2
                state = "code"
            elif char == "\\" and following:
                out.extend((char, following))
                index += 1
            else:
                out.append(char)
        elif state == "string":
            out.append(char)
            if char == "\\" and following:
                out.append(following)
                index += 1
            elif char == quote:
                state = "code"
        elif char == "/" and following == "/":
            out.extend((" ", " "))
            index += 1
            state = "line"
        elif char == "/" and following == "*":
            out.extend((" ", " "))
            index += 1
            state = "block"
        elif text.startswith('"""', index):
            state = "textblock"
            out.extend(('"', '"', '"'))
            index += 2
        elif char in {'"', "'", "`"}:
            quote = char
            state = "string"
            out.append(char)
        else:
            out.append(char)
        index += 1
    return "".join(out)


def strip_c_comments_and_literals(text: str) -> str:
    """Mask comments and quoted literals while preserving offsets and newlines."""
    without_comments = strip_c_comments(text)
    out: list[str] = []
    index = 0
    quote = ""
    textblock = False
    while index < len(without_comments):
        char = without_comments[index]
        following = without_comments[index + 1] if index + 1 < len(without_comments) else ""
        if textblock:
            if without_comments.startswith('"""', index):
                out.extend((" ", " ", " "))
                index += 2
                textblock = False
            elif char == "\\" and following:
                out.extend((" ", " "))
                index += 1
            else:
                out.append("\n" if char == "\n" else " ")
        elif quote:
            if char == "\\" and following:
                out.extend((" ", " "))
                index += 1
            elif char == quote:
                out.append(" ")
                quote = ""
            else:
                out.append("\n" if char == "\n" else " ")
        elif without_comments.startswith('"""', index):
            textblock = True
            out.extend((" ", " ", " "))
            index += 2
        elif char in {'"', "'", "`"}:
            quote = char
            out.append(" ")
        else:
            out.append(char)
        index += 1
    return "".join(out)


def java_type_span(source: str, symbol: str) -> tuple[int, int] | None:
    """Return one Java type declaration span, ignoring declaration-shaped text in literals/comments."""
    code = strip_c_comments_and_literals(source)
    declaration = re.search(
        rf"\b(?:class|record|interface|enum|@interface)\s+{re.escape(symbol)}\b", code,
    )
    if declaration is None:
        return None
    opening = code.find("{", declaration.end())
    if opening < 0:
        return None
    depth = 0
    for offset in range(opening, len(code)):
        if code[offset] == "{":
            depth += 1
        elif code[offset] == "}":
            depth -= 1
            if depth == 0:
                return declaration.start(), offset + 1
    return None


def java_exact_top_level_type_header(source: str, symbol: str, expected: str) -> bool:
    """Require one unannotated top-level type declaration with an exact supported header."""
    code = strip_c_comments_and_literals(source)
    depths = java_brace_depths(code)
    declarations = [match for match in re.finditer(
        rf"\b(?:class|record|interface|enum|@interface)\s+{re.escape(symbol)}\b", code)
        if depths[match.start()] == 0
    ]
    if len(declarations) != 1:
        return False
    declaration = declarations[0]
    start = code.rfind("\n", 0, declaration.start()) + 1
    opening = code.find("{", declaration.end())
    if opening < 0 or normalized(code[start:opening]) != normalized(expected):
        return False
    boundary = 0
    for offset, char in enumerate(code[:start]):
        if depths[offset] == 0 and char in ";}":
            boundary = offset + 1
    return not code[boundary:start].strip()


def java_type_declares_field(source: str, symbol: str, field: str) -> bool:
    """Check an exact record component or direct member declared by the named Java type."""
    span = java_type_span(source, symbol)
    if span is None:
        return False
    code = strip_c_comments_and_literals(source)[slice(*span)]
    opening = code.find("{")
    parts = field.split(".")
    if len(parts) != 1:
        return False
    identifier = parts[-1]

    type_match = re.search(
        rf"\b(?P<kind>class|record|interface|enum|@interface)\s+{re.escape(symbol)}\b", code[:opening],
    )
    if type_match is None:
        return False
    declared: set[str] = set()
    if type_match.group("kind") == "record":
        parenthesis = code.find("(", type_match.end(), opening)
        if parenthesis >= 0:
            depth = 0
            component_start = parenthesis + 1
            for offset in range(parenthesis + 1, opening):
                char = code[offset]
                if char in "(<[":
                    depth += 1
                elif char in ")>]":
                    if char == ")" and depth == 0:
                        component = code[component_start:offset]
                        names = re.findall(r"\b[A-Za-z_$][\w$]*\b", component)
                        if names:
                            declared.add(names[-1])
                        break
                    depth -= 1
                elif char == "," and depth == 0:
                    component = code[component_start:offset]
                    names = re.findall(r"\b[A-Za-z_$][\w$]*\b", component)
                    if names:
                        declared.add(names[-1])
                    component_start = offset + 1

    body = code[opening + 1:-1]
    depth = 1
    statement: list[str] = []
    for char in body:
        if char == "{":
            if depth == 1:
                statement.clear()
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 1:
                statement.clear()
        elif depth == 1:
            statement.append(char)
            if char == ";":
                unit = "".join(statement)
                statement.clear()
                declaration = re.match(
                    r"\s*(?:(?:public|protected|private|static|final|volatile|transient)\s+)*"
                    r"[A-Za-z_$][\w$<>,.?\[\] @]*\s+([A-Za-z_$][\w$]*)\s*(?:=|;|,)",
                    unit,
                )
                if declaration is not None:
                    declared.add(declaration.group(1))
    return identifier in declared


def normalized(value: str) -> str:
    return " ".join(value.split())


def line_number(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def symbol_markers(code: str, suffix: str) -> tuple[tuple[int, str], ...]:
    """Index declaration starts once; candidate lookup must stay linearithmic on large files."""
    markers: list[tuple[int, str]] = [(0, "module")]
    offset = 0
    for line in code.splitlines(keepends=True):
        match = None
        if suffix == ".java":
            match = re.search(r"\b(?:class|record|interface|enum)\s+([A-Za-z_$][\w$]*)", line)
            if match is None and "(" in line and line.rstrip().endswith("{"):
                match = re.search(r"([A-Za-z_$][\w$]*)\s*\([^;{}]*\)\s*(?:throws\s+[^{}]+)?\{\s*$", line)
        elif suffix in {".js", ".mjs", ".ts"}:
            match = re.search(r"\b(?:function\s+)?([A-Za-z_$][\w$]*)\s*\([^;{}]*\)\s*\{\s*$", line)
        elif suffix == ".py":
            match = re.match(r"\s*(?:async\s+)?def\s+([A-Za-z_][\w]*)\s*\(", line)
        if match is not None:
            markers.append((offset + match.start(), match.group(1)))
        offset += len(line)
    return tuple(markers)


def containing_symbol(markers: tuple[tuple[int, str], ...], offset: int) -> str:
    position = bisect_right(markers, (offset, chr(0x10FFFF))) - 1
    return markers[position][1]


def statement_spans(code: str) -> Iterable[tuple[int, int, str]]:
    """Yield semicolon or newline units without splitting inside quoted strings."""
    start = 0
    quote = ""
    escaped = False
    for index, char in enumerate(code):
        if quote:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = ""
            continue
        if char in {'"', "'", "`"}:
            quote = char
        elif char == ";":
            yield start, index + 1, code[start:index + 1]
            start = index + 1
    if start < len(code):
        for match in re.finditer(r"[^\n]+", code[start:]):
            yield start + match.start(), start + match.end(), match.group(0)


def code_candidates(relative: Path, text: str, surface_name: str) -> list[tuple[int, str, str, str, str, str]]:
    suffix = relative.suffix
    code = strip_c_comments(text)
    markers = symbol_markers(code, suffix)
    rows: list[tuple[int, str, str, str, str, str]] = []
    for start, _end, raw in statement_spans(code):
        evidence = normalized(raw)
        if not evidence:
            continue

        for binding in ENVIRONMENT_BINDING.finditer(raw):
            candidate_offset = start + binding.start()
            token = binding.group(0)
            rows.append((candidate_offset, containing_symbol(markers, candidate_offset),
                         "environment-binding", token, token, evidence))
        for binding in PROPERTY_BINDING.finditer(raw):
            candidate_offset = start + binding.start()
            token = binding.group(1)
            rows.append((candidate_offset, containing_symbol(markers, candidate_offset),
                         "property-binding", token, token, evidence))
        for binding in SYSTEM_PROPERTY_READ.finditer(raw):
            argument = normalized(binding.group(1))
            if PROPERTY_BINDING.fullmatch(argument):
                continue
            candidate_offset = start + binding.start(1)
            rows.append((candidate_offset, containing_symbol(markers, candidate_offset),
                         "property-binding", "System.getProperty", argument.strip('"'), evidence))

        kind: str | None = None
        label = "literal"
        assignment = JS_DECLARATION.search(evidence) if suffix in {".js", ".mjs", ".ts"} \
            else ASSIGNMENT_NAME.search(evidence)
        if STATIC_FINAL.search(evidence) or (suffix in {".js", ".mjs", ".ts"}
                                              and evidence.lstrip().startswith("const ")):
            kind = "fixed-declaration"
            if assignment:
                label = assignment.group(1)
        elif assignment and OPERATIONAL_WORD.search(assignment.group(1)):
            kind = "operational-declaration"
            label = assignment.group(1)
        elif KNOWN_OPERATIONAL_CALL.search(evidence):
            kind = "inline-operational-call"
            call = KNOWN_OPERATIONAL_CALL.search(evidence)
            assert call is not None
            label = normalized(call.group(1)).replace(" ", "-")
        if kind:
            for atom in FIXED_ATOM.finditer(raw):
                candidate_offset = start + atom.start()
                rows.append((candidate_offset, containing_symbol(markers, candidate_offset), kind,
                             label, normalized(atom.group(0)), evidence))
    if suffix == ".java":
        # Parse only the supported timeout argument. Feeding these method names into the broad
        # KNOWN_OPERATIONAL_CALL branch would incorrectly collect permit counts and unrelated
        # literals elsewhere in the containing statement.
        occupied_offsets = {row[0] for row in rows}
        masked = strip_c_comments_and_literals(text)
        for start, end, raw in statement_spans(code):
            evidence = normalized(raw)
            for call in TIME_UNIT_OPERATIONAL_CALL.finditer(masked, start, end):
                opening = masked.find("(", call.start(), end)
                if opening < 0:
                    continue
                parsed = split_java_arguments(text, masked, opening)
                if parsed is None:
                    continue
                arguments, closing = parsed
                if closing >= end:
                    continue
                method = call.group(1)
                if method == "tryAcquire":
                    if len(arguments) not in {2, 3}:
                        continue
                elif len(arguments) != 2:
                    continue
                unit = arguments[-1]
                unit_expression = re.sub(r"\s+", "", masked[unit[1]:unit[2]])
                if TIME_UNIT_ARGUMENT.fullmatch(unit_expression) is None:
                    continue
                timeout = arguments[-2]
                for atom in NUMBER.finditer(masked, timeout[1], timeout[2]):
                    candidate_offset = atom.start()
                    if candidate_offset in occupied_offsets:
                        continue
                    occupied_offsets.add(candidate_offset)
                    rows.append((candidate_offset, containing_symbol(markers, candidate_offset),
                                 "inline-operational-call", f"timeunit-{method}",
                                 normalized(text[atom.start():atom.end()]), evidence))
    return rows


def line_candidates(relative: Path, text: str, surface_name: str) -> list[tuple[int, str, str, str, str, str]]:
    rows: list[tuple[int, str, str, str, str, str]] = []
    suffix = relative.suffix
    markers = symbol_markers(text, suffix)
    offset = 0
    for index, raw in enumerate(text.splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith(("#", "//")):
            offset += len(raw) + 1
            continue
        evidence = normalized(raw)
        for binding in ENVIRONMENT_BINDING.finditer(raw):
            candidate_offset = offset + binding.start()
            token = binding.group(0)
            rows.append((candidate_offset, containing_symbol(markers, candidate_offset),
                         "environment-binding", token, token, evidence))
        label: str | None = None
        kind: str | None = None
        if suffix in {".yaml", ".yml", ".json"}:
            match = YAML_SCALAR.match(raw)
            if match and FIXED.search(match.group(2)):
                label = match.group(1)
                kind = "configuration-scalar"
        elif suffix in {".py", ".sh"} or relative.name.startswith("Dockerfile"):
            docker = re.match(r"^\s*(USER|EXPOSE)\s+(.+)$", raw, re.IGNORECASE)
            match = PY_ASSIGNMENT.match(raw)
            shell = re.match(r"^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$", raw)
            name = match.group(1) if match else shell.group(1) if shell else None
            if docker and FIXED.search(docker.group(2)):
                label = docker.group(1).upper()
                kind = "container-directive"
            elif name and (name.isupper() or OPERATIONAL_WORD.search(name)) and FIXED.search(raw):
                label = name
                kind = "script-default"
            elif (re.search(r"\b(?:sleep|timeout)\s+[0-9]", raw)
                  or (OPERATIONAL_WORD.search(raw) and FIXED.search(raw))):
                label = "inline"
                kind = "inline-script-operational"
            elif ENVIRONMENT_BINDING.search(raw) and FIXED.search(raw):
                label = "binding-value"
                kind = "binding-default"
        if kind and label:
            for atom in FIXED_ATOM.finditer(raw):
                candidate_offset = offset + atom.start()
                rows.append((candidate_offset, containing_symbol(markers, candidate_offset), kind,
                             label, normalized(atom.group(0)), evidence))
        offset += len(raw) + 1
    return rows


def json_pointer(document: object, reference: str) -> object:
    """Resolve one same-document JSON Pointer, rejecting external and malformed references."""
    if not reference.startswith("#/"):
        raise ValueError("only same-document JSON Pointer references are supported")
    value = document
    for encoded in reference[2:].split("/"):
        token = encoded.replace("~1", "/").replace("~0", "~")
        if isinstance(value, dict) and token in value:
            value = value[token]
        elif isinstance(value, list) and token.isdigit() and int(token) < len(value):
            value = value[int(token)]
        else:
            raise ValueError("JSON Pointer target is absent")
    return value


def resolved_json_schema_value(document: object, value: object,
                               references: tuple[str, ...] = ()) -> object:
    """Return a canonicalizable local-ref expansion and fail closed on cycles."""
    if isinstance(value, dict):
        if "$ref" in value:
            if set(value) != {"$ref"} or not isinstance(value["$ref"], str):
                raise ValueError("unsupported sibling or value next to $ref")
            reference = value["$ref"]
            if reference in references:
                raise ValueError("cyclic local JSON reference")
            target = json_pointer(document, reference)
            return {"$ref": reference,
                    "resolved": resolved_json_schema_value(document, target, references + (reference,))}
        return {key: resolved_json_schema_value(document, child, references)
                for key, child in sorted(value.items())}
    if isinstance(value, list):
        return [resolved_json_schema_value(document, child, references) for child in value]
    return value


def json_schema_reference_candidates(text: str) -> list[tuple[int, str, str, str, str, str]]:
    """Discover per-property local `$ref` edges with their resolved constraint evidence."""
    try:
        document = json.loads(text)
    except json.JSONDecodeError:
        return []
    rows: list[tuple[int, str, str, str, str, str]] = []
    cursor = 0

    def visit(value: object, pointer: str, required_property: bool = False) -> None:
        nonlocal cursor
        if isinstance(value, dict):
            reference = value.get("$ref")
            if isinstance(reference, str):
                offset = text.find('"$ref"', cursor)
                if offset < 0:
                    offset = 0
                else:
                    cursor = offset + len('"$ref"')
                try:
                    if set(value) != {"$ref"}:
                        raise ValueError("unsupported sibling next to $ref")
                    target = json_pointer(document, reference)
                    resolved = resolved_json_schema_value(document, target, (reference,))
                    resolution_error = None
                except ValueError as invalid:
                    resolved = None
                    resolution_error = str(invalid)
                reference_pointer = f"{pointer}/$ref"
                evidence = {
                    "pointer": reference_pointer,
                    "reference": reference,
                    "required": required_property,
                    "resolved": resolved,
                    "resolutionError": resolution_error,
                }
                rows.append((offset, pointer, "schema-reference-binding", reference_pointer,
                             reference, json.dumps(evidence, sort_keys=True, separators=(",", ":"))))
            for key, child in value.items():
                escaped = str(key).replace("~", "~0").replace("/", "~1")
                if key == "properties" and isinstance(child, dict):
                    required = set(value.get("required", [])) if isinstance(value.get("required"), list) else set()
                    for property_name, property_schema in child.items():
                        property_token = str(property_name).replace("~", "~0").replace("/", "~1")
                        visit(property_schema, f"{pointer}/{escaped}/{property_token}",
                              property_name in required)
                else:
                    visit(child, f"{pointer}/{escaped}", required_property)
        elif isinstance(value, list):
            for index, child in enumerate(value):
                visit(child, f"{pointer}/{index}", required_property)

    visit(document, "")
    return rows


def discover(root: Path) -> tuple[Candidate, ...]:
    provisional: list[tuple[str, int, str, str, str, str, str, str, bool]] = []
    for relative in tracked_files(root):
        surface_name = surface(relative)
        if surface_name is None or (relative.suffix not in SOURCE_SUFFIXES
                                    and not relative.name.startswith("Dockerfile")):
            continue
        text = (root / relative).read_text(encoding="utf-8", errors="strict")
        if relative.suffix in {".java", ".js", ".mjs", ".ts"}:
            found = code_candidates(relative, text, surface_name)
        else:
            found = line_candidates(relative, text, surface_name)
            if relative.suffix == ".json":
                found.extend(json_schema_reference_candidates(text))
        for offset, symbol_name, kind, role, expression, evidence in found:
            provisional.append((relative.as_posix(), line_number(text, offset), symbol_name,
                                kind, role, expression, evidence, surface_name,
                                surface_name == "test-fixture"))

    occurrences: Counter[tuple[str, str, str, str, str]] = Counter()
    candidates: list[Candidate] = []
    for path, line, symbol_name, kind, role, expression, evidence, surface_name, fixture in sorted(provisional):
        key = (path, symbol_name, kind, role, expression)
        occurrence = occurrences[key]
        occurrences[key] += 1
        evidence_digest = hashlib.sha256(evidence.encode("utf-8")).hexdigest()
        material = "\0".join((path, symbol_name, kind, role, expression, evidence_digest,
                               str(occurrence)))
        digest = hashlib.sha256(expression.encode("utf-8")).hexdigest()
        identifier = "oc-" + hashlib.sha256(material.encode("utf-8")).hexdigest()[:20]
        candidates.append(Candidate(identifier, path, line, symbol_name, kind, role, expression, digest,
                                    evidence, evidence_digest, surface_name, fixture))
    return tuple(candidates)


def load_inventory(path: Path = INVENTORY, *, allow_previous_schema: bool = False,
                   allow_unreconciled: bool = False) -> dict[str, object]:
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        raise ValueError(f"missing inventory: {path.relative_to(ROOT)}") from None
    accepted_versions = {SCHEMA_VERSION, SCHEMA_VERSION - 1} if allow_previous_schema else {SCHEMA_VERSION}
    if document.get("schemaVersion") not in accepted_versions:
        raise ValueError(f"inventory schemaVersion must be {SCHEMA_VERSION}")
    entries = document.get("entries")
    if not isinstance(entries, list):
        raise ValueError("inventory entries must be an array")
    retired = document.get("retiredEntries", [])
    if not isinstance(retired, list):
        raise ValueError("inventory retiredEntries must be an array")
    if document.get("schemaVersion") == SCHEMA_VERSION and not isinstance(document.get("evidenceRecords"), dict):
        raise ValueError("inventory evidenceRecords must be an object")
    if document.get("schemaVersion") == SCHEMA_VERSION \
            and document.get("reconciliationRequired") is not True \
            and not allow_unreconciled:
        raise ValueError("schema v5 inventory requires reconciliationRequired=true")
    if not isinstance(document.get("migrationHistory", []), list):
        raise ValueError("inventory migrationHistory must be an array")
    if not isinstance(document.get("normalizedIdentityReappearanceHistory", []), list):
        raise ValueError("inventory normalizedIdentityReappearanceHistory must be an array")
    return document


@lru_cache(maxsize=None)
def current_source_owner(root: Path, owner: str) -> tuple[Path, str] | None:
    """Resolve a tracked in-repository ``path#symbol`` authority without following escapes."""
    if "#" not in owner:
        return None
    owner_path, owner_symbol = owner.rsplit("#", 1)
    relative = Path(owner_path)
    if not owner_symbol or relative.is_absolute() or ".." in relative.parts:
        return None
    root_resolved = root.resolve()
    authority = (root / relative).resolve()
    try:
        authority.relative_to(root_resolved)
    except ValueError:
        return None
    tracked = subprocess.run(["git", "ls-files", "--error-unmatch", "--", relative.as_posix()], cwd=root,
                             capture_output=True)
    if tracked.returncode != 0 or not authority.is_file():
        return None
    source = authority.read_text(encoding="utf-8")
    suffix = relative.suffix
    symbol = re.escape(owner_symbol)
    if suffix == ".java":
        return (relative, owner_symbol) if java_type_span(source, owner_symbol) is not None else None
    declarations = {
        ".py": (rf"(?m)^\s*(?:class|def|async\s+def)\s+{symbol}\b",),
        ".js": (rf"\b(?:class|function)\s+{symbol}\b", rf"\b(?:const|let|var)\s+{symbol}\s*="),
        ".mjs": (rf"\b(?:class|function)\s+{symbol}\b", rf"\b(?:const|let|var)\s+{symbol}\s*="),
        ".ts": (rf"\b(?:class|interface|type|enum|function)\s+{symbol}\b",
                rf"\b(?:const|let|var)\s+{symbol}\s*="),
        ".sh": (rf"(?m)^\s*(?:function\s+)?{symbol}\s*(?:\(\s*\))?\s*\{{",
                rf"(?m)^\s*{symbol}="),
        ".yaml": (rf"(?m)^\s*{symbol}\s*:",),
        ".yml": (rf"(?m)^\s*{symbol}\s*:",),
    }.get(suffix, ())
    if not declarations or not any(re.search(pattern, source) for pattern in declarations):
        return None
    return relative, owner_symbol


def current_source_field(root: Path, owner: str, field: str) -> bool:
    """Verify a Java operator field is declared by its typed current-source owner."""
    resolved = current_source_owner(root, owner)
    if resolved is None:
        return False
    relative, owner_symbol = resolved
    if relative.suffix != ".java":
        return False
    return java_type_declares_field((root / relative).read_text(encoding="utf-8"), owner_symbol, field)


def java_record_components(source: str, symbol: str) -> tuple[str, ...]:
    span = java_type_span(source, symbol)
    if span is None:
        return ()
    code = strip_c_comments_and_literals(source)[slice(*span)]
    declaration = re.search(rf"\brecord\s+{re.escape(symbol)}\b", code)
    opening_brace = code.find("{")
    if declaration is None or opening_brace < 0:
        return ()
    opening = code.find("(", declaration.end(), opening_brace)
    if opening < 0:
        return ()
    components: list[str] = []
    start = opening + 1
    depth = 0
    for offset in range(start, opening_brace):
        char = code[offset]
        if char in "(<[":
            depth += 1
        elif char in ")>]":
            if char == ")" and depth == 0:
                names = re.findall(r"\b[A-Za-z_$][\w$]*\b", code[start:offset])
                if names:
                    components.append(names[-1])
                return tuple(components)
            depth -= 1
        elif char == "," and depth == 0:
            names = re.findall(r"\b[A-Za-z_$][\w$]*\b", code[start:offset])
            if names:
                components.append(names[-1])
            start = offset + 1
    return ()


def java_record_default_expression_span(source: str, symbol: str, instance_symbol: str,
                                        field: str) -> tuple[str, int, int] | None:
    """Read one positional component expression and span from a unique direct record default."""
    components = java_record_components(source, symbol)
    if field not in components:
        return None
    span = java_type_span(source, symbol)
    assert span is not None
    base, limit = span
    actual = source[base:limit]
    code = strip_c_comments_and_literals(source)[base:limit]
    initializers = list(re.finditer(
        rf"\b{re.escape(instance_symbol)}\b\s*=\s*new\s+{re.escape(symbol)}\s*\(", code,
    ))
    if len(initializers) != 1:
        return None
    opening = code.find("(", initializers[0].start())
    parsed = split_java_arguments(actual, code, opening)
    if parsed is None or len(parsed[0]) != len(components):
        return None
    expression, start, end = parsed[0][components.index(field)]
    return expression, base + start, base + end


def java_record_default_expression(source: str, symbol: str, instance_symbol: str,
                                   field: str) -> str | None:
    result = java_record_default_expression_span(source, symbol, instance_symbol, field)
    return result[0] if result is not None else None


def java_static_final_initializer(source: str, symbol: str,
                                  field: str) -> tuple[str, int, int] | None:
    """Return one direct static-final field initializer in a named Java type."""
    span = java_type_span(source, symbol)
    if span is None:
        return None
    base, limit = span
    actual = source[base:limit]
    code = strip_c_comments_and_literals(source)[base:limit]
    depths = java_brace_depths(code)
    matches: list[tuple[str, int, int]] = []
    for name in re.finditer(rf"\b{re.escape(field)}\b\s*=", code):
        if depths[name.start()] != 1:
            continue
        statement_start = max(code.rfind(";", 0, name.start()), code.rfind("{", 0, name.start())) + 1
        prefix = code[statement_start:name.start()]
        if re.search(r"\bstatic\s+final\b|\bfinal\s+static\b", prefix) is None:
            continue
        equals = code.find("=", name.start(), name.end())
        end = equals + 1
        round_depth = square_depth = brace_depth = 0
        while end < len(code):
            char = code[end]
            if char == "(": round_depth += 1
            elif char == ")": round_depth -= 1
            elif char == "[": square_depth += 1
            elif char == "]": square_depth -= 1
            elif char == "{": brace_depth += 1
            elif char == "}": brace_depth -= 1
            elif char == ";" and round_depth == square_depth == brace_depth == 0:
                start = equals + 1
                matches.append((normalized(actual[start:end]), base + start, base + end))
                break
            end += 1
    return matches[0] if len(matches) == 1 else None


def candidate_ids_in_source_span(relative: Path, source: str, start: int, end: int,
                                 kind: str, role: str,
                                 discovered: dict[str, Candidate]) -> list[str]:
    """Reconcile lexical offsets with stable candidate occurrences without storing offsets publicly."""
    grouped: dict[tuple[str, str, str, str, str], list[str]] = {}
    for candidate in discovered.values():
        if candidate.path != relative.as_posix():
            continue
        key = (candidate.symbol, candidate.kind, candidate.role, candidate.expression,
               candidate.evidence_digest)
        grouped.setdefault(key, []).append(candidate.id)
    occurrences: Counter[tuple[str, str, str, str, str]] = Counter()
    selected: list[str] = []
    for offset, symbol_name, candidate_kind, candidate_role, expression, evidence in code_candidates(
            relative, source, surface(relative) or "java"):
        evidence_digest = hashlib.sha256(evidence.encode("utf-8")).hexdigest()
        key = (symbol_name, candidate_kind, candidate_role, expression, evidence_digest)
        occurrence = occurrences[key]
        occurrences[key] += 1
        ids = grouped.get(key, [])
        if start <= offset < end and candidate_kind == kind and candidate_role == role \
                and occurrence < len(ids):
            selected.append(ids[occurrence])
    return selected


def java_package(source: str) -> str:
    code = strip_c_comments_and_literals(source)
    match = re.search(r"\bpackage\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;", code)
    return match.group(1) if match is not None else ""


def java_constant_reference_matches(source: str, source_owner: str, expression: str,
                                    target_owner: str, target_field: str,
                                    target_source: str) -> bool:
    """Match one bounded unqualified, imported/simple, or fully qualified Java field reference."""
    normalized_expression = normalized(expression)
    source_path, source_type = source_owner.rsplit("#", 1)
    target_path, target_type = target_owner.rsplit("#", 1)
    if normalized_expression == target_field:
        return source_path == target_path and source_type == target_type
    if normalized_expression == f"{target_type}.{target_field}":
        target_package = java_package(target_source)
        return java_package(source) == target_package or re.search(
            rf"\bimport\s+{re.escape(target_package + '.' + target_type)}\s*;",
            strip_c_comments_and_literals(source),
        ) is not None
    target_package = java_package(target_source)
    return bool(target_package) and normalized_expression == \
        f"{target_package}.{target_type}.{target_field}"

def matching_delimiter(code: str, opening: int, left: str, right: str) -> int | None:
    depth = 0
    for offset in range(opening, len(code)):
        if code[offset] == left:
            depth += 1
        elif code[offset] == right:
            depth -= 1
            if depth == 0:
                return offset
    return None


def java_brace_depths(code: str) -> list[int]:
    """Return the brace depth immediately before each character in masked Java source."""
    depths: list[int] = []
    depth = 0
    for char in code:
        depths.append(depth)
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
    return depths


def java_method_span(source: str, type_symbol: str, method: str) -> tuple[int, int] | None:
    """Resolve exactly one direct Java method body in a named type."""
    type_span = java_type_span(source, type_symbol)
    if type_span is None:
        return None
    base, limit = type_span
    code = strip_c_comments_and_literals(source)[base:limit]
    depths = java_brace_depths(code)
    matches: list[tuple[int, int]] = []
    for name in re.finditer(rf"\b{re.escape(method)}\s*\(", code):
        if depths[name.start()] != 1 or (name.start() and code[name.start() - 1] == "."):
            continue
        opening = code.find("(", name.start())
        closing = matching_delimiter(code, opening, "(", ")")
        if closing is None:
            continue
        suffix = re.match(r"\s*(?:throws\s+[^{};]+)?\s*\{", code[closing + 1:])
        if suffix is None:
            continue
        opening_brace = closing + 1 + suffix.end() - 1
        closing_brace = matching_delimiter(code, opening_brace, "{", "}")
        if closing_brace is not None:
            matches.append((base + name.start(), base + closing_brace + 1))
    return matches[0] if len(matches) == 1 else None


def split_java_arguments(actual: str, code: str, opening: int) -> tuple[list[tuple[str, int, int]], int] | None:
    closing = matching_delimiter(code, opening, "(", ")")
    if closing is None:
        return None
    arguments: list[tuple[str, int, int]] = []
    start = opening + 1
    round_depth = square_depth = brace_depth = 0
    for offset in range(start, closing):
        char = code[offset]
        if char == "(": round_depth += 1
        elif char == ")": round_depth -= 1
        elif char == "[": square_depth += 1
        elif char == "]": square_depth -= 1
        elif char == "{": brace_depth += 1
        elif char == "}": brace_depth -= 1
        elif char == "," and round_depth == square_depth == brace_depth == 0:
            arguments.append((normalized(actual[start:offset]), start, offset))
            start = offset + 1
    arguments.append((normalized(actual[start:closing]), start, closing))
    return arguments, closing


def java_constructor_component_call(source: str, type_symbol: str, method: str,
                                    constructor_type: str, components: tuple[str, ...],
                                    component: str) -> tuple[str, int, int] | None:
    """Return the exact direct constructor argument occupying one record-component position."""
    method_span = java_method_span(source, type_symbol, method)
    if method_span is None or component not in components:
        return None
    base, limit = method_span
    actual = source[base:limit]
    code = strip_c_comments_and_literals(source)[base:limit]
    constructor = re.compile(rf"\bnew\s+{re.escape(constructor_type)}\s*\(")
    found: list[tuple[list[tuple[str, int, int]], int, int]] = []
    for match in constructor.finditer(code):
        opening = code.find("(", match.start())
        parsed = split_java_arguments(actual, code, opening)
        if parsed is not None and len(parsed[0]) == len(components):
            found.append((parsed[0], opening, parsed[1]))
    if len(found) != 1:
        return None
    arguments, opening, closing = found[0]
    argument, start, end = arguments[components.index(component)]
    return argument, base + start, base + end


def java_invocation_arguments(source: str, type_symbol: str, method: str,
                              invocation: str) -> tuple[str, ...] | None:
    """Return one exact invocation's arguments from an unambiguous direct method."""
    method_span = java_method_span(source, type_symbol, method)
    if method_span is None:
        return None
    base, limit = method_span
    actual = source[base:limit]
    code = strip_c_comments_and_literals(source)[base:limit]
    pattern = re.compile(rf"(?<![\w$]){re.escape(invocation)}\s*\(")
    matches = list(pattern.finditer(code))
    if len(matches) != 1:
        return None
    opening = matches[0].end() - 1
    parsed = split_java_arguments(actual, code, opening)
    if parsed is None:
        return None
    if parsed[1] == opening + 1:
        return ()
    return tuple(argument for argument, _start, _end in parsed[0])


def java_type_assignment_expressions(source: str, type_symbol: str,
                                     field: str) -> tuple[str, ...]:
    """Return every executable ``this.field = expression`` in one Java type."""
    type_span = java_type_span(source, type_symbol)
    if type_span is None:
        return ()
    base, limit = type_span
    actual = source[base:limit]
    code = strip_c_comments_and_literals(source)[base:limit]
    expressions: list[str] = []
    for match in re.finditer(rf"\bthis\s*\.\s*{re.escape(field)}\s*=", code):
        start = match.end()
        round_depth = square_depth = brace_depth = 0
        for end in range(start, len(code)):
            char = code[end]
            if char == "(": round_depth += 1
            elif char == ")": round_depth -= 1
            elif char == "[": square_depth += 1
            elif char == "]": square_depth -= 1
            elif char == "{": brace_depth += 1
            elif char == "}": brace_depth -= 1
            elif char == ";" and round_depth == square_depth == brace_depth == 0:
                expressions.append(normalized(actual[start:end]))
                break
    return tuple(expressions)


def java_constructor_delegation_arguments(source: str, type_symbol: str,
                                          parameters: tuple[str, ...]) -> tuple[str, ...] | None:
    """Read the leading ``this(...)`` delegation from one exact constructor overload."""
    type_span = java_type_span(source, type_symbol)
    if type_span is None:
        return None
    base, limit = type_span
    actual = source[base:limit]
    code = strip_c_comments_and_literals(source)[base:limit]
    depths = java_brace_depths(code)
    found: list[tuple[str, ...]] = []
    for match in re.finditer(rf"\b{re.escape(type_symbol)}\s*\(", code):
        if depths[match.start()] != 1:
            continue
        opening = match.end() - 1
        parsed_parameters = split_java_arguments(actual, code, opening)
        if parsed_parameters is None:
            continue
        parameter_names = tuple(
            re.findall(r"\b[A-Za-z_$][\w$]*\b", argument)[-1]
            for argument, _start, _end in parsed_parameters[0]
            if re.findall(r"\b[A-Za-z_$][\w$]*\b", argument)
        )
        if parameter_names != parameters:
            continue
        closing = parsed_parameters[1]
        opening_brace = code.find("{", closing)
        if opening_brace < 0:
            continue
        closing_brace = matching_delimiter(code, opening_brace, "{", "}")
        if closing_brace is None:
            continue
        body_actual = actual[opening_brace + 1:closing_brace]
        body_code = code[opening_brace + 1:closing_brace]
        delegation = re.search(r"^\s*this\s*\(", body_code)
        if delegation is None:
            continue
        call_open = body_code.find("(", delegation.start())
        call = split_java_arguments(body_actual, body_code, call_open)
        if call is not None:
            found.append(tuple(argument for argument, _start, _end in call[0]))
    return found[0] if len(found) == 1 else None


def java_method_if_conditions(source: str, type_symbol: str, method: str) -> tuple[str, ...] | None:
    """Extract executable if conditions from one unambiguous method."""
    span = java_method_span(source, type_symbol, method)
    if span is None:
        return None
    actual = source[slice(*span)]
    code = strip_c_comments_and_literals(source)[slice(*span)]
    conditions: list[str] = []
    for match in re.finditer(r"\bif\s*\(", code):
        opening = code.find("(", match.start())
        closing = matching_delimiter(code, opening, "(", ")")
        if closing is None:
            return None
        conditions.append(normalized(actual[opening + 1:closing]))
    return tuple(conditions)


def java_method_digest(source: str, type_symbol: str, method: str) -> str | None:
    span = java_method_span(source, type_symbol, method)
    if span is None:
        return None
    body = normalized(strip_c_comments(source[slice(*span)]))
    return hashlib.sha256(body.encode("utf-8")).hexdigest()


def java_method_header(source: str, type_symbol: str, method: str) -> str | None:
    """Return the normalized declaration header for one supported direct method."""
    span = java_method_span(source, type_symbol, method)
    if span is None:
        return None
    start = source.rfind("\n", 0, span[0]) + 1
    code = strip_c_comments_and_literals(source)
    opening = code.find("{", span[0], span[1])
    if opening < 0:
        return None
    return normalized(source[start:opening])


def java_method_annotations(source: str, type_symbol: str, method: str) -> tuple[str, ...] | None:
    """Return the contiguous, one-line annotations on one supported direct Java method."""
    span = java_method_span(source, type_symbol, method)
    if span is None:
        return None
    declaration_line = source.rfind("\n", 0, span[0]) + 1
    preceding = source[:declaration_line].splitlines()
    annotations: list[str] = []
    while preceding:
        line = preceding.pop().strip()
        if not line.startswith("@"):
            break
        annotations.append(normalized(line))
    annotations.reverse()
    return tuple(annotations)


def java_test_type_is_directly_runnable(source: str, type_symbol: str) -> bool:
    """Accept one unannotated top-level test class with no abstract/inherited execution shape."""
    code = strip_c_comments_and_literals(source)
    depths = java_brace_depths(code)
    declarations = [
        match for match in re.finditer(rf"\bclass\s+{re.escape(type_symbol)}\b", code)
        if depths[match.start()] == 0
    ]
    if len(declarations) != 1:
        return False
    declaration = declarations[0]
    opening = code.find("{", declaration.end())
    if opening < 0 or re.fullmatch(r"\s*\{", code[declaration.end():opening + 1]) is None:
        return False
    boundary = 0
    for offset, char in enumerate(code[:declaration.start()]):
        if depths[offset] == 0 and char in ";}":
            boundary = offset + 1
    prefix = code[boundary:declaration.start()]
    return re.fullmatch(r"\s*(?:final\s+)?", prefix) is not None


RATE_TEST_IMPORTS = {
    "Test": "org.junit.jupiter.api.Test",
    "ParameterizedTest": "org.junit.jupiter.params.ParameterizedTest",
    "MethodSource": "org.junit.jupiter.params.provider.MethodSource",
    "Stream": "java.util.stream.Stream",
}


def java_has_exact_rate_test_imports(source: str, type_symbol: str) -> bool:
    """Bind the supported short annotation/factory names to their exact library types."""
    code = strip_c_comments_and_literals(source)
    if re.search(
            r"(?m)^\s*import\s+static\s+[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*"
            r"\.(?:Stream|\*)\s*;", code,
    ) is not None:
        return False
    imports = re.findall(
        r"(?m)^\s*import\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+)\s*;", code,
    )
    for simple, qualified in RATE_TEST_IMPORTS.items():
        matching = [imported for imported in imports if imported.rsplit(".", 1)[-1] == simple]
        if matching != [qualified]:
            return False
        if re.search(
                rf"\b(?:class|record|enum|interface)\s+{re.escape(simple)}\b"
                rf"|@interface\s+{re.escape(simple)}\b", code,
        ) is not None:
            return False
    if java_type_declares_field(source, type_symbol, "Stream"):
        return False
    return True


def java_direct_stream_string_return(source: str, type_symbol: str,
                                     method: str) -> tuple[str, ...] | None:
    """Parse one direct `return Stream.of("...")` body with quoted literals only."""
    span = java_method_span(source, type_symbol, method)
    if span is None:
        return None
    actual = strip_c_comments(source[slice(*span)])
    code = strip_c_comments_and_literals(source[slice(*span)])
    body = code.find("{")
    if body < 0:
        return None
    direct = re.match(r"\s*return\s+Stream\s*\.\s*of\s*\(", code[body + 1:])
    if direct is None:
        return None
    opening = body + 1 + direct.end() - 1
    parsed = split_java_arguments(actual, code, opening)
    if parsed is None:
        return None
    arguments, closing = parsed
    if re.fullmatch(r"\s*;\s*}", code[closing + 1:]) is None:
        return None
    values: list[str] = []
    for argument, _start, _end in arguments:
        literal = re.fullmatch(r'"(RAVENROOT_[A-Z0-9_]+)"', argument)
        if literal is None:
            return None
        values.append(literal.group(1))
    return tuple(values)


def java_compact_constructor_span(source: str, type_symbol: str) -> tuple[int, int] | None:
    """Resolve one direct compact record constructor, excluding methods and nested types."""
    type_span = java_type_span(source, type_symbol)
    if type_span is None:
        return None
    base, limit = type_span
    code = strip_c_comments_and_literals(source)[base:limit]
    depths = java_brace_depths(code)
    matches: list[tuple[int, int]] = []
    for name in re.finditer(rf"\b{re.escape(type_symbol)}\s*\{{", code):
        if depths[name.start()] != 1:
            continue
        opening = code.find("{", name.start())
        closing = matching_delimiter(code, opening, "{", "}")
        if closing is not None:
            matches.append((base + name.start(), base + closing + 1))
    return matches[0] if len(matches) == 1 else None


def java_span_digest(source: str, span: tuple[int, int] | None) -> str | None:
    if span is None:
        return None
    body = normalized(strip_c_comments(source[slice(*span)]))
    return hashlib.sha256(body.encode("utf-8")).hexdigest()


def java_reachable_helpers_from_span(source: str, type_symbol: str,
                                    span: tuple[int, int] | None) -> set[str] | None:
    """Close same-type calls beginning in a direct constructor or initializer span."""
    if span is None:
        return None
    declared = java_declared_method_names(source, type_symbol)
    code = strip_c_comments_and_literals(source)[slice(*span)]
    roots = {name for name in declared if re.search(rf"\b{re.escape(name)}\s*\(", code)}
    reachable = set(roots)
    pending = list(roots)
    while pending:
        method = pending.pop()
        calls = java_method_calls(source, type_symbol, method, declared)
        if calls is None:
            return None
        for called in calls - reachable:
            reachable.add(called)
            pending.append(called)
    return reachable


JAVA_DECIMAL_INTEGER = re.compile(r"(?:0|[1-9](?:_?[0-9])*)")
JAVA_INT_MAX = (1 << 31) - 1
JAVA_LONG_MAX = (1 << 63) - 1


def java_positive_decimal(value: str, maximum: int) -> int | None:
    if JAVA_DECIMAL_INTEGER.fullmatch(value) is None:
        return None
    parsed = int(value.replace("_", ""))
    return parsed if 0 < parsed <= maximum else None


def evaluated_java_default(expression: str, component_is_duration: bool) -> dict[str, object] | None:
    """Evaluate the closed positive int/default-duration forms used by rate configuration."""
    expression = normalized(expression)
    if component_is_duration:
        matched = re.fullmatch(
            r"Duration\.of(Seconds|Minutes|Hours|Days)\(((?:0|[1-9](?:_?[0-9])*))\)",
            expression,
        )
        if matched is None:
            return None
        value = java_positive_decimal(matched.group(2), JAVA_LONG_MAX)
        multipliers = {"Seconds": 1, "Minutes": 60, "Hours": 3600, "Days": 86400}
        if value is None or value > JAVA_LONG_MAX // multipliers[matched.group(1)]:
            return None
        seconds = value * multipliers[matched.group(1)]
        if seconds > JAVA_INT_MAX:
            return None
        return {"kind": "duration-seconds", "value": seconds}
    direct = java_positive_decimal(expression, JAVA_INT_MAX)
    if direct is not None:
        return {"kind": "integer", "value": direct}
    multiplied = re.fullmatch(
        r"((?:0|[1-9](?:_?[0-9])*))\s*\*\s*((?:0|[1-9](?:_?[0-9])*))",
        expression,
    )
    if multiplied is None:
        return None
    left = java_positive_decimal(multiplied.group(1), JAVA_INT_MAX)
    right = java_positive_decimal(multiplied.group(2), JAVA_INT_MAX)
    if left is None or right is None or left > JAVA_INT_MAX // right:
        return None
    return {"kind": "integer", "value": left * right}


def java_declared_method_names(source: str, type_symbol: str) -> set[str]:
    """Return unambiguous method names declared directly by one Java type."""
    type_span = java_type_span(source, type_symbol)
    if type_span is None:
        return set()
    base, limit = type_span
    code = strip_c_comments_and_literals(source)[base:limit]
    depths = java_brace_depths(code)
    names: Counter[str] = Counter()
    for match in re.finditer(r"\b([A-Za-z_$][\w$]*)\s*\(", code):
        if depths[match.start()] != 1:
            continue
        name = match.group(1)
        if name in {"if", "for", "while", "switch", "catch", "synchronized", "try", "do"}:
            continue
        opening = code.find("(", match.start())
        closing = matching_delimiter(code, opening, "(", ")")
        if closing is None:
            continue
        suffix = re.match(r"\s*(?:throws\s+[^{};]+)?\s*\{", code[closing + 1:])
        if suffix is not None:
            names[name] += 1
    return {name for name, count in names.items() if count == 1}


def java_direct_method_declaration_count(source: str, type_symbol: str, method: str) -> int:
    """Count every supported direct declaration, including ambiguous overloads."""
    type_span = java_type_span(source, type_symbol)
    if type_span is None:
        return 0
    base, limit = type_span
    code = strip_c_comments_and_literals(source)[base:limit]
    depths = java_brace_depths(code)
    count = 0
    for match in re.finditer(rf"\b{re.escape(method)}\s*\(", code):
        if depths[match.start()] != 1:
            continue
        opening = code.find("(", match.start())
        closing = matching_delimiter(code, opening, "(", ")")
        if closing is None:
            continue
        suffix = re.match(r"\s*(?:throws\s+[^{};]+)?\s*\{", code[closing + 1:])
        if suffix is not None:
            count += 1
    return count


def java_method_calls(source: str, type_symbol: str, method: str,
                      declared_methods: set[str]) -> set[str] | None:
    """Find same-type helper names called from one supported, unambiguous method body."""
    span = java_method_span(source, type_symbol, method)
    if span is None:
        return None
    code = strip_c_comments_and_literals(source)[slice(*span)]
    opening = code.find("{")
    if opening < 0:
        return None
    body = code[opening + 1:-1]
    return {name for name in declared_methods
            if re.search(rf"\b{re.escape(name)}\s*\(", body)}


def java_reachable_helper_methods(source: str, type_symbol: str,
                                  roots: tuple[str, ...]) -> set[str] | None:
    """Close direct same-type calls from resolver roots; overloads fail closed."""
    declared = java_declared_method_names(source, type_symbol)
    if any(root not in declared for root in roots):
        return None
    reachable = set(roots)
    pending = list(roots)
    while pending:
        method = pending.pop()
        calls = java_method_calls(source, type_symbol, method, declared)
        if calls is None:
            return None
        for called in calls - reachable:
            reachable.add(called)
            pending.append(called)
    return reachable - set(roots)


@lru_cache(maxsize=None)
def committed_source(root: Path, revision: str, path: str) -> str | None:
    if not historical_source_locator_is_safe(revision, path):
        return None
    relative = Path(path)
    result = subprocess.run(["git", "show", f"{revision}:{relative.as_posix()}"], cwd=root,
                            capture_output=True, text=True)
    return result.stdout if result.returncode == 0 else None


def historical_source_locator_is_safe(revision: object, path: object) -> bool:
    """Accept only a full lowercase commit id and one normalized repository-relative path."""
    if not isinstance(revision, str) or re.fullmatch(r"[0-9a-f]{40}", revision) is None \
            or not isinstance(path, str) or not path or "\0" in path or "\\" in path:
        return False
    relative = Path(path)
    return not relative.is_absolute() and ".." not in relative.parts \
        and "." not in relative.parts and relative.as_posix() == path


@lru_cache(maxsize=None)
def commit_exists(root: Path, revision: str) -> bool:
    if re.fullmatch(r"[0-9a-f]{40}", revision) is None:
        return False
    return subprocess.run(["git", "cat-file", "-e", f"{revision}^{{commit}}"], cwd=root,
                          capture_output=True).returncode == 0


@lru_cache(maxsize=None)
def revision_is_ancestor(root: Path, before: str, after: str) -> bool:
    if re.fullmatch(r"[0-9a-f]{40}", before) is None \
            or re.fullmatch(r"[0-9a-f]{40}", after) is None:
        return False
    return subprocess.run(["git", "merge-base", "--is-ancestor", before, after], cwd=root,
                          capture_output=True).returncode == 0


def revision_transition_errors(root: Path, identifier: str, provenance: dict[str, object],
                               *, path: str, symbol: str, label: str) -> tuple[list[str], str | None, str | None]:
    errors: list[str] = []
    before = str(provenance["beforeRevision"])
    after = str(provenance["afterRevision"])
    if before == after:
        errors.append(f"{identifier}: {label} revisions must be distinct")
    for revision_field, revision in (("beforeRevision", before), ("afterRevision", after)):
        if not commit_exists(root, revision):
            errors.append(f"{identifier}: {label} {revision_field} is not a local commit")
    if not errors and not revision_is_ancestor(root, before, after):
        errors.append(f"{identifier}: {label} beforeRevision is not an ancestor of afterRevision")
    before_source = committed_source(root, before, path)
    after_source = committed_source(root, after, path)
    if before_source is None or not re.search(rf"\b{re.escape(symbol)}\b", before_source):
        errors.append(f"{identifier}: {label} path/symbol is absent from beforeRevision")
    if after_source is None or not re.search(rf"\b{re.escape(symbol)}\b", after_source):
        errors.append(f"{identifier}: {label} path/symbol is absent from afterRevision")
    if before_source is not None and after_source is not None and before_source == after_source:
        errors.append(f"{identifier}: {label} source is unchanged between revisions")
    return errors, before_source, after_source


def yaml_scalar_rows(source: str) -> list[tuple[int, str, str]]:
    """Return line, path, and value for mapping-only YAML scalars used by deployment values."""
    stack: list[tuple[int, str]] = []
    rows: list[tuple[int, str, str]] = []
    for line, raw in enumerate(source.splitlines(), start=1):
        if not raw.strip() or raw.lstrip().startswith(("#", "-")):
            continue
        match = re.match(r'^([ ]*)([A-Za-z_][A-Za-z0-9_.-]*)\s*:\s*(.*?)\s*$', raw)
        if match is None:
            continue
        indent = len(match.group(1))
        while stack and stack[-1][0] >= indent:
            stack.pop()
        key = match.group(2)
        value = match.group(3)
        path = tuple(item[1] for item in stack) + (key,)
        if value:
            rows.append((line, ".".join(path), normalized(value)))
        else:
            stack.append((indent, key))
    return rows


def yaml_scalar_at_path(source: str, dotted_path: str) -> str | None:
    """Return one scalar at an exact mapping-only YAML path used by deployment values."""
    found = [value for _line, path, value in yaml_scalar_rows(source) if path == dotted_path]
    return found[0] if len(found) == 1 else None


def helm_schema_pointer(value_path: str) -> str:
    return "/properties/" + "/properties/".join(value_path.split("."))


def helm_schema_contract(schema: object, value_path: str) -> object | None:
    """Resolve one value leaf while requiring every mapping ancestor to be closed and required."""
    node = schema
    for component in value_path.split("."):
        if not isinstance(node, dict):
            return None
        reference = node.get("$ref")
        if reference is not None:
            resolved = json_pointer(schema, str(reference))
            if not isinstance(resolved, dict):
                return None
            node = resolved
        properties = node.get("properties")
        if node.get("type") != "object" or node.get("additionalProperties") is not False \
                or not isinstance(properties, dict) or component not in properties \
                or not isinstance(node.get("required"), list) \
                or node["required"].count(component) != 1:
            return None
        node = properties[component]
    if isinstance(node, dict) and "$ref" in node:
        node = json_pointer(schema, str(node["$ref"]))
    return node


def helm_schema_rule_matches(schema: object, value_path: str, rule: str) -> bool:
    node = helm_schema_contract(schema, value_path)
    if not isinstance(node, dict):
        return False
    graph_blank = {"$ref": "#/definitions/graphBlank"}
    rules: dict[str, object] = {
        "nonempty-string": {"type": "string", "minLength": 1},
        "string": {"type": "string"},
        "image-digest": {"type": "string", "pattern": "^(|sha256:[a-f0-9]{64})$"},
        "image-pull-policy": {"type": "string", "enum": ["Always", "IfNotPresent", "Never"]},
        "service-type": {"type": "string", "enum": ["ClusterIP", "NodePort", "LoadBalancer"]},
        "service-port": {"type": "integer", "minimum": 1, "maximum": 65535},
        "required-auth": {"type": "string", "minLength": 1, "not": graph_blank},
        "program-timeout": {"x-ravenroot-environment": "RAVENROOT_PROGRAM_TIMEOUT_MS",
                            "oneOf": [{"type": "integer", "minimum": 100, "maximum": 300000}, graph_blank]},
        "boolean": {"type": "boolean"},
        "positive-id": {"type": "integer", "minimum": 1, "maximum": 2147483647},
        "fs-group-policy": {"type": "string", "enum": ["Always", "OnRootMismatch"]},
        "probe-initial": {"type": "integer", "minimum": 0, "maximum": 2147483647},
        "probe-period": {"type": "integer", "minimum": 1, "maximum": 2147483647},
        "positive-quantity": {"type": "string", "pattern": "^\\+?(?:[1-9][0-9]*(?:\\.[0-9]+)?|0\\.[0-9]*[1-9][0-9]*|\\.[0-9]*[1-9][0-9]*)(?:[eE][+-]?[0-9]+|n|u|m|k|M|G|T|P|E|Ki|Mi|Gi|Ti|Pi|Ei)?$"},
        "quantity": {"type": "string", "pattern": "^\\+?(?:(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+|n|u|m|k|M|G|T|P|E|Ki|Mi|Gi|Ti|Pi|Ei)?$"},
    }
    graph_blank_contract = {
        "type": "string",
        "pattern": "^[\u0009-\u000D\u001C-\u0020\u1680\u2000-\u2006\u2008-\u200A\u2028-\u2029\u205F\u3000]*$",
    }
    if ("#/definitions/graphBlank" in json.dumps(node, sort_keys=True)
            and json_pointer(schema, "#/definitions/graphBlank") != graph_blank_contract):
        return False
    return rule in rules and node == rules[rule]


def helm_values_leaf_paths(source: str) -> set[str]:
    paths = {path for _line, path, _value in yaml_scalar_rows(source)}
    stack: list[tuple[int, str]] = []
    for raw in source.splitlines():
        mapping = re.match(r'^([ ]*)([A-Za-z_][A-Za-z0-9_.-]*)\s*:\s*(.*?)\s*$', raw)
        if mapping is not None:
            indent = len(mapping.group(1))
            while stack and stack[-1][0] >= indent:
                stack.pop()
            if not mapping.group(3):
                stack.append((indent, mapping.group(2)))
            continue
        item = re.match(r'^([ ]*)-\s+(\S.*?)\s*$', raw)
        if item is not None:
            indent = len(item.group(1))
            while stack and stack[-1][0] >= indent:
                stack.pop()
            if stack:
                prefix = ".".join(component for _depth, component in stack)
                index = sum(1 for path in paths if path.startswith(prefix + ".") and
                            path[len(prefix) + 1:].split(".", 1)[0].isdigit())
                paths.add(f"{prefix}.{index}")
    return paths


def json_value_spans(source: str) -> dict[tuple[str, ...], tuple[int, int]] | None:
    """Return exact character spans for values in a JSON object without accepting extensions."""
    decoder = json.JSONDecoder()
    spans: dict[tuple[str, ...], tuple[int, int]] = {}

    def whitespace(offset: int) -> int:
        while offset < len(source) and source[offset].isspace():
            offset += 1
        return offset

    def parse(offset: int, path: tuple[str, ...]) -> int:
        start = whitespace(offset)
        if start >= len(source):
            raise ValueError("missing JSON value")
        if source[start] == "{":
            cursor = whitespace(start + 1)
            if cursor < len(source) and source[cursor] == "}":
                end = cursor + 1
            else:
                while True:
                    key, consumed = decoder.raw_decode(source[cursor:])
                    if not isinstance(key, str):
                        raise ValueError("JSON object key is not a string")
                    cursor = whitespace(cursor + consumed)
                    if cursor >= len(source) or source[cursor] != ":":
                        raise ValueError("missing JSON colon")
                    cursor = parse(cursor + 1, path + (key,))
                    cursor = whitespace(cursor)
                    if cursor < len(source) and source[cursor] == ",":
                        cursor = whitespace(cursor + 1)
                        continue
                    if cursor >= len(source) or source[cursor] != "}":
                        raise ValueError("unterminated JSON object")
                    end = cursor + 1
                    break
        elif source[start] == "[":
            cursor = whitespace(start + 1)
            index = 0
            if cursor < len(source) and source[cursor] == "]":
                end = cursor + 1
            else:
                while True:
                    cursor = parse(cursor, path + (str(index),))
                    index += 1
                    cursor = whitespace(cursor)
                    if cursor < len(source) and source[cursor] == ",":
                        cursor = whitespace(cursor + 1)
                        continue
                    if cursor >= len(source) or source[cursor] != "]":
                        raise ValueError("unterminated JSON array")
                    end = cursor + 1
                    break
        else:
            _value, consumed = decoder.raw_decode(source[start:])
            end = start + consumed
        spans[path] = (start, end)
        return end

    try:
        end = parse(0, ())
        if whitespace(end) != len(source):
            return None
    except (ValueError, json.JSONDecodeError):
        return None
    return spans


def helm_projection_matches(root: Path, value_path: str, projection: str) -> bool:
    executable = lambda path: "\n".join(
        line for line in (root / path).read_text(encoding="utf-8").splitlines()
        if not line.lstrip().startswith("#"))
    try:
        deployment = executable(HELM_TEMPLATE_PATHS[1])
        helpers = executable(HELM_TEMPLATE_PATHS[0])
        pvc = executable(HELM_TEMPLATE_PATHS[2])
        service = executable(HELM_TEMPLATE_PATHS[3])
    except (FileNotFoundError, UnicodeDecodeError):
        return False
    direct = {
        "image-pull-policy": (deployment, r"imagePullPolicy:\s*{{\s*\.Values\.image\.pullPolicy\s*}}"),
        "service-type": (service, r"type:\s*{{\s*\.Values\.service\.type\s*}}"),
        "service-port": (service, r"port:\s*{{\s*\.Values\.service\.port\s*}}"),
        "resources": (deployment, r"(?m)^          resources:\s*$\n^            {{- toYaml \.Values\.resources \| nindent 12 }}$"),
        "pod-security": (deployment, r"(?m)^      securityContext:\s*$\n^        {{- toYaml \.Values\.podSecurityContext \| nindent 8 }}$"),
        "container-security": (deployment, r"(?m)^          securityContext:\s*$\n^            {{- toYaml \.Values\.securityContext \| nindent 12 }}$"),
        "readiness-probe": (deployment, r"(?m)^            {{- toYaml \.Values\.probes\.readiness \| nindent 12 }}$"),
        "liveness-probe": (deployment, r"(?m)^            {{- toYaml \.Values\.probes\.liveness \| nindent 12 }}$"),
        "tmpfs": (deployment, r"sizeLimit:\s*{{\s*\.Values\.tmpfs\.sizeLimit\s*\|\s*quote\s*}}"),
        "persistence-size": (pvc, r"storage:\s*{{\s*\.Values\.persistence\.size\s*\|\s*quote\s*}}"),
        "persistence-storage-class": (pvc, r"with\s+\.Values\.persistence\.storageClass\b"),
    }
    if projection == "image-helper":
        return len(re.findall(r"\.Values\.image\.(?:repository|tag|digest)\b", helpers)) == 5 \
            and len(re.findall(r'include\s+"ravenroot\.image"', deployment)) == 1
    if projection == "auth-required":
        leaf = value_path.rsplit(".", 1)[1]
        environment = {"issuer": "ISSUER", "audience": "AUDIENCE", "jwksUri": "JWKS_URI"}[leaf]
        pattern = rf'- name:\s*RAVENROOT_AUTH_{environment}\s+value:\s*{{{{\s*required\s+"auth\.{leaf} is required"\s+\.Values\.auth\.{leaf}\s*\|\s*quote\s*}}}}'
        return len(re.findall(pattern, deployment)) == 1
    if projection == "program-timeout":
        return deployment.count(
            '- name: RAVENROOT_PROGRAM_TIMEOUT_MS\n'
            '              value: {{ include "ravenroot.graphLimitValue" .Values.programTimeoutMs }}') == 1
    source_pattern = direct.get(projection)
    return source_pattern is not None and len(re.findall(source_pattern[1], source_pattern[0])) == 1


def helm_test_evidence_errors(root: Path) -> list[str]:
    errors: list[str] = []
    sources: dict[str, str] = {}
    for path in HELM_TEST_ROLES:
        test = root / path
        if not test.is_file():
            errors.append(f"Helm authority test evidence is missing: {path}")
        else:
            sources[path] = "\n".join(line for line in test.read_text(encoding="utf-8").splitlines()
                                      if not line.lstrip().startswith("#"))
    values = sources.get("scripts/tests/test_helm_values_contract.sh", "")
    timeout = sources.get("scripts/tests/test_program_timeout_helm_contract.sh", "")
    unsupported = sources.get("scripts/tests/test_execution_manifest_pin_helm_contract.sh", "")
    requirements = (
        (values, r'^helm_base >"\$TEMP_DIR/default\.yaml"$', "default render"),
        (values, r'^helm_base \\$', "nondefault render"),
        (values, r'^  >"\$TEMP_DIR/tag-only\.yaml"$', "tag-only render"),
        (values, r'^for invalid in \\$', "invalid-value refusal"),
        (values, r'set\(schema\.get\("required", \[\]\)\) != expected_top', "required closure"),
        (timeout, r'^for value in 100 15000 300000; do$', "timeout boundaries"),
        (timeout, r'^for invalid in 99 300001; do$', "timeout range refusal"),
        (timeout, r'^for label_and_value in ', "timeout blank delegation"),
        (unsupported, r'RAVENROOT_EXECUTION_MANIFEST_PIN_ATTEMPTS', "unsupported setting refusal"),
    )
    for source, pattern, label in requirements:
        if re.search(pattern, source, re.MULTILINE) is None:
            errors.append(f"Helm authority test evidence lacks executable {label}")
    for value_path, _setting, _default, _schema_rule, _projection in HELM_OPERATOR_VALUE_CONTRACTS:
        source = timeout if value_path == "programTimeoutMs" else values
        if value_path not in source:
            errors.append(f"Helm authority test evidence does not exercise {value_path}")
    for assertion in (
            "default image values did not render", "required OIDC values did not render",
            "default Service values did not render", "default pod security values did not render",
            "default resource requirements did not render", "default probe timing values did not render",
            "default tmpfs size limit did not render",
            "default persistent-volume contract did not render", "nondefault image values did not render",
            "tag-only image values did not render",
            "nondefault Service values did not render", "nondefault resource requirements did not render",
            "nondefault pod security values did not render", "nondefault container identity values did not render",
            "nondefault probe timing values did not render", "nondefault tmpfs size limit did not render",
            "nondefault persistent-volume values did not render"):
        if assertion not in values:
            errors.append(f"Helm authority test evidence lacks executable assertion: {assertion}")
    for refusal in (
            "podSecurityContext.runAsNonRoot=false",
            "securityContext.allowPrivilegeEscalation=true",
            "securityContext.readOnlyRootFilesystem=false"):
        if refusal not in values:
            errors.append(f"Helm authority test evidence lacks fixed-hardening refusal: {refusal}")
    tag_only_case = (
        'helm_base \\\n'
        '  --set-string image.repository=registry.example.test/ravenroot \\\n'
        '  --set-string image.tag=release-test \\\n'
        '  --set-string image.digest= \\\n'
        '  >"$TEMP_DIR/tag-only.yaml"')
    tag_only_assertion = (
        'tag_image = tag_deployment["spec"]["template"]["spec"]["containers"][0]["image"]\n'
        'if tag_image != "registry.example.test/ravenroot:release-test":\n'
        '    raise SystemExit("tag-only image values did not render")')
    if values.count(tag_only_case) != 1 or values.count(tag_only_assertion) != 1:
        errors.append("Helm authority test evidence lacks the exact executable tag-only image case")
    return errors


def helm_chart_present(root: Path, candidates: tuple[Candidate, ...]) -> bool:
    """Distinguish true chart absence from a partial or invalid supported chart."""
    chart_paths = {HELM_CHART_PATH, HELM_VALUES_PATH, HELM_SCHEMA_PATH, *HELM_TEMPLATE_PATHS}
    return any((root / path).exists() for path in chart_paths) \
        or any(candidate.path in chart_paths for candidate in candidates)


def helm_chart_metadata(root: Path) -> dict[str, object] | None:
    """Read the exact flat metadata contract that identifies the supported chart."""
    try:
        source = (root / HELM_CHART_PATH).read_text(encoding="utf-8")
    except (FileNotFoundError, UnicodeDecodeError):
        return None
    expected_fields = {
        "apiVersion", "name", "description", "type", "version", "appVersion", "kubeVersion",
    }
    fields: dict[str, str] = {}
    for raw in source.splitlines():
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        match = re.fullmatch(r"([A-Za-z][A-Za-z0-9]*)\s*:\s*(.*?)\s*", raw)
        if match is None or match.group(1) in fields or not match.group(2):
            return None
        key, scalar = match.groups()
        if scalar.startswith('"'):
            try:
                value = json.loads(scalar)
            except json.JSONDecodeError:
                return None
            if not isinstance(value, str):
                return None
        elif scalar.startswith("'"):
            if len(scalar) < 2 or not scalar.endswith("'"):
                return None
            quoted = scalar[1:-1]
            if "'" in quoted.replace("''", ""):
                return None
            value = quoted.replace("''", "'")
        elif scalar[0] in "[{>|&*!" or " #" in scalar \
                or re.search(r":(?:\s|$)", scalar):
            return None
        else:
            value = scalar
        if not value.strip():
            return None
        fields[key] = value
    if set(fields) != expected_fields \
            or fields["apiVersion"] != "v2" \
            or fields["name"] != "ravenroot" \
            or fields["type"] != "application":
        return None
    # Release tooling owns version transitions; this proof reuses its accepted grammar and
    # equality check while treating both values as chart metadata rather than operator settings.
    if PRODUCT_SEMVER.fullmatch(fields["version"]) is None \
            or product_helm_errors(fields["version"], source) \
            or re.fullmatch(r">=[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?",
                            fields["kubeVersion"]) is None:
        return None
    return {
        "path": HELM_CHART_PATH,
        "apiVersion": fields["apiVersion"],
        "name": fields["name"],
        "description": fields["description"],
        "type": fields["type"],
        "version": fields["version"],
        "appVersion": fields["appVersion"],
        "kubeVersion": fields["kubeVersion"],
        "digest": hashlib.sha256(source.encode("utf-8")).hexdigest(),
    }


def helm_timeout_runtime_evidence(root: Path) -> dict[str, object] | None:
    path = ("ravenroot/ravenroot-programming-graalvm/src/main/java/ai/ravenroot/"
            "programming/graalvm/GraalVmProgramRuntime.java")
    try:
        source = (root / path).read_text(encoding="utf-8")
    except (FileNotFoundError, UnicodeDecodeError):
        return None
    methods = ("policyFor", "compatibilityFingerprint", "invokeSupervisor")
    spans = {method: java_method_span(source, "GraalVmProgramRuntime", method) for method in methods}
    masked = strip_c_comments_and_literals(source)
    signature = re.search(
        r'\bstatic\s+GraalVmProgramRuntime\s+fromEnvironment\s*\(\s*java\.util\.Map<String,\s*String>\s+environment\s*\)\s*\{',
        masked)
    spans["fromEnvironmentMap"] = None
    if signature is not None:
        opening = masked.find("{", signature.start())
        closing = matching_delimiter(masked, opening, "{", "}")
        spans["fromEnvironmentMap"] = None if closing is None else (signature.start(), closing + 1)
    if any(span is None for span in spans.values()):
        return None
    bodies = {method: strip_c_comments(source[slice(*spans[method])]) for method in methods}
    bodies["fromEnvironment"] = strip_c_comments(source[slice(*spans["fromEnvironmentMap"])])
    checks = (
        re.search(r'Duration\s+timeout\s*=\s*Duration\.ofMillis\s*\(\s*integerEnvironment\s*\(\s*environment\s*,\s*"RAVENROOT_PROGRAM_TIMEOUT_MS"\s*,\s*5_000\s*,\s*100\s*,\s*300_000\s*\)\s*\)', bodies["fromEnvironment"]),
        re.search(r'new\s+SandboxPolicy\s*\(\s*timeout\s*,\s*Math\.toIntExact\s*\(\s*timeout\.toMillis\s*\(\s*\)\s*\)', bodies["policyFor"]),
        re.search(r'policy\.deadline\s*\(\s*\)\.toMillis\s*\(\s*\)', bodies["compatibilityFingerprint"]),
        re.search(r'deadline\s*=\s*start\s*\+\s*policy\.deadline\s*\(\s*\)\.toNanos\s*\(\s*\)', bodies["invokeSupervisor"]),
    )
    if not all(checks):
        return None
    return {"path": path, "type": "GraalVmProgramRuntime",
            "methods": {method: hashlib.sha256(normalized(bodies[
                "fromEnvironment" if method == "fromEnvironmentMap" else method]).encode("utf-8")).hexdigest()
                        for method in (*methods, "fromEnvironmentMap")}}


def helm_authority_from_source(root: Path, candidates: tuple[Candidate, ...]) -> dict[str, object] | None:
    try:
        values_source = (root / HELM_VALUES_PATH).read_text(encoding="utf-8")
        schema_source = (root / HELM_SCHEMA_PATH).read_text(encoding="utf-8")
        release_contract_source = (root / HELM_RELEASE_CONTRACT_PATH).read_text(encoding="utf-8")
        schema = json.loads(schema_source)
    except (FileNotFoundError, UnicodeDecodeError, json.JSONDecodeError):
        return None
    chart_metadata = helm_chart_metadata(root)
    if chart_metadata is None:
        return None
    operator_paths = {contract[0] for contract in HELM_OPERATOR_VALUE_CONTRACTS}
    fixed_paths = set(HELM_FIXED_VALUE_CONTRACTS) | set(HELM_FIXED_LIST_CONTRACTS)
    actual_paths = helm_values_leaf_paths(values_source)
    external_paths = {path for path in actual_paths
                      if any(path.startswith(prefix) for prefix in HELM_JAVA_CARRIER_PREFIXES)}
    if actual_paths != operator_paths | fixed_paths | external_paths:
        return None
    schema_spans = json_value_spans(schema_source)
    if schema_spans is None:
        return None
    contracts: list[dict[str, object]] = []
    scalar_rows = {(line, path): value for line, path, value in yaml_scalar_rows(values_source)}
    values_candidates = [candidate for candidate in candidates if candidate.path == HELM_VALUES_PATH]
    schema_candidates = [candidate for candidate in candidates if candidate.path == HELM_SCHEMA_PATH]
    template_candidates = [candidate for candidate in candidates
                           if candidate.path in HELM_TEMPLATE_PATHS]
    schema_lines = [0]
    for match in re.finditer("\n", schema_source):
        schema_lines.append(match.end())
    for value_path, setting, default, schema_rule, projection in HELM_OPERATOR_VALUE_CONTRACTS:
        if yaml_scalar_at_path(values_source, value_path) != default \
                or not helm_schema_rule_matches(schema, value_path, schema_rule) \
                or not helm_projection_matches(root, value_path, projection):
            return None
        lines = {line for (line, path), _value in scalar_rows.items() if path == value_path}
        pointer_path = tuple(component for pair in
                             (("properties", part) for part in value_path.split("."))
                             for component in pair)
        candidate_ids = {candidate.id for candidate in values_candidates if candidate.line in lines}
        schema_span = schema_spans.get(pointer_path)
        if schema_span is not None:
            first_line = bisect_right(schema_lines, schema_span[0])
            last_line = bisect_right(schema_lines, max(schema_span[0], schema_span[1] - 1))
            candidate_ids.update(candidate.id for candidate in schema_candidates
                                 if first_line <= candidate.line <= last_line)
        template_value = f".Values.{value_path}"
        # Direct leaf projections are unambiguous. Parent toYaml projections remain structural
        # Helm candidates and are covered by the authority-wide exact set instead of being assigned
        # to multiple logical settings.
        for path in HELM_TEMPLATE_PATHS:
            local_lines = {index for index, line in enumerate(
                (root / path).read_text(encoding="utf-8").splitlines(), 1)
                           if template_value in line}
            if local_lines:
                candidate_ids.update(candidate.id for candidate in template_candidates
                                     if candidate.path == path and candidate.line in local_lines)
        schema_contract = helm_schema_contract(schema, value_path)
        environment_name = schema_contract.get("x-ravenroot-environment") \
            if isinstance(schema_contract, dict) else None
        environment_name = {
            "auth.issuer": "RAVENROOT_AUTH_ISSUER",
            "auth.audience": "RAVENROOT_AUTH_AUDIENCE",
            "auth.jwksUri": "RAVENROOT_AUTH_JWKS_URI",
        }.get(value_path, environment_name)
        if isinstance(environment_name, str):
            candidate_ids.update(candidate.id for candidate in template_candidates
                                 if candidate.kind == "environment-binding"
                                 and candidate.expression == environment_name)
        contracts.append({
            "setting": setting,
            "owner": f"{HELM_VALUES_PATH}#{value_path.split('.', 1)[0]}",
            "field": value_path,
            "valuePath": value_path,
            "default": default,
            "defaultDisplay": (
                "no valid default; an explicit nonblank public OIDC value is required"
                if schema_rule == "required-auth" else
                "15000 ms Helm deployment profile; blank delegates to the Java-owned 5000 ms default"
                if value_path == "programTimeoutMs" else default),
            "validation": schema_rule,
            "scope": "one rendered Helm release",
            "pinning": "resolved by Helm schema validation and rendered into the pod specification",
            "coverage": "closed values/schema/template and executable default, nondefault, and refusal contracts",
            "schemaPointer": helm_schema_pointer(value_path),
            "schemaRule": schema_rule,
            "schemaContract": schema_contract,
            "projection": projection,
            "bindings": [] if environment_name is None else [environment_name],
            "candidateIds": sorted(candidate_ids),
        })
    for path, expected in HELM_FIXED_VALUE_CONTRACTS.items():
        if yaml_scalar_at_path(values_source, path) != expected:
            return None
    fixed_schema = {
        "replicaCount": {"type": "integer", "minimum": 1, "maximum": 1},
        "engine": {"type": "string", "enum": ["pekko"]},
        "podSecurityContext.runAsNonRoot": {"type": "boolean", "const": True},
        "podSecurityContext.seccompProfile.type": {"type": "string", "enum": ["RuntimeDefault"]},
        "securityContext.allowPrivilegeEscalation": {"type": "boolean", "const": False},
        "securityContext.readOnlyRootFilesystem": {"type": "boolean", "const": True},
    }
    if any(helm_schema_contract(schema, path) != contract
           for path, contract in fixed_schema.items()):
        return None
    if json_pointer(schema, "#/properties/securityContext/properties/capabilities/properties/drop") \
            != {"type": "array", "minItems": 1, "uniqueItems": True,
                "items": {"type": "string", "enum": ["ALL"]}} \
            or json_pointer(schema, "#/properties/persistence/properties/accessModes") \
            != {"type": "array", "minItems": 1, "maxItems": 1, "uniqueItems": True,
                "items": {"type": "string", "enum": ["ReadWriteOnce"]}}:
        return None
    for path, expected in HELM_FIXED_LIST_CONTRACTS.items():
        parent, index = path.rsplit(".", 1)
        if index != "0" or re.search(
                rf'(?m)^\s*{re.escape(parent.rsplit(".", 1)[-1])}:\s*$[\s\S]*?^\s*-\s*{re.escape(expected)}\s*$',
                values_source) is None:
            return None
    runtime = helm_timeout_runtime_evidence(root)
    if runtime is None or helm_test_evidence_errors(root):
        return None
    covered_paths = {
        HELM_CHART_PATH, HELM_VALUES_PATH, HELM_SCHEMA_PATH,
        *HELM_TEMPLATE_PATHS, *HELM_TEST_ROLES,
    }
    return {
        "kind": "helm-values-authority-v1",
        "chartMetadata": chart_metadata,
        "releaseVersionEvidence": {
            "path": HELM_RELEASE_CONTRACT_PATH,
            "fields": ["version", "appVersion"],
            "digest": hashlib.sha256(release_contract_source.encode("utf-8")).hexdigest(),
        },
        "valuesPath": HELM_VALUES_PATH,
        "schemaPath": HELM_SCHEMA_PATH,
        "templatePaths": list(HELM_TEMPLATE_PATHS),
        "contracts": contracts,
        "fixedValuePaths": sorted(fixed_paths),
        "javaCarrierPaths": sorted(external_paths),
        "testEvidence": [{"path": path, "roles": list(roles),
                          "digest": hashlib.sha256((root / path).read_bytes()).hexdigest()}
                         for path, roles in HELM_TEST_ROLES.items()],
        "timeoutRuntime": runtime,
        "candidateIds": sorted(candidate.id for candidate in candidates
                               if candidate.path in covered_paths),
    }


def helm_authority_errors(root: Path, authorities: object,
                          entries: dict[str, dict[str, object]],
                          candidates: tuple[Candidate, ...]) -> list[str]:
    chart_present = helm_chart_present(root, candidates)
    expected = helm_authority_from_source(root, candidates)
    helm_entries = [entry for entry in entries.values() if entry.get("helmAuthority") is not None]
    if not chart_present:
        return ([] if authorities in (None, {}) and not helm_entries else
                ["Helm authority or owned rows exist without a supported Helm chart"])
    errors: list[str] = []
    if expected is None:
        return ["Helm values, schema, templates, runtime, or executable tests violate the closed authority"]
    if not isinstance(authorities, dict) or set(authorities) != {HELM_AUTHORITY_ID} \
            or authorities.get(HELM_AUTHORITY_ID) != expected:
        errors.append("Helm settings require the exact source-derived closed values authority")
    by_setting = {str(contract["setting"]): contract for contract in expected["contracts"]}
    expected_candidate_settings: dict[str, str] = {}
    for setting, contract in by_setting.items():
        candidate_ids = contract.get("candidateIds")
        if not isinstance(candidate_ids, list):
            errors.append(f"{setting}: Helm authority candidate evidence is malformed")
            continue
        for identifier in candidate_ids:
            previous = expected_candidate_settings.setdefault(str(identifier), setting)
            if previous != setting:
                errors.append(
                    f"Helm candidate {identifier} is assigned to both {previous} and {setting}")
    assigned: dict[str, set[str]] = defaultdict(set)
    for entry in entries.values():
        marker = entry.get("helmAuthority")
        if marker is None:
            continue
        setting = str(entry.get("setting", ""))
        contract = by_setting.get(setting)
        assigned[setting].add(str(entry.get("id")))
        if marker != HELM_AUTHORITY_ID or contract is None:
            errors.append(f"{entry.get('id')}: unsupported Helm authority or setting")
            continue
        if entry.get("owner") != contract["owner"] or entry.get("field") != contract["field"]:
            errors.append(f"{entry.get('id')}: Helm owner or value path has drifted")
        for field in ("bindings", "defaultDisplay", "validation", "scope", "pinning", "coverage"):
            entry_field = "default" if field == "defaultDisplay" else field
            if entry.get(entry_field) != contract[field]:
                errors.append(f"{entry.get('id')}: Helm {entry_field} metadata has drifted")
        if not str(entry.get("owner", "")).startswith(HELM_VALUES_PATH + "#"):
            errors.append(f"{entry.get('id')}: Helm proof cannot authorize a non-values owner")
    for setting, contract in by_setting.items():
        expected_ids = {str(identifier) for identifier in contract.get("candidateIds", [])}
        if assigned[setting] != expected_ids:
            errors.append(
                f"{setting}: Helm candidate coverage is incomplete, duplicate, or foreign")
    errors.extend(helm_test_evidence_errors(root))
    return errors


def yaml_default_removal_errors(root: Path, identifier: str, entry: dict[str, object],
                                removal: dict[str, object],
                                active_entries: dict[str, dict[str, object]]) -> list[str]:
    """Verify removal of one exact YAML default in favor of a typed Java authority."""
    required = ("yamlPath", "beforeValue", "afterValue", "replacementOwner", "replacementField",
                "replacementInstanceSymbol", "replacementDefaultExpression")
    if any(not isinstance(removal.get(field), str) or not str(removal[field]).strip()
           for field in required):
        return [f"{identifier}: YAML default removal requires {', '.join(required)}"]
    errors: list[str] = []
    before = str(removal.get("beforeRevision", ""))
    after = str(removal.get("afterRevision", ""))
    path = str(entry.get("path", ""))
    if before == after:
        errors.append(f"{identifier}: removal revisions must be distinct")
    for field, revision in (("beforeRevision", before), ("afterRevision", after)):
        if not commit_exists(root, revision):
            errors.append(f"{identifier}: removal {field} is not a local commit")
    if not errors and not revision_is_ancestor(root, before, after):
        errors.append(f"{identifier}: removal beforeRevision is not an ancestor of afterRevision")
    before_source = committed_source(root, before, path)
    after_source = committed_source(root, after, path)
    if before_source is None or after_source is None:
        errors.append(f"{identifier}: removal YAML path is absent from a revision")
        return errors
    yaml_path = str(removal["yamlPath"])
    before_value = yaml_scalar_at_path(before_source, yaml_path)
    after_value = yaml_scalar_at_path(after_source, yaml_path)
    exact_before = [(candidate_path, value) for line, candidate_path, value
                    in yaml_scalar_rows(before_source) if line == entry.get("line")]
    if exact_before != [(yaml_path, before_value)]:
        errors.append(f"{identifier}: YAML default removal path does not identify the retired source line")
    if before_value != normalized(str(removal["beforeValue"])) \
            or before_value != normalized(str(entry.get("expression", ""))):
        errors.append(f"{identifier}: YAML default removal beforeValue does not match the exact path")
    if after_value != normalized(str(removal["afterValue"])):
        errors.append(f"{identifier}: YAML default removal afterValue does not match the exact path")
    if str(entry.get("role", "")) != yaml_path.rsplit(".", 1)[-1]:
        errors.append(f"{identifier}: YAML default removal path does not match the candidate role")

    setting = str(entry.get("setting", ""))
    replacement_owner = str(removal["replacementOwner"])
    replacement_field = str(removal["replacementField"])
    representatives = [candidate for candidate in active_entries.values()
                       if candidate.get("setting") == setting
                       and candidate.get("classification") == "operator-configurable"
                       and candidate.get("status") != "pending-review"
                       and candidate.get("owner") == replacement_owner
                       and candidate.get("field") == replacement_field]
    if not setting or not representatives:
        errors.append(f"{identifier}: YAML default removal has no active typed replacement setting")
        return errors
    authority = representatives[0].get("defaultAuthority")
    if not isinstance(authority, dict) \
            or authority.get("owner") != replacement_owner \
            or authority.get("field") != replacement_field \
            or authority.get("instanceSymbol") != removal["replacementInstanceSymbol"] \
            or normalized(str(authority.get("sourceExpression", ""))) \
            != normalized(str(removal["replacementDefaultExpression"])):
        errors.append(f"{identifier}: YAML default removal does not match active defaultAuthority")

    if "#" not in replacement_owner:
        errors.append(f"{identifier}: replacementOwner must be path#symbol")
        return errors
    owner_path, owner_symbol = replacement_owner.rsplit("#", 1)
    replacement_source = committed_source(root, after, owner_path)
    if replacement_source is None or java_type_span(replacement_source, owner_symbol) is None \
            or not java_type_declares_field(replacement_source, owner_symbol, replacement_field):
        errors.append(f"{identifier}: typed replacement owner/field is absent from afterRevision")
        return errors
    actual_default = java_record_default_expression(
        replacement_source, owner_symbol, str(removal["replacementInstanceSymbol"]), replacement_field)
    if actual_default is None or normalized(actual_default) \
            != normalized(str(removal["replacementDefaultExpression"])):
        errors.append(f"{identifier}: typed replacement default has drifted in afterRevision")
    return errors


SOURCE_METADATA_FIELDS = {
    "id", "path", "line", "symbol", "kind", "role", "expression",
    "expressionDigest", "evidenceDigest", "surface",
}


def committed_json(root: Path, revision: str, path: str) -> tuple[dict[str, object] | None, bytes | None]:
    """Load one committed JSON object without accepting an option-shaped locator."""
    if not historical_source_locator_is_safe(revision, path):
        return None, None
    result = subprocess.run(["git", "show", f"{revision}:{path}"], cwd=root,
                            capture_output=True)
    if result.returncode != 0:
        return None, None
    try:
        value = json.loads(result.stdout)
    except (UnicodeDecodeError, json.JSONDecodeError):
        return None, result.stdout
    return value if isinstance(value, dict) else None, result.stdout


def candidate_set_digest(identifiers: Iterable[str]) -> str:
    payload = "\n".join(sorted(identifiers)) + "\n"
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def reconciliation_target_tree_errors(root: Path, target_revision: str) -> list[str]:
    """Require the scanned worktree to be exactly the committed reconciliation target."""
    head = subprocess.run(["git", "rev-parse", "HEAD"], cwd=root, capture_output=True, text=True)
    if head.returncode != 0 or not revision_is_ancestor(root, target_revision, head.stdout.strip()):
        return ["reconciliation target revision is not an ancestor of the checked-out HEAD"]
    status = subprocess.run(
        ["git", "status", "--porcelain=v1", "--untracked-files=all"],
        cwd=root, capture_output=True, text=True,
    )
    if status.returncode != 0:
        return ["reconciliation target worktree status cannot be verified"]
    allowed = {
        (root / INVENTORY.relative_to(ROOT)).resolve(),
        (root / REPORT.relative_to(ROOT)).resolve(),
    }
    changed: list[str] = []
    def is_reconciliation_source(raw: str) -> bool:
        relative = Path(raw)
        if relative.as_posix() == "scripts/audit_operational_configuration.py":
            return True
        return surface(relative) is not None and (
            relative.suffix in SOURCE_SUFFIXES or relative.name.startswith("Dockerfile"))

    committed_changes = subprocess.run(
        ["git", "diff", "--name-only", f"{target_revision}..{head.stdout.strip()}"],
        cwd=root, capture_output=True, text=True,
    )
    if committed_changes.returncode != 0:
        return ["reconciliation target commit range cannot be verified"]
    for raw in committed_changes.stdout.splitlines():
        if (root / raw).resolve() not in allowed and is_reconciliation_source(raw):
            changed.append(raw)
    for row in status.stdout.splitlines():
        raw = row[3:]
        if " -> " in raw:
            raw = raw.split(" -> ", 1)[1]
        path = (root / raw).resolve()
        if path not in allowed and is_reconciliation_source(raw):
            changed.append(raw)
    return (["reconciliation target has uncommitted source changes: " + ", ".join(changed[:5])]
            if changed else [])


def candidate_semantic_payload(entry: dict[str, object]) -> dict[str, object]:
    """Return reviewed metadata that an identity-only migration must preserve exactly."""
    return {key: value for key, value in entry.items()
            if key not in SOURCE_METADATA_FIELDS and key not in {"retirement", "identityMigration"}}


def catalog_property_key(source: str | None, line: object) -> str | None:
    if source is None or not isinstance(line, int):
        return None
    lines = source.splitlines()
    if line < 1 or line > len(lines):
        return None
    matched = re.match(r"\s*(['\"])([^'\"]+)\1\s*:", lines[line - 1])
    return matched.group(2) if matched is not None else None


def candidate_reference_locations(value: object, identifiers: set[str],
                                  path: tuple[str, ...] = ()) -> list[tuple[str, ...]]:
    found: list[tuple[str, ...]] = []
    if isinstance(value, dict):
        for key, child in value.items():
            found.extend(candidate_reference_locations(child, identifiers, path + (str(key),)))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            found.extend(candidate_reference_locations(child, identifiers, path + (str(index),)))
    elif isinstance(value, str) and value in identifiers:
        found.append(path)
    return found


def allowed_migrated_reference(path: tuple[str, ...]) -> bool:
    """Allow only the candidate-ID fields declared by schema v5."""
    if len(path) == 3 and path[0] == "entries" and path[2] == "id":
        return path[1].isdigit()
    if len(path) == 4 and path[0] == "entries" and path[1].isdigit() \
            and path[2] == "defaultEvidence":
        return path[3].isdigit()
    if len(path) == 4 and path[0] == "entries" and path[1].isdigit() \
            and path[2] == "schemaEvidence" and path[3] == "candidateId":
        return True
    if len(path) == 4 and path[0] == "entries" and path[1].isdigit() \
            and path[2] == "bindingAuthority" \
            and path[3] in {"environmentCandidateId", "propertyCandidateId"}:
        return True
    if len(path) == 5 and path[0] == "entries" and path[1].isdigit() \
            and path[2] == "bindingAuthority" \
            and path[3] in {"sourceEnvironmentCandidateIds", "declarationCandidateIds"}:
        return path[4].isdigit()
    if len(path) == 5 and path[0] == "entries" and path[1].isdigit() \
            and path[2] == "defaultAuthority" and path[3] == "candidateIds":
        return path[4].isdigit()
    if len(path) == 8 and path[0] == "entries" and path[1].isdigit() \
            and path[2:5] == ("defaultAuthority", "constantReferenceAuthority", "hops") \
            and path[5].isdigit() and path[6] == "candidateIds":
        return path[7].isdigit()
    if len(path) == 5 and path[0] == "entries" and path[1].isdigit() \
            and path[2] == "coverageEvidence" \
            and path[3] in {
                "composeCandidateIds", "helmValueCandidateIds", "helmTemplateCandidateIds",
                "helmSchemaEnvironmentCandidateIds", "helmSchemaReferenceCandidateIds",
                "rawKubernetesCandidateIds",
            }:
        return path[4].isdigit()
    if len(path) == 6 and path[0] == "entries" and path[1].isdigit() \
            and path[2:4] == ("carrierEvidence", "expectedCandidateIds") \
            and path[4] in {"compose", "deploymentExamples", "helm", "rawKubernetes"}:
        return path[5].isdigit()
    if len(path) == 5 and path[0] == "routeTableAuthorities" \
            and path[2] == "candidateIdsByRole":
        return path[4].isdigit()
    if len(path) == 7 and path[0] == "routeTableAuthorities" \
            and path[2] == "descriptorCandidateIds" and path[3].isdigit() \
            and path[4] == "candidateIds":
        return path[6].isdigit()
    if len(path) == 6 and path[0] == "graphLimitAuthorities" \
            and path[2] == "settings" and path[3].isdigit() \
            and path[4] == "defaultEvidence":
        return path[5].isdigit()
    if len(path) == 5 and path[0] == "graphLimitAuthorities" \
            and path[2] == "settings" and path[3].isdigit() \
            and path[4] == "environmentCandidateId":
        return True
    if len(path) == 7 and path[0] == "assistantLimitAuthorities" \
            and path[2] == "settings" and path[3].isdigit() \
            and path[4] == "defaultAuthority" and path[5] == "candidateIds":
        return path[6].isdigit()
    if len(path) == 6 and path[0] == "assistantLimitAuthorities" \
            and path[2] == "settings" and path[3].isdigit() \
            and path[4] == "bindingAuthority" \
            and path[5] == "environmentCandidateId":
        return True
    if len(path) == 7 and path[0] == "assistantLimitAuthorities" \
            and path[2] == "settings" and path[3].isdigit() \
            and path[4] == "bindingAuthority" and path[5] == "declarationCandidateIds":
        return path[6].isdigit()
    if len(path) == 7 and path[0] == "assistantLimitAuthorities" \
            and path[2] == "carrierEvidence" \
            and path[4] == "expectedCandidateIds" \
            and path[5] in {"compose", "deploymentExamples", "helm", "rawKubernetes"}:
        return path[6].isdigit()
    if len(path) == 4 and path[0] == "helmAuthorities" \
            and path[2] == "candidateIds":
        return path[3].isdigit()
    if len(path) == 6 and path[0] == "helmAuthorities" \
            and path[2] == "contracts" and path[3].isdigit() \
            and path[4] == "candidateIds":
        return path[5].isdigit()
    if len(path) == 4 and path[0] == "persistencePolicyAuthorities" \
            and path[2] == "candidateIds":
        return path[3].isdigit()
    if len(path) == 6 and path[0] == "persistencePolicyAuthorities" \
            and path[2] == "contracts" and path[3].isdigit() \
            and path[4] in {"candidateIds", "defaultCandidateIds"}:
        return path[5].isdigit()
    if len(path) == 5 and path[0] == "remediationDomains" \
            and path[1] == "domains" and path[2].isdigit() \
            and path[3] == "candidateIds":
        return path[4].isdigit()
    return False


def immutable_historical_reference(path: tuple[str, ...]) -> bool:
    """Recognize anchored historical candidate references that must never be rewritten."""
    return bool(path) and path[0] in {
        "reconciliationHistory", "semanticReviewHistory", "retiredEntries",
        "normalizedIdentityReappearanceHistory",
    }


def remap_declared_candidate_references(document: dict[str, object],
                                        replacements: dict[str, str]) -> None:
    """Remap only live candidate-ID fields explicitly declared by schema v5."""
    def remap_list(container: object, field: str) -> None:
        if isinstance(container, dict) and isinstance(container.get(field), list):
            container[field][:] = [replacements.get(value, value) for value in container[field]]

    entries = document.get("entries")
    if isinstance(entries, list):
        for entry in entries:
            if not isinstance(entry, dict):
                continue
            remap_list(entry, "defaultEvidence")
            binding = entry.get("bindingAuthority")
            if isinstance(binding, dict):
                for field in ("environmentCandidateId", "propertyCandidateId"):
                    if isinstance(binding.get(field), str):
                        binding[field] = replacements.get(binding[field], binding[field])
                for field in ("sourceEnvironmentCandidateIds", "declarationCandidateIds"):
                    remap_list(binding, field)
            default = entry.get("defaultAuthority")
            if isinstance(default, dict):
                remap_list(default, "candidateIds")
                chain = default.get("constantReferenceAuthority")
                hops = chain.get("hops") if isinstance(chain, dict) else None
                if isinstance(hops, list):
                    for hop in hops:
                        remap_list(hop, "candidateIds")
            schema = entry.get("schemaEvidence")
            if isinstance(schema, dict) and isinstance(schema.get("candidateId"), str):
                schema["candidateId"] = replacements.get(schema["candidateId"], schema["candidateId"])
            coverage = entry.get("coverageEvidence")
            for field in (
                    "composeCandidateIds", "helmValueCandidateIds", "helmTemplateCandidateIds",
                    "helmSchemaEnvironmentCandidateIds", "helmSchemaReferenceCandidateIds",
                    "rawKubernetesCandidateIds"):
                remap_list(coverage, field)
            carrier = entry.get("carrierEvidence")
            expected = carrier.get("expectedCandidateIds") if isinstance(carrier, dict) else None
            for field in ("compose", "deploymentExamples", "helm", "rawKubernetes"):
                remap_list(expected, field)

    authorities = document.get("routeTableAuthorities")
    if isinstance(authorities, dict):
        for authority in authorities.values():
            if not isinstance(authority, dict):
                continue
            by_role = authority.get("candidateIdsByRole")
            if isinstance(by_role, dict):
                for role in ("methods", "path", "summary", "successStatuses"):
                    remap_list(by_role, role)
            descriptors = authority.get("descriptorCandidateIds")
            if isinstance(descriptors, list):
                for descriptor in descriptors:
                    candidate_ids = descriptor.get("candidateIds") \
                        if isinstance(descriptor, dict) else None
                    if isinstance(candidate_ids, dict):
                        for role in ("methods", "path", "summary", "successStatuses"):
                            remap_list(candidate_ids, role)

    graph_authorities = document.get("graphLimitAuthorities")
    if isinstance(graph_authorities, dict):
        for authority in graph_authorities.values():
            settings = authority.get("settings") if isinstance(authority, dict) else None
            if not isinstance(settings, list):
                continue
            for setting in settings:
                remap_list(setting, "defaultEvidence")
                if isinstance(setting, dict) and isinstance(setting.get("environmentCandidateId"), str):
                    setting["environmentCandidateId"] = replacements.get(
                        setting["environmentCandidateId"], setting["environmentCandidateId"])

    assistant_authorities = document.get("assistantLimitAuthorities")
    if isinstance(assistant_authorities, dict):
        for authority in assistant_authorities.values():
            settings = authority.get("settings") if isinstance(authority, dict) else None
            if isinstance(settings, list):
                for setting in settings:
                    if not isinstance(setting, dict):
                        continue
                    binding = setting.get("bindingAuthority")
                    if isinstance(binding, dict):
                        if isinstance(binding.get("environmentCandidateId"), str):
                            binding["environmentCandidateId"] = replacements.get(
                                binding["environmentCandidateId"], binding["environmentCandidateId"])
                        remap_list(binding, "declarationCandidateIds")
                    remap_list(setting.get("defaultAuthority"), "candidateIds")
            carriers = authority.get("carrierEvidence") if isinstance(authority, dict) else None
            if isinstance(carriers, dict):
                for carrier in carriers.values():
                    expected = carrier.get("expectedCandidateIds") \
                        if isinstance(carrier, dict) else None
                    for field in ("compose", "deploymentExamples", "helm", "rawKubernetes"):
                        remap_list(expected, field)

    helm_authorities = document.get("helmAuthorities")
    if isinstance(helm_authorities, dict):
        for authority in helm_authorities.values():
            if not isinstance(authority, dict):
                continue
            remap_list(authority, "candidateIds")
            contracts = authority.get("contracts")
            if isinstance(contracts, list):
                for contract in contracts:
                    remap_list(contract, "candidateIds")

    persistence_authorities = document.get("persistencePolicyAuthorities")
    if isinstance(persistence_authorities, dict):
        for authority in persistence_authorities.values():
            if not isinstance(authority, dict):
                continue
            remap_list(authority, "candidateIds")
            contracts = authority.get("contracts")
            if isinstance(contracts, list):
                for contract in contracts:
                    remap_list(contract, "candidateIds")
                    remap_list(contract, "defaultCandidateIds")

    domains = document.get("remediationDomains")
    domain_rows = domains.get("domains") if isinstance(domains, dict) else None
    if isinstance(domain_rows, list):
        for domain in domain_rows:
            remap_list(domain, "candidateIds")


def reconciliation_plan_errors(root: Path, document: dict[str, object],
                               candidates: tuple[Candidate, ...],
                               plan: dict[str, object]) -> tuple[list[str], dict[str, dict[str, object]]]:
    """Validate one explicit old-to-current reconciliation before applying it."""
    errors: list[str] = []
    required = {
        "id", "issue", "sourceRevision", "targetRevision", "sourceInventoryPath",
        "sourceInventoryDigest", "targetCandidateDigest", "mappings", "retirements", "additions",
    }
    if set(plan) != required or not isinstance(plan.get("issue"), str) \
            or re.fullmatch(r"#[1-9][0-9]*", str(plan["issue"])) is None \
            or not isinstance(plan.get("id"), str) or not str(plan["id"]).strip():
        return ["reconciliation plan has an unsupported or incomplete shape"], {}
    source_revision = plan["sourceRevision"]
    target_revision = plan["targetRevision"]
    source_path = plan["sourceInventoryPath"]
    if not historical_source_locator_is_safe(source_revision, source_path) \
            or not isinstance(target_revision, str) \
            or re.fullmatch(r"[0-9a-f]{40}", target_revision) is None:
        return ["reconciliation plan has an unsafe source or target locator"], {}
    source_document, source_bytes = committed_json(root, str(source_revision), str(source_path))
    if source_document is None or source_bytes is None:
        return ["reconciliation source inventory is not a resolvable committed JSON object"], {}
    if hashlib.sha256(source_bytes).hexdigest() != plan["sourceInventoryDigest"]:
        errors.append("reconciliation source inventory digest has drifted")
    if not commit_exists(root, str(target_revision)) \
            or not revision_is_ancestor(root, str(source_revision), str(target_revision)):
        errors.append("reconciliation target revision is not a resolvable descendant")
    else:
        errors.extend(reconciliation_target_tree_errors(root, str(target_revision)))
    current = {candidate.id: candidate for candidate in candidates}
    if candidate_set_digest(current) != plan["targetCandidateDigest"]:
        errors.append("reconciliation target candidate set has drifted")
    source_entries_raw = source_document.get("entries", [])
    if not isinstance(source_entries_raw, list):
        return errors + ["reconciliation source inventory has no entries array"], {}
    source_entries = {str(entry.get("id")): entry for entry in source_entries_raw
                      if isinstance(entry, dict) and isinstance(entry.get("id"), str)}
    mappings = plan["mappings"]
    retirements = plan["retirements"]
    additions = plan["additions"]
    if not all(isinstance(value, list) for value in (mappings, retirements, additions)):
        return errors + ["reconciliation mappings, retirements, and additions must be arrays"], source_entries

    mapping_from: set[str] = set()
    mapping_to: set[str] = set()
    before_sources: dict[str, str | None] = {}
    after_sources: dict[str, str | None] = {}
    for mapping in mappings:
        if not isinstance(mapping, dict) or set(mapping) != {
                "fromId", "toId", "approved", "rationale", "equivalence"}:
            errors.append("identity migration has an unsupported or incomplete shape")
            continue
        before = mapping.get("fromId")
        after = mapping.get("toId")
        rationale = mapping.get("rationale")
        equivalence = mapping.get("equivalence")
        if mapping.get("approved") is not True or not isinstance(rationale, str) or not rationale.strip():
            errors.append(f"identity migration {before!r} requires row-level approval and rationale")
            continue
        if not isinstance(before, str) or not isinstance(after, str) \
                or before in mapping_from or after in mapping_to:
            errors.append(f"identity migration {before!r}->{after!r} is duplicate or invalid")
            continue
        mapping_from.add(before)
        mapping_to.add(after)
        old = source_entries.get(before)
        candidate = current.get(after)
        if old is None or candidate is None or not isinstance(equivalence, dict):
            errors.append(f"identity migration {before}->{after} has no source-backed endpoints")
            continue
        kind = equivalence.get("kind")
        old_atom = tuple(old.get(field) for field in ("path", "symbol", "kind", "role", "expression"))
        new_atom = (candidate.path, candidate.symbol, candidate.kind, candidate.role, candidate.expression)
        if kind == "same-atom-v1":
            if old_atom != new_atom \
                    or equivalence.get("beforeEvidenceDigest") != old.get("evidenceDigest") \
                    or equivalence.get("afterEvidenceDigest") != candidate.evidence_digest:
                errors.append(f"identity migration {before}->{after} is not the same source atom")
        elif kind == "catalog-property-v1":
            if old.get("path") != candidate.path or old.get("path") != \
                    "ravenroot/ravenroot-ui/src/ui-text.js" or old.get("kind") != candidate.kind:
                errors.append(f"identity migration {before}->{after} is not one catalog property")
                continue
            path = str(old["path"])
            before_sources.setdefault(path, committed_source(root, str(source_revision), path))
            after_sources.setdefault(path, committed_source(root, str(target_revision), path))
            old_key = catalog_property_key(before_sources[path], old.get("line"))
            new_key = catalog_property_key(after_sources[path], candidate.line)
            if old_key is None or old_key != new_key or equivalence.get("property") != old_key \
                    or equivalence.get("beforeExpressionDigest") != old.get("expressionDigest") \
                    or equivalence.get("afterExpressionDigest") != candidate.expression_digest:
                errors.append(f"identity migration {before}->{after} lacks exact catalog-key evidence")
        elif kind == "same-expression-location-v1":
            required_equivalence = {
                "kind", "path", "beforeLine", "afterLine",
                "beforeEvidenceDigest", "afterEvidenceDigest",
            }
            if set(equivalence) != required_equivalence \
                    or old.get("path") != candidate.path \
                    or old.get("expression") != candidate.expression \
                    or equivalence.get("path") != candidate.path \
                    or equivalence.get("beforeLine") != old.get("line") \
                    or equivalence.get("afterLine") != candidate.line \
                    or equivalence.get("beforeEvidenceDigest") != old.get("evidenceDigest") \
                    or equivalence.get("afterEvidenceDigest") != candidate.evidence_digest:
                errors.append(f"identity migration {before}->{after} lacks exact expression/location evidence")
        else:
            errors.append(f"identity migration {before}->{after} uses unsupported equivalence {kind!r}")

    retired_ids: set[str] = set()
    for retirement in retirements:
        identifier = retirement.get("id") if isinstance(retirement, dict) else None
        rationale = retirement.get("rationale") if isinstance(retirement, dict) else None
        if not isinstance(retirement, dict) or set(retirement) != {"id", "approved", "rationale"} \
                or retirement.get("approved") is not True or not isinstance(identifier, str) \
                or not isinstance(rationale, str) or not rationale.strip() or identifier in retired_ids:
            errors.append(f"retirement {identifier!r} requires unique row-level approval and rationale")
        else:
            retired_ids.add(identifier)

    addition_ids: set[str] = set()
    addition_metadata: dict[str, dict[str, object]] = {}
    for addition in additions:
        identifier = addition.get("id") if isinstance(addition, dict) else None
        metadata = addition.get("metadata") if isinstance(addition, dict) else None
        if not isinstance(addition, dict) or set(addition) != {"id", "metadata"} \
                or not isinstance(identifier, str) or identifier in addition_ids \
                or not isinstance(metadata, dict) or metadata.get("status") == "pending-review" \
                or metadata.get("classification") not in CLASSIFICATIONS:
            errors.append(f"new candidate {identifier!r} requires one supported semantic classification")
        else:
            addition_ids.add(identifier)
            addition_metadata[identifier] = metadata

    source_ids = set(source_entries)
    current_ids = set(current)
    unchanged = source_ids & current_ids
    missing = source_ids - current_ids
    new = current_ids - source_ids
    if mapping_from & retired_ids or mapping_to & addition_ids:
        errors.append("migrations and genuine retirements/additions are not disjoint")
    if mapping_from | retired_ids != missing or mapping_to | addition_ids != new:
        errors.append(
            "reconciliation does not exactly partition unchanged, migrated, retired, and added candidates")
    if mapping_from - missing or mapping_to - new:
        errors.append("reconciliation attempts an arbitrary remap outside absent/new candidates")
    if len(unchanged) + len(mapping_from) + len(retired_ids) != len(source_ids) \
            or len(unchanged) + len(mapping_to) + len(addition_ids) != len(current_ids):
        errors.append("reconciliation partition counts are inconsistent")
    return errors, source_entries


def apply_reconciliation(root: Path, document: dict[str, object], candidates: tuple[Candidate, ...],
                         plan: dict[str, object]) -> tuple[dict[str, object] | None, list[str]]:
    """Apply a fully approved reconciliation without rewriting undeclared JSON references."""
    errors, source_entries = reconciliation_plan_errors(root, document, candidates, plan)
    if errors:
        return None, errors
    replacements = {str(item["fromId"]): str(item["toId"])
                    for item in plan["mappings"] if isinstance(item, dict)}
    source_ids = set(source_entries)
    unexpected = [path for path in candidate_reference_locations(document, source_ids - {
        candidate.id for candidate in candidates})
                  if not allowed_migrated_reference(path)
                  and not immutable_historical_reference(path)]
    if unexpected:
        rendered = ", ".join("/".join(path) for path in unexpected[:5])
        return None, [f"candidate identity appears in an undeclared reference field: {rendered}"]

    current = {candidate.id: candidate for candidate in candidates}
    merged: list[dict[str, object]] = []
    for candidate in candidates:
        if candidate.id in source_entries:
            preserved = dict(source_entries[candidate.id])
        else:
            old_id = next((before for before, after in replacements.items()
                           if after == candidate.id), None)
            if old_id is not None:
                preserved = dict(source_entries[old_id])
                preserved["identityMigration"] = {"history": plan["id"], "fromId": old_id}
            else:
                addition = next(item for item in plan["additions"]
                                if isinstance(item, dict) and item.get("id") == candidate.id)
                preserved = {**candidate.source_fields(), **addition["metadata"]}
        preserved.pop("evidence", None)
        preserved.pop("retirement", None)
        preserved.update(candidate.source_fields())
        merged.append(preserved)

    refreshed = dict(document)
    refreshed["schemaVersion"] = SCHEMA_VERSION
    refreshed["reconciliationRequired"] = True
    refreshed["entries"] = merged
    remap_declared_candidate_references(refreshed, replacements)
    refreshed["routeTableAuthorities"] = {
        ROUTE_TABLE_AUTHORITY_ID: current_route_table_authority(root),
    }
    chart_present = helm_chart_present(root, candidates)
    if chart_present:
        helm_authority = helm_authority_from_source(root, candidates)
        if helm_authority is None:
            return None, ["cannot derive the closed Helm values authority from current source"]
        refreshed["helmAuthorities"] = {HELM_AUTHORITY_ID: helm_authority}
    if persistence_policy_source_present(root):
        persistence_authority = persistence_policy_authority_from_source(root, current)
        if persistence_authority is None:
            return None, ["cannot derive the closed persistence policy authority from current source"]
        refreshed["persistencePolicyAuthorities"] = {
            PERSISTENCE_POLICY_AUTHORITY_ID: persistence_authority,
        }
    history = list(refreshed.get("reconciliationHistory", []))
    history.append(plan)
    refreshed["reconciliationHistory"] = history
    refreshed.setdefault("semanticReviewHistory", [])
    refreshed["migrationHistory"] = expected_reconciled_migration_history(
        document, plan, source_entries)
    refreshed["retiredEntries"] = expected_reconciled_retired_entries(
        document, source_entries, plan)
    refreshed["evidenceRecords"] = {
        digest: evidence for digest, evidence in sorted({
            candidate.evidence_digest: candidate.evidence for candidate in candidates
        }.items())
    }
    return refreshed, []


def expected_reconciled_retired_entries(source_document: dict[str, object],
                                        source_entries: dict[str, dict[str, object]],
                                        plan: dict[str, object]) -> list[object]:
    """Rebuild the exact append-only retirement ledger for one reconciliation."""
    retired_history = list(source_document.get("retiredEntries", []))
    old_evidence = source_document.get("evidenceRecords", {})
    for approval in plan["retirements"]:
        archived = dict(source_entries[str(approval["id"])])
        archived.pop("retirement", None)
        archived["retirementRationale"] = str(approval["rationale"]).strip()
        if "evidence" not in archived and isinstance(old_evidence, dict):
            archived["evidence"] = old_evidence.get(str(archived.get("evidenceDigest")), "")
        retired_history.append(archived)
    return retired_history


def expected_reconciled_migration_history(source_document: dict[str, object],
                                          plan: dict[str, object],
                                          source_entries: dict[str, dict[str, object]]) -> list[object]:
    """Rebuild the exact append-only schema migration ledger for one reconciliation."""
    migration_history = list(source_document.get("migrationHistory", []))
    if source_document.get("schemaVersion") != SCHEMA_VERSION:
        migration_history.append({
            "fromSchema": source_document.get("schemaVersion"),
            "toSchema": SCHEMA_VERSION,
            "sourceRevision": plan["sourceRevision"],
            "sourcePath": plan["sourceInventoryPath"],
            "sourceFileDigest": plan["sourceInventoryDigest"],
            "candidateCount": len(source_entries),
            "statusCounts": dict(sorted(Counter(
                str(entry.get("status")) for entry in source_entries.values()).items())),
            "rationale": "Schema v5 records explicit source reconciliation, row-level identity migrations, and deterministic follow-up ownership.",
        })
    return migration_history


def reconciliation_history_errors(root: Path, document: dict[str, object],
                                  candidates: tuple[Candidate, ...]) -> list[str]:
    history = document.get("reconciliationHistory")
    if document.get("reconciliationRequired") is False and not history:
        return []
    if not isinstance(history, list) or not history:
        return ["schema v5 inventory requires reconciliationHistory"]
    identifiers: set[str] = set()
    for record in history:
        if not isinstance(record, dict) or not isinstance(record.get("id"), str) \
                or record["id"] in identifiers:
            return ["reconciliationHistory contains an invalid or duplicate record"]
        identifiers.add(str(record["id"]))
    plan = history[-1]
    errors, source_entries = reconciliation_plan_errors(root, document, candidates, plan)
    if errors:
        return errors
    active = {str(entry["id"]): entry for entry in document.get("entries", [])
              if isinstance(entry, dict) and isinstance(entry.get("id"), str)}
    retired = {str(entry["id"]): entry for entry in document.get("retiredEntries", [])
               if isinstance(entry, dict) and isinstance(entry.get("id"), str)}
    source_document, _source_bytes = committed_json(
        root, str(plan["sourceRevision"]), str(plan["sourceInventoryPath"]))
    source_history = source_document.get("reconciliationHistory", []) \
        if isinstance(source_document, dict) else []
    if source_history != history[:-1]:
        errors.append("reconciliation history is not an append-only chain from the committed source inventory")
    if isinstance(source_document, dict):
        source_reappearances = source_document.get("normalizedIdentityReappearanceHistory", [])
        current_reappearances = document.get("normalizedIdentityReappearanceHistory", [])
        if not isinstance(source_reappearances, list) \
                or not isinstance(current_reappearances, list) \
                or current_reappearances[:len(source_reappearances)] != source_reappearances:
            errors.append(
                "normalizedIdentityReappearanceHistory is not an append-only chain from the committed source inventory")
        expected_retired = expected_reconciled_retired_entries(
            source_document, source_entries, plan)
        if document.get("retiredEntries") != expected_retired:
            errors.append(
                "retiredEntries is not the exact anchored ledger plus approved reconciliation retirements")
        expected_migrations = expected_reconciled_migration_history(
            source_document, plan, source_entries)
        if document.get("migrationHistory") != expected_migrations:
            errors.append(
                "migrationHistory is not the exact anchored ledger plus the required schema migration")

    expected_metadata: dict[str, dict[str, object]] = {}
    reference_replacements = {
        str(mapping["fromId"]): str(mapping["toId"]) for mapping in plan["mappings"]
    }
    for mapping in plan["mappings"]:
        before = str(mapping["fromId"])
        after = str(mapping["toId"])
        target = active.get(after)
        payload_holder: dict[str, object] = {
            "entries": [candidate_semantic_payload(source_entries[before])],
        }
        remap_declared_candidate_references(payload_holder, reference_replacements)
        expected_metadata[after] = payload_holder["entries"][0]
        if target is not None and target.get("identityMigration") != \
                {"history": plan["id"], "fromId": before}:
            errors.append(f"identity migration {before}->{after} lacks its row-level history link")
    for approval in plan["retirements"]:
        identifier = str(approval["id"])
        archived = retired.get(identifier)
        if archived is None or archived.get("retirementRationale") != \
                str(approval["rationale"]).strip():
            errors.append(f"retirement {identifier} did not retain its approved rationale")
    for addition in plan["additions"]:
        identifier = str(addition["id"])
        expected_metadata[identifier] = dict(addition["metadata"])
    for identifier in set(source_entries) & set(active):
        expected_metadata[identifier] = candidate_semantic_payload(source_entries[identifier])

    review_history = document.get("semanticReviewHistory", [])
    if not isinstance(review_history, list):
        errors.append("semanticReviewHistory must be an array")
        review_history = []
    source_reviews = source_document.get("semanticReviewHistory", []) \
        if isinstance(source_document, dict) else []
    if not isinstance(source_reviews, list) or review_history[:len(source_reviews)] != source_reviews:
        errors.append(
            "semanticReviewHistory is not an append-only chain from the committed source inventory")
        source_reviews = []
    for review in review_history[len(source_reviews):]:
        required = {"candidateId", "approved", "rationale", "sourceRevision",
                    "beforeMetadata", "afterMetadata"}
        identifier = review.get("candidateId") if isinstance(review, dict) else None
        if not isinstance(review, dict) or set(review) != required or review.get("approved") is not True \
                or not isinstance(identifier, str) or identifier not in expected_metadata \
                or not isinstance(review.get("rationale"), str) or not str(review["rationale"]).strip() \
                or not isinstance(review.get("beforeMetadata"), dict) \
                or not isinstance(review.get("afterMetadata"), dict) \
                or not isinstance(review.get("sourceRevision"), str) \
                or re.fullmatch(r"[0-9a-f]{40}", str(review.get("sourceRevision"))) is None:
            errors.append(f"semantic review {identifier!r} has an unsupported or incomplete approval")
            continue
        committed, _raw = committed_json(root, str(review["sourceRevision"]),
                                         str(plan["sourceInventoryPath"]))
        committed_entry = next((item for item in committed.get("entries", [])
                                if isinstance(item, dict) and item.get("id") == identifier), None) \
            if isinstance(committed, dict) else None
        if expected_metadata[identifier] != review["beforeMetadata"] \
                or committed_entry is None \
                or candidate_semantic_payload(committed_entry) != review["beforeMetadata"]:
            errors.append(f"semantic review {identifier} is not anchored to its committed prior metadata")
            continue
        expected_metadata[identifier] = dict(review["afterMetadata"])
    for identifier, expected in expected_metadata.items():
        target = active.get(identifier)
        if target is None or candidate_semantic_payload(target) != expected:
            errors.append(f"candidate {identifier} has an unapproved semantic metadata change")
    return errors


REAPPEARANCE_KIND = "pending-candidate-normalized-identity-reappearance-v1"
REAPPEARANCE_FIELDS = {
    "kind", "issue", "candidateId", "approved", "rationale",
    "priorInventoryRevision", "priorInventoryPath", "priorInventoryDigest",
    "targetSourceRevision", "identityCheckpointRevision",
    "identityCheckpointInventoryDigest", "reconciliationId",
    "retiredPayloadDigest", "currentEvidenceDigest",
}
NORMALIZED_IDENTITY_FIELDS = (
    "path", "symbol", "kind", "role", "expression", "expressionDigest",
    "evidenceDigest", "surface",
)


def canonical_json_digest(value: object) -> str:
    return hashlib.sha256(json.dumps(
        value, sort_keys=True, separators=(",", ":"), ensure_ascii=False,
    ).encode("utf-8")).hexdigest()


def normalized_identity_reappearance_errors(
        root: Path, document: dict[str, object],
        candidates: tuple[Candidate, ...]) -> tuple[list[str], set[str]]:
    """Validate the closed, source-anchored exception for a normalized ID reappearing.

    A valid record explains identity reuse only. It neither rewrites the immutable retirement
    ledger nor transfers the retired row's unreviewed semantic state to the active candidate.
    """
    errors: list[str] = []
    raw_entries = document.get("entries", [])
    raw_retired = document.get("retiredEntries", [])
    active = {str(entry["id"]): entry for entry in raw_entries
              if isinstance(entry, dict) and isinstance(entry.get("id"), str)} \
        if isinstance(raw_entries, list) else {}
    retired_by_id: dict[str, list[dict[str, object]]] = defaultdict(list)
    if isinstance(raw_retired, list):
        for entry in raw_retired:
            if isinstance(entry, dict) and isinstance(entry.get("id"), str):
                retired_by_id[str(entry["id"])].append(entry)
    collisions = set(active) & set(retired_by_id)
    raw_history = document.get("normalizedIdentityReappearanceHistory", [])
    if not isinstance(raw_history, list):
        return ["normalizedIdentityReappearanceHistory must be an array"], set()

    records: dict[str, dict[str, object]] = {}
    for raw_record in raw_history:
        candidate_id = raw_record.get("candidateId") if isinstance(raw_record, dict) else None
        if not isinstance(raw_record, dict) or set(raw_record) != REAPPEARANCE_FIELDS \
                or not isinstance(candidate_id, str) or not candidate_id:
            errors.append("normalized identity reappearance has an unsupported or incomplete shape")
            continue
        if candidate_id in records:
            errors.append(f"duplicate normalized identity reappearance record: {candidate_id}")
            continue
        records[candidate_id] = raw_record
    missing = collisions - set(records)
    foreign = set(records) - collisions
    if missing:
        errors.append(
            "normalized identity reappearance history is missing active/retired collisions: "
            + ", ".join(sorted(missing)))
    if foreign:
        errors.append(
            "normalized identity reappearance history contains non-colliding candidates: "
            + ", ".join(sorted(foreign)))
    if errors:
        return errors, set()
    if not collisions:
        return [], set()

    inventory_path = INVENTORY.relative_to(ROOT).as_posix()
    discovered = {candidate.id: candidate for candidate in candidates}
    head_result = subprocess.run(["git", "rev-parse", "HEAD"], cwd=root,
                                 capture_output=True, text=True)
    checked_head = head_result.stdout.strip() if head_result.returncode == 0 else ""
    committed_cache: dict[tuple[str, str], tuple[dict[str, object] | None, bytes | None]] = {}

    def cached_json(revision: str, path: str) -> tuple[dict[str, object] | None, bytes | None]:
        key = (revision, path)
        if key not in committed_cache:
            committed_cache[key] = committed_json(root, revision, path)
        return committed_cache[key]

    def rows(value: dict[str, object] | None, field: str, identifier: str) \
            -> list[dict[str, object]]:
        raw = value.get(field, []) if isinstance(value, dict) else []
        return [entry for entry in raw
                if isinstance(entry, dict) and entry.get("id") == identifier] \
            if isinstance(raw, list) else []

    for identifier, record in records.items():
        prefix = f"normalized identity reappearance {identifier}"
        if record.get("kind") != REAPPEARANCE_KIND or record.get("approved") is not True \
                or not isinstance(record.get("issue"), str) \
                or re.fullmatch(r"#[1-9][0-9]*", str(record.get("issue"))) is None \
                or not isinstance(record.get("rationale"), str) \
                or not str(record.get("rationale")).strip():
            errors.append(f"{prefix} lacks its closed row-level approval")
            continue
        prior_revision = record.get("priorInventoryRevision")
        prior_path = record.get("priorInventoryPath")
        target_revision = record.get("targetSourceRevision")
        checkpoint_revision = record.get("identityCheckpointRevision")
        digests = (
            record.get("priorInventoryDigest"),
            record.get("identityCheckpointInventoryDigest"),
            record.get("retiredPayloadDigest"),
            record.get("currentEvidenceDigest"),
        )
        if prior_path != inventory_path \
                or not all(isinstance(value, str) and re.fullmatch(r"[0-9a-f]{40}", value)
                           for value in (prior_revision, target_revision, checkpoint_revision)) \
                or not all(isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value)
                           for value in digests):
            errors.append(f"{prefix} has an unsafe or malformed source anchor")
            continue
        assert isinstance(prior_revision, str) and isinstance(target_revision, str)
        assert isinstance(checkpoint_revision, str) and isinstance(prior_path, str)
        if not commit_exists(root, prior_revision) \
                or not commit_exists(root, target_revision) \
                or not commit_exists(root, checkpoint_revision) \
                or not revision_is_ancestor(root, prior_revision, target_revision) \
                or not revision_is_ancestor(root, target_revision, checkpoint_revision) \
                or not checked_head \
                or not revision_is_ancestor(root, checkpoint_revision, checked_head):
            errors.append(f"{prefix} revisions are not a resolvable ordered ancestry")
            continue
        prior_document, prior_bytes = cached_json(prior_revision, prior_path)
        checkpoint_document, checkpoint_bytes = cached_json(checkpoint_revision, prior_path)
        if prior_document is None or prior_bytes is None \
                or hashlib.sha256(prior_bytes).hexdigest() != record["priorInventoryDigest"]:
            errors.append(f"{prefix} prior inventory is absent or its digest has drifted")
            continue
        if checkpoint_document is None or checkpoint_bytes is None \
                or hashlib.sha256(checkpoint_bytes).hexdigest() \
                != record["identityCheckpointInventoryDigest"]:
            errors.append(f"{prefix} identity checkpoint is absent or its digest has drifted")
            continue

        prior_active = rows(prior_document, "entries", identifier)
        prior_retired = rows(prior_document, "retiredEntries", identifier)
        checkpoint_active = rows(checkpoint_document, "entries", identifier)
        checkpoint_retired = rows(checkpoint_document, "retiredEntries", identifier)
        current_retired = retired_by_id.get(identifier, [])
        if prior_active or len(prior_retired) != 1:
            errors.append(f"{prefix} was not absent from active prior inventory with one retirement")
            continue
        if len(checkpoint_active) != 1 or len(checkpoint_retired) != 1:
            errors.append(f"{prefix} is not an exact active/retired checkpoint collision")
            continue
        if len(current_retired) != 1 or current_retired[0] != prior_retired[0] \
                or checkpoint_retired[0] != prior_retired[0] \
                or canonical_json_digest(prior_retired[0]) != record["retiredPayloadDigest"]:
            errors.append(f"{prefix} immutable retired payload has drifted")
            continue

        retired = prior_retired[0]
        refresh = retired.get("sourceRefresh")
        expected_refresh_fields = {
            "kind", "beforeRevision", "afterRevision", "group",
            "semanticRetirement", "duplicateAuthorityCredit",
        }
        before = refresh.get("beforeRevision") if isinstance(refresh, dict) else None
        after = refresh.get("afterRevision") if isinstance(refresh, dict) else None
        eligible = retired.get("status") == "pending-review" \
            and retired.get("classification") is None \
            and "removal" not in retired and "retirement" not in retired \
            and isinstance(refresh, dict) and set(refresh) == expected_refresh_fields \
            and refresh.get("kind") == "pending-candidate-source-refresh-v1" \
            and refresh.get("semanticRetirement") is False \
            and isinstance(refresh.get("duplicateAuthorityCredit"), int) \
            and not isinstance(refresh.get("duplicateAuthorityCredit"), bool) \
            and refresh.get("duplicateAuthorityCredit") == 0 \
            and isinstance(refresh.get("group"), str) and bool(str(refresh.get("group")).strip()) \
            and isinstance(before, str) and isinstance(after, str) \
            and commit_exists(root, before) and commit_exists(root, after) \
            and revision_is_ancestor(root, before, after) \
            and revision_is_ancestor(root, after, prior_revision)
        if not eligible:
            errors.append(f"{prefix} retired row is not an eligible mechanical pending refresh")
            continue

        reconciliation_id = record.get("reconciliationId")
        checkpoint_history = checkpoint_document.get("reconciliationHistory", [])
        current_history = document.get("reconciliationHistory", [])
        checkpoint_plans = [plan for plan in checkpoint_history
                            if isinstance(plan, dict) and plan.get("id") == reconciliation_id] \
            if isinstance(checkpoint_history, list) else []
        current_plans = [plan for plan in current_history
                         if isinstance(plan, dict) and plan.get("id") == reconciliation_id] \
            if isinstance(current_history, list) else []
        if len(checkpoint_plans) != 1 or len(current_plans) != 1 \
                or current_plans[0] != checkpoint_plans[0]:
            errors.append(f"{prefix} identity reconciliation is absent or changed")
            continue
        plan = checkpoint_plans[0]
        additions = [item for item in plan.get("additions", [])
                     if isinstance(item, dict) and item.get("id") == identifier] \
            if isinstance(plan.get("additions"), list) else []
        mapping_ids = {str(item.get(field)) for item in plan.get("mappings", [])
                       if isinstance(item, dict) for field in ("fromId", "toId")} \
            if isinstance(plan.get("mappings"), list) else set()
        retirement_ids = {str(item.get("id")) for item in plan.get("retirements", [])
                          if isinstance(item, dict)} \
            if isinstance(plan.get("retirements"), list) else set()
        if plan.get("issue") != record["issue"] \
                or plan.get("sourceRevision") != prior_revision \
                or plan.get("sourceInventoryPath") != prior_path \
                or plan.get("sourceInventoryDigest") != record["priorInventoryDigest"] \
                or plan.get("targetRevision") != target_revision \
                or len(additions) != 1 or identifier in mapping_ids or identifier in retirement_ids:
            errors.append(f"{prefix} is not the exact approved checkpoint addition")
            continue
        checkpoint_metadata = candidate_semantic_payload(checkpoint_active[0])
        if checkpoint_metadata != additions[0].get("metadata") \
                or checkpoint_metadata.get("status") == "pending-review" \
                or checkpoint_metadata.get("classification") not in CLASSIFICATIONS \
                or not isinstance(checkpoint_metadata.get("rationale"), str) \
                or not str(checkpoint_metadata.get("rationale")).strip():
            errors.append(f"{prefix} checkpoint addition metadata does not match its active row")
            continue

        candidate = discovered.get(identifier)
        current_entry = active.get(identifier)
        if candidate is None or current_entry is None:
            errors.append(f"{prefix} current candidate is absent")
            continue
        if any(current_entry.get(key) != value for key, value in candidate.source_fields().items()):
            errors.append(f"{prefix} current source fields do not match discovery")
            continue
        normalized_rows = (retired, checkpoint_active[0], current_entry)
        if any(tuple(row.get(field) for field in NORMALIZED_IDENTITY_FIELDS)
               != tuple(normalized_rows[0].get(field) for field in NORMALIZED_IDENTITY_FIELDS)
               for row in normalized_rows[1:]) \
                or candidate.evidence_digest != record["currentEvidenceDigest"]:
            errors.append(f"{prefix} normalized source identity or evidence has drifted")

    return (errors, set(records)) if not errors else (errors, set())


REMEDIATION_DOMAIN_TITLES = {
    "#316": "resolved execution manifest",
    "#317": "Helm configuration contract",
    "#318": "deployment registry and store connections",
    "#319": "egress and external I/O",
    "#320": "program authoring and GitHub operations",
    "#321": "remaining baseline review and final closure",
}


def remediation_issue_for(entry: dict[str, object],
                          setting_owners: dict[str, str] | None = None) -> str:
    """Map only pending or unresolved operator rows to one ordered follow-up domain."""
    path = str(entry.get("path", "")).lower()
    symbol = str(entry.get("symbol", "")).lower()
    role = str(entry.get("role", "")).lower()
    setting = str(entry.get("setting", "")).lower()
    if setting_owners is not None and setting in setting_owners:
        return setting_owners[setting]
    joined = " ".join((path, symbol, role, setting))
    if path.startswith("deploy/helm/"):
        return "#317"
    if "manifest" in joined or any(token in joined for token in (
            "graphexecutionlimits", "graphmllimits", "payloadlimits")):
        return "#316"
    if "/persistence/" in path or any(token in joined for token in (
            "deploymentregistry", "storeconnection", "storeconfig", "executionstoreconfiguration")):
        return "#318"
    if any(token in joined for token in (
            "externalio", "egress", "httpclient", "websocket", "webhook", "network", "outbound")):
        return "#319"
    if any(token in joined for token in (
            "github", "authoring", "editor", "program", "app-commands", "graph-editor")):
        return "#320"
    return "#321"


def build_remediation_domains(entries: Iterable[dict[str, object]]) -> dict[str, object]:
    population = [entry for entry in entries if entry.get("status") == "pending-review" or (
        entry.get("classification") == "operator-configurable"
        and entry.get("status") in {"confirmed-hardcoded", "deferred"})]
    unresolved = [entry for entry in population
                  if entry.get("classification") == "operator-configurable"
                  and entry.get("status") in {"confirmed-hardcoded", "deferred"}
                  and isinstance(entry.get("setting"), str) and str(entry["setting"]).strip()]
    setting_owners: dict[str, str] = {}
    for setting in sorted({str(entry["setting"]) for entry in unresolved}):
        owners = {str(entry.get("followUp", "")) for entry in unresolved
                  if entry.get("setting") == setting}
        if len(owners) != 1 or next(iter(owners)) not in REMEDIATION_DOMAIN_TITLES:
            raise ValueError(f"unresolved setting {setting} requires one follow-up owner")
        setting_owners[setting] = next(iter(owners))
    domains: list[dict[str, object]] = []
    for issue, title in REMEDIATION_DOMAIN_TITLES.items():
        assigned = sorted((entry for entry in population
                           if remediation_issue_for(entry, setting_owners) == issue),
                          key=lambda item: str(item["id"]))
        domains.append({
            "issue": issue,
            "title": title,
            "candidateIds": [str(entry["id"]) for entry in assigned],
            "statusCounts": dict(sorted(Counter(str(entry["status"]) for entry in assigned).items())),
            "inheritedPendingReview": sum(entry.get("status") == "pending-review" for entry in assigned),
            "confirmedUnresolvedOperatorSettings": len({str(entry.get("setting")) for entry in assigned
                if entry.get("classification") == "operator-configurable"
                and entry.get("status") in {"confirmed-hardcoded", "deferred"}
                and entry.get("setting")}),
        })
    return {
        "kind": "pending-and-unresolved-operator-domain-map-v1",
        "candidateDigest": candidate_set_digest(str(entry["id"]) for entry in population),
        "settingOwners": [
            {"setting": setting, "issue": issue}
            for setting, issue in sorted(setting_owners.items())
        ],
        "domains": domains,
    }


def remediation_domain_errors(document: dict[str, object]) -> list[str]:
    if "remediationDomains" not in document:
        if document.get("reconciliationRequired") is False \
                and not document.get("reconciliationHistory"):
            return []
        return ["inventory requires a remediation domain map"]
    entries = [entry for entry in document.get("entries", []) if isinstance(entry, dict)]
    actual = document.get("remediationDomains")
    if not isinstance(actual, dict) or not isinstance(actual.get("settingOwners"), list):
        return ["remediation domain map requires setting-level ownership"]
    ownership_rows = actual["settingOwners"]
    settings: set[str] = set()
    for owner in ownership_rows:
        setting = owner.get("setting") if isinstance(owner, dict) else None
        issue = owner.get("issue") if isinstance(owner, dict) else None
        if not isinstance(owner, dict) or set(owner) != {"setting", "issue"} \
                or not isinstance(setting, str) or not setting.strip() \
                or issue not in REMEDIATION_DOMAIN_TITLES or setting in settings:
            return ["remediation domain map has invalid or duplicate setting ownership"]
        settings.add(setting)
    try:
        expected = build_remediation_domains(entries)
    except ValueError as invalid:
        return [str(invalid)]
    if actual != expected:
        return ["remediation domain map does not exactly partition the pending and unresolved operator population"]
    candidate_ids = [identifier for domain in expected["domains"]
                     for identifier in domain["candidateIds"]]
    if len(candidate_ids) != len(set(candidate_ids)):
        return ["remediation domain map assigns a candidate more than once"]
    return []


def conversion_evidence_errors(identifier: str, entry: dict[str, object],
                               conversion: dict[str, object], before_source: str | None,
                               after_source: str | None) -> list[str]:
    """Verify that a converted setting's declared binding transition occurred in executable source."""
    if before_source is None or after_source is None:
        return []
    binding = str(conversion["binding"])
    binding_symbol = str(conversion["bindingSymbol"])
    field = str(conversion["field"])
    before_expression = normalized(str(conversion["beforeExpression"]))
    after_expression = normalized(str(conversion["afterExpression"]))
    before_code = normalized(strip_c_comments_and_literals(before_source))
    after_code = normalized(strip_c_comments_and_literals(after_source))
    errors: list[str] = []
    bindings = entry.get("bindings", [])
    if not isinstance(bindings, list) or binding not in bindings:
        errors.append(f"{identifier}: conversion binding is not declared by the setting")
    if field not in before_expression or field not in after_expression:
        errors.append(f"{identifier}: conversion expressions must both identify the setting field")
    if binding_symbol not in after_expression:
        errors.append(f"{identifier}: conversion afterExpression must use bindingSymbol")
    if before_expression == after_expression:
        errors.append(f"{identifier}: conversion expressions must be distinct")
    if before_expression not in before_code or before_expression in after_code:
        errors.append(f"{identifier}: conversion beforeExpression does not identify the replaced source")
    if after_expression in before_code or after_expression not in after_code:
        errors.append(f"{identifier}: conversion afterExpression does not identify the added source")
    if re.search(rf"\b{re.escape(binding)}\b", strip_c_comments(before_source)):
        errors.append(f"{identifier}: conversion binding already exists in beforeRevision")
    if not re.search(rf"\b{re.escape(binding)}\b", strip_c_comments(after_source)):
        errors.append(f"{identifier}: conversion binding is absent from afterRevision")
    declaration = re.compile(rf"\b{re.escape(binding_symbol)}\b\s*=")
    executable_after = strip_c_comments_and_literals(after_source)
    declared_binding = any(
        re.match(rf"\s*\"{re.escape(binding)}\"", after_source[match.end():])
        for match in declaration.finditer(executable_after)
    )
    if not declared_binding:
        errors.append(f"{identifier}: conversion bindingSymbol does not declare the named binding")
    return errors


def manifest_pin_attempt_conversion_errors(root: Path, identifier: str,
                                           entry: dict[str, object],
                                           conversion: dict[str, object]) -> list[str]:
    """Verify removal of the private adapter limit and addition of the typed server authority."""
    required = {
        "kind", "issue", "beforeRevision", "afterRevision", "beforePath", "beforeSymbol",
        "beforeExpression", "afterPath", "afterOwner", "afterField", "afterExpression",
        "binding", "bindingSymbol",
    }
    if set(conversion) != required or conversion.get("kind") != \
            "java-manifest-pin-attempts-conversion-v1" or conversion.get("issue") != "#316" \
            or conversion.get("beforePath") != MANIFEST_PIN_STORE_PATH.as_posix() \
            or conversion.get("beforeSymbol") != "MAX_PIN_ATTEMPTS" \
            or conversion.get("afterPath") != MANIFEST_PIN_CONFIGURATION_PATH.as_posix() \
            or conversion.get("afterOwner") != \
            f"{MANIFEST_PIN_CONFIGURATION_PATH.as_posix()}#Shared" \
            or conversion.get("afterField") != "manifestPinAttempts" \
            or conversion.get("binding") != "RAVENROOT_EXECUTION_MANIFEST_PIN_ATTEMPTS" \
            or conversion.get("bindingSymbol") != "MANIFEST_PIN_ATTEMPTS_VARIABLE":
        return [f"{identifier}: manifest pin conversion has incomplete or unsupported provenance"]
    before_revision = str(conversion["beforeRevision"])
    after_revision = str(conversion["afterRevision"])
    if re.fullmatch(r"[0-9a-f]{40}", before_revision) is None \
            or re.fullmatch(r"[0-9a-f]{40}", after_revision) is None \
            or not commit_exists(root, before_revision) or not commit_exists(root, after_revision) \
            or not revision_is_ancestor(root, before_revision, after_revision):
        return [f"{identifier}: manifest pin conversion revisions do not form a resolvable transition"]
    before_store = committed_source(root, before_revision, MANIFEST_PIN_STORE_PATH.as_posix())
    after_store = committed_source(root, after_revision, MANIFEST_PIN_STORE_PATH.as_posix())
    before_configuration = committed_source(
        root, before_revision, MANIFEST_PIN_CONFIGURATION_PATH.as_posix())
    after_configuration = committed_source(
        root, after_revision, MANIFEST_PIN_CONFIGURATION_PATH.as_posix())
    if any(source is None for source in (
            before_store, after_store, before_configuration, after_configuration)):
        return [f"{identifier}: manifest pin conversion source is not resolvable"]
    before_expression = normalized(str(conversion["beforeExpression"]))
    after_expression = normalized(str(conversion["afterExpression"]))
    before_store_code = normalized(strip_c_comments(before_store))
    after_store_code = normalized(strip_c_comments(after_store))
    before_configuration_code = normalized(strip_c_comments(before_configuration))
    after_configuration_code = normalized(strip_c_comments(after_configuration))
    errors: list[str] = []
    if before_expression != "private static final int MAX_PIN_ATTEMPTS = 3;" \
            or before_store_code.count(before_expression) != 1 \
            or before_expression in after_store_code:
        errors.append(f"{identifier}: manifest pin conversion does not identify the removed adapter limit")
    expected_after = normalized(
        "positiveInt(environment, MANIFEST_PIN_ATTEMPTS_VARIABLE, DEFAULT_MANIFEST_PIN_ATTEMPTS)")
    if after_expression != expected_after or after_expression in before_configuration_code \
            or after_configuration_code.count(after_expression) != 1:
        errors.append(f"{identifier}: manifest pin conversion does not identify the added typed binding")
    binding = str(conversion["binding"])
    if re.search(rf"\b{re.escape(binding)}\b", strip_c_comments(before_configuration)) \
            or not re.search(rf"\b{re.escape(binding)}\b", strip_c_comments(after_configuration)):
        errors.append(f"{identifier}: manifest pin conversion binding transition has drifted")
    if entry.get("field") != "manifestPinAttempts" \
            or entry.get("bindings") != [binding]:
        errors.append(f"{identifier}: manifest pin conversion metadata disagrees with its authority")
    return errors


def environment_resolver_authority_errors(root: Path, identifier: str,
                                          authority: dict[str, object]) -> list[str]:
    required = {
        "kind", "path", "type", "factoryMethod", "factoryBodyDigest", "integerMethod",
        "integerBodyDigest", "dependencyBodyDigests", "validationBodyDigest",
        "validationDependencyBodyDigests", "testPath", "testType", "testMethods",
        "testMethodDigests",
    }
    if set(authority) != required:
        return [f"resolver authority {identifier} requires exactly {', '.join(sorted(required))}"]
    relative = Path(str(authority["path"]))
    type_symbol = str(authority["type"])
    if current_source_owner(root, f"{relative.as_posix()}#{type_symbol}") is None:
        return [f"resolver authority {identifier} has no tracked Java type"]
    source = (root / relative).read_text(encoding="utf-8")
    factory = str(authority["factoryMethod"])
    integer = str(authority["integerMethod"])
    errors: list[str] = []
    expected_factory = f"public static {type_symbol} {factory}(Map<String, String> environment)"
    expected_integer = (
        f"private static int {integer}(Map<String, String> environment, "
        "String name, int defaultValue)"
    )
    if java_method_header(source, type_symbol, factory) != expected_factory:
        errors.append(f"resolver authority {identifier} factory signature is unsupported")
    if java_method_header(source, type_symbol, integer) != expected_integer:
        errors.append(f"resolver authority {identifier} integer signature is unsupported")
    if java_method_digest(source, type_symbol, factory) != authority["factoryBodyDigest"]:
        errors.append(f"resolver authority {identifier} factory body digest has drifted")
    if java_method_digest(source, type_symbol, integer) != authority["integerBodyDigest"]:
        errors.append(f"resolver authority {identifier} integer body digest has drifted")
    dependencies = authority["dependencyBodyDigests"]
    reachable = java_reachable_helper_methods(source, type_symbol, (integer,))
    if not isinstance(dependencies, dict) or reachable != set() or set(dependencies) != reachable \
            or any(java_method_digest(source, type_symbol, method) != digest
                   for method, digest in dependencies.items()):
        errors.append(f"resolver authority {identifier} has incomplete integer helper dependencies")
    constructor = java_compact_constructor_span(source, type_symbol)
    if java_span_digest(source, constructor) != authority["validationBodyDigest"]:
        errors.append(f"resolver authority {identifier} compact constructor digest has drifted")
    validation_dependencies = authority["validationDependencyBodyDigests"]
    validation_reachable = java_reachable_helpers_from_span(source, type_symbol, constructor)
    if not isinstance(validation_dependencies, dict) or validation_reachable is None \
            or validation_reachable != {"positive", "burst"} \
            or set(validation_dependencies) != validation_reachable \
            or any(java_method_digest(source, type_symbol, method) != digest
                   for method, digest in validation_dependencies.items()):
        errors.append(f"resolver authority {identifier} has incomplete validation helper dependencies")
    declared_methods = java_declared_method_names(source, type_symbol)
    burst_calls = java_method_calls(source, type_symbol, "burst", declared_methods)
    if burst_calls is None or "positive" not in burst_calls:
        errors.append(f"resolver authority {identifier} burst validation no longer delegates to positive")

    test_relative = Path(str(authority["testPath"]))
    test_type = str(authority["testType"])
    if current_source_owner(root, f"{test_relative.as_posix()}#{test_type}") is None:
        errors.append(f"resolver authority {identifier} has no tracked Java test type")
    else:
        test_source = (root / test_relative).read_text(encoding="utf-8")
        methods = authority["testMethods"]
        digests = authority["testMethodDigests"]
        if not java_test_type_is_directly_runnable(test_source, test_type):
            errors.append(
                f"resolver authority {identifier} test type is not a supported runnable top-level class")
        if not java_has_exact_rate_test_imports(test_source, test_type):
            errors.append(
                f"resolver authority {identifier} test type does not bind the exact JUnit and Stream types")
        if not isinstance(methods, dict) or set(methods) != ENVIRONMENT_RESOLVER_TEST_ROLES \
                or any(not isinstance(method, str) or not method.strip()
                       for method in methods.values()) \
                or len(set(methods.values())) != len(ENVIRONMENT_RESOLVER_TEST_ROLES) \
                or not isinstance(digests, dict) or set(digests) != set(methods.values()) \
                or any(java_method_digest(test_source, test_type, method) != digests.get(method)
                       for method in methods.values()):
            errors.append(f"resolver authority {identifier} has missing rate-limit test evidence")
        else:
            parameterized = {"malformedOverflowRefusal", "nonPositiveRefusal"}
            ordinary = {
                "blankTypedDefault", "asciiTrimContract", "documentedBoundaryAcceptance",
                "relationalConstraintRefusal",
            }
            for role in parameterized:
                method = str(methods[role])
                if java_method_header(test_source, test_type, method) != f"void {method}(String name)":
                    errors.append(
                        f"resolver authority {identifier} test role {role} has unsupported signature")
                if java_method_annotations(test_source, test_type, method) != (
                        "@ParameterizedTest", f'@MethodSource("{methods["bindingEnumeration"]}")'):
                    errors.append(
                        f"resolver authority {identifier} test role {role} is not linked to its enumeration")
            for role in ordinary:
                method = str(methods[role])
                if java_method_header(test_source, test_type, method) != f"void {method}()":
                    errors.append(
                        f"resolver authority {identifier} test role {role} has unsupported signature")
                if java_method_annotations(test_source, test_type, method) != ("@Test",):
                    errors.append(
                        f"resolver authority {identifier} test role {role} is not a runnable @Test")
            blank_span = java_method_span(
                test_source, test_type, str(methods["blankTypedDefault"]),
            )
            blank_code = strip_c_comments_and_literals(
                test_source[slice(*blank_span)] if blank_span is not None else "",
            )
            if re.search(
                    rf'\b{re.escape(str(methods["bindingEnumeration"]))}\s*\(', blank_code,
            ) is None:
                errors.append(
                    f"resolver authority {identifier} blank-default test does not use its enumeration")
    return errors


def resolver_authority_errors(root: Path, authorities: object) -> list[str]:
    if not isinstance(authorities, dict):
        return ["property-bound settings require a resolverAuthorities object"]
    errors: list[str] = []
    required = ("path", "type", "integerMethod", "wholeMethod", "integerBodyDigest",
                "wholeBodyDigest", "dependencyBodyDigests", "testPath", "testType",
                "testMethods", "testMethodDigests", "compositionMethods",
                "compositionMethodDigests", "compositionLinks")
    for identifier, authority in authorities.items():
        if isinstance(authority, dict) and authority.get("kind") == "java-environment-integer-resolver-v1":
            errors.extend(environment_resolver_authority_errors(root, str(identifier), authority))
            continue
        if isinstance(authority, dict) and "kind" in authority:
            errors.append(f"resolver authority {identifier} has unsupported kind {authority['kind']}")
            continue
        if not isinstance(authority, dict) or any(key not in authority for key in required):
            errors.append(f"resolver authority {identifier} requires {', '.join(required)}")
            continue
        relative = Path(str(authority["path"]))
        source_path = root / relative
        if current_source_owner(root, f"{relative.as_posix()}#{authority['type']}") is None:
            errors.append(f"resolver authority {identifier} has no tracked Java type")
            continue
        source = source_path.read_text(encoding="utf-8")
        for key in ("integerMethod", "wholeMethod"):
            method = str(authority[key])
            digest = java_method_digest(source, str(authority["type"]), method)
            if digest != authority[f"{key.removesuffix('Method')}BodyDigest"]:
                errors.append(f"resolver authority {identifier} {method} body digest has drifted")
        integer_span = java_method_span(source, str(authority["type"]), str(authority["integerMethod"]))
        if integer_span is None or not re.search(
                rf"\b{re.escape(str(authority['wholeMethod']))}\s*\(",
                strip_c_comments_and_literals(source[slice(*integer_span)])):
            errors.append(f"resolver authority {identifier} integer helper does not delegate to whole")
        dependencies = authority["dependencyBodyDigests"]
        reachable = java_reachable_helper_methods(
            source, str(authority["type"]),
            (str(authority["integerMethod"]), str(authority["wholeMethod"])),
        )
        if not isinstance(dependencies, dict) or reachable is None or set(dependencies) != reachable \
                or any(java_method_digest(source, str(authority["type"]), method) != digest
                       for method, digest in dependencies.items()):
            errors.append(f"resolver authority {identifier} has incomplete or drifted helper dependencies")
        composition_methods = authority["compositionMethods"]
        composition_digests = authority["compositionMethodDigests"]
        composition_links = authority["compositionLinks"]
        if not isinstance(composition_methods, dict) \
                or set(composition_methods) != RESOLVER_COMPOSITION_METHOD_ROLES \
                or any(not isinstance(method, str) or not method.strip()
                       for method in composition_methods.values()) \
                or len(set(composition_methods.values())) != len(RESOLVER_COMPOSITION_METHOD_ROLES) \
                or not isinstance(composition_digests, dict) \
                or set(composition_digests) != set(composition_methods.values()) \
                or not isinstance(composition_links, dict) \
                or set(composition_links) != RESOLVER_COMPOSITION_LINK_ROLES:
            errors.append(f"resolver authority {identifier} has incomplete fallback-composition evidence")
        else:
            for method in composition_methods.values():
                if java_method_digest(source, str(authority["type"]), str(method)) \
                        != composition_digests.get(method):
                    errors.append(f"resolver authority {identifier} composition method {method} has drifted")
            for role, link in composition_links.items():
                if not isinstance(link, dict) or set(link) != {"method", "expression"} \
                        or link["method"] not in composition_methods.values() \
                        or not isinstance(link["expression"], str) or not link["expression"].strip():
                    errors.append(
                        f"resolver authority {identifier} fallback-composition link {role} is incomplete")
                    continue
                span = java_method_span(source, str(authority["type"]), str(link["method"]))
                code = normalized(strip_c_comments_and_literals(
                    source[slice(*span)] if span is not None else ""))
                if normalized(str(link["expression"])) not in code:
                    errors.append(
                        f"resolver authority {identifier} fallback-composition link {role} has drifted")
        test_relative = Path(str(authority["testPath"]))
        if current_source_owner(root, f"{test_relative.as_posix()}#{authority['testType']}") is None:
            errors.append(f"resolver authority {identifier} has no tracked Java test type")
        else:
            test_source = (root / test_relative).read_text(encoding="utf-8")
            methods = authority["testMethods"]
            digests = authority["testMethodDigests"]
            if not isinstance(methods, dict) or set(methods) != RESOLVER_TEST_ROLES \
                    or any(not isinstance(method, str) or not method for method in methods.values()) \
                    or not isinstance(digests, dict) or set(digests) != set(methods.values()) or any(
                    java_method_digest(test_source, str(authority["testType"]), method)
                    != digests.get(method) for method in methods.values()):
                errors.append(f"resolver authority {identifier} has missing precedence/refusal test evidence")
    return errors


def graph_default_constructor_arguments(source: str, type_symbol: str,
                                        components: tuple[str, ...]) \
        -> dict[str, tuple[str, int, int]] | None:
    """Return the exact component arguments of one direct static DEFAULTS constructor."""
    initializer = java_static_final_initializer(source, type_symbol, "DEFAULTS")
    if initializer is None:
        return None
    _expression, start, end = initializer
    actual = source[start:end]
    code = strip_c_comments_and_literals(source)[start:end]
    constructor = re.match(rf"\s*new\s+{re.escape(type_symbol)}\s*\(", code)
    if constructor is None:
        return None
    opening = code.find("(", constructor.start())
    parsed = split_java_arguments(actual, code, opening)
    if parsed is None or len(parsed[0]) != len(components) \
            or code[parsed[1] + 1:].strip():
        return None
    return {
        component: (argument, start + argument_start, start + argument_end)
        for component, (argument, argument_start, argument_end)
        in zip(components, parsed[0])
    }


def graph_record_semantics_match(source: str, type_symbol: str,
                                 components: tuple[str, ...], expected_constructor: str) -> bool:
    """Bind graph defaults to the accepted compact-constructor and implicit-accessor semantics."""
    compact = java_compact_constructor_span(source, type_symbol)
    if compact is None or normalized(strip_c_comments(source[slice(*compact)])) \
            != normalized(expected_constructor):
        return False
    return all(java_method_span(source, type_symbol, component) is None
               for component in components)


def graph_numeric_constant_initializer(source: str, type_symbol: str,
                                       field: str) -> tuple[str, int, int] | None:
    """Return one checker-supported direct int/long constant initializer."""
    if type_symbol != "GraphDefinitionStore":
        initializer = java_static_final_initializer(source, type_symbol, field)
        span = java_type_span(source, type_symbol)
        if initializer is None or span is None:
            return None
        base, limit = span
        code = strip_c_comments_and_literals(source)[base:limit]
        depths = java_brace_depths(code)
        declarations = [match for match in re.finditer(
            rf"\b(?:public|private)\s+static\s+final\s+(?:int|long)\s+"
            rf"{re.escape(field)}\s*=", code,
        ) if depths[match.start()] == 1]
        return initializer if len(declarations) == 1 else None

    span = java_type_span(source, type_symbol)
    if span is None:
        return None
    base, limit = span
    actual = source[base:limit]
    code = strip_c_comments_and_literals(source)[base:limit]
    depths = java_brace_depths(code)
    matches: list[tuple[str, int, int]] = []
    for declaration in re.finditer(rf"\bint\s+{re.escape(field)}\s*=", code):
        if depths[declaration.start()] != 1:
            continue
        equals = code.find("=", declaration.start(), declaration.end())
        semicolon = code.find(";", equals + 1)
        if semicolon < 0 or any(char in code[equals + 1:semicolon] for char in "{}"):
            continue
        start = base + equals + 1
        end = base + semicolon
        matches.append((normalized(source[start:end]), start, end))
    return matches[0] if len(matches) == 1 else None


def numeric_candidate_ids_in_source_span(relative: Path, source: str, start: int, end: int,
                                         discovered: dict[str, Candidate]) -> list[str]:
    """Return numeric atom IDs from one exact source span, preserving lexical multiplicity."""
    grouped: dict[tuple[str, str, str, str, str], list[str]] = {}
    for candidate in discovered.values():
        if candidate.path != relative.as_posix():
            continue
        key = (candidate.symbol, candidate.kind, candidate.role, candidate.expression,
               candidate.evidence_digest)
        grouped.setdefault(key, []).append(candidate.id)
    occurrences: Counter[tuple[str, str, str, str, str]] = Counter()
    selected: list[str] = []
    for offset, symbol_name, candidate_kind, candidate_role, expression, evidence in code_candidates(
            relative, source, surface(relative) or "java"):
        evidence_digest = hashlib.sha256(evidence.encode("utf-8")).hexdigest()
        key = (symbol_name, candidate_kind, candidate_role, expression, evidence_digest)
        occurrence = occurrences[key]
        occurrences[key] += 1
        identifiers = grouped.get(key, [])
        if start <= offset < end and NUMBER.fullmatch(expression) and occurrence < len(identifiers):
            selected.append(identifiers[occurrence])
    return selected


def graph_numeric_value_and_evidence(
        expression: str, current_owner: str, sources: dict[str, tuple[Path, str]],
        span: tuple[Path, str, int, int] | None, discovered: dict[str, Candidate],
        active: set[str] | None = None, require_evidence: bool = True) -> tuple[int, list[str]] | None:
    """Evaluate the closed graph integer grammar and retain terminal numeric atom IDs."""
    active = set() if active is None else active
    direct_ids = (numeric_candidate_ids_in_source_span(*span, discovered)
                  if span is not None else [])
    referenced_ids: list[str] = []

    def resolve(token: str) -> int | None:
        qualified = token if "." in token else f"{current_owner}.{token}"
        expected = GRAPH_NUMERIC_CONSTANT_EXPRESSIONS.get(qualified)
        if expected is None or qualified in active:
            return None
        owner, field = qualified.split(".", 1)
        source_info = sources.get(owner)
        if source_info is None:
            return None
        relative, source = source_info
        initializer = graph_numeric_constant_initializer(source, owner, field)
        if initializer is None or normalized(initializer[0]) != normalized(expected):
            return None
        active.add(qualified)
        resolved = graph_numeric_value_and_evidence(
            initializer[0], owner, sources,
            (relative, source, initializer[1], initializer[2]), discovered, active,
            require_evidence)
        active.remove(qualified)
        if resolved is None:
            return None
        value, identifiers = resolved
        referenced_ids.extend(identifiers)
        return value

    # Every supported long literal is within int32; the suffix changes Java type, not its value.
    integer_expression = re.sub(r"(?<=\d)[lL]\b", "", expression)
    value = java_int_expression_value(integer_expression, resolve)
    if value is None:
        return None
    identifiers = direct_ids + referenced_ids
    return (value, identifiers) if identifiers or not require_evidence else None


def graph_limit_family_from_source(root: Path,
                                   discovered: dict[str, Candidate]) -> dict[str, object] | None:
    """Derive the closed 25-setting GraphExecutionLimits binding family from source."""
    if set(GRAPH_LIMIT_AUTHORITY_BY_SETTING) != set(GRAPH_LIMIT_SOURCE_BY_SETTING) \
            or len(GRAPH_LIMIT_SOURCE_CONTRACTS) != 25:
        return None
    source = (root / GRAPH_EXECUTION_LIMITS_PATH).read_text(encoding="utf-8")
    graph_ml_source = (root / GRAPH_ML_LIMITS_PATH).read_text(encoding="utf-8")
    payload_source = (root / PAYLOAD_LIMITS_PATH).read_text(encoding="utf-8")
    graph_store_source = (root / GRAPH_DEFINITION_STORE_PATH).read_text(encoding="utf-8")
    root_components = java_record_components(source, "GraphExecutionLimits")
    graph_ml_components = java_record_components(graph_ml_source, "GraphMlLimits")
    payload_components = java_record_components(payload_source, "PayloadLimits")
    if root_components != (
            "graphMl", "payload", "maxFanOut", "maxResidentActors",
            "maxLiveActorsPerTraversal", "maxInFlightHopsPerTraversal",
            "maxQueuedAdmissionsPerNode", "maxTraversalSteps", "maxAmplifiedDeliveries",
            "maxCumulativePayloadBytes", "maxRecoveryDeliveriesPerAttempt") \
            or graph_ml_components != (
                "maxBytes", "maxNodes", "maxEdges", "maxProperties", "maxDepth",
                "maxStringLength", "maxKeys", "maxElements", "maxAttributes",
                "maxNamespaceDeclarations") \
            or payload_components != (
                "maxEncodedBytes", "maxDepth", "maxCollectionSize", "maxValueCount",
                "maxTextLength", "maxKeyLength"):
        return None
    if java_package(graph_ml_source) != "ai.ravenroot.core.graph" \
            or java_package(payload_source) != "ai.ravenroot.api.payload" \
            or java_package(graph_store_source) != "ai.ravenroot.api.persistence" \
            or not java_exact_top_level_type_header(
                graph_store_source, "GraphDefinitionStore",
                "public interface GraphDefinitionStore extends AutoCloseable") \
            or any(imported.rsplit(".", 1)[-1] == "AutoCloseable" for imported in re.findall(
                r"(?m)^\s*import\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+)\s*;",
                strip_c_comments_and_literals(graph_store_source))) \
            or not java_has_no_simple_name_shadow(
                graph_store_source, "GraphDefinitionStore", {"AutoCloseable"}) \
            or not exact_import_identity(
                graph_ml_source, "ai.ravenroot.api.persistence.GraphDefinitionStore") \
            or not java_has_no_simple_name_shadow(
                graph_ml_source, "GraphMlLimits", {"GraphDefinitionStore"}):
        return None
    if any(not graph_record_semantics_match(record_source, type_symbol, components, constructor)
           for record_source, type_symbol, components, constructor in (
        (graph_ml_source, "GraphMlLimits", graph_ml_components, """
            GraphMlLimits {
                if (maxBytes < 1 || maxNodes < 1 || maxEdges < 1 || maxProperties < 1
                        || maxDepth < 1 || maxStringLength < 1 || maxKeys < 1
                        || maxElements < 1 || maxAttributes < 1 || maxNamespaceDeclarations < 1) {
                    throw new IllegalArgumentException("GraphML limits must all be positive");
                }
                if (maxBytes > GraphDefinitionStore.HARD_MAX_DEFINITION_BYTES
                        || maxNodes > HARD_MAX_NODES || maxEdges > HARD_MAX_EDGES
                        || maxProperties > HARD_MAX_PROPERTIES || maxDepth > HARD_MAX_DEPTH
                        || maxStringLength > HARD_MAX_STRING_LENGTH || maxKeys > HARD_MAX_KEYS
                        || maxElements > HARD_MAX_ELEMENTS || maxAttributes > HARD_MAX_ATTRIBUTES
                        || maxNamespaceDeclarations > HARD_MAX_NAMESPACE_DECLARATIONS) {
                    throw new IllegalArgumentException("GraphML limits exceed the supported safety ceiling");
                }
            }
        """),
        (payload_source, "PayloadLimits", payload_components, """
            PayloadLimits {
                if (maxEncodedBytes < 1 || maxDepth < 1 || maxCollectionSize < 1 || maxValueCount < 1
                        || maxTextLength < 1 || maxKeyLength < 1) {
                    throw new IllegalArgumentException("payload limits must all be positive");
                }
                if (maxEncodedBytes > HARD_MAX_ENCODED_BYTES || maxDepth > HARD_MAX_DEPTH
                        || maxCollectionSize > HARD_MAX_COLLECTION_SIZE
                        || maxValueCount > HARD_MAX_VALUE_COUNT || maxTextLength > HARD_MAX_TEXT_LENGTH
                        || maxKeyLength > HARD_MAX_KEY_LENGTH) {
                    throw new IllegalArgumentException("payload limits exceed the supported safety ceiling");
                }
            }
        """),
        (source, "GraphExecutionLimits", root_components, """
            GraphExecutionLimits {
                Objects.requireNonNull(graphMl, "graphMl");
                Objects.requireNonNull(payload, "payload");
                positiveWithin("maxFanOut", maxFanOut, HARD_MAX_FAN_OUT);
                positiveWithin("maxResidentActors", maxResidentActors, HARD_MAX_RESIDENT_ACTORS);
                positiveWithin("maxLiveActorsPerTraversal", maxLiveActorsPerTraversal, HARD_MAX_LIVE_ACTORS);
                positiveWithin("maxInFlightHopsPerTraversal", maxInFlightHopsPerTraversal,
                        HARD_MAX_IN_FLIGHT_HOPS);
                positiveWithin("maxQueuedAdmissionsPerNode", maxQueuedAdmissionsPerNode,
                        HARD_MAX_QUEUED_ADMISSIONS);
                positiveWithin("maxTraversalSteps", maxTraversalSteps, HARD_MAX_TRAVERSAL_STEPS);
                positiveWithin("maxAmplifiedDeliveries", maxAmplifiedDeliveries, HARD_MAX_AMPLIFIED_DELIVERIES);
                positiveWithin("maxCumulativePayloadBytes", maxCumulativePayloadBytes,
                        HARD_MAX_CUMULATIVE_PAYLOAD_BYTES);
                positiveWithin("maxRecoveryDeliveriesPerAttempt", maxRecoveryDeliveriesPerAttempt,
                        HARD_MAX_RECOVERY_DELIVERIES);
            }
        """),
    )):
        return None
    for record_source, record_type in (
            (graph_ml_source, "GraphMlLimits"), (payload_source, "PayloadLimits")):
        imports = re.findall(
            r"(?m)^\s*import\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+)\s*;",
            strip_c_comments_and_literals(record_source),
        )
        if any(imported.rsplit(".", 1)[-1] == "IllegalArgumentException"
               for imported in imports) \
                or not java_has_no_simple_name_shadow(
                    record_source, record_type, {"IllegalArgumentException"}):
            return None
    if java_package(source) != "ai.ravenroot.core.runtime" \
            or java_method_header(source, "GraphExecutionLimits", "fromEnvironment") != \
            "public static GraphExecutionLimits fromEnvironment(Map<String, String> environment)" \
            or any(not exact_import_identity(source, imported) for imported in (
                "java.util.Map", "java.util.Objects", "ai.ravenroot.core.graph.GraphMlLimits",
                "ai.ravenroot.api.payload.PayloadLimits",
                "ai.ravenroot.api.persistence.GraphDefinitionStore",
            )) \
            or not java_has_no_simple_name_shadow(
                source, "GraphExecutionLimits",
                {"Map", "Objects", "GraphMlLimits", "PayloadLimits", "GraphDefinitionStore"}):
        return None
    java_lang_types = {"String", "Long", "NumberFormatException", "IllegalArgumentException"}
    normal_imports = re.findall(
        r"(?m)^\s*import\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+)\s*;",
        strip_c_comments_and_literals(source),
    )
    if any(imported.rsplit(".", 1)[-1] in java_lang_types for imported in normal_imports) \
            or not java_has_no_simple_name_shadow(
                source, "GraphExecutionLimits", java_lang_types):
        return None
    factory_span = java_method_span(source, "GraphExecutionLimits", "fromEnvironment")
    if factory_span is None:
        return None
    expected_factory = """
        fromEnvironment(Map<String, String> environment) {
            Objects.requireNonNull(environment, "environment");
            GraphExecutionLimits defaults = DEFAULTS;
            GraphMlLimits graphMl = defaults.graphMl;
            graphMl = new GraphMlLimits(
                    integer(environment, MAX_GRAPHML_BYTES_VARIABLE, graphMl.maxBytes(),
                            GraphDefinitionStore.HARD_MAX_DEFINITION_BYTES),
                    integer(environment, MAX_NODES_VARIABLE, graphMl.maxNodes(), GraphMlLimits.HARD_MAX_NODES),
                    integer(environment, MAX_EDGES_VARIABLE, graphMl.maxEdges(), GraphMlLimits.HARD_MAX_EDGES),
                    integer(environment, MAX_PROPERTIES_VARIABLE, graphMl.maxProperties(),
                            GraphMlLimits.HARD_MAX_PROPERTIES),
                    integer(environment, MAX_GRAPHML_DEPTH_VARIABLE, graphMl.maxDepth(), GraphMlLimits.HARD_MAX_DEPTH),
                    integer(environment, MAX_GRAPHML_STRING_LENGTH_VARIABLE, graphMl.maxStringLength(),
                            GraphMlLimits.HARD_MAX_STRING_LENGTH),
                    integer(environment, MAX_GRAPHML_KEYS_VARIABLE, graphMl.maxKeys(), GraphMlLimits.HARD_MAX_KEYS),
                    integer(environment, MAX_GRAPHML_ELEMENTS_VARIABLE, graphMl.maxElements(),
                            GraphMlLimits.HARD_MAX_ELEMENTS),
                    integer(environment, MAX_GRAPHML_ATTRIBUTES_VARIABLE, graphMl.maxAttributes(),
                            GraphMlLimits.HARD_MAX_ATTRIBUTES),
                    integer(environment, MAX_GRAPHML_NAMESPACE_DECLARATIONS_VARIABLE,
                            graphMl.maxNamespaceDeclarations(), GraphMlLimits.HARD_MAX_NAMESPACE_DECLARATIONS));
            PayloadLimits payload = defaults.payload;
            payload = new PayloadLimits(
                    integer(environment, MAX_PAYLOAD_BYTES_VARIABLE, payload.maxEncodedBytes(),
                            PayloadLimits.HARD_MAX_ENCODED_BYTES),
                    integer(environment, MAX_PAYLOAD_DEPTH_VARIABLE, payload.maxDepth(), PayloadLimits.HARD_MAX_DEPTH),
                    integer(environment, MAX_PAYLOAD_COLLECTION_SIZE_VARIABLE, payload.maxCollectionSize(),
                            PayloadLimits.HARD_MAX_COLLECTION_SIZE),
                    integer(environment, MAX_PAYLOAD_VALUE_COUNT_VARIABLE, payload.maxValueCount(),
                            PayloadLimits.HARD_MAX_VALUE_COUNT),
                    integer(environment, MAX_PAYLOAD_TEXT_LENGTH_VARIABLE, payload.maxTextLength(),
                            PayloadLimits.HARD_MAX_TEXT_LENGTH),
                    integer(environment, MAX_PAYLOAD_KEY_LENGTH_VARIABLE, payload.maxKeyLength(),
                            PayloadLimits.HARD_MAX_KEY_LENGTH));
            return new GraphExecutionLimits(graphMl, payload,
                    integer(environment, MAX_FAN_OUT_VARIABLE, defaults.maxFanOut, HARD_MAX_FAN_OUT),
                    integer(environment, MAX_RESIDENT_ACTORS_VARIABLE, defaults.maxResidentActors,
                            HARD_MAX_RESIDENT_ACTORS),
                    integer(environment, MAX_LIVE_ACTORS_VARIABLE, defaults.maxLiveActorsPerTraversal,
                            HARD_MAX_LIVE_ACTORS),
                    integer(environment, MAX_IN_FLIGHT_HOPS_VARIABLE, defaults.maxInFlightHopsPerTraversal,
                            HARD_MAX_IN_FLIGHT_HOPS),
                    integer(environment, MAX_QUEUED_ADMISSIONS_VARIABLE, defaults.maxQueuedAdmissionsPerNode,
                            HARD_MAX_QUEUED_ADMISSIONS),
                    longInteger(environment, MAX_TRAVERSAL_STEPS_VARIABLE, defaults.maxTraversalSteps,
                            HARD_MAX_TRAVERSAL_STEPS),
                    longInteger(environment, MAX_AMPLIFIED_DELIVERIES_VARIABLE, defaults.maxAmplifiedDeliveries,
                            HARD_MAX_AMPLIFIED_DELIVERIES),
                    longInteger(environment, MAX_CUMULATIVE_PAYLOAD_BYTES_VARIABLE,
                            defaults.maxCumulativePayloadBytes, HARD_MAX_CUMULATIVE_PAYLOAD_BYTES),
                    integer(environment, MAX_RECOVERY_DELIVERIES_VARIABLE,
                            defaults.maxRecoveryDeliveriesPerAttempt, HARD_MAX_RECOVERY_DELIVERIES));
        }
    """
    if normalized(strip_c_comments(source[slice(*factory_span)])) != normalized(expected_factory):
        return None
    factory_code = normalized(strip_c_comments_and_literals(source[slice(*factory_span)]))
    required_composition = (
        "Objects.requireNonNull(environment,              );",
        "GraphExecutionLimits defaults = DEFAULTS;",
        "GraphMlLimits graphMl = defaults.graphMl;",
        "PayloadLimits payload = defaults.payload;",
    )
    # The string masker removes the requireNonNull label, but retains the structural call.
    if any(normalized(value) not in factory_code for value in required_composition) \
            or java_identifier_write_count(factory_code, "environment") != 0 \
            or java_identifier_write_count(factory_code, "defaults") != 1 \
            or java_identifier_write_count(factory_code, "graphMl") != 2 \
            or java_identifier_write_count(factory_code, "payload") != 2:
        return None
    root_graph = java_constructor_component_call(
        source, "GraphExecutionLimits", "fromEnvironment", "GraphExecutionLimits",
        root_components, "graphMl")
    root_payload = java_constructor_component_call(
        source, "GraphExecutionLimits", "fromEnvironment", "GraphExecutionLimits",
        root_components, "payload")
    if root_graph is None or root_graph[0] != "graphMl" \
            or root_payload is None or root_payload[0] != "payload":
        return None

    exact_helpers = {
        "integer": (
            "private static int integer(Map<String, String> environment, String name, int fallback, int ceiling)",
            "integer(Map<String, String> environment, String name, int fallback, int ceiling) { "
            "long value = longInteger(environment, name, fallback, ceiling); return (int) value; }",
        ),
        "longInteger": (
            "private static long longInteger(Map<String, String> environment, String name, long fallback, long ceiling)",
            "longInteger(Map<String, String> environment, String name, long fallback, long ceiling) { "
            "String raw = environment.get(name); if (raw == null || raw.isBlank()) return fallback; "
            "long value; try { value = Long.parseLong(raw.strip()); } catch (NumberFormatException invalid) "
            "{ throw invalid(name, ceiling); } if (value < 1 || value > ceiling) throw invalid(name, ceiling); "
            "return value; }",
        ),
        "invalid": (
            "private static IllegalArgumentException invalid(String name, long ceiling)",
            "invalid(String name, long ceiling) { return new IllegalArgumentException(name + "
            "\" must be a whole number from 1 through \" + ceiling); }",
        ),
        "positiveWithin": (
            "private static void positiveWithin(String name, long value, long ceiling)",
            "positiveWithin(String name, long value, long ceiling) { if (value < 1) "
            "throw new IllegalArgumentException(name + \" must be positive\"); if (value > ceiling) "
            "throw new IllegalArgumentException(name + \" exceeds the supported safety ceiling\"); }",
        ),
    }
    helper_digests: dict[str, str] = {}
    for method, (header, body) in exact_helpers.items():
        span = java_method_span(source, "GraphExecutionLimits", method)
        if java_method_header(source, "GraphExecutionLimits", method) != header \
                or span is None \
                or normalized(strip_c_comments(source[slice(*span)])) != normalized(body):
            return None
        digest = java_method_digest(source, "GraphExecutionLimits", method)
        if digest is None:
            return None
        helper_digests[method] = digest

    target_components = {
        "GraphMlLimits": graph_ml_components,
        "PayloadLimits": payload_components,
        "GraphExecutionLimits": root_components,
    }
    numeric_sources = {
        "GraphExecutionLimits": (GRAPH_EXECUTION_LIMITS_PATH, source),
        "GraphMlLimits": (GRAPH_ML_LIMITS_PATH, graph_ml_source),
        "PayloadLimits": (PAYLOAD_LIMITS_PATH, payload_source),
        "GraphDefinitionStore": (GRAPH_DEFINITION_STORE_PATH, graph_store_source),
    }
    default_arguments = {
        target: graph_default_constructor_arguments(numeric_sources[target][1], target, components)
        for target, components in target_components.items()
    }
    if any(arguments is None for arguments in default_arguments.values()):
        return None
    root_defaults = default_arguments["GraphExecutionLimits"]
    if root_defaults is None \
            or normalized(root_defaults["graphMl"][0]) != "GraphMlLimits.DEFAULTS" \
            or normalized(root_defaults["payload"][0]) != "PayloadLimits.DEFAULTS":
        return None
    settings: list[dict[str, object]] = []
    for setting, target, root_component, environment_symbol, helper, fallback, ceiling \
            in GRAPH_LIMIT_SOURCE_CONTRACTS:
        fixed = GRAPH_LIMIT_AUTHORITY_BY_SETTING[setting]
        field = str(fixed["field"])
        components = target_components[target]
        call = java_constructor_component_call(
            source, "GraphExecutionLimits", "fromEnvironment", target, components, field)
        if call is None:
            return None
        argument, start, end = call
        expected_argument = f"{helper}(environment, {environment_symbol}, {fallback}, {ceiling})"
        if normalized(argument) != normalized(expected_argument):
            return None
        default_contract = GRAPH_LIMIT_DEFAULT_CONTRACTS.get(setting)
        typed_defaults = default_arguments[target]
        if default_contract is None or typed_defaults is None or field not in typed_defaults:
            return None
        default_expression, default_start, default_end = typed_defaults[field]
        expected_default_expression, unit = default_contract
        if normalized(default_expression) != normalized(expected_default_expression):
            return None
        default_result = graph_numeric_value_and_evidence(
            default_expression, target, numeric_sources,
            (numeric_sources[target][0], numeric_sources[target][1], default_start, default_end),
            discovered,
        )
        ceiling_result = graph_numeric_value_and_evidence(
            ceiling, "GraphExecutionLimits", numeric_sources, None, discovered,
            require_evidence=False)
        if default_result is None or ceiling_result is None:
            return None
        default_value, default_ids = default_result
        ceiling_value, _ceiling_ids = ceiling_result
        initializer = java_static_final_initializer(
            source, "GraphExecutionLimits", environment_symbol)
        environment = str(fixed["environment"])
        if initializer is None or normalized(initializer[0]) != f'"{environment}"':
            return None
        type_span = java_type_span(source, "GraphExecutionLimits")
        type_code = strip_c_comments_and_literals(source)[slice(*type_span)] \
            if type_span is not None else ""
        depths = java_brace_depths(type_code)
        declarations = [match for match in re.finditer(
            rf"\bpublic\s+static\s+final\s+String\s+{re.escape(environment_symbol)}\s*=", type_code,
        ) if depths[match.start()] == 1]
        environment_ids = candidate_ids_in_source_span(
            GRAPH_EXECUTION_LIMITS_PATH, source, initializer[1], initializer[2],
            "environment-binding", environment, discovered)
        if len(declarations) != 1 or len(environment_ids) != 1 \
                or java_identifier_write_count(factory_code, environment_symbol) != 0:
            return None
        root_index = root_components.index(root_component)
        target_index = components.index(field)
        settings.append({
            "setting": setting, "typedOwner": fixed["typedOwner"], "field": field,
            "sourceOwner": f"{GRAPH_EXECUTION_LIMITS_PATH.as_posix()}#GraphExecutionLimits",
            "factoryMethod": "fromEnvironment", "rootComponent": root_component,
            "rootComponentIndex": root_index, "targetConstructor": target,
            "targetComponentIndex": target_index, "environmentSymbol": environment_symbol,
            "environment": environment, "environmentCandidateId": environment_ids[0],
            "helper": helper, "fallbackAccessor": fallback, "ceilingAccessor": ceiling,
            "callDigest": hashlib.sha256(argument.encode("utf-8")).hexdigest(),
            "defaultExpression": expected_default_expression,
            "defaultValue": default_value,
            "defaultDisplay": f"{default_value} {unit}",
            "defaultEvidence": sorted(default_ids),
            "ceilingValue": ceiling_value,
            "validationDisplay": f"1..{ceiling_value}",
        })
    return {
        "kind": "java-graph-environment-family-v1",
        "sourceOwner": f"{GRAPH_EXECUTION_LIMITS_PATH.as_posix()}#GraphExecutionLimits",
        "factoryMethod": "fromEnvironment",
        "factoryBodyDigest": java_method_digest(source, "GraphExecutionLimits", "fromEnvironment"),
        "helperBodyDigests": helper_digests,
        "settings": settings,
    }


def graph_limit_authority_errors(root: Path, authorities: object,
                                 entries: dict[str, dict[str, object]],
                                 discovered: dict[str, Candidate]) -> list[str]:
    """Verify the mandatory graph family across nested typed owners and exact carriers."""
    expected_settings = set(GRAPH_LIMIT_AUTHORITY_BY_SETTING)
    reviewed = {
        str(entry.get("setting")) for entry in entries.values()
        if entry.get("status") != "pending-review"
        and str(entry.get("setting", "")) in expected_settings
    }
    if not reviewed:
        return ([] if authorities in (None, {})
                else ["graph limit authority exists without reviewed graph settings"])
    errors: list[str] = []
    if reviewed != expected_settings:
        errors.append("reviewed graph settings do not equal the closed 25-setting family")
    derived = graph_limit_family_from_source(root, discovered)
    if derived is None:
        return errors + ["GraphExecutionLimits environment source family has drifted"]
    if not isinstance(authorities, dict) or set(authorities) != {GRAPH_LIMIT_FAMILY_ID} \
            or authorities.get(GRAPH_LIMIT_FAMILY_ID) != derived:
        errors.append("graph settings require the exact source-derived 25-setting family authority")
    for spec in derived["settings"]:
        setting = str(spec["setting"])
        setting_entries = [entry for entry in entries.values() if entry.get("setting") == setting]
        if not setting_entries:
            errors.append(f"{setting}: graph family has no inventory rows")
            continue
        representative = setting_entries[0]
        if representative.get("owner") != spec["typedOwner"] \
                or representative.get("field") != spec["field"] \
                or representative.get("bindings") != [spec["environment"]] \
                or representative.get("bindingAuthority") is not None:
            errors.append(f"{setting}: graph typed owner/field/environment metadata has drifted")
        if any(entry.get("default") != spec["defaultDisplay"]
               or entry.get("validation") != spec["validationDisplay"]
               or not isinstance(entry.get("defaultEvidence"), list)
               or Counter(str(identifier) for identifier in entry["defaultEvidence"])
               != Counter(str(identifier) for identifier in spec["defaultEvidence"])
               for entry in setting_entries):
            errors.append(f"{setting}: graph default, range, or exact default evidence has drifted")
        if any(identifier not in discovered
               or entries.get(identifier, {}).get("setting") != setting
               for identifier in spec["defaultEvidence"]):
            errors.append(f"{setting}: graph typed default atoms are absent or assigned elsewhere")
        if current_source_owner(root, str(spec["typedOwner"])) is None \
                or not current_source_field(root, str(spec["typedOwner"]), str(spec["field"])):
            errors.append(f"{setting}: graph typed owner does not declare its exact component")
        source_id = str(spec["environmentCandidateId"])
        source_candidate = discovered.get(source_id)
        if source_candidate is None or source_candidate.path != GRAPH_EXECUTION_LIMITS_PATH.as_posix() \
                or source_candidate.kind != "environment-binding" \
                or source_candidate.expression != spec["environment"] \
                or entries.get(source_id, {}).get("setting") != setting:
            errors.append(f"{setting}: graph source environment candidate is absent or assigned elsewhere")
        source_ids = {
            candidate.id for candidate in discovered.values()
            if candidate.path == GRAPH_EXECUTION_LIMITS_PATH.as_posix()
            and candidate.kind == "environment-binding"
            and candidate.expression == spec["environment"]
        }
        if source_ids != {source_id}:
            errors.append(f"{setting}: graph source environment candidate partition has drifted")
        coverage = representative.get("coverageEvidence")
        carrier_ids: set[str] = set()
        if not isinstance(coverage, dict) \
                or coverage.get("kind") != "graph-platform-carriers-v1":
            errors.append(f"{setting}: graph family requires complete platform carrier evidence")
        else:
            for field in ("composeCandidateIds", "helmTemplateCandidateIds",
                          "helmSchemaEnvironmentCandidateIds", "rawKubernetesCandidateIds"):
                identifiers = coverage.get(field)
                if not isinstance(identifiers, list):
                    errors.append(f"{setting}: graph carrier evidence has no {field}")
                    continue
                carrier_ids.update(str(identifier) for identifier in identifiers)
        assigned = {
            str(entry["id"]) for entry in setting_entries
            if entry.get("kind") == "environment-binding"
        }
        if assigned != {source_id} | carrier_ids or any(
                identifier not in discovered
                or discovered[identifier].expression != spec["environment"]
                or entries.get(identifier, {}).get("setting") != setting
                for identifier in carrier_ids):
            errors.append(f"{setting}: graph environment candidates are not fully partitioned")
    return errors


def deployment_carrier_evidence_errors(setting: str, contract: dict[str, object],
                                       entries: dict[str, dict[str, object]],
                                       discovered: dict[str, Candidate]) -> tuple[list[str], set[str]]:
    evidence = contract.get("carrierEvidence")
    required = {"kind", "environment", "expectedCandidateIds"}
    if not isinstance(evidence, dict) or set(evidence) != required:
        return ([f"{setting}: environment-bound setting requires exact carrierEvidence fields"], set())
    errors: list[str] = []
    if evidence["kind"] != "deployment-environment-carriers-v1":
        errors.append(f"{setting}: unsupported carrierEvidence kind")
    bindings = contract.get("bindings", [])
    environment = str(evidence["environment"])
    if not isinstance(bindings, list) or bindings != [environment]:
        errors.append(f"{setting}: carrier environment must be the setting's sole binding")
    expected = evidence["expectedCandidateIds"]
    if not isinstance(expected, dict) or set(expected) != set(DEPLOYMENT_ENVIRONMENT_CARRIER_PATHS):
        return (errors + [f"{setting}: carrierEvidence must retain every checker-owned carrier group"], set())
    accounted: set[str] = set()
    for group, paths in DEPLOYMENT_ENVIRONMENT_CARRIER_PATHS.items():
        actual_ids = sorted(
            candidate.id for candidate in discovered.values()
            if candidate.path in paths and candidate.kind == "environment-binding"
            and candidate.expression == environment
        )
        declared = expected[group]
        if not isinstance(declared, list) or [str(identifier) for identifier in declared] != actual_ids:
            errors.append(f"{setting}: {group} carrier candidate set has drifted")
            continue
        accounted.update(actual_ids)
        if any(entries.get(identifier, {}).get("setting") != setting for identifier in actual_ids):
            errors.append(f"{setting}: {group} carrier candidate is absent or assigned elsewhere")
    return errors, accounted



PERSISTENCE_POLICY_AUTHORITY_ID = "ravenroot-persistence-policy-v1"
PERSISTENCE_POSTGRES_CONFIG_PATH = Path(
    "ravenroot/ravenroot-persistence-postgresql/src/main/java/ai/ravenroot/persistence/postgresql/PostgresStoreConfig.java")
PERSISTENCE_POSTGRES_RESOLVER_PATH = Path(
    "ravenroot/ravenroot-server/src/main/java/ai/ravenroot/server/persistence/PostgresStoreConfiguration.java")
PERSISTENCE_STORE_CONFIGURATION_PATH = Path(
    "ravenroot/ravenroot-server/src/main/java/ai/ravenroot/server/persistence/ExecutionStoreConfiguration.java")
PERSISTENCE_SHARED_CONNECTION_PATH = Path(
    "ravenroot/ravenroot-server/src/main/java/ai/ravenroot/server/persistence/SharedStoreConnection.java")
PERSISTENCE_SHARED_DATASOURCE_PATH = Path(
    "ravenroot/ravenroot-server/src/main/java/ai/ravenroot/server/persistence/SharedExecutionStoreDataSource.java")
PERSISTENCE_OWNERSHIP_CONFIGURATION_PATH = Path(
    "ravenroot/ravenroot-server/src/main/java/ai/ravenroot/server/persistence/ExecutionOwnershipConfiguration.java")
PERSISTENCE_EXECUTION_OWNERSHIP_PATH = Path(
    "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/runtime/ExecutionOwnership.java")
PERSISTENCE_BACKUP_CONFIGURATION_PATH = Path(
    "ravenroot/ravenroot-cli/src/main/java/ai/ravenroot/cli/BackupRestoreConfiguration.java")
PERSISTENCE_AUDIT_DIRECTORY_PATH = Path(
    "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/audit/AuditTrailDirectory.java")
PERSISTENCE_AUDIT_CONFIGURATION_PATH = Path(
    "ravenroot/ravenroot-server/src/main/java/ai/ravenroot/server/audit/AuditTrailConfiguration.java")
PERSISTENCE_SQLITE_LOCATION_PATH = Path(
    "ravenroot/ravenroot-persistence-sqlite/src/main/java/ai/ravenroot/persistence/sqlite/SqliteStoreLocation.java")
PERSISTENCE_REGISTRY_POLICY_PATH = Path(
    "ravenroot/ravenroot-application-api/src/main/java/ai/ravenroot/api/deployment/registry/DeploymentRegistryPolicy.java")
PERSISTENCE_IN_MEMORY_POLICY_PATH = Path(
    "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/persistence/InMemoryExecutionStorePolicy.java")
PERSISTENCE_SQLITE_CONFIG_PATH = Path(
    "ravenroot/ravenroot-persistence-sqlite/src/main/java/ai/ravenroot/persistence/sqlite/SqliteStoreConfig.java")
PERSISTENCE_SQLITE_CONNECTION_POLICY_PATH = Path(
    "ravenroot/ravenroot-persistence-sqlite/src/main/java/ai/ravenroot/persistence/sqlite/SqliteConnectionPolicy.java")
PERSISTENCE_QUERY_PATH = Path(
    "ravenroot/ravenroot-application-api/src/main/java/ai/ravenroot/api/persistence/ProcessInventoryQuery.java")
PERSISTENCE_MANAGED_STORE_PATH = Path(
    "ravenroot/ravenroot-server/src/main/java/ai/ravenroot/server/persistence/ManagedExecutionStore.java")
PERSISTENCE_MANIFEST_PATH = Path(
    "ravenroot/ravenroot-application-api/src/main/java/ai/ravenroot/api/persistence/ExecutionManifest.java")
PERSISTENCE_OPERATIONAL_POLICY_PATH = Path(
    "ravenroot/ravenroot-application-api/src/main/java/ai/ravenroot/api/persistence/ResolvedOperationalPolicy.java")
PERSISTENCE_MANIFEST_DIGEST_PATH = Path(
    "ravenroot/ravenroot-application-api/src/main/java/ai/ravenroot/api/persistence/ExecutionManifestDigest.java")
PERSISTENCE_AUTHORITY_PATH = Path(
    "ravenroot/ravenroot-application-api/src/main/java/ai/ravenroot/api/persistence/ExecutionPersistenceAuthority.java")
PERSISTENCE_MANIFEST_RESOLVER_PATH = Path(
    "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/manifest/ExecutionManifestResolver.java")
PERSISTENCE_DEFAULT_APPLICATION_PATH = Path(
    "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/runtime/DefaultRavenrootApplication.java")
PERSISTENCE_BOOTSTRAP_PATH = Path(
    "ravenroot/ravenroot-server/src/main/java/ai/ravenroot/server/persistence/ExecutionStoreBootstrap.java")
PERSISTENCE_SERVER_MAIN_PATH = Path(
    "ravenroot/ravenroot-server/src/main/java/ai/ravenroot/server/RavenrootServerMain.java")
PERSISTENCE_IN_MEMORY_REGISTRY_PATH = Path(
    "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/deployment/registry/InMemoryDeploymentRegistry.java")
PERSISTENCE_SQLITE_REGISTRY_PATH = Path(
    "ravenroot/ravenroot-persistence-sqlite/src/main/java/ai/ravenroot/persistence/sqlite/SqliteDeploymentRegistry.java")
PERSISTENCE_POSTGRES_REGISTRY_PATH = Path(
    "ravenroot/ravenroot-persistence-postgresql/src/main/java/ai/ravenroot/persistence/postgresql/PostgresDeploymentRegistry.java")
PERSISTENCE_SQLITE_ARTIFACT_PATH = Path(
    "ravenroot/ravenroot-persistence-sqlite/src/main/java/ai/ravenroot/persistence/sqlite/SqliteArtifactRegistry.java")
PERSISTENCE_SQLITE_EMBED_PATH = Path(
    "ravenroot/ravenroot-persistence-sqlite/src/main/java/ai/ravenroot/persistence/sqlite/SqliteEmbedRegistrationStore.java")
PERSISTENCE_SQLITE_EXECUTION_PATH = Path(
    "ravenroot/ravenroot-persistence-sqlite/src/main/java/ai/ravenroot/persistence/sqlite/SqliteExecutionStore.java")
PERSISTENCE_POSTGRES_EXECUTION_PATH = Path(
    "ravenroot/ravenroot-persistence-postgresql/src/main/java/ai/ravenroot/persistence/postgresql/PostgresExecutionStore.java")
PERSISTENCE_STORE_CONFIGURATION_TEST_PATH = Path(
    "ravenroot/ravenroot-server/src/test/java/ai/ravenroot/server/persistence/ExecutionStoreConfigurationTest.java")
PERSISTENCE_OWNERSHIP_CONFIGURATION_TEST_PATH = Path(
    "ravenroot/ravenroot-server/src/test/java/ai/ravenroot/server/persistence/ExecutionOwnershipConfigurationTest.java")
PERSISTENCE_MANAGED_STORE_TEST_PATH = Path(
    "ravenroot/ravenroot-server/src/test/java/ai/ravenroot/server/persistence/ManagedExecutionStoreTest.java")
PERSISTENCE_CLI_SELECTOR_TEST_PATH = Path(
    "ravenroot/ravenroot-cli/src/test/java/ai/ravenroot/cli/SharedStoreBundleRefusalTest.java")
PERSISTENCE_AUDIT_DIRECTORY_TEST_PATH = Path(
    "ravenroot/ravenroot-core/src/test/java/ai/ravenroot/core/audit/AuditTrailDirectoryTest.java")
PERSISTENCE_AUDIT_CONFIGURATION_TEST_PATH = Path(
    "ravenroot/ravenroot-server/src/test/java/ai/ravenroot/server/audit/AuditTrailConfigurationTest.java")
PERSISTENCE_DIRECTORY_PARITY_TEST_PATH = Path(
    "ravenroot/ravenroot-cli/src/test/java/ai/ravenroot/server/persistence/BackupRestoreDirectoryParityTest.java")
PERSISTENCE_SQLITE_LOCATION_TEST_PATH = Path(
    "ravenroot/ravenroot-persistence-sqlite/src/test/java/ai/ravenroot/persistence/sqlite/SqliteBackupRestoreTest.java")
PERSISTENCE_OPERATIONAL_POLICY_TEST_PATH = Path(
    "ravenroot/ravenroot-application-api/src/test/java/ai/ravenroot/api/persistence/ResolvedOperationalPolicyTest.java")
PERSISTENCE_MANIFEST_RESOLVER_TEST_PATH = Path(
    "ravenroot/ravenroot-core/src/test/java/ai/ravenroot/core/manifest/ExecutionManifestResolverEnginePolicyTest.java")
PERSISTENCE_APPLICATION_MANIFEST_TEST_PATH = Path(
    "ravenroot/ravenroot-core/src/test/java/ai/ravenroot/core/runtime/DefaultRavenrootApplicationExecutionManifestTest.java")

PERSISTENCE_POSTGRES_FIELDS = (
    ("postgres.lock-timeout", "lockTimeout", "ravenroot.postgresql.lock-timeout-ms",
     "RAVENROOT_POSTGRES_LOCK_TIMEOUT_MS", "millis", None, "Duration.ofSeconds(5)"),
    ("postgres.statement-timeout", "statementTimeout", "ravenroot.postgresql.statement-timeout-ms",
     "RAVENROOT_POSTGRES_STATEMENT_TIMEOUT_MS", "millis", None, "Duration.ofSeconds(30)"),
    ("postgres.serialization-retries", "serializationRetries", "ravenroot.postgresql.serialization-retries",
     "RAVENROOT_POSTGRES_SERIALIZATION_RETRIES", "integer", "0", "3"),
    ("postgres.max-lease-ttl", "maxLeaseTtl", "ravenroot.postgresql.max-lease-ttl-seconds",
     "RAVENROOT_POSTGRES_MAX_LEASE_TTL_SECONDS", "seconds", "false", "Duration.ofMinutes(5)"),
    ("postgres.max-payload-bytes", "maxPayloadBytes", "ravenroot.postgresql.max-payload-bytes",
     "RAVENROOT_POSTGRES_MAX_PAYLOAD_BYTES", "integer", "1", "1024 * 1024"),
    ("postgres.max-clock-skew", "maxClockSkew", "ravenroot.postgresql.max-clock-skew-seconds",
     "RAVENROOT_POSTGRES_MAX_CLOCK_SKEW_SECONDS", "seconds", "true", "Duration.ofSeconds(5)"),
    ("postgres.journal-retention", "journalRetention", "ravenroot.postgresql.journal-retention-seconds",
     "RAVENROOT_POSTGRES_JOURNAL_RETENTION_SECONDS", "seconds", "false", "Duration.ofHours(24)"),
    ("postgres.max-inventory-page-size", "maxInventoryPageSize", "ravenroot.postgresql.max-inventory-page-size",
     "RAVENROOT_POSTGRES_MAX_INVENTORY_PAGE_SIZE", "integer", "1", "100"),
    ("postgres.terminal-retention", "terminalRetention", "ravenroot.postgresql.terminal-retention-seconds",
     "RAVENROOT_POSTGRES_TERMINAL_RETENTION_SECONDS", "seconds", "false", "Duration.ofDays(7)"),
    ("postgres.execution-result-retention", "executionResultRetention",
     "ravenroot.postgresql.execution-result-retention-seconds",
     "RAVENROOT_POSTGRES_EXECUTION_RESULT_RETENTION_SECONDS", "seconds", "false", "Duration.ofDays(7)"),
    ("graph.definition.upsert-retries", "graphDefinitionUpsertAttempts",
     "ravenroot.postgresql.graph-definition-upsert-attempts",
     "RAVENROOT_POSTGRES_GRAPH_DEFINITION_UPSERT_ATTEMPTS", "integer", "1", "3"),
)
PERSISTENCE_IN_MEMORY_FIELDS = (
    ("inmemory.maximum-lease-ttl", "maximumLeaseTtl", "Duration.ofMinutes(5)"),
    ("inmemory.maximum-payload-bytes", "maximumPayloadBytes", "1024 * 1024"),
    ("inmemory.maximum-clock-skew", "maximumClockSkew", "Duration.ofSeconds(5)"),
    ("inmemory.journal-retention", "journalRetention", "Duration.ofHours(24)"),
    ("inmemory.maximum-inventory-page-size", "maximumInventoryPageSize", "100"),
    ("inmemory.terminal-retention", "terminalRetention", "Duration.ofDays(7)"),
    ("inmemory.execution-result-retention", "executionResultRetention", "Duration.ofDays(7)"),
)
PERSISTENCE_SQLITE_FIELDS = (
    ("sqlite.synchronous-mode", "synchronousMode", "SynchronousMode.FULL"),
    ("sqlite.busy-timeout", "busyTimeout", "SqliteConnectionPolicy.DEFAULTS.busyTimeout()"),
    ("sqlite.maximum-lease-ttl", "maxLeaseTtl", "Duration.ofMinutes(5)"),
    ("sqlite.maximum-payload-bytes", "maxPayloadBytes", "1024 * 1024"),
    ("sqlite.maximum-clock-skew", "maxClockSkew", "Duration.ofSeconds(5)"),
    ("sqlite.journal-retention", "journalRetention", "Duration.ofHours(24)"),
    ("sqlite.maximum-inventory-page-size", "maxInventoryPageSize", "100"),
    ("sqlite.terminal-retention", "terminalRetention", "Duration.ofDays(7)"),
    ("sqlite.execution-result-retention", "executionResultRetention", "Duration.ofDays(7)"),
)


def _source_digest(source: str) -> str:
    return hashlib.sha256(source.encode("utf-8")).hexdigest()


def _exact_candidate_ids(discovered: dict[str, Candidate], path: Path, kind: str,
                         expression: str) -> list[str]:
    return sorted(candidate.id for candidate in discovered.values()
                  if candidate.path == path.as_posix() and candidate.kind == kind
                  and candidate.expression == expression)


def persistence_policy_source_present(root: Path) -> bool:
    """Distinguish true absence from any partial #318 persistence policy source family."""
    return any((root / path).exists() for path in (
        PERSISTENCE_POSTGRES_RESOLVER_PATH, PERSISTENCE_REGISTRY_POLICY_PATH,
        PERSISTENCE_IN_MEMORY_POLICY_PATH, PERSISTENCE_MANAGED_STORE_PATH,
        PERSISTENCE_AUDIT_DIRECTORY_PATH, PERSISTENCE_AUDIT_CONFIGURATION_PATH,
        PERSISTENCE_SQLITE_LOCATION_PATH,
    ))


def persistence_policy_authority_from_source(
        root: Path, discovered: dict[str, Candidate]) -> dict[str, object] | None:
    """Derive the closed persistence policy from typed declarations and executable consumers."""
    paths = (
        PERSISTENCE_POSTGRES_CONFIG_PATH, PERSISTENCE_POSTGRES_RESOLVER_PATH,
        PERSISTENCE_STORE_CONFIGURATION_PATH, PERSISTENCE_SHARED_CONNECTION_PATH,
        PERSISTENCE_SHARED_DATASOURCE_PATH, PERSISTENCE_OWNERSHIP_CONFIGURATION_PATH,
        PERSISTENCE_EXECUTION_OWNERSHIP_PATH,
        PERSISTENCE_BACKUP_CONFIGURATION_PATH, PERSISTENCE_AUDIT_DIRECTORY_PATH,
        PERSISTENCE_AUDIT_CONFIGURATION_PATH, PERSISTENCE_SQLITE_LOCATION_PATH,
        PERSISTENCE_REGISTRY_POLICY_PATH, PERSISTENCE_IN_MEMORY_POLICY_PATH,
        PERSISTENCE_SQLITE_CONFIG_PATH, PERSISTENCE_SQLITE_CONNECTION_POLICY_PATH,
        PERSISTENCE_QUERY_PATH, PERSISTENCE_MANAGED_STORE_PATH, PERSISTENCE_MANIFEST_PATH,
        PERSISTENCE_BOOTSTRAP_PATH, PERSISTENCE_SERVER_MAIN_PATH,
        PERSISTENCE_IN_MEMORY_REGISTRY_PATH, PERSISTENCE_SQLITE_REGISTRY_PATH,
        PERSISTENCE_POSTGRES_REGISTRY_PATH, PERSISTENCE_SQLITE_ARTIFACT_PATH,
        PERSISTENCE_SQLITE_EMBED_PATH, PERSISTENCE_SQLITE_EXECUTION_PATH,
        PERSISTENCE_POSTGRES_EXECUTION_PATH, PERSISTENCE_OPERATIONAL_POLICY_PATH,
        PERSISTENCE_MANIFEST_DIGEST_PATH, PERSISTENCE_AUTHORITY_PATH,
        PERSISTENCE_MANIFEST_RESOLVER_PATH,
        PERSISTENCE_DEFAULT_APPLICATION_PATH,
        PERSISTENCE_STORE_CONFIGURATION_TEST_PATH, PERSISTENCE_OWNERSHIP_CONFIGURATION_TEST_PATH,
        PERSISTENCE_MANAGED_STORE_TEST_PATH, PERSISTENCE_CLI_SELECTOR_TEST_PATH,
        PERSISTENCE_AUDIT_DIRECTORY_TEST_PATH, PERSISTENCE_AUDIT_CONFIGURATION_TEST_PATH,
        PERSISTENCE_DIRECTORY_PARITY_TEST_PATH, PERSISTENCE_SQLITE_LOCATION_TEST_PATH,
        PERSISTENCE_OPERATIONAL_POLICY_TEST_PATH, PERSISTENCE_MANIFEST_RESOLVER_TEST_PATH,
        PERSISTENCE_APPLICATION_MANIFEST_TEST_PATH,
        Path("ravenroot/ravenroot-application-api/src/test/java/ai/ravenroot/api/deployment/registry/DeploymentRegistryPolicyTest.java"),
        Path("ravenroot/ravenroot-core/src/test/java/ai/ravenroot/core/persistence/InMemoryExecutionStorePolicyTest.java"),
        Path("ravenroot/ravenroot-persistence-sqlite/src/test/java/ai/ravenroot/persistence/sqlite/SqliteConnectionPolicyTest.java"),
        Path("ravenroot/ravenroot-persistence-testkit/src/main/java/ai/ravenroot/testkit/persistence/ManagedExecutionStoreContract.java"),
        Path("ravenroot/ravenroot-persistence-sqlite/src/test/java/ai/ravenroot/persistence/sqlite/SqliteManagedExecutionStoreContractTest.java"),
        Path("ravenroot/ravenroot-persistence-postgresql/src/test/java/ai/ravenroot/persistence/postgresql/PostgresManagedExecutionStoreContractTest.java"),
    )
    try:
        sources = {path: (root / path).read_text(encoding="utf-8") for path in paths}
    except OSError:
        return None
    pg = sources[PERSISTENCE_POSTGRES_CONFIG_PATH]
    resolver = sources[PERSISTENCE_POSTGRES_RESOLVER_PATH]
    store_configuration = sources[PERSISTENCE_STORE_CONFIGURATION_PATH]
    shared_connection = sources[PERSISTENCE_SHARED_CONNECTION_PATH]
    shared_data_source = sources[PERSISTENCE_SHARED_DATASOURCE_PATH]
    ownership_configuration = sources[PERSISTENCE_OWNERSHIP_CONFIGURATION_PATH]
    execution_ownership = sources[PERSISTENCE_EXECUTION_OWNERSHIP_PATH]
    backup_configuration = sources[PERSISTENCE_BACKUP_CONFIGURATION_PATH]
    audit_directory = sources[PERSISTENCE_AUDIT_DIRECTORY_PATH]
    audit_configuration = sources[PERSISTENCE_AUDIT_CONFIGURATION_PATH]
    sqlite_location = sources[PERSISTENCE_SQLITE_LOCATION_PATH]
    registry = sources[PERSISTENCE_REGISTRY_POLICY_PATH]
    in_memory = sources[PERSISTENCE_IN_MEMORY_POLICY_PATH]
    sqlite = sources[PERSISTENCE_SQLITE_CONFIG_PATH]
    sqlite_connection = sources[PERSISTENCE_SQLITE_CONNECTION_POLICY_PATH]
    query = sources[PERSISTENCE_QUERY_PATH]
    managed = sources[PERSISTENCE_MANAGED_STORE_PATH]
    manifest = sources[PERSISTENCE_MANIFEST_PATH]
    bootstrap = sources[PERSISTENCE_BOOTSTRAP_PATH]
    server_main = sources[PERSISTENCE_SERVER_MAIN_PATH]
    in_memory_registry = sources[PERSISTENCE_IN_MEMORY_REGISTRY_PATH]
    sqlite_registry = sources[PERSISTENCE_SQLITE_REGISTRY_PATH]
    postgres_registry = sources[PERSISTENCE_POSTGRES_REGISTRY_PATH]
    sqlite_artifact = sources[PERSISTENCE_SQLITE_ARTIFACT_PATH]
    sqlite_embed = sources[PERSISTENCE_SQLITE_EMBED_PATH]
    sqlite_execution = sources[PERSISTENCE_SQLITE_EXECUTION_PATH]
    postgres_execution = sources[PERSISTENCE_POSTGRES_EXECUTION_PATH]
    operational_policy = sources[PERSISTENCE_OPERATIONAL_POLICY_PATH]
    manifest_digest = sources[PERSISTENCE_MANIFEST_DIGEST_PATH]
    persistence_authority = sources[PERSISTENCE_AUTHORITY_PATH]
    manifest_resolver = sources[PERSISTENCE_MANIFEST_RESOLVER_PATH]
    default_application = sources[PERSISTENCE_DEFAULT_APPLICATION_PATH]
    pg_components = tuple(field for _setting, field, _property, _environment, _helper, _constraint, _default
                          in PERSISTENCE_POSTGRES_FIELDS)
    if java_record_components(pg, "PostgresStoreConfig") != pg_components \
            or java_record_components(in_memory, "InMemoryExecutionStorePolicy") != tuple(
                field for _setting, field, _default in PERSISTENCE_IN_MEMORY_FIELDS) \
            or java_record_components(sqlite, "SqliteStoreConfig") != tuple(
                field for _setting, field, _default in PERSISTENCE_SQLITE_FIELDS) \
            or java_record_components(registry, "DeploymentRegistryPolicy") != ("commandRetention", "limits") \
            or java_record_components(sqlite_connection, "SqliteConnectionPolicy") != ("busyTimeout",):
        return None
    resolver_components = java_record_components(pg, "PostgresStoreConfig")
    contracts: list[dict[str, object]] = []
    for setting, field, property_name, environment, helper, constraint, approved_default in PERSISTENCE_POSTGRES_FIELDS:
        default_span = java_record_default_expression_span(pg, "PostgresStoreConfig", "DEFAULTS", field)
        call = java_constructor_component_call(
            resolver, "PostgresStoreConfiguration", "fromSources", "PostgresStoreConfig",
            resolver_components, field)
        if default_span is None or call is None \
                or normalized(default_span[0]) != normalized(approved_default):
            return None
        argument = normalized(call[0])
        suffix = "" if constraint is None else f", {constraint}"
        expected_argument = normalized(
            f'{helper}(properties, environment, "{property_name}", "{environment}", '
            f'defaults.{field}(){suffix})')
        if argument != expected_argument:
            return None
        property_ids = _exact_candidate_ids(discovered, PERSISTENCE_POSTGRES_RESOLVER_PATH,
                                            "property-binding", property_name)
        environment_ids = _exact_candidate_ids(discovered, PERSISTENCE_POSTGRES_RESOLVER_PATH,
                                               "environment-binding", environment)
        default_ids = numeric_candidate_ids_in_source_span(
            PERSISTENCE_POSTGRES_CONFIG_PATH, pg, default_span[1], default_span[2], discovered)
        if len(property_ids) != 1 or len(environment_ids) != 1 or not default_ids:
            return None
        contracts.append({
            "setting": setting,
            "owner": f"{PERSISTENCE_POSTGRES_CONFIG_PATH.as_posix()}#PostgresStoreConfig",
            "field": field, "bindings": [property_name, environment],
            "defaultExpression": normalized(default_span[0]),
            "defaultCandidateIds": sorted(default_ids),
            "candidateIds": sorted(default_ids + property_ids + environment_ids),
            "resolverCallDigest": hashlib.sha256(call[0].encode("utf-8")).hexdigest(),
        })
    for setting, field, approved_default in PERSISTENCE_IN_MEMORY_FIELDS:
        span = java_record_default_expression_span(
            in_memory, "InMemoryExecutionStorePolicy", "DEFAULTS", field)
        if span is None or normalized(span[0]) != normalized(approved_default):
            return None
        contracts.append({
            "setting": setting,
            "owner": f"{PERSISTENCE_IN_MEMORY_POLICY_PATH.as_posix()}#InMemoryExecutionStorePolicy",
            "field": field, "bindings": [], "defaultExpression": normalized(span[0]),
            "defaultCandidateIds": sorted(numeric_candidate_ids_in_source_span(
                PERSISTENCE_IN_MEMORY_POLICY_PATH, in_memory, span[1], span[2], discovered)),
            "candidateIds": sorted(numeric_candidate_ids_in_source_span(
                PERSISTENCE_IN_MEMORY_POLICY_PATH, in_memory, span[1], span[2], discovered)),
        })
    for setting, field, approved_default in PERSISTENCE_SQLITE_FIELDS:
        span = java_record_default_expression_span(sqlite, "SqliteStoreConfig", "DEFAULTS", field)
        if span is None or normalized(span[0]) != normalized(approved_default):
            return None
        default_ids = numeric_candidate_ids_in_source_span(
            PERSISTENCE_SQLITE_CONFIG_PATH, sqlite, span[1], span[2], discovered)
        if field == "busyTimeout":
            connection_default = java_record_default_expression_span(
                sqlite_connection, "SqliteConnectionPolicy", "DEFAULTS", "busyTimeout")
            if connection_default is None or normalized(span[0]) != \
                    "SqliteConnectionPolicy.DEFAULTS.busyTimeout()" \
                    or normalized(connection_default[0]) != "Duration.ofSeconds(5)":
                return None
            default_ids = numeric_candidate_ids_in_source_span(
                PERSISTENCE_SQLITE_CONNECTION_POLICY_PATH, sqlite_connection,
                connection_default[1], connection_default[2], discovered)
        contracts.append({
            "setting": setting,
            "owner": f"{PERSISTENCE_SQLITE_CONFIG_PATH.as_posix()}#SqliteStoreConfig",
            "field": field, "bindings": [], "defaultExpression": normalized(span[0]),
            "defaultCandidateIds": sorted(default_ids), "candidateIds": sorted(default_ids),
        })
    registry_span = java_static_final_initializer(registry, "DeploymentRegistryPolicy", "DEFAULTS")
    if registry_span is None:
        return None
    registry_expression = registry[registry_span[1]:registry_span[2]]
    registry_code = strip_c_comments_and_literals(registry_expression)
    policy_match = re.fullmatch(r"\s*new\s+DeploymentRegistryPolicy\s*\((.*)\)\s*",
                                registry_code, re.DOTALL)
    if policy_match is None:
        return None
    policy_open = registry_code.find("(")
    policy_args = split_java_arguments(registry_expression, registry_code, policy_open)
    if policy_args is None or len(policy_args[0]) != 2:
        return None
    _limits_normalized, limits_start, limits_end = policy_args[0][1]
    limits_expression = registry_expression[limits_start:limits_end]
    limits_code = strip_c_comments_and_literals(limits_expression)
    limits_match = re.fullmatch(r"\s*new\s+DeploymentRegistry\.Limits\s*\((.*)\)\s*",
                                limits_code, re.DOTALL)
    if limits_match is None:
        return None
    limits_open = limits_code.find("(")
    limits_args = split_java_arguments(limits_expression, limits_code, limits_open)
    if limits_args is None or len(limits_args[0]) != 3:
        return None
    registry_settings = (
        ("deployment.registry.command-retention", "commandRetention", policy_args[0][0],
         "Duration.ofDays(7)", registry_span[1]),
        ("deployment.registry.max-page-size", "limits", limits_args[0][0], "100",
         registry_span[1] + limits_start),
        ("deployment.registry.max-lease-ttl", "limits", limits_args[0][1],
         "Duration.ofMinutes(5)", registry_span[1] + limits_start),
        ("deployment.registry.max-clock-skew", "limits", limits_args[0][2],
         "Duration.ofSeconds(5)", registry_span[1] + limits_start),
    )
    for setting, field, argument_span, expected_expression, base_offset in registry_settings:
        expression, start, end = argument_span
        if normalized(expression) != normalized(expected_expression):
            return None
        identifiers = numeric_candidate_ids_in_source_span(
            PERSISTENCE_REGISTRY_POLICY_PATH, registry, base_offset + start, base_offset + end,
            discovered)
        if len(identifiers) != 1:
            return None
        contracts.append({
            "setting": setting,
            "owner": f"{PERSISTENCE_REGISTRY_POLICY_PATH.as_posix()}#DeploymentRegistryPolicy",
            "field": field, "bindings": [], "defaultExpression": normalized(expression),
            "defaultCandidateIds": identifiers, "candidateIds": identifiers,
        })
    query_default = java_static_final_initializer(query, "ProcessInventoryQuery", "DEFAULT_LIMIT")
    query_ids = _exact_candidate_ids(discovered, PERSISTENCE_QUERY_PATH, "fixed-declaration", "50")
    if query_default is None or normalized(query_default[0]) != "50" or len(query_ids) != 1:
        return None
    contracts.append({
        "setting": "execution.inventory.default-page-size",
        "owner": f"{PERSISTENCE_QUERY_PATH.as_posix()}#Builder", "field": "limit",
        "bindings": ["ProcessInventoryQuery.Builder.limit(int)"], "defaultExpression": "50",
        "defaultCandidateIds": query_ids, "candidateIds": query_ids,
    })
    # The eight deployment/composition settings below deliberately do not use the generic numeric
    # record-component authorities.  Their executable contracts include a sealed selector, opaque
    # credentials, a split pool policy, and a dynamic worker-name fallback.  Each exact method digest
    # is an approved source shape: changing behavior cannot be blessed merely by refreshing the
    # generated whole-file digest in inventory metadata.
    approved_method_digests = (
        (PERSISTENCE_STORE_CONFIGURATION_PATH, "ExecutionStoreConfiguration", "fromSources",
         "a039ad53983628039513d754eab7915736c73ae4fd62d5a3622070cae12ecf3d"),
        (PERSISTENCE_STORE_CONFIGURATION_PATH, "ExecutionStoreConfiguration", "selectorIn",
         "7c2c5afebf35bf617e1cc3ed71e83f8ee0389484a80a32457ffdc2af54ef6af7"),
        (PERSISTENCE_SHARED_CONNECTION_PATH, "SharedStoreConnection", "fromSources",
         "3894af506beb3e9023fed14ef15e2096216d9e0fc0ee28953cc455c7a0184c00"),
        (PERSISTENCE_SHARED_CONNECTION_PATH, "SharedStoreConnection", "poolSize",
         "5e5b3ffa3add995faacc61f53bc2fe3e584face1c8b327a433d42da459300e96"),
        (PERSISTENCE_SHARED_CONNECTION_PATH, "SharedStoreConnection", "poolTimeout",
         "a88b3622bfc4791c7f08909a9987798ee154761db1caa70171f57af3af5c7bf0"),
        (PERSISTENCE_SHARED_CONNECTION_PATH, "SharedStoreConnection", "selected",
         "2ea7879f6b19fa0afcc348c0770bf5aba14c54dc6510de8b43ac5f6b49eee292"),
        (PERSISTENCE_SHARED_CONNECTION_PATH, "SharedStoreConnection", "trimmed",
         "a021259e23c302f4aaa3ac554251fb3d69322adf9b6b7eecfdfe5d243d8955f0"),
        (PERSISTENCE_SHARED_CONNECTION_PATH, "SharedStoreConnection", "toString",
         "01bc141b77a1150809f3100afdecf1b73408d7474b5d0341e02c9b77a912c6cd"),
        (PERSISTENCE_SHARED_DATASOURCE_PATH, "SharedExecutionStoreDataSource", "open",
         "6e2620dbfe05f4053025efecc0603b2dc2558d34a1388841088b9370098fa3b1"),
        (PERSISTENCE_OWNERSHIP_CONFIGURATION_PATH, "ExecutionOwnershipConfiguration", "fromSources",
         "a885517152cb34299e887fdc594001c67452c06f0602e3436ae32f527bddf6b0"),
        (PERSISTENCE_OWNERSHIP_CONFIGURATION_PATH, "ExecutionOwnershipConfiguration", "leaseTtl",
         "9e579c6bc82463894d8fc9ae854fad7ae8716b033d7ef01ec2f9fc23bcfbe81b"),
        (PERSISTENCE_OWNERSHIP_CONFIGURATION_PATH, "ExecutionOwnershipConfiguration", "selected",
         "d55b771b9e9e35402eeb5b2e54e9a4308bb2135e6da4309b77273d12cdd5a5f9"),
        (PERSISTENCE_OWNERSHIP_CONFIGURATION_PATH, "ExecutionOwnershipConfiguration", "hostName",
         "e239183f35515e56f755cebcf261b0ca55580f63c6959ee216e58b2a72686372"),
        (PERSISTENCE_OWNERSHIP_CONFIGURATION_PATH, "ExecutionOwnershipConfiguration", "usableName",
         "adf5c56cd390d84d4ea30aff6e5cf99d793eadc4e9d540022f303dd8fb7b88c5"),
        (PERSISTENCE_OWNERSHIP_CONFIGURATION_PATH, "ExecutionOwnershipConfiguration", "runtimeOwnership",
         "73754fdc86f588dcfdc0494242240230bfa7f923490fc1df482b13ac432b5b45"),
        (PERSISTENCE_OWNERSHIP_CONFIGURATION_PATH, "ExecutionOwnershipConfiguration", "recoveryIdentity",
         "79a701c248ce4e2af942ee2fd859ac90c724d223b3477df8c2f184355d99e2be"),
        (PERSISTENCE_OWNERSHIP_CONFIGURATION_PATH, "ExecutionOwnershipConfiguration", "requireCompatible",
         "7dc8e183ddde66ccda77fff516efba5704ef5ab3dfe5518579685cea98844070"),
        (PERSISTENCE_SERVER_MAIN_PATH, "RavenrootServerMain", "run",
         "e0ddeb698b2f2755b9fcd57f393473609859851814d4c494d4ee0aa8209fc078"),
        (PERSISTENCE_AUDIT_DIRECTORY_PATH, "AuditTrailDirectory", "resolve",
         "fabf6b48115874f29c018fb61e71bc358a3f977634dfc1723a1bf3aa335fb227"),
        (PERSISTENCE_AUDIT_CONFIGURATION_PATH, "AuditTrailConfiguration", "fromEnvironment",
         "4cf8be7a3951a412f80bd9937287b876fbd4cc2bea77de3dd100164dfbad9378"),
        (PERSISTENCE_SQLITE_LOCATION_PATH, "SqliteStoreLocation", "underConfiguredDirectory",
         "46fed5770f116f2dc9298d7b678394b542cd53146db93d8657c0e90878763e22"),
        (PERSISTENCE_SQLITE_LOCATION_PATH, "SqliteStoreLocation", "underDirectory",
         "41bf3e1968896c4dcf8bfac8744c432da1cd510ed983282cc838b6648c2c9f69"),
        (PERSISTENCE_BACKUP_CONFIGURATION_PATH, "BackupRestoreConfiguration", "fromEnvironment",
         "2382822498bda441f9bb5b63b50c36ec1a4bea3db64d67bb3b790619dce63513"),
        (PERSISTENCE_STORE_CONFIGURATION_PATH, "ExecutionStoreConfiguration", "singleHostLocation",
         "10b29a815f02ec4ce915c796bbb7091b06f6b7687e240a13659fb8f2feccace3"),
        (PERSISTENCE_STORE_CONFIGURATION_PATH, "ExecutionStoreConfiguration", "enabledIn",
         "c37f4a358c11a630fb93873a1ebf02d1815cfb4c0aa8b7647d59c5e895ebb52f"),
    )
    if any(java_method_digest(sources[path], type_symbol, method) != digest
           for path, type_symbol, method, digest in approved_method_digests):
        return None
    configuration_span = java_type_span(store_configuration, "ExecutionStoreConfiguration")
    configuration_type = strip_c_comments(
        store_configuration[slice(*configuration_span)] if configuration_span is not None else "")
    selector_constants_exact = all(len(re.findall(
        rf'\bString\s+{field}\s*=\s*"{re.escape(value)}"\s*;', configuration_type)) == 1
        for field, value in (("SQLITE_SELECTOR", "sqlite"),
                             ("POSTGRESQL_SELECTOR", "postgresql")))
    enabled_default_exact = len(re.findall(
        r'\bstatic\s+final\s+String\s+DEFAULT_ENABLED_VALUE\s*=\s*"true"\s*;',
        configuration_type)) == 1
    compact = java_compact_constructor_span(shared_connection, "SharedStoreConnection")
    if compact is None or java_span_digest(shared_connection, compact) != \
            "def1d81068a369cafbe90d4975eb1fc57067f20be43bdf211a45e059dd95c864" \
            or _source_digest(backup_configuration) != \
            "e7992790f1a312a57ea9d888b8ca8e0107877965e2eedf3b80589812f7ca3fe0" \
            or not selector_constants_exact or not enabled_default_exact \
            or java_static_final_initializer(
                shared_connection, "SharedStoreConnection", "REQUIRED_URL_PREFIX") is None \
            or normalized(java_static_final_initializer(
                shared_connection, "SharedStoreConnection", "REQUIRED_URL_PREFIX")[0]) != \
            '"jdbc:postgresql:"':
        return None
    lease_default = java_static_final_initializer(
        execution_ownership, "ExecutionOwnership", "DEFAULT_LEASE_TTL")
    if lease_default is None or normalized(lease_default[0]) != "Duration.ofSeconds(30)":
        return None

    def exact_ids(specs: tuple[tuple[Path, str, str, str], ...]) -> list[str] | None:
        identifiers: list[str] = []
        for path, kind, expression, role in specs:
            matched = sorted(candidate.id for candidate in discovered.values()
                             if candidate.path == path.as_posix() and candidate.kind == kind
                             and candidate.expression == expression and candidate.role == role)
            if len(matched) != 1:
                return None
            identifiers.extend(matched)
        return identifiers if len(identifiers) == len(set(identifiers)) else None

    selector_ids = exact_ids((
        (PERSISTENCE_BACKUP_CONFIGURATION_PATH, "environment-binding",
         "RAVENROOT_EXECUTION_STORE", "RAVENROOT_EXECUTION_STORE"),
        (PERSISTENCE_BACKUP_CONFIGURATION_PATH, "fixed-declaration",
         '"RAVENROOT_EXECUTION_STORE"', "STORE_SELECTOR_VARIABLE"),
        (PERSISTENCE_BACKUP_CONFIGURATION_PATH, "fixed-declaration",
         '"postgresql"', "SHARED_STORE_SELECTOR"),
        (PERSISTENCE_BACKUP_CONFIGURATION_PATH, "fixed-declaration",
         '"ravenroot.execution-store"', "STORE_SELECTOR_PROPERTY"),
        (PERSISTENCE_BACKUP_CONFIGURATION_PATH, "property-binding",
         "ravenroot.execution-store", "ravenroot.execution-store"),
        (PERSISTENCE_STORE_CONFIGURATION_PATH, "environment-binding",
         "RAVENROOT_EXECUTION_STORE", "RAVENROOT_EXECUTION_STORE"),
        (PERSISTENCE_STORE_CONFIGURATION_PATH, "property-binding",
         "ravenroot.execution-store", "ravenroot.execution-store"),
    ))
    url_ids = exact_ids(((PERSISTENCE_STORE_CONFIGURATION_PATH, "environment-binding",
                          "RAVENROOT_EXECUTION_STORE_URL", "RAVENROOT_EXECUTION_STORE_URL"),))
    user_ids = exact_ids(((PERSISTENCE_STORE_CONFIGURATION_PATH, "environment-binding",
                           "RAVENROOT_EXECUTION_STORE_USER", "RAVENROOT_EXECUTION_STORE_USER"),))
    password_ids = exact_ids(((PERSISTENCE_STORE_CONFIGURATION_PATH, "environment-binding",
                               "RAVENROOT_EXECUTION_STORE_PASSWORD",
                               "RAVENROOT_EXECUTION_STORE_PASSWORD"),))
    pool_size_ids = exact_ids((
        (PERSISTENCE_STORE_CONFIGURATION_PATH, "environment-binding",
         "RAVENROOT_EXECUTION_STORE_POOL_SIZE", "RAVENROOT_EXECUTION_STORE_POOL_SIZE"),
        (PERSISTENCE_STORE_CONFIGURATION_PATH, "operational-declaration",
         '"RAVENROOT_EXECUTION_STORE_POOL_SIZE"', "POOL_SIZE_VARIABLE"),
        (PERSISTENCE_SHARED_CONNECTION_PATH, "fixed-declaration", "10", "DEFAULT_POOL_SIZE"),
        (PERSISTENCE_SHARED_CONNECTION_PATH, "fixed-declaration", "1_000", "MAX_POOL_SIZE"),
        (PERSISTENCE_STORE_CONFIGURATION_PATH, "operational-declaration",
         '"ravenroot.execution-store.pool-size"', "POOL_SIZE_PROPERTY"),
        (PERSISTENCE_STORE_CONFIGURATION_PATH, "property-binding",
         "ravenroot.execution-store.pool-size", "ravenroot.execution-store.pool-size"),
    ))
    pool_timeout_ids = exact_ids((
        (PERSISTENCE_STORE_CONFIGURATION_PATH, "environment-binding",
         "RAVENROOT_EXECUTION_STORE_POOL_TIMEOUT_MS", "RAVENROOT_EXECUTION_STORE_POOL_TIMEOUT_MS"),
        (PERSISTENCE_STORE_CONFIGURATION_PATH, "operational-declaration",
         '"RAVENROOT_EXECUTION_STORE_POOL_TIMEOUT_MS"', "POOL_TIMEOUT_VARIABLE"),
        (PERSISTENCE_SHARED_CONNECTION_PATH, "fixed-declaration", "10", "DEFAULT_POOL_TIMEOUT"),
        (PERSISTENCE_SHARED_CONNECTION_PATH, "fixed-declaration", "250", "MIN_POOL_TIMEOUT"),
        (PERSISTENCE_STORE_CONFIGURATION_PATH, "operational-declaration",
         '"ravenroot.execution-store.pool-timeout-ms"', "POOL_TIMEOUT_PROPERTY"),
        (PERSISTENCE_STORE_CONFIGURATION_PATH, "property-binding",
         "ravenroot.execution-store.pool-timeout-ms",
         "ravenroot.execution-store.pool-timeout-ms"),
    ))
    worker_ids = exact_ids((
        (PERSISTENCE_OWNERSHIP_CONFIGURATION_PATH, "environment-binding",
         "RAVENROOT_WORKER_ID", "RAVENROOT_WORKER_ID"),
        (PERSISTENCE_OWNERSHIP_CONFIGURATION_PATH, "fixed-declaration",
         '"RAVENROOT_WORKER_ID"', "WORKER_ID_VARIABLE"),
        (PERSISTENCE_OWNERSHIP_CONFIGURATION_PATH, "fixed-declaration",
         '"ravenroot.execution.worker-id"', "WORKER_ID_PROPERTY"),
        (PERSISTENCE_OWNERSHIP_CONFIGURATION_PATH, "property-binding",
         "ravenroot.execution.worker-id", "ravenroot.execution.worker-id"),
        (PERSISTENCE_OWNERSHIP_CONFIGURATION_PATH, "fixed-declaration",
         '"unnamed-replica"', "UNRESOLVED_REPLICA_NAME"),
    ))
    lease_ids = exact_ids((
        (PERSISTENCE_EXECUTION_OWNERSHIP_PATH, "fixed-declaration", "30", "DEFAULT_LEASE_TTL"),
        (PERSISTENCE_OWNERSHIP_CONFIGURATION_PATH, "environment-binding",
         "RAVENROOT_EXECUTION_LEASE_TTL_SECONDS", "RAVENROOT_EXECUTION_LEASE_TTL_SECONDS"),
        (PERSISTENCE_OWNERSHIP_CONFIGURATION_PATH, "fixed-declaration",
         '"RAVENROOT_EXECUTION_LEASE_TTL_SECONDS"', "LEASE_TTL_VARIABLE"),
        (PERSISTENCE_OWNERSHIP_CONFIGURATION_PATH, "fixed-declaration",
         '"ravenroot.execution.lease-ttl-seconds"', "LEASE_TTL_PROPERTY"),
        (PERSISTENCE_OWNERSHIP_CONFIGURATION_PATH, "property-binding",
         "ravenroot.execution.lease-ttl-seconds", "ravenroot.execution.lease-ttl-seconds"),
    ))
    if any(group is None for group in (selector_ids, url_ids, user_ids, password_ids,
                                       pool_size_ids, pool_timeout_ids, worker_ids, lease_ids)):
        return None
    assert selector_ids is not None and url_ids is not None and user_ids is not None \
        and password_ids is not None and pool_size_ids is not None \
        and pool_timeout_ids is not None and worker_ids is not None and lease_ids is not None
    # MIN_POOL_TIMEOUT mirrors HikariCP's own admissibility floor.  It is source evidence for the
    # parser's validation, but it is neither an operator default nor part of the operator setting's
    # candidate partition; inventory retains that atom as a fixed dependency contract.
    pool_timeout_contract_ids = [identifier for index, identifier in enumerate(pool_timeout_ids)
                                 if index != 3]
    contracts.extend((
        {
            "setting": "execution.store.selector",
            "owner": f"{PERSISTENCE_STORE_CONFIGURATION_PATH.as_posix()}#ExecutionStoreConfiguration",
            "field": "selector", "bindings": ["ravenroot.execution-store", "RAVENROOT_EXECUTION_STORE"],
            "defaultExpression": "SQLITE_SELECTOR", "defaultKind": "closed-variant-fallback",
            "defaultCandidateIds": [selector_ids[5]],
            "candidateIds": sorted(selector_ids),
            "consumerEvidence": [
                f"{PERSISTENCE_SERVER_MAIN_PATH.as_posix()}#run",
                f"{PERSISTENCE_BACKUP_CONFIGURATION_PATH.as_posix()}#sharedStoreSelected",
            ],
            "sourceSemantics": "Property overrides environment; blank delegates; absent selects sqlite; only sqlite or postgresql is accepted; CLI spelling and refusal match the server.",
        },
        {
            "setting": "execution.store.url",
            "owner": f"{PERSISTENCE_SHARED_CONNECTION_PATH.as_posix()}#SharedStoreConnection",
            "field": "url", "bindings": ["RAVENROOT_EXECUTION_STORE_URL"],
            "defaultExpression": "required for postgresql; no fallback",
            "defaultKind": "required-no-fallback",
            "defaultCandidateIds": url_ids, "candidateIds": url_ids,
            "consumerEvidence": [f"{PERSISTENCE_SHARED_DATASOURCE_PATH.as_posix()}#open"],
            "sourceSemantics": "Required only for postgresql and accepted only with the jdbc:postgresql: prefix; diagnostics do not echo the value.",
        },
        {
            "setting": "execution.store.user",
            "owner": f"{PERSISTENCE_SHARED_CONNECTION_PATH.as_posix()}#SharedStoreConnection",
            "field": "user", "bindings": ["RAVENROOT_EXECUTION_STORE_USER"],
            "defaultExpression": "Optional.empty() when absent or blank",
            "defaultKind": "optional-absent",
            "defaultCandidateIds": user_ids, "candidateIds": user_ids,
            "consumerEvidence": [f"{PERSISTENCE_SHARED_DATASOURCE_PATH.as_posix()}#open"],
            "sourceSemantics": "An optional nonblank role is trimmed; absence delegates authentication to the URL or server.",
        },
        {
            "setting": "execution.store.password",
            "owner": f"{PERSISTENCE_SHARED_CONNECTION_PATH.as_posix()}#SharedStoreConnection",
            "field": "password", "bindings": ["RAVENROOT_EXECUTION_STORE_PASSWORD"],
            "defaultExpression": "Optional.empty() only when absent",
            "defaultKind": "optional-absent-opaque", "valueHandling": "secret-verbatim-redacted",
            "defaultCandidateIds": password_ids, "candidateIds": password_ids,
            "consumerEvidence": [f"{PERSISTENCE_SHARED_DATASOURCE_PATH.as_posix()}#open"],
            "sourceSemantics": "The optional secret is opaque and every present value, including empty or whitespace, is preserved verbatim and redacted from rendering.",
        },
        {
            "setting": "execution.store.pool-size",
            "owner": f"{PERSISTENCE_SHARED_CONNECTION_PATH.as_posix()}#SharedStoreConnection",
            "field": "poolSize", "bindings": ["ravenroot.execution-store.pool-size",
                                                  "RAVENROOT_EXECUTION_STORE_POOL_SIZE"],
            "defaultExpression": "10", "defaultKind": "static-typed-default",
            "defaultCandidateIds": [pool_size_ids[2]],
            "candidateIds": sorted(pool_size_ids),
            "consumerEvidence": [f"{PERSISTENCE_SHARED_DATASOURCE_PATH.as_posix()}#open"],
            "sourceSemantics": "Property overrides environment; blank delegates to 10; accepted range is 1 through 1000 and the resolved value configures the pool.",
        },
        {
            "setting": "execution.store.pool-timeout",
            "owner": f"{PERSISTENCE_SHARED_CONNECTION_PATH.as_posix()}#SharedStoreConnection",
            "field": "poolTimeout", "bindings": ["ravenroot.execution-store.pool-timeout-ms",
                                                    "RAVENROOT_EXECUTION_STORE_POOL_TIMEOUT_MS"],
            "defaultExpression": "Duration.ofSeconds(10)",
            "defaultKind": "static-typed-default",
            "defaultCandidateIds": [pool_timeout_ids[2]],
            "candidateIds": sorted(pool_timeout_contract_ids),
            "consumerEvidence": [f"{PERSISTENCE_SHARED_DATASOURCE_PATH.as_posix()}#open"],
            "sourceSemantics": "Property overrides environment; blank delegates to 10 seconds; at least 250 ms and strictly below the resolved statement timeout; the resolved duration configures the pool.",
        },
        {
            "setting": "execution.worker-id",
            "owner": f"{PERSISTENCE_OWNERSHIP_CONFIGURATION_PATH.as_posix()}#ExecutionOwnershipConfiguration",
            "field": "replicaName", "bindings": ["ravenroot.execution.worker-id", "RAVENROOT_WORKER_ID"],
            "defaultExpression": "usable host name or UNRESOLVED_REPLICA_NAME",
            "defaultKind": "dynamic-host-fallback",
            "defaultCandidateIds": [worker_ids[4]], "candidateIds": sorted(worker_ids),
            "consumerEvidence": [
                f"{PERSISTENCE_SERVER_MAIN_PATH.as_posix()}#run",
                f"{PERSISTENCE_OWNERSHIP_CONFIGURATION_PATH.as_posix()}#runtimeOwnership",
                f"{PERSISTENCE_OWNERSHIP_CONFIGURATION_PATH.as_posix()}#recoveryIdentity",
            ],
            "sourceSemantics": "Property overrides environment; blank delegates to a validated host name or unnamed-replica; runtime and recovery identities have distinct roles.",
        },
        {
            "setting": "execution.lease-ttl",
            "owner": f"{PERSISTENCE_OWNERSHIP_CONFIGURATION_PATH.as_posix()}#ExecutionOwnershipConfiguration",
            "field": "leaseTtl", "bindings": ["ravenroot.execution.lease-ttl-seconds",
                                                "RAVENROOT_EXECUTION_LEASE_TTL_SECONDS"],
            "defaultExpression": "ExecutionOwnership.DEFAULT_LEASE_TTL (Duration.ofSeconds(30))",
            "defaultKind": "static-typed-default",
            "defaultCandidateIds": [lease_ids[0]], "candidateIds": sorted(lease_ids),
            "consumerEvidence": [
                f"{PERSISTENCE_SERVER_MAIN_PATH.as_posix()}#run",
                f"{PERSISTENCE_OWNERSHIP_CONFIGURATION_PATH.as_posix()}#requireCompatible",
            ],
            "sourceSemantics": "Property overrides environment; blank delegates to 30 seconds; positive whole seconds, above store skew and no greater than store max lease; used by runtime and recovery claims.",
        },
    ))
    audit_ids = exact_ids((
        (PERSISTENCE_AUDIT_DIRECTORY_PATH, "environment-binding",
         "RAVENROOT_AUDIT_DIR", "RAVENROOT_AUDIT_DIR"),
        (PERSISTENCE_AUDIT_DIRECTORY_PATH, "fixed-declaration",
         '"RAVENROOT_AUDIT_DIR"', "ENVIRONMENT_VARIABLE"),
        (PERSISTENCE_AUDIT_DIRECTORY_PATH, "fixed-declaration",
         '"./data/audit"', "DEFAULT_DIRECTORY"),
    ))
    execution_directory_ids = exact_ids((
        (PERSISTENCE_BACKUP_CONFIGURATION_PATH, "environment-binding",
         "RAVENROOT_EXECUTION_STORE_DIR", "RAVENROOT_EXECUTION_STORE_DIR"),
        (PERSISTENCE_BACKUP_CONFIGURATION_PATH, "fixed-declaration",
         '"RAVENROOT_EXECUTION_STORE_DIR"', "EXECUTION_STORE_DIR_VARIABLE"),
        (PERSISTENCE_STORE_CONFIGURATION_PATH, "environment-binding",
         "RAVENROOT_EXECUTION_STORE_DIR", "RAVENROOT_EXECUTION_STORE_DIR"),
        (PERSISTENCE_STORE_CONFIGURATION_PATH, "operational-declaration",
         '"RAVENROOT_EXECUTION_STORE_DIR"', "DIRECTORY_VARIABLE"),
        (PERSISTENCE_SQLITE_LOCATION_PATH, "fixed-declaration",
         '"./data/execution-store"', "DEFAULT_DIRECTORY"),
    ))
    enabled_ids = exact_ids((
        (PERSISTENCE_STORE_CONFIGURATION_PATH, "environment-binding",
         "RAVENROOT_EXECUTION_STORE_ENABLED", "RAVENROOT_EXECUTION_STORE_ENABLED"),
        (PERSISTENCE_STORE_CONFIGURATION_PATH, "fixed-declaration",
         '"true"', "DEFAULT_ENABLED_VALUE"),
    ))
    if audit_ids is None or execution_directory_ids is None or enabled_ids is None:
        return None
    contracts.extend((
        {
            "setting": "deployment.audit-directory",
            "owner": f"{PERSISTENCE_AUDIT_DIRECTORY_PATH.as_posix()}#AuditTrailDirectory",
            "field": "path", "bindings": ["RAVENROOT_AUDIT_DIR"],
            "defaultExpression": '"./data/audit"', "defaultKind": "static-typed-default",
            "defaultCandidateIds": [audit_ids[2]], "candidateIds": sorted(audit_ids),
            "consumerEvidence": [
                f"{PERSISTENCE_AUDIT_CONFIGURATION_PATH.as_posix()}#fromEnvironment",
                f"{PERSISTENCE_SERVER_MAIN_PATH.as_posix()}#run",
                f"{PERSISTENCE_BACKUP_CONFIGURATION_PATH.as_posix()}#fromEnvironment",
            ],
            "sourceSemantics": "Server and offline CLI read the same environment variable; absent or blank delegates to ./data/audit, nonblank is trimmed, malformed paths are refused without disclosure, and server retention remains 24 hours.",
        },
        {
            "setting": "execution.store.directory",
            "owner": f"{PERSISTENCE_STORE_CONFIGURATION_PATH.as_posix()}#SingleHost",
            "field": "location", "bindings": ["RAVENROOT_EXECUTION_STORE_DIR"],
            "defaultExpression": '"./data/execution-store"',
            "defaultKind": "static-typed-default",
            "defaultCandidateIds": [execution_directory_ids[4]],
            "candidateIds": sorted(execution_directory_ids),
            "consumerEvidence": [
                f"{PERSISTENCE_STORE_CONFIGURATION_PATH.as_posix()}#singleHostLocation",
                f"{PERSISTENCE_BACKUP_CONFIGURATION_PATH.as_posix()}#fromEnvironment",
                f"{PERSISTENCE_SQLITE_LOCATION_PATH.as_posix()}#underConfiguredDirectory",
            ],
            "sourceSemantics": "Server and offline CLI share one SQLite directory default and resolver; absent or blank delegates, nonblank is trimmed, malformed paths are refused without disclosure, and underDirectory supplies the fixed database filename.",
        },
        {
            "setting": "execution.store.enabled",
            "owner": f"{PERSISTENCE_STORE_CONFIGURATION_PATH.as_posix()}#ExecutionStoreConfiguration",
            "field": "enabled", "bindings": ["RAVENROOT_EXECUTION_STORE_ENABLED"],
            "defaultExpression": 'DEFAULT_ENABLED_VALUE ("true")',
            "defaultKind": "closed-variant-fallback", "defaultCandidateIds": [enabled_ids[1]],
            "candidateIds": enabled_ids,
            "consumerEvidence": [f"{PERSISTENCE_STORE_CONFIGURATION_PATH.as_posix()}#fromSources"],
            "sourceSemantics": "Absent or blank and canonical true enable the selected store; false, off, 0 and no select Disabled with the same maintenance directory; every other value is refused before composition.",
        },
    ))
    # Each relation below is extracted from an executable method or direct field assignment.  Whole
    # file digests are retained as provenance, but cannot by themselves approve a changed consumer.
    selected_body = normalized(strip_c_comments(
        resolver[slice(*java_method_span(resolver, "PostgresStoreConfiguration", "selected"))])) \
        if java_method_span(resolver, "PostgresStoreConfiguration", "selected") is not None else ""
    encode_span = java_method_span(operational_policy, "ResolvedOperationalPolicy", "encodeForManifest")
    decode_span = java_method_span(operational_policy, "ResolvedOperationalPolicy", "decodeForManifest")
    encode_body = normalized(strip_c_comments(operational_policy[slice(*encode_span)])) \
        if encode_span is not None else ""
    decode_body = normalized(strip_c_comments(operational_policy[slice(*decode_span)])) \
        if decode_span is not None else ""
    authority_from_span = java_method_span(
        persistence_authority, "ExecutionPersistenceAuthority", "from")
    authority_from_body = normalized(strip_c_comments(
        persistence_authority[slice(*authority_from_span)])) \
        if authority_from_span is not None else ""
    policy_for_nodes_span = java_method_span(
        manifest_resolver, "ExecutionManifestResolver", "operationalPolicyForNodes")
    policy_for_nodes_body = normalized(strip_c_comments(
        manifest_resolver[slice(*policy_for_nodes_span)])) \
        if policy_for_nodes_span is not None else ""
    manifest_for_resolved_span = java_method_span(
        manifest_resolver, "ExecutionManifestResolver", "manifestForResolved")
    manifest_for_resolved_body = normalized(strip_c_comments(
        manifest_resolver[slice(*manifest_for_resolved_span)])) \
        if manifest_for_resolved_span is not None else ""
    operational_components = java_record_components(
        operational_policy, "ResolvedOperationalPolicy")
    persistence_argument = java_constructor_component_call(
        manifest_resolver, "ExecutionManifestResolver", "operationalPolicyFor",
        "ResolvedOperationalPolicy", operational_components, "persistence")
    digest_conditions = java_method_if_conditions(
        manifest_digest, "ExecutionManifestDigest", "of")
    store_selection_conditions = java_method_if_conditions(
        store_configuration, "ExecutionStoreConfiguration", "fromSources")
    postgres_only_span = java_method_span(
        store_configuration, "ExecutionStoreConfiguration", "postgresqlOnlyPolicyConfigured")
    postgres_only_body = normalized(strip_c_comments(store_configuration[slice(*postgres_only_span)])) \
        if postgres_only_span is not None else ""
    expected_managed_authority_conditions = (
        "!rows.next()",
        "!authority.manifestDigest().value().equals(rows.getString(1)) || "
        "(rows.getInt(2) != ai.ravenroot.api.persistence.ExecutionManifest.FORMAT_VERSION_3 && "
        "rows.getInt(2) != ai.ravenroot.api.persistence.ExecutionManifest.FORMAT_VERSION_4)",
        "pinned != authority.maximumPayloadBytes() || pinned != config.maxPayloadBytes()",
    )
    expected_selected = normalized("""selected(Map<String, String> properties,
            Map<String, String> environment, String property, String variable) {
        String raw = properties.get(property);
        if (!nonblank(raw)) raw = environment.get(variable);
        return nonblank(raw) ? raw.trim() : null;
    }""")
    expected_postgres_only_body = normalized("""postgresqlOnlyPolicyConfigured(Map<String, String> properties,
            Map<String, String> environment) {
        return PostgresStoreConfiguration.anyConfigured(properties, environment)
                || isConfigured(properties, POOL_SIZE_PROPERTY)
                || isConfigured(properties, POOL_TIMEOUT_PROPERTY);
        }""")
    audit_environment = java_static_final_initializer(
        audit_directory, "AuditTrailDirectory", "ENVIRONMENT_VARIABLE")
    audit_default = java_static_final_initializer(
        audit_directory, "AuditTrailDirectory", "DEFAULT_DIRECTORY")
    audit_configuration_variable = java_static_final_initializer(
        audit_configuration, "AuditTrailConfiguration", "DIRECTORY_VARIABLE")
    backup_audit_variable = java_static_final_initializer(
        backup_configuration, "BackupRestoreConfiguration", "AUDIT_DIR_VARIABLE")
    backup_store_variable = java_static_final_initializer(
        backup_configuration, "BackupRestoreConfiguration", "EXECUTION_STORE_DIR_VARIABLE")
    sqlite_directory_default = java_static_final_initializer(
        sqlite_location, "SqliteStoreLocation", "DEFAULT_DIRECTORY")
    sqlite_file_name = java_static_final_initializer(
        sqlite_location, "SqliteStoreLocation", "DEFAULT_FILE_NAME")
    configuration_constants_exact = all(len(re.findall(
        rf'\bString\s+{field}\s*=\s*{re.escape(value)}\s*;', configuration_type)) == 1
        for field, value in (
            ("ENABLED_VARIABLE", '"RAVENROOT_EXECUTION_STORE_ENABLED"'),
            ("DIRECTORY_VARIABLE", '"RAVENROOT_EXECUTION_STORE_DIR"'),
            ("DEFAULT_DIRECTORY", "SqliteStoreLocation.DEFAULT_DIRECTORY"),
        ))
    if selected_body != expected_selected \
            or postgres_only_body != expected_postgres_only_body \
            or java_record_components(audit_directory, "AuditTrailDirectory") != ("path",) \
            or java_record_components(audit_configuration, "AuditTrailConfiguration") != ("directory",) \
            or audit_environment is None or normalized(audit_environment[0]) != '"RAVENROOT_AUDIT_DIR"' \
            or audit_default is None or normalized(audit_default[0]) != '"./data/audit"' \
            or audit_configuration_variable is None \
            or normalized(audit_configuration_variable[0]) != \
                "AuditTrailDirectory.ENVIRONMENT_VARIABLE" \
            or backup_audit_variable is None or normalized(backup_audit_variable[0]) != \
                "AuditTrailDirectory.ENVIRONMENT_VARIABLE" \
            or backup_store_variable is None or normalized(backup_store_variable[0]) != \
                '"RAVENROOT_EXECUTION_STORE_DIR"' \
            or sqlite_directory_default is None \
            or normalized(sqlite_directory_default[0]) != '"./data/execution-store"' \
            or sqlite_file_name is None \
            or normalized(sqlite_file_name[0]) != '"ravenroot-execution-store.db"' \
            or not configuration_constants_exact \
            or store_selection_conditions is None \
            or normalized("!POSTGRESQL_SELECTOR.equals(selector) && "
                          "postgresqlOnlyPolicyConfigured(properties, environment)") \
                not in store_selection_conditions \
            or java_invocation_arguments(server_main, "RavenrootServerMain", "run",
                                         "ai.ravenroot.server.persistence.ManagedExecutionStore.protect") != (
                "executionStoreOwner.store()", "executionStoreOwner.executionManifestStore()") \
            or java_invocation_arguments(server_main, "RavenrootServerMain", "run",
                                         "AuditTrailConfiguration.fromEnvironment") != ("System.getenv()",) \
            or java_invocation_arguments(server_main, "RavenrootServerMain", "run",
                                         "new FileAuditTrail") != (
                "auditDirectory.path()", "java.time.Clock.systemUTC()", "Duration.ofHours(24)") \
            or java_invocation_arguments(audit_configuration, "AuditTrailConfiguration",
                                         "fromEnvironment", "AuditTrailDirectory.resolve") != (
                "environment.get(DIRECTORY_VARIABLE)",) \
            or java_invocation_arguments(backup_configuration, "BackupRestoreConfiguration",
                                         "fromEnvironment", "AuditTrailDirectory.resolve") != (
                "environment.get(AUDIT_DIR_VARIABLE)",) \
            or java_invocation_arguments(backup_configuration, "BackupRestoreConfiguration",
                                         "fromEnvironment",
                                         "SqliteStoreLocation.underConfiguredDirectory") != (
                "environment.get(EXECUTION_STORE_DIR_VARIABLE)",) \
            or java_invocation_arguments(store_configuration, "ExecutionStoreConfiguration",
                                         "singleHostLocation",
                                         "SqliteStoreLocation.underConfiguredDirectory") != (
                "environment.get(DIRECTORY_VARIABLE)",) \
            or java_invocation_arguments(bootstrap, "ExecutionStoreBootstrap", "openShared",
                                         "new PostgresExecutionStore") != (
                "pool.dataSource()", "clock", "storeConfig", "humanTaskPolicy") \
            or java_invocation_arguments(bootstrap, "ExecutionStoreBootstrap", "openShared",
                                         "new PostgresGraphDefinitionStore") != (
                "pool.dataSource()", "clock",
                "ai.ravenroot.api.persistence.GraphDefinitionReferences.NONE",
                "graphMlLimits.maxBytes()", "storeConfig") \
            or java_invocation_arguments(bootstrap, "ExecutionStoreBootstrap", "openShared",
                                         "new PostgresExecutionManifestStore") != (
                "pool.dataSource()", "clock",
                "ai.ravenroot.api.persistence.ExecutionManifestReferences.NONE",
                "configuration.manifestPinAttempts()", "storeConfig") \
            or java_invocation_arguments(sqlite_artifact, "SqliteArtifactRegistry", "prepare",
                                         "connectionPolicy.apply") != ("connection",) \
            or java_invocation_arguments(sqlite_embed, "SqliteEmbedRegistrationStore", "prepare",
                                         "connectionPolicy.apply") != ("opened",) \
            or java_type_assignment_expressions(sqlite_registry, "SqliteDeploymentRegistry",
                                                "commandRetention") != ("policy.commandRetention()",) \
            or java_type_assignment_expressions(sqlite_registry, "SqliteDeploymentRegistry",
                                                "limits") != ("policy.limits()",) \
            or java_type_assignment_expressions(postgres_registry, "PostgresDeploymentRegistry",
                                                "commandRetention") != ("policy.commandRetention()",) \
            or java_type_assignment_expressions(postgres_registry, "PostgresDeploymentRegistry",
                                                "limits") != ("policy.limits()",) \
            or java_type_assignment_expressions(in_memory_registry, "InMemoryDeploymentRegistry",
                                                "limits") != ("Objects.requireNonNull(limits, \"limits\")",) \
            or java_constructor_delegation_arguments(
                in_memory_registry, "InMemoryDeploymentRegistry", ("clock",)) != (
                    "clock", "tenant -> DeploymentId.of(UUID.randomUUID().toString())",
                    "DeploymentRegistryPolicy.inMemoryLimits()") \
            or java_constructor_delegation_arguments(
                in_memory_registry, "InMemoryDeploymentRegistry", ("clock", "ids")) != (
                    "clock", "ids", "DeploymentRegistryPolicy.inMemoryLimits()") \
            or java_constructor_delegation_arguments(
                sqlite_registry, "SqliteDeploymentRegistry", ("databaseFile", "clock", "ids")) != (
                    "SqliteStoreLocation.ofFile(databaseFile)", "clock", "ids",
                    "DeploymentRegistryPolicy.DEFAULTS") \
            or java_constructor_delegation_arguments(
                postgres_registry, "PostgresDeploymentRegistry", ("dataSource", "clock", "ids")) != (
                    "dataSource", "clock", "ids", "DeploymentRegistryPolicy.DEFAULTS",
                    "PostgresStoreConfig.defaults()") \
            or java_direct_return_expression(in_memory_registry, "InMemoryDeploymentRegistry", "limits") != "limits" \
            or java_direct_return_expression(sqlite_registry, "SqliteDeploymentRegistry", "limits") != "limits" \
            or java_direct_return_expression(postgres_registry, "PostgresDeploymentRegistry", "limits") != "limits" \
            or java_invocation_arguments(sqlite_execution, "SqliteExecutionStore", "applyManaged",
                                         "applyInternal") != ("batch", "authority") \
            or java_invocation_arguments(postgres_execution, "PostgresExecutionStore", "applyManaged",
                                         "applyInternal") != ("batch", "authority") \
            or java_invocation_arguments(sqlite_execution, "SqliteExecutionStore", "applyLocked",
                                         "requireManagedAuthority") != ("key", "authority") \
            or java_invocation_arguments(postgres_execution, "PostgresExecutionStore", "applyLocked",
                                         "requireManagedAuthority") != ("connection", "key", "authority") \
            or java_invocation_arguments(sqlite_execution, "SqliteExecutionStore", "claimManaged",
                                         "requireManagedAuthority") != ("key", "authority") \
            or java_invocation_arguments(postgres_execution, "PostgresExecutionStore", "claimManaged",
                                         "requireManagedAuthority") != ("connection", "key", "authority") \
            or java_method_if_conditions(
                sqlite_execution, "SqliteExecutionStore", "requireManagedAuthority") \
                != expected_managed_authority_conditions \
            or java_method_if_conditions(
                postgres_execution, "PostgresExecutionStore", "requireManagedAuthority") \
                != expected_managed_authority_conditions \
            or java_invocation_arguments(
                sqlite_execution, "SqliteExecutionStore", "requireManagedAuthority",
                "decodeForManifest") != ("rows.getString(3)", "rows.getInt(2)") \
            or java_invocation_arguments(
                postgres_execution, "PostgresExecutionStore", "requireManagedAuthority",
                "decodeForManifest") != ("rows.getString(3)", "rows.getInt(2)") \
            or java_invocation_arguments(managed, "ManagedExecutionStore", "apply",
                                         "delegate.applyManaged") != ("batch", "authority") \
            or java_invocation_arguments(managed, "ManagedExecutionStore", "collectAuthorities",
                                         "delegate.managedClaimCandidates") != (
                "tenantId", "workerId", "pageSize", "ttl", "timersOnly", "scan.nextAfter()") \
            or java_invocation_arguments(default_application, "DefaultRavenrootApplication",
                                         "executionManifests",
                                         "ai.ravenroot.core.manifest.ExecutionManifestResolver.completeManaged") != (
                "engine", "executionStore.capabilities()", "resultPayloadBytes",
                "executionStore.maxPayloadBytes()", "behaviors", "unknownBehaviors",
                "graphExecutionLimits", "programRuntime") \
            or java_invocation_arguments(default_application, "DefaultRavenrootApplication",
                                         "executionManifests",
                                         "executionStore.protectsManagedPersistence") != () \
            or java_invocation_arguments(manifest_digest, "ExecutionManifestDigest", "of",
                                         "manifest.operationalPolicy().encodeForManifest") != (
                "manifest.formatVersion()",) \
            or digest_conditions is None \
            or normalized("manifest.formatVersion() == ExecutionManifest.FORMAT_VERSION_2 || "
                          "manifest.formatVersion() == ExecutionManifest.FORMAT_VERSION_3 || "
                          "manifest.formatVersion() == ExecutionManifest.FORMAT_VERSION_4") \
                not in digest_conditions \
            or authority_from_body != normalized("""from(StoredExecutionManifest stored) {
                Objects.requireNonNull(stored, "stored");
                ExecutionManifest manifest = stored.manifest();
                if ((manifest.formatVersion() != ExecutionManifest.FORMAT_VERSION_3
                        && manifest.formatVersion() != ExecutionManifest.FORMAT_VERSION_4)
                        || manifest.operationalPolicy() == null
                        || manifest.operationalPolicy().persistence().isEmpty()) {
                    throw new IllegalArgumentException("execution manifest has no generic persistence capacity");
                }
                return new ExecutionPersistenceAuthority(stored.digest(), manifest.operationalPolicy()
                        .persistence().orElseThrow().maximumPayloadBytes());
            }""") \
            or policy_for_nodes_body != normalized("""operationalPolicyForNodes(Collection<GraphNode> nodes) {
                Objects.requireNonNull(nodes, "nodes");
                var behaviorNames = nodes.stream().filter(node -> node.behavior() != null)
                        .map(GraphNode::behavior).collect(java.util.stream.Collectors.toSet());
                ResolvedOperationalPolicy base = operationalPolicyFor(behaviorNames);
                return new ResolvedOperationalPolicy(base.graph(), base.results(), base.builtInHttp(),
                        base.nodePackages(), base.persistence(),
                        behaviors.nodeExternalIoCapacitiesFor(nodes));
            }""") \
            or manifest_for_resolved_body != normalized("""manifestForResolved(ExecutionKey key,
                    GraphContentId graphContentId, GraphDefinitionIdentity graphIdentity,
                    ExecutionPolicy policy, Instant pinnedAt, ResolvedOperationalPolicy operational) {
                Objects.requireNonNull(policy, "policy");
                Objects.requireNonNull(operational, "operational");
                var runtime = runtime(policy, executionLimitsDigestOf(operational.graph()));
                List<PinnedNodePackage> packages = operational.nodePackages().stream()
                        .map(entry -> behaviors.nodePackageBinding(entry.packageId()).orElseThrow().identity())
                        .toList();
                return new ExecutionManifest(ExecutionManifest.FORMAT_VERSION_4, key, graphContentId,
                        graphIdentity, runtime, packages, pinnedAt, operational);
            }""") \
            or persistence_argument is None \
            or normalized(persistence_argument[0]) != normalized(
                "java.util.Optional.ofNullable(maximumPersistencePayloadBytes)"
                " .map(ResolvedOperationalPolicy.PersistenceLimits::new)") \
            or encode_body != normalized("""encodeForManifest(int manifestFormatVersion) {
                if (manifestFormatVersion == ExecutionManifest.FORMAT_VERSION_2
                        && persistence.isEmpty() && nodeExternalIo.isEmpty()
                        && hasLegacyDecompressionAuthority()) {
                    return encodeVersion(ENCODING_VERSION_1);
                }
                if (manifestFormatVersion == ExecutionManifest.FORMAT_VERSION_3
                        && persistence.isPresent() && hasLegacyDecompressionAuthority()) {
                    if (!nodeExternalIo.isEmpty()) {
                        throw new IllegalArgumentException("manifest format 3 cannot carry new external-I/O capacity");
                    }
                    return encodeVersion(ENCODING_VERSION_2);
                }
                if (manifestFormatVersion == ExecutionManifest.FORMAT_VERSION_4
                        && hasCompleteDecompressionAuthority()) {
                    return encodeVersion(ENCODING_VERSION_3);
                }
                throw new IllegalArgumentException("operational policy does not match manifest format");
            }""") \
            or decode_body != normalized("""decodeForManifest(String encoded, int manifestFormatVersion) {
                return decodeVersion(encoded, switch (manifestFormatVersion) {
                    case ExecutionManifest.FORMAT_VERSION_2 -> ENCODING_VERSION_1;
                    case ExecutionManifest.FORMAT_VERSION_3 -> ENCODING_VERSION_2;
                    case ExecutionManifest.FORMAT_VERSION_4 -> ENCODING_VERSION_3;
                    default -> throw new IllegalArgumentException("manifest format has no operational policy");
                });
            }""") \
            or java_static_final_initializer(manifest, "ExecutionManifest", "CURRENT_FORMAT_VERSION") is None \
            or normalized(java_static_final_initializer(
                manifest, "ExecutionManifest", "CURRENT_FORMAT_VERSION")[0]) != "FORMAT_VERSION_4" \
            or any(java_static_final_initializer(manifest, "ExecutionManifest", field) is None
                   or normalized(java_static_final_initializer(
                       manifest, "ExecutionManifest", field)[0]) != value
                   for field, value in (("FORMAT_VERSION_1", "1"), ("FORMAT_VERSION_2", "2"),
                                        ("FORMAT_VERSION_3", "3"), ("FORMAT_VERSION_4", "4"))):
        return None
    candidate_ids = sorted(identifier for contract in contracts for identifier in contract["candidateIds"])
    if len(candidate_ids) != len(set(candidate_ids)):
        return None
    test_methods = (
        (PERSISTENCE_STORE_CONFIGURATION_TEST_PATH, "ExecutionStoreConfigurationTest",
         "postgresqlPolicyUsesPropertiesBeforeEnvironmentAndOneResolvedStatementBound"),
        (PERSISTENCE_STORE_CONFIGURATION_TEST_PATH, "ExecutionStoreConfigurationTest",
         "everyPostgresqlPolicyFieldIsResolvedOnceFromTheDocumentedPropertyFamily"),
        (PERSISTENCE_STORE_CONFIGURATION_TEST_PATH, "ExecutionStoreConfigurationTest",
         "poolPropertiesArePostgresqlOnlyWhileBlankValuesDelegate"),
        (PERSISTENCE_STORE_CONFIGURATION_TEST_PATH, "ExecutionStoreConfigurationTest",
         "anExplicitSqliteSelectorIsTheSameAsNoSelector"),
        (PERSISTENCE_STORE_CONFIGURATION_TEST_PATH, "ExecutionStoreConfigurationTest",
         "anUnknownSelectorFailsClosedWithoutEchoingIt"),
        (PERSISTENCE_STORE_CONFIGURATION_TEST_PATH, "ExecutionStoreConfigurationTest",
         "theSharedStoreRequiresAUrlAndRefusesAnotherDriversUrl"),
        (PERSISTENCE_STORE_CONFIGURATION_TEST_PATH, "ExecutionStoreConfigurationTest",
         "poolSettingsDefaultAndAreBoundedOnBothSides"),
        (PERSISTENCE_STORE_CONFIGURATION_TEST_PATH, "ExecutionStoreConfigurationTest",
         "theConnectionNeverRendersItsUrlOrPassword"),
        (PERSISTENCE_STORE_CONFIGURATION_TEST_PATH, "ExecutionStoreConfigurationTest",
         "aPasswordIsNotTrimmedBecauseItIsOpaque"),
        (PERSISTENCE_OWNERSHIP_CONFIGURATION_TEST_PATH, "ExecutionOwnershipConfigurationTest",
         "theTtlDefaultsToTheValueThatUsedToBeHardCoded"),
        (PERSISTENCE_OWNERSHIP_CONFIGURATION_TEST_PATH, "ExecutionOwnershipConfigurationTest",
         "ownershipPropertiesOverrideEnvironmentAndBlankPropertiesDelegate"),
        (PERSISTENCE_OWNERSHIP_CONFIGURATION_TEST_PATH, "ExecutionOwnershipConfigurationTest",
         "aMalformedTtlFailsClosedRatherThanFallingBackToTheDefault"),
        (PERSISTENCE_OWNERSHIP_CONFIGURATION_TEST_PATH, "ExecutionOwnershipConfigurationTest",
         "theConfiguredNameIsUsedForBothRolesAndTheRolesStayDistinct"),
        (PERSISTENCE_OWNERSHIP_CONFIGURATION_TEST_PATH, "ExecutionOwnershipConfigurationTest",
         "anUnsetNameStillProducesAUsableIdentity"),
        (PERSISTENCE_OWNERSHIP_CONFIGURATION_TEST_PATH, "ExecutionOwnershipConfigurationTest",
         "aMalformedNameIsRefusedAgainstTheVariableTheOperatorSet"),
        (PERSISTENCE_OWNERSHIP_CONFIGURATION_TEST_PATH, "ExecutionOwnershipConfigurationTest",
         "theTtlIsCheckedAgainstTheComposedStoresOwnPublishedBounds"),
        (PERSISTENCE_CLI_SELECTOR_TEST_PATH, "SharedStoreBundleRefusalTest",
         "theSelectorSpellingMatchesTheServersOwn"),
        (PERSISTENCE_CLI_SELECTOR_TEST_PATH, "SharedStoreBundleRefusalTest",
         "selectorPropertyOverridesEnvironmentForBundleRefusal"),
        (PERSISTENCE_CLI_SELECTOR_TEST_PATH, "SharedStoreBundleRefusalTest",
         "backupAndRestoreAreRefusedUnderTheSharedStore"),
        (PERSISTENCE_AUDIT_DIRECTORY_TEST_PATH, "AuditTrailDirectoryTest",
         "absentAndBlankValuesResolveToTheSoleDefault"),
        (PERSISTENCE_AUDIT_DIRECTORY_TEST_PATH, "AuditTrailDirectoryTest",
         "aConfiguredPathIsTrimmedOnceBeforeParsing"),
        (PERSISTENCE_AUDIT_DIRECTORY_TEST_PATH, "AuditTrailDirectoryTest",
         "anInvalidPathIsRefusedWithoutRepeatingIt"),
        (PERSISTENCE_AUDIT_CONFIGURATION_TEST_PATH, "AuditTrailConfigurationTest",
         "serverCompositionUsesTheSharedDefaultBlankAndTrimRules"),
        (PERSISTENCE_AUDIT_CONFIGURATION_TEST_PATH, "AuditTrailConfigurationTest",
         "malformedServerAuditDirectoryIsRefusedBeforeOpeningTheTrail"),
        (PERSISTENCE_DIRECTORY_PARITY_TEST_PATH, "BackupRestoreDirectoryParityTest",
         "auditDirectoryDefaultBlankAndNondefaultResolutionMatchesTheServer"),
        (PERSISTENCE_DIRECTORY_PARITY_TEST_PATH, "BackupRestoreDirectoryParityTest",
         "executionStoreDirectoryDefaultBlankAndNondefaultResolutionMatchesTheServer"),
        (PERSISTENCE_DIRECTORY_PARITY_TEST_PATH, "BackupRestoreDirectoryParityTest",
         "bothCompositionRootsRefuseTheSameMalformedPaths"),
        (PERSISTENCE_SQLITE_LOCATION_TEST_PATH, "SqliteBackupRestoreTest",
         "configuredDirectoriesShareOneDefaultAndBlankTrimRule"),
        (PERSISTENCE_STORE_CONFIGURATION_TEST_PATH, "ExecutionStoreConfigurationTest",
         "absentBlankAndPaddedDirectoriesUseTheSharedSqliteRule"),
        (PERSISTENCE_STORE_CONFIGURATION_TEST_PATH, "ExecutionStoreConfigurationTest",
         "acceptsOnlyTheCanonicalPositiveAndDocumentedNegativeAliases"),
        (PERSISTENCE_STORE_CONFIGURATION_TEST_PATH, "ExecutionStoreConfigurationTest",
         "malformedEnabledValueIsRejectedWithoutEchoOrCause"),
        (PERSISTENCE_STORE_CONFIGURATION_TEST_PATH, "ExecutionStoreConfigurationTest",
         "disabledKeepsTheConfiguredDirectoryAsTheMaintenanceAuthority"),
        (PERSISTENCE_STORE_CONFIGURATION_TEST_PATH, "ExecutionStoreConfigurationTest",
         "malformedDirectoryIsRejectedWithoutRepeatingTheEnvironmentValue"),
        (PERSISTENCE_MANAGED_STORE_TEST_PATH, "ManagedExecutionStoreTest",
         "matchingReplayAuthorityReachesAdapterBeforeLiveCapacityComparison"),
        (PERSISTENCE_MANAGED_STORE_TEST_PATH, "ManagedExecutionStoreTest",
         "boundedSweepsAdvancePastEightIncompatiblePages"),
        (PERSISTENCE_MANAGED_STORE_TEST_PATH, "ManagedExecutionStoreTest",
         "everyExecutionStoreMethodHasAnExplicitManagedRoute"),
        (Path("ravenroot/ravenroot-application-api/src/test/java/ai/ravenroot/api/deployment/registry/DeploymentRegistryPolicyTest.java"),
         "DeploymentRegistryPolicyTest", "defaultsAreOneTypedDurableRegistryDecision"),
        (Path("ravenroot/ravenroot-core/src/test/java/ai/ravenroot/core/persistence/InMemoryExecutionStorePolicyTest.java"),
         "InMemoryExecutionStorePolicyTest", "explicitPolicyControlsTheReferenceStoreWithoutClaimingManagedPersistence"),
        (Path("ravenroot/ravenroot-persistence-sqlite/src/test/java/ai/ravenroot/persistence/sqlite/SqliteConnectionPolicyTest.java"),
         "SqliteConnectionPolicyTest", "oneTypedPolicyControlsArtifactAndEmbedConnectionWaits"),
        (Path("ravenroot/ravenroot-persistence-testkit/src/main/java/ai/ravenroot/testkit/persistence/ManagedExecutionStoreContract.java"),
         "ManagedExecutionStoreContract", "fencingThenMatchingReplayPrecedeAChangedLiveCapacityCheck"),
        (Path("ravenroot/ravenroot-persistence-testkit/src/main/java/ai/ravenroot/testkit/persistence/ManagedExecutionStoreContract.java"),
         "ManagedExecutionStoreContract", "individualManagedClaimsRefuseMissingStaleAndLegacyAuthorityWithoutLeasing"),
        (Path("ravenroot/ravenroot-persistence-testkit/src/main/java/ai/ravenroot/testkit/persistence/ManagedExecutionStoreContract.java"),
         "ManagedExecutionStoreContract", "individualManagedClaimRefusesReopenedCapacityDriftWithoutLeasing"),
        (Path("ravenroot/ravenroot-persistence-testkit/src/main/java/ai/ravenroot/testkit/persistence/ManagedExecutionStoreContract.java"),
         "ManagedExecutionStoreContract", "restrictedPendingWorkClaimsAreAtomicAndExcludeUnverifiedNewKeys"),
        (Path("ravenroot/ravenroot-persistence-testkit/src/main/java/ai/ravenroot/testkit/persistence/ManagedExecutionStoreContract.java"),
         "ManagedExecutionStoreContract", "restrictedDueTimerClaimsAreAtomicAndExcludeUnverifiedNewKeys"),
        (Path("ravenroot/ravenroot-persistence-sqlite/src/test/java/ai/ravenroot/persistence/sqlite/SqliteManagedExecutionStoreContractTest.java"),
         "SqliteManagedExecutionStoreContractTest", "processCreationAndOrphanCleanupSerializeAcrossTheManifestLock"),
        (Path("ravenroot/ravenroot-persistence-sqlite/src/test/java/ai/ravenroot/persistence/sqlite/SqliteManagedExecutionStoreContractTest.java"),
         "SqliteManagedExecutionStoreContractTest", "processCreationAndOrphanPurgeSerializeAcrossTheManifestLock"),
        (Path("ravenroot/ravenroot-persistence-postgresql/src/test/java/ai/ravenroot/persistence/postgresql/PostgresManagedExecutionStoreContractTest.java"),
         "PostgresManagedExecutionStoreContractTest", "processCreationAndOrphanCleanupSerializeAcrossTheManifestRowLock"),
        (Path("ravenroot/ravenroot-persistence-postgresql/src/test/java/ai/ravenroot/persistence/postgresql/PostgresManagedExecutionStoreContractTest.java"),
         "PostgresManagedExecutionStoreContractTest", "processCreationAndOrphanPurgeSerializeAcrossTheManifestRowLock"),
        (PERSISTENCE_OPERATIONAL_POLICY_TEST_PATH, "ResolvedOperationalPolicyTest",
         "formatFourRoundTripsExplicitPersistenceDispositionAndNodeBoundIo"),
        (PERSISTENCE_OPERATIONAL_POLICY_TEST_PATH, "ResolvedOperationalPolicyTest",
         "nodeIoStructuralBoundaryFitsTheCodecAndOneMoreIsRefused"),
        (PERSISTENCE_OPERATIONAL_POLICY_TEST_PATH, "ResolvedOperationalPolicyTest",
         "olderManifestCodecCannotDiscardANodeIoSnapshot"),
        (PERSISTENCE_MANIFEST_RESOLVER_TEST_PATH, "ExecutionManifestResolverEnginePolicyTest",
         "formatFourPinsExactNodeIoWhileOlderRowsRefuseOnlyAffectedGraphs"),
        (PERSISTENCE_APPLICATION_MANIFEST_TEST_PATH,
         "DefaultRavenrootApplicationExecutionManifestTest",
         "rawEmbeddedAdmissionAndRecoveryCarryV4NodeIoWithoutInventingPersistenceCapacity"),
        (Path("ravenroot/ravenroot-persistence-testkit/src/main/java/ai/ravenroot/testkit/persistence/ManagedExecutionStoreContract.java"),
         "ManagedExecutionStoreContract",
         "exactFormatFourAuthorityCreatesAndClaimsIndividualAndRestrictedWork"),
    )
    approved_new_test_digests = {
        "anExplicitSqliteSelectorIsTheSameAsNoSelector": "ae9e843851cccb75ebfd373f4b4fc2890f59e280fbaabcee3ccee6c78aa8aeb4",
        "anUnknownSelectorFailsClosedWithoutEchoingIt": "3c048de0c904f8f210a2e89b0c0e05f9c884e177f045df0268d7aba80e37329e",
        "theSharedStoreRequiresAUrlAndRefusesAnotherDriversUrl": "18ce8ec8f9490e849603cd0ef1bb914974a27a96d9ad4bb537541683581ba541",
        "poolSettingsDefaultAndAreBoundedOnBothSides": "1956e0e920ab339bc87c2f5066009ca91e80800ad60142ccab895b20aadad6fe",
        "theConnectionNeverRendersItsUrlOrPassword": "917716431ddb7e3291a12f59921917b224d4c22d5642547dfda6ed07cd6a32a9",
        "aPasswordIsNotTrimmedBecauseItIsOpaque": "e5bc520883ba15972c312eecfed1150202a3094b86fd37d78c0b6497aa0df345",
        "theTtlDefaultsToTheValueThatUsedToBeHardCoded": "07ad8a3b60da4835e6262575c5c48e303c7682ce61c5190f241299d89a8c7914",
        "ownershipPropertiesOverrideEnvironmentAndBlankPropertiesDelegate": "b3133e9a0c521aab63f5ccbef19974bb614cf29f7e8190528c5fdb0bbfe91517",
        "aMalformedTtlFailsClosedRatherThanFallingBackToTheDefault": "aba1426f81a999845f700f745fc52e986d8bb71587db4b6efa0596b97a887cd1",
        "theConfiguredNameIsUsedForBothRolesAndTheRolesStayDistinct": "d94ce04c84dbd4215f0a79072398ef44b4b0c093929414796021263db88aa178",
        "anUnsetNameStillProducesAUsableIdentity": "3be679136b89ee7081de5a8150c0b4ee9b3b8ef61d07ed4463e222873817790c",
        "aMalformedNameIsRefusedAgainstTheVariableTheOperatorSet": "1fe0af9b6877c12c78a7e3324a831f15c8f6b470d0ab8839c79ed17e853d1091",
        "theTtlIsCheckedAgainstTheComposedStoresOwnPublishedBounds": "81f3e4d0b75c4974ba60bad0ef528fb49436feebb926f497bece7fa84718c5e0",
        "theSelectorSpellingMatchesTheServersOwn": "caf7902ec0f9f11a8c1706b22f294aa828ea5716c6463717fed7a10a0e30165e",
        "selectorPropertyOverridesEnvironmentForBundleRefusal": "21355efcd75acec2071f4be9c8c2c65499afc6f12d987d14678efc04e03b6745",
        "backupAndRestoreAreRefusedUnderTheSharedStore": "6adff2e480e35b2fec11c2433462ea2d442e71f81e6537628912ede299e9dcd0",
        "absentAndBlankValuesResolveToTheSoleDefault": "723506176b197283fe82f3bda91fcad41dc46e68c720b240c31362d2ccbc0875",
        "aConfiguredPathIsTrimmedOnceBeforeParsing": "b01243e10c701e9d2aabe2b1f4395eb4be023e5e2d0dbc1f7a694293d7c55dea",
        "anInvalidPathIsRefusedWithoutRepeatingIt": "11a0afb17c679f68c8b79adb58372a0465c47a19d9f81a36132d407b6fb4e5ee",
        "serverCompositionUsesTheSharedDefaultBlankAndTrimRules": "8d3e9493c705f53d33dcef1789c71ff74f2a96407332f72ff966afcb374f3d04",
        "malformedServerAuditDirectoryIsRefusedBeforeOpeningTheTrail": "bc0d93dadcf86d2b7ac71f5e60e49ad14a9a27a6d5dbf4103d04ef0feeadcb02",
        "auditDirectoryDefaultBlankAndNondefaultResolutionMatchesTheServer": "9f11784459d8ee120a9599fe081391d67446c84e48060fae8b272478954e995b",
        "executionStoreDirectoryDefaultBlankAndNondefaultResolutionMatchesTheServer": "b08e6b5417fd6a9aa66c3a8c36db5513d5b87bedfa5e089b60628ebb7f65f01d",
        "bothCompositionRootsRefuseTheSameMalformedPaths": "14f23154a56a7ece244059662c587c9b4f7046e066f35d96c12df60d972f24b9",
        "configuredDirectoriesShareOneDefaultAndBlankTrimRule": "8c6e524f4734caa56fb87dea79ce57382a264dd383c67bd5d24d5e1fa5d4ab67",
        "absentBlankAndPaddedDirectoriesUseTheSharedSqliteRule": "1dd9d27888784f7258ddb392c04b08b25a6512b6a3309fe72f67f6740090478f",
        "acceptsOnlyTheCanonicalPositiveAndDocumentedNegativeAliases": "b42172c272bc28a45e40990f643b9f02f9e717334992efd5ac22dd1be9e53037",
        "malformedEnabledValueIsRejectedWithoutEchoOrCause": "29cba941a77b6eb7dc66fc8b9c78fa102dac12cc42fae36c8d0b51e9c1c2871e",
        "disabledKeepsTheConfiguredDirectoryAsTheMaintenanceAuthority": "eb981d95c3160561bd00b421ec5d6ff27aa0bfc060afb3af81bc656ac518f3f8",
        "malformedDirectoryIsRejectedWithoutRepeatingTheEnvironmentValue": "5e496c8c8a9c25e4461c54aa23fc322c4027e5aecaf7a5e540a086bf73817441",
    }
    test_evidence: list[dict[str, str]] = []
    for path, type_symbol, method in test_methods:
        source = sources[path]
        digest = java_method_digest(source, type_symbol, method)
        if digest is None or java_method_annotations(source, type_symbol, method) != ("@Test",):
            return None
        if method in approved_new_test_digests and approved_new_test_digests[method] != digest:
            return None
        test_evidence.append({"path": path.as_posix(), "type": type_symbol,
                              "method": method, "methodDigest": digest})
    return {
        "kind": "java-persistence-policy-family-v1",
        "contracts": contracts,
        "candidateIds": candidate_ids,
        "sourceDigests": [{"path": path.as_posix(), "digest": _source_digest(source)}
                          for path, source in sources.items()],
        "testEvidence": test_evidence,
    }


def persistence_policy_authority_errors(root: Path, authorities: object,
                                        entries: dict[str, dict[str, object]],
                                        discovered: dict[str, Candidate]) -> list[str]:
    """Require the complete source-derived #318 policy even when inventory markers are removed."""
    if not persistence_policy_source_present(root):
        return ([] if authorities in (None, {})
                else ["persistence policy authority exists without its typed resolver source"])
    expected = persistence_policy_authority_from_source(root, discovered)
    if expected is None:
        return ["persistence policy source family is incomplete or unsupported"]
    errors: list[str] = []
    if not isinstance(authorities, dict) or set(authorities) != {PERSISTENCE_POLICY_AUTHORITY_ID} \
            or authorities.get(PERSISTENCE_POLICY_AUTHORITY_ID) != expected:
        errors.append("persistence settings require the exact mandatory source-derived authority")
    expected_by_id = {
        str(identifier): contract for contract in expected["contracts"]
        for identifier in contract["candidateIds"]
    }
    missing = set(expected_by_id) - set(entries)
    if missing:
        errors.append("persistence authority current source candidate set is incomplete")
    reviewed_ids = {identifier for identifier in expected_by_id
                    if entries.get(identifier, {}).get("status") != "pending-review"}
    marked = {identifier: entry for identifier, entry in entries.items()
              if entry.get("persistenceAuthority") is not None}
    if set(marked) != reviewed_ids:
        errors.append("persistence authority candidate partition is missing, duplicated, or foreign")
    for identifier, contract in expected_by_id.items():
        entry = entries.get(identifier)
        if entry is None:
            continue
        if entry.get("status") == "pending-review":
            continue
        if entry.get("status") != "already-centralized" \
                or entry.get("classification") != "operator-configurable":
            errors.append(f"{identifier}: persistence authority requires one reviewed operator setting")
        if entry.get("persistenceAuthority") != PERSISTENCE_POLICY_AUTHORITY_ID \
                or entry.get("setting") != contract["setting"]:
            errors.append(f"{identifier}: persistence authority setting assignment has drifted")
        default_evidence = entry.get("defaultEvidence")
        if entry.get("owner") != contract["owner"] or entry.get("field") != contract["field"] \
                or entry.get("bindings") != contract["bindings"] \
                or not isinstance(default_evidence, list) \
                or sorted(str(item) for item in default_evidence) != contract["defaultCandidateIds"]:
            errors.append(f"{identifier}: persistence owner, binding, or default evidence has drifted")
    return errors


MANIFEST_PIN_ATTEMPTS_SETTING = "execution.manifest.pin-retries"
MANIFEST_PIN_CONFIGURATION_PATH = PERSISTENCE_STORE_CONFIGURATION_PATH
MANIFEST_PIN_BOOTSTRAP_PATH = Path(
    "ravenroot/ravenroot-server/src/main/java/ai/ravenroot/server/persistence/ExecutionStoreBootstrap.java")
MANIFEST_PIN_STORE_PATH = Path(
    "ravenroot/ravenroot-persistence-postgresql/src/main/java/ai/ravenroot/persistence/postgresql/"
    "PostgresExecutionManifestStore.java")


def manifest_pin_attempt_authorities(root: Path,
                                     discovered: dict[str, Candidate]) -> dict[str, object] | None:
    """Derive the closed nested Shared-setting authority from its exact executable source."""
    try:
        configuration = (root / MANIFEST_PIN_CONFIGURATION_PATH).read_text(encoding="utf-8")
        bootstrap = (root / MANIFEST_PIN_BOOTSTRAP_PATH).read_text(encoding="utf-8")
        store = (root / MANIFEST_PIN_STORE_PATH).read_text(encoding="utf-8")
    except OSError:
        return None
    components = ("connection", "manifestPinAttempts", "storeConfig")
    if java_record_components(configuration, "Shared") != components or not exact_import_identity(
            configuration, "ai.ravenroot.persistence.postgresql.PostgresExecutionManifestStore"):
        return None
    call = java_constructor_component_call(
        configuration, "ExecutionStoreConfiguration", "fromSources", "Shared",
        components, "manifestPinAttempts")
    expected_call = "positiveInt(environment, MANIFEST_PIN_ATTEMPTS_VARIABLE, DEFAULT_MANIFEST_PIN_ATTEMPTS)"
    helper_span = java_method_span(configuration, "ExecutionStoreConfiguration", "positiveInt")
    compact_span = java_compact_constructor_span(configuration, "Shared")
    if call is None or normalized(call[0]) != normalized(expected_call) \
            or helper_span is None or compact_span is None:
        return None
    helper_code = normalized(strip_c_comments(configuration[slice(*helper_span)]))
    compact_code = normalized(strip_c_comments(configuration[slice(*compact_span)]))
    expected_helper = normalized("""positiveInt(Map<String, String> environment, String variable, int fallback) {
        String raw = environment.get(variable);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < 1) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(variable + " must be a positive integer");
        }
    }""")
    expected_compact = normalized("""Shared {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(storeConfig, "storeConfig");
        if (manifestPinAttempts < 1) {
            throw new IllegalArgumentException("manifestPinAttempts must be positive");
        }
    }""")
    if java_method_header(configuration, "ExecutionStoreConfiguration", "fromSources") != \
            "private static ExecutionStoreConfiguration fromSources(Map<String, String> properties, Map<String, String> environment)" \
            or java_method_header(configuration, "ExecutionStoreConfiguration", "positiveInt") != \
            "private static int positiveInt(Map<String, String> environment, String variable, int fallback)" \
            or helper_code != expected_helper or compact_code != expected_compact:
        return None
    environment = "RAVENROOT_EXECUTION_MANIFEST_PIN_ATTEMPTS"
    def ids(kind: str, expression: str, role: str | None = None) -> list[str]:
        return sorted(candidate.id for candidate in discovered.values()
                      if candidate.path == MANIFEST_PIN_CONFIGURATION_PATH.as_posix()
                      and candidate.kind == kind and candidate.expression == expression
                      and (role is None or candidate.role == role))
    environment_candidates = ids("environment-binding", environment)
    declaration_candidates = ids("operational-declaration", f'"{environment}"',
                                "MANIFEST_PIN_ATTEMPTS_VARIABLE")
    terminal_candidates = sorted(
        candidate.id for candidate in discovered.values()
        if candidate.path == MANIFEST_PIN_STORE_PATH.as_posix()
        and candidate.kind == "fixed-declaration"
        and candidate.role == "DEFAULT_MAX_PIN_ATTEMPTS" and candidate.expression == "3")
    terminal = java_static_final_initializer(store, "PostgresExecutionManifestStore",
                                             "DEFAULT_MAX_PIN_ATTEMPTS")
    alias = re.findall(r"\bint\s+DEFAULT_MANIFEST_PIN_ATTEMPTS\s*=\s*"
                       r"PostgresExecutionManifestStore\.DEFAULT_MAX_PIN_ATTEMPTS\s*;",
                       strip_c_comments(configuration))
    if any(len(group) != 1 for group in (environment_candidates, declaration_candidates,
                                         terminal_candidates)) \
            or terminal is None or normalized(terminal[0]) != "3" or len(alias) != 1:
        return None
    bootstrap_span = java_method_span(bootstrap, "ExecutionStoreBootstrap", "openShared")
    bootstrap_code = normalized(strip_c_comments(
        bootstrap[slice(*bootstrap_span)] if bootstrap_span is not None else ""))
    expected_bootstrap_call = normalized("""new PostgresExecutionManifestStore(pool.dataSource(), clock,
        ai.ravenroot.api.persistence.ExecutionManifestReferences.NONE,
        configuration.manifestPinAttempts(), storeConfig)""")
    if bootstrap_span is None or bootstrap_code.count(expected_bootstrap_call) != 1:
        return None
    tests = (
        ("typedParsing", Path("ravenroot/ravenroot-server/src/test/java/ai/ravenroot/server/"
                              "persistence/ExecutionStoreConfigurationTest.java"),
         "ExecutionStoreConfigurationTest", "manifestPinRepairAttemptsAreTypedAndPostgresqlOnly"),
        ("selectorConflict", Path("ravenroot/ravenroot-server/src/test/java/ai/ravenroot/server/"
                                  "ReplicaTopologyStartupCheckTest.java"),
         "ReplicaTopologyStartupCheckTest", "manifestPinAttemptsWithoutTheSharedSelectorAreRefused"),
        ("bootstrapPropagation", Path("ravenroot/ravenroot-server/src/test/java/ai/ravenroot/server/"
                                     "persistence/SharedExecutionStoreBootstrapSmokeTest.java"),
         "SharedExecutionStoreBootstrapSmokeTest", "theSharedSelectorComposesThreeStoresOverOneRealDatabase"),
    )
    test_evidence: list[dict[str, object]] = []
    for role, path, type_symbol, method in tests:
        try:
            source = (root / path).read_text(encoding="utf-8")
        except OSError:
            return None
        digest = java_method_digest(source, type_symbol, method)
        if digest is None:
            return None
        test_evidence.append({"role": role, "path": path.as_posix(), "type": type_symbol,
                              "method": method, "methodDigest": digest})
    owner = f"{MANIFEST_PIN_CONFIGURATION_PATH.as_posix()}#Shared"
    binding = {
        "kind": "java-shared-manifest-pin-attempts-v1",
        "sourceOwner": owner,
        "factoryOwner": f"{MANIFEST_PIN_CONFIGURATION_PATH.as_posix()}#ExecutionStoreConfiguration",
        "method": "fromSources", "constructorType": "Shared",
        "component": "manifestPinAttempts", "componentIndex": 1,
        "helper": "positiveInt", "environmentSymbol": "MANIFEST_PIN_ATTEMPTS_VARIABLE",
        "environment": environment, "environmentCandidateId": environment_candidates[0],
        "declarationCandidateIds": declaration_candidates,
        "callDigest": hashlib.sha256(call[0].encode("utf-8")).hexdigest(),
        "factoryBodyDigest": java_method_digest(configuration, "ExecutionStoreConfiguration", "fromSources"),
        "helperBodyDigest": java_method_digest(configuration, "ExecutionStoreConfiguration", "positiveInt"),
        "compactConstructorDigest": java_span_digest(configuration, compact_span),
        "bootstrapBodyDigest": java_method_digest(bootstrap, "ExecutionStoreBootstrap", "openShared"),
        "tests": test_evidence,
    }
    default = {
        "kind": "java-interface-alias-static-final-v1", "owner": owner,
        "instanceSymbol": "DEFAULT_MANIFEST_PIN_ATTEMPTS", "field": "manifestPinAttempts",
        "sourceExpression": "PostgresExecutionManifestStore.DEFAULT_MAX_PIN_ATTEMPTS",
        "candidateIds": terminal_candidates,
        "aliasOwner": f"{MANIFEST_PIN_CONFIGURATION_PATH.as_posix()}#ExecutionStoreConfiguration",
        "aliasField": "DEFAULT_MANIFEST_PIN_ATTEMPTS",
        "terminalOwner": f"{MANIFEST_PIN_STORE_PATH.as_posix()}#PostgresExecutionManifestStore",
        "terminalField": "DEFAULT_MAX_PIN_ATTEMPTS", "terminalSourceExpression": "3",
        "evaluatedDefault": {"kind": "integer", "value": 3},
    }
    all_ids = environment_candidates + declaration_candidates + terminal_candidates
    return {"bindingAuthority": binding, "defaultAuthority": default,
            "candidateIds": sorted(all_ids)}

def manifest_pin_attempt_authority_errors(root: Path, setting: str,
                                          contract: dict[str, object],
                                          setting_entries: list[dict[str, object]],
                                          entries: dict[str, dict[str, object]],
                                          discovered: dict[str, Candidate]) -> list[str]:
    if setting != MANIFEST_PIN_ATTEMPTS_SETTING:
        return [f"{setting}: unsupported shared manifest-pin authority"]
    expected = manifest_pin_attempt_authorities(root, discovered)
    if expected is None:
        return [f"{setting}: nested Shared authority source or coverage has drifted"]
    errors: list[str] = []
    if contract.get("owner") != \
            f"{MANIFEST_PIN_CONFIGURATION_PATH.as_posix()}#Shared" \
            or contract.get("field") != "manifestPinAttempts" \
            or contract.get("bindings") != ["RAVENROOT_EXECUTION_MANIFEST_PIN_ATTEMPTS"]:
        errors.append(f"{setting}: typed owner, field, or environment binding has drifted")
    if contract.get("bindingAuthority") != expected["bindingAuthority"]:
        errors.append(f"{setting}: nested Shared binding authority has drifted")
    if contract.get("defaultAuthority") != expected["defaultAuthority"] \
            or contract.get("defaultEvidence") != expected["defaultAuthority"]["candidateIds"] \
            or contract.get("default") != "3 attempts":
        errors.append(f"{setting}: single adapter default authority has drifted")
    actual_ids = sorted(str(entry["id"]) for entry in setting_entries)
    if actual_ids != expected["candidateIds"] \
            or any(entries.get(identifier, {}).get("setting") != setting
                   for identifier in expected["candidateIds"]):
        errors.append(f"{setting}: source candidate partition has drifted")
    return errors


def environment_binding_authority_errors(root: Path, setting: str, contract: dict[str, object],
                                         setting_entries: list[dict[str, object]],
                                         entries: dict[str, dict[str, object]],
                                         discovered: dict[str, Candidate],
                                         resolver_authorities: object) -> list[str]:
    required = {
        "kind", "sourceOwner", "method", "constructorType", "component", "componentIndex",
        "helper", "environmentCandidateId", "sourceEnvironmentCandidateIds", "environment",
        "defaultAccessor", "valueTransform", "callDigest", "resolverAuthority",
    }
    authority = contract.get("bindingAuthority")
    if not isinstance(authority, dict) or set(authority) != required:
        return [f"{setting}: environment-bound setting requires exact bindingAuthority fields"]
    errors: list[str] = []
    if authority["kind"] != "java-environment-constructor-v1":
        errors.append(f"{setting}: unsupported environment bindingAuthority kind")
    source_owner = str(authority["sourceOwner"])
    if source_owner != contract.get("owner"):
        errors.append(f"{setting}: environment binding sourceOwner must equal the typed setting owner")
        return errors
    resolved = current_source_owner(root, source_owner)
    if resolved is None or resolved[0].suffix != ".java":
        return errors + [f"{setting}: environment binding sourceOwner must be a tracked Java record"]
    source_path, source_type = resolved
    source = (root / source_path).read_text(encoding="utf-8")
    components = java_record_components(source, source_type)
    component = str(authority["component"])
    index = authority["componentIndex"]
    if not isinstance(index, int) or isinstance(index, bool) or not 0 <= index < len(components) \
            or components[index] != component or component != contract.get("field"):
        errors.append(f"{setting}: environment binding component index/field has drifted")
        return errors
    if authority["constructorType"] != source_type:
        errors.append(f"{setting}: environment constructorType must be the exact owner record")
        return errors
    method = str(authority["method"])
    call = java_constructor_component_call(source, source_type, method, source_type, components, component)
    if call is None:
        return errors + [f"{setting}: environment binding has no unique constructor-position call"]
    argument, start, end = call
    environment = str(authority["environment"])
    identity = re.fullmatch(
        rf'integer\(environment, "{re.escape(environment)}", DEFAULTS\.{re.escape(component)}\)',
        argument,
    )
    duration = re.fullmatch(
        rf'Duration\.ofSeconds\(integer\(environment, "{re.escape(environment)}", '
        rf'\(int\) DEFAULTS\.{re.escape(component)}\.toSeconds\(\)\)\)',
        argument,
    )
    expected_transform = "identity" if identity else "duration-seconds" if duration else None
    expected_accessor = (f"DEFAULTS.{component}" if identity
                         else f"(int) DEFAULTS.{component}.toSeconds()" if duration else None)
    if authority["helper"] != "integer" or authority["valueTransform"] != expected_transform \
            or authority["defaultAccessor"] != expected_accessor:
        errors.append(f"{setting}: constructor component is not a supported environment integer call")
    if hashlib.sha256(argument.encode("utf-8")).hexdigest() != authority["callDigest"]:
        errors.append(f"{setting}: environment binding callDigest has drifted")
    if contract.get("bindings") != [environment]:
        errors.append(f"{setting}: environment binding must be the setting's sole binding")
    constructor_ids = candidate_ids_in_source_span(
        source_path, source, start, end, "environment-binding", environment, discovered,
    )
    if constructor_ids != [str(authority["environmentCandidateId"])]:
        errors.append(f"{setting}: constructor environment candidate is not the exact component literal")
    source_ids = sorted(
        candidate.id for candidate in discovered.values()
        if candidate.path == source_path.as_posix() and candidate.kind == "environment-binding"
        and candidate.expression == environment
    )
    declared_source_ids = authority["sourceEnvironmentCandidateIds"]
    if not isinstance(declared_source_ids, list) \
            or [str(identifier) for identifier in declared_source_ids] != source_ids:
        errors.append(f"{setting}: source environment candidate partition has drifted")
    carrier_errors, carrier_ids = deployment_carrier_evidence_errors(
        setting, contract, entries, discovered,
    )
    errors.extend(carrier_errors)
    assigned_ids = {
        str(entry["id"]) for entry in setting_entries
        if entry.get("kind") == "environment-binding"
    }
    if assigned_ids != set(source_ids) | carrier_ids:
        errors.append(f"{setting}: assigned environment candidates are not fully partitioned")
    resolver = str(authority["resolverAuthority"])
    if not isinstance(resolver_authorities, dict) or resolver not in resolver_authorities:
        errors.append(f"{setting}: environment bindingAuthority references an absent resolver authority")
    else:
        resolved_authority = resolver_authorities[resolver]
        if not isinstance(resolved_authority, dict) \
                or resolved_authority.get("kind") != "java-environment-integer-resolver-v1" \
                or resolved_authority.get("path") != source_path.as_posix() \
                or resolved_authority.get("type") != source_type \
                or resolved_authority.get("factoryMethod") != method \
                or resolved_authority.get("integerMethod") != authority["helper"]:
            errors.append(f"{setting}: environment resolver authority does not match the binding source")
    return errors


def dual_source_binding_authority_errors(root: Path, setting: str, contract: dict[str, object],
                                        entries: dict[str, dict[str, object]],
                                        discovered: dict[str, Candidate],
                                        resolver_authorities: object) -> list[str]:
    bindings = contract.get("bindings", [])
    property_candidates = [entry for entry in entries.values()
                           if entry.get("setting") == setting and entry.get("kind") == "property-binding"]
    authority = contract.get("bindingAuthority")
    if not property_candidates:
        return [] if authority is None else [f"{setting}: bindingAuthority exists without a property candidate"]
    required = ("kind", "sourceOwner", "method", "constructorType", "component", "helper",
                "propertyCandidateId", "property", "environmentCandidateId", "environment",
                "defaultAccessor", "callDigest", "resolverAuthority")
    if not isinstance(authority, dict) or any(not isinstance(authority.get(key), str)
                                              or not str(authority[key]).strip() for key in required):
        return [f"{setting}: property-bound setting requires atomic bindingAuthority fields {', '.join(required)}"]
    errors: list[str] = []
    if authority["kind"] != "java-dual-source-constructor-v1":
        errors.append(f"{setting}: unsupported bindingAuthority kind")
    source_owner = str(authority["sourceOwner"])
    resolved_source = current_source_owner(root, source_owner)
    if resolved_source is None or resolved_source[0].suffix != ".java":
        errors.append(f"{setting}: bindingAuthority sourceOwner must be a tracked Java type")
        return errors
    source_path, source_type = resolved_source
    owner = current_source_owner(root, str(contract.get("owner", "")))
    component = str(authority["component"])
    if owner is None or owner[0].suffix != ".java" or component != contract.get("field"):
        errors.append(f"{setting}: bindingAuthority component must match its Java setting owner")
        return errors
    owner_source = (root / owner[0]).read_text(encoding="utf-8")
    components = java_record_components(owner_source, owner[1])
    if str(authority["constructorType"]).rsplit(".", 1)[-1] != owner[1]:
        errors.append(f"{setting}: bindingAuthority constructorType does not match its setting owner")
        return errors
    call = java_constructor_component_call(
        (root / source_path).read_text(encoding="utf-8"), source_type, str(authority["method"]),
        str(authority["constructorType"]), components, component,
    )
    if call is None:
        errors.append(f"{setting}: bindingAuthority has no unique constructor-position call")
        return errors
    argument, start, end = call
    pattern = re.compile(
        r'^(integer|whole)\(properties, environment, "([^"]+)", "([^"]+)", '
        rf'defaults\.{re.escape(component)}\(\)\)$'
    )
    parsed = pattern.fullmatch(argument)
    if parsed is None or parsed.group(1) != authority["helper"]:
        errors.append(f"{setting}: constructor component is not a supported direct integer/whole authority call")
        return errors
    helper, property_name, environment_name = parsed.groups()
    expected_accessor = f"defaults.{component}()"
    if (property_name != authority["property"] or environment_name != authority["environment"]
            or authority["defaultAccessor"] != expected_accessor):
        errors.append(f"{setting}: bindingAuthority literals/default accessor do not match the direct call")
    digest = hashlib.sha256(argument.encode("utf-8")).hexdigest()
    if digest != authority["callDigest"]:
        errors.append(f"{setting}: bindingAuthority callDigest has drifted")
    if not isinstance(bindings, list) or sorted(str(value) for value in bindings) != sorted(
            (property_name, environment_name)):
        errors.append(f"{setting}: bindings do not equal the constructor authority pair")
    source = (root / source_path).read_text(encoding="utf-8")
    expected_candidates = (
        (str(authority["propertyCandidateId"]), "property-binding", property_name),
        (str(authority["environmentCandidateId"]), "environment-binding", environment_name),
    )
    component_candidate_ids: dict[str, list[str]] = {}
    for candidate_id, kind, name in expected_candidates:
        candidate = discovered.get(candidate_id)
        entry = entries.get(candidate_id)
        if candidate is None or entry is None or entry.get("setting") != setting:
            errors.append(f"{setting}: binding authority candidate is absent or assigned elsewhere: {candidate_id}")
            continue
        actual_ids = candidate_ids_in_source_span(
            source_path, source, start, end, kind, name, discovered,
        )
        component_candidate_ids[kind] = actual_ids
        if candidate.path != source_path.as_posix() or candidate.kind != kind or candidate.expression != name \
                or actual_ids != [candidate_id]:
            errors.append(f"{setting}: binding candidate is not the literal in its constructor component: {candidate_id}")
    assigned_property_ids = {str(entry["id"]) for entry in property_candidates}
    if assigned_property_ids != {str(authority["propertyCandidateId"])}:
        errors.append(f"{setting}: every property candidate must be the one atomic binding authority")
    assigned_environment_ids = {
        candidate_id for candidate_id in component_candidate_ids.get("environment-binding", [])
        if entries.get(candidate_id, {}).get("setting") == setting
    }
    if assigned_environment_ids != {str(authority["environmentCandidateId"])}:
        errors.append(f"{setting}: constructor binding authority must select exactly one environment candidate")
    resolver = str(authority["resolverAuthority"])
    if not isinstance(resolver_authorities, dict) or resolver not in resolver_authorities:
        errors.append(f"{setting}: bindingAuthority references an absent resolver authority")
    else:
        resolver_contract = resolver_authorities[resolver]
        if not isinstance(resolver_contract, dict) or helper not in {
                resolver_contract.get("integerMethod"), resolver_contract.get("wholeMethod")}:
            errors.append(f"{setting}: binding helper is outside its resolver authority")
        elif resolver_contract.get("path") != source_path.as_posix() \
                or resolver_contract.get("type") != source_type:
            errors.append(f"{setting}: resolver authority must be the binding source type")
    return errors


def binding_authority_errors(root: Path, setting: str, contract: dict[str, object],
                             setting_entries: list[dict[str, object]],
                             entries: dict[str, dict[str, object]],
                             discovered: dict[str, Candidate],
                             resolver_authorities: object) -> list[str]:
    property_candidates = [entry for entry in setting_entries if entry.get("kind") == "property-binding"]
    environment_candidates = [
        entry for entry in setting_entries if entry.get("kind") == "environment-binding"
    ]
    authority = contract.get("bindingAuthority")
    if isinstance(authority, dict) \
            and authority.get("kind") == "java-shared-manifest-pin-attempts-v1":
        return manifest_pin_attempt_authority_errors(
            root, setting, contract, setting_entries, entries, discovered)
    if property_candidates:
        return dual_source_binding_authority_errors(
            root, setting, contract, entries, discovered, resolver_authorities,
        )
    if not environment_candidates:
        return ([] if contract.get("bindingAuthority") is None
                else [f"{setting}: bindingAuthority exists without a binding candidate"])
    if setting in GRAPH_LIMIT_AUTHORITY_BY_SETTING:
        return ([] if contract.get("bindingAuthority") is None
                else [f"{setting}: graph binding belongs to the closed graph family authority"])
    return environment_binding_authority_errors(
        root, setting, contract, setting_entries, entries, discovered, resolver_authorities,
    )


def environment_resolver_group_errors(root: Path,
                                      representatives: dict[str, dict[str, object]],
                                      resolver_authorities: object) -> list[str]:
    """Require one reviewed authority for every component of each supported env-only factory."""
    if not isinstance(resolver_authorities, dict):
        return (["environment-bound settings require a resolverAuthorities object"]
                if any(isinstance(item.get("bindingAuthority"), dict)
                       and item["bindingAuthority"].get("kind") == "java-environment-constructor-v1"
                       for item in representatives.values()) else [])
    groups: dict[str, list[tuple[str, dict[str, object], dict[str, object]]]] = {}
    for setting, contract in representatives.items():
        authority = contract.get("bindingAuthority")
        if isinstance(authority, dict) and authority.get("kind") == "java-environment-constructor-v1":
            groups.setdefault(str(authority.get("resolverAuthority", "")), []).append(
                (setting, contract, authority),
            )
    errors: list[str] = []
    environment_resolvers = {
        str(identifier) for identifier, authority in resolver_authorities.items()
        if isinstance(authority, dict)
        and authority.get("kind") == "java-environment-integer-resolver-v1"
    }
    if set(groups) != environment_resolvers:
        errors.append("environment resolver authorities must be referenced by one exact component set")
    for resolver_id, contracts in groups.items():
        resolver = resolver_authorities.get(resolver_id)
        if not isinstance(resolver, dict):
            continue
        path = Path(str(resolver.get("path", "")))
        type_symbol = str(resolver.get("type", ""))
        if current_source_owner(root, f"{path.as_posix()}#{type_symbol}") is None:
            continue
        source = (root / path).read_text(encoding="utf-8")
        components = java_record_components(source, type_symbol)
        actual = {(item[2].get("componentIndex"), item[2].get("component")) for item in contracts}
        expected = set(enumerate(components))
        if actual != expected or len(contracts) != len(components):
            errors.append(f"resolver authority {resolver_id} does not bijectively cover every record component")
        environments = [str(item[2].get("environment", "")) for item in contracts]
        if len(set(environments)) != len(components):
            errors.append(f"resolver authority {resolver_id} environment bindings are not one-to-one")
        if any(item[2].get("sourceOwner") != item[1].get("owner")
               or item[2].get("method") != resolver.get("factoryMethod")
               for item in contracts):
            errors.append(f"resolver authority {resolver_id} component ownership/factory has drifted")
        test_methods = resolver.get("testMethods", {})
        enumeration = (test_methods.get("bindingEnumeration")
                       if isinstance(test_methods, dict) else None)
        test_path = Path(str(resolver.get("testPath", "")))
        test_type = str(resolver.get("testType", ""))
        test_source = ((root / test_path).read_text(encoding="utf-8")
                       if current_source_owner(root, f"{test_path.as_posix()}#{test_type}") is not None
                       else "")
        if enumeration and java_method_header(test_source, test_type, str(enumeration)) \
                != f"private static Stream<String> {enumeration}()":
            errors.append(
                f"resolver authority {resolver_id} binding enumeration has unsupported factory signature")
        enumerated = (java_direct_stream_string_return(
            test_source, test_type, str(enumeration),
        ) if enumeration else None)
        if enumerated is None or Counter(enumerated) != Counter(environments) \
                or len(enumerated) != len(environments):
            errors.append(
                f"resolver authority {resolver_id} binding enumeration is not one direct exact Stream.of literal list")
    return errors


def default_authority_errors(root: Path, setting: str, contract: dict[str, object],
                             entries: dict[str, dict[str, object]],
                             discovered: dict[str, Candidate]) -> list[str]:
    property_bound = any(entry.get("setting") == setting and entry.get("kind") == "property-binding"
                         for entry in entries.values())
    binding_authority = contract.get("bindingAuthority")
    if isinstance(binding_authority, dict) \
            and binding_authority.get("kind") == "java-shared-manifest-pin-attempts-v1":
        return []
    environment_bound = isinstance(binding_authority, dict) \
        and binding_authority.get("kind") == "java-environment-constructor-v1"
    authority = contract.get("defaultAuthority")
    if authority is None:
        return ([f"{setting}: bound setting requires defaultAuthority"]
                if property_bound or environment_bound else [])
    required = {"owner", "instanceSymbol", "field", "sourceExpression", "candidateIds"}
    if environment_bound:
        required.update({"componentIndex", "evaluatedDefault"})
    if not isinstance(authority, dict) or not required.issubset(authority):
        return [f"{setting}: defaultAuthority requires {', '.join(sorted(required))}"]
    if environment_bound and set(authority) != required:
        return [f"{setting}: environment defaultAuthority requires exactly {', '.join(sorted(required))}"]
    errors: list[str] = []
    owner = str(authority["owner"])
    field = str(authority["field"])
    if owner != contract.get("owner") or field != contract.get("field"):
        errors.append(f"{setting}: defaultAuthority owner/field must match the setting authority")
    resolved = current_source_owner(root, owner)
    if resolved is None or resolved[0].suffix != ".java":
        errors.append(f"{setting}: defaultAuthority must resolve to a tracked Java record")
    else:
        relative, symbol = resolved
        owner_source = (root / relative).read_text(encoding="utf-8")
        actual_span = java_record_default_expression_span(
            owner_source, symbol, str(authority["instanceSymbol"]), field)
        if actual_span is None or normalized(str(authority["sourceExpression"])) != normalized(actual_span[0]):
            errors.append(f"{setting}: defaultAuthority sourceExpression does not match the record component")
        if environment_bound:
            components = java_record_components(owner_source, symbol)
            index = authority["componentIndex"]
            if not isinstance(index, int) or isinstance(index, bool) or not 0 <= index < len(components) \
                    or components[index] != field:
                errors.append(f"{setting}: defaultAuthority componentIndex has drifted")
            evaluated = evaluated_java_default(
                actual_span[0] if actual_span is not None else "",
                isinstance(binding_authority, dict)
                and binding_authority.get("valueTransform") == "duration-seconds",
            )
            declared = authority["evaluatedDefault"]
            if not isinstance(declared, dict) or set(declared) != {"kind", "value"} \
                    or declared != evaluated:
                errors.append(f"{setting}: evaluatedDefault does not match the typed Java expression")
            elif contract.get("default") != str(declared["value"]):
                errors.append(f"{setting}: default must be the canonical evaluated decimal value")
    candidate_ids = authority.get("candidateIds")
    if not isinstance(candidate_ids, list):
        errors.append(f"{setting}: defaultAuthority candidateIds must be an array")
    elif resolved is not None and resolved[0].suffix == ".java" and actual_span is not None:
        expected_ids = candidate_ids_in_source_span(
            resolved[0], owner_source, actual_span[1], actual_span[2],
            "fixed-declaration", str(authority["instanceSymbol"]), discovered,
        )
        if [str(candidate_id) for candidate_id in candidate_ids] != expected_ids or any(
                entries.get(candidate_id, {}).get("setting") != setting for candidate_id in expected_ids):
            errors.append(f"{setting}: defaultAuthority candidateIds are not the exact initializer atom multiset")
        reference = authority.get("constantReferenceAuthority")
        if expected_ids and reference is not None:
            errors.append(f"{setting}: direct default atoms cannot also use constantReferenceAuthority")
        if not expected_ids and reference is None:
            errors.append(f"{setting}: indirect default requires constantReferenceAuthority")
        if not expected_ids and reference is not None:
            errors.extend(constant_reference_authority_errors(
                root, setting, contract, authority, owner_source, discovered, entries))
    return errors


def constant_reference_authority_errors(root: Path, setting: str, contract: dict[str, object],
                                        default_authority: dict[str, object], owner_source: str,
                                        discovered: dict[str, Candidate],
                                        entries: dict[str, dict[str, object]]) -> list[str]:
    """Verify a bounded ordered static-final reference chain ending in fixed atoms."""
    reference = default_authority.get("constantReferenceAuthority")
    if not isinstance(reference, dict) or reference.get("kind") != "java-static-final-chain-v1" \
            or not isinstance(reference.get("hops"), list) or not 1 <= len(reference["hops"]) <= 4:
        return [f"{setting}: constantReferenceAuthority requires a bounded static-final hop chain"]
    previous_owner = str(default_authority["owner"])
    previous_source = owner_source
    previous_expression = str(default_authority["sourceExpression"])
    errors: list[str] = []
    terminal_ids: list[str] = []
    for index, hop in enumerate(reference["hops"]):
        required = ("owner", "field", "sourceExpression", "initializerDigest", "candidateIds")
        if not isinstance(hop, dict) or any(key not in hop for key in required) \
                or not isinstance(hop.get("candidateIds"), list):
            errors.append(f"{setting}: constant reference hop {index} is incomplete")
            return errors
        hop_owner = str(hop["owner"])
        if "#" not in hop_owner:
            errors.append(f"{setting}: constant reference hop {index} owner must be path#type")
            return errors
        resolved = current_source_owner(root, hop_owner)
        if resolved is None or resolved[0].suffix != ".java":
            errors.append(f"{setting}: constant reference hop {index} has no tracked Java owner")
            return errors
        relative, symbol = resolved
        hop_source = (root / relative).read_text(encoding="utf-8")
        field = str(hop["field"])
        if not java_constant_reference_matches(
                previous_source, previous_owner, previous_expression, hop_owner, field, hop_source):
            errors.append(f"{setting}: constant reference hop {index} does not match the preceding initializer")
        initializer = java_static_final_initializer(hop_source, symbol, field)
        if initializer is None:
            errors.append(f"{setting}: constant reference hop {index} is not one direct static-final field")
            return errors
        expression, start, end = initializer
        if normalized(str(hop["sourceExpression"])) != normalized(expression):
            errors.append(f"{setting}: constant reference hop {index} initializer has drifted")
        digest = hashlib.sha256(normalized(expression).encode("utf-8")).hexdigest()
        if hop["initializerDigest"] != digest:
            errors.append(f"{setting}: constant reference hop {index} initializer digest has drifted")
        expected_ids = candidate_ids_in_source_span(
            relative, hop_source, start, end, "fixed-declaration", field, discovered)
        actual_ids = [str(candidate_id) for candidate_id in hop["candidateIds"]]
        if actual_ids != expected_ids or any(
                entries.get(candidate_id, {}).get("setting") != setting for candidate_id in expected_ids):
            errors.append(f"{setting}: constant reference hop {index} atom multiset has drifted")
        if index < len(reference["hops"]) - 1 and expected_ids:
            errors.append(f"{setting}: nonterminal constant reference hop contains fixed atoms")
        terminal_ids = expected_ids
        previous_owner = hop_owner
        previous_source = hop_source
        previous_expression = expression
    default_evidence = contract.get("defaultEvidence")
    if not terminal_ids or not isinstance(default_evidence, list) \
            or [str(candidate_id) for candidate_id in default_evidence] != terminal_ids:
        errors.append(f"{setting}: defaultEvidence must equal the terminal constant atom multiset")
    return errors


def schema_evidence_errors(setting: str, contract: dict[str, object],
                           entries: dict[str, dict[str, object]],
                           discovered: dict[str, Candidate],
                           evidence_records: dict[str, object]) -> list[str]:
    schema = contract.get("schemaEvidence")
    if schema is None:
        return []
    required = ("candidateId", "path", "pointer", "reference", "required")
    if not isinstance(schema, dict) or any(key not in schema for key in required):
        return [f"{setting}: schemaEvidence requires {', '.join(required)}"]
    errors: list[str] = []
    candidate_id = str(schema["candidateId"])
    candidate = discovered.get(candidate_id)
    inventory_entry = entries.get(candidate_id)
    if candidate is None or inventory_entry is None:
        return [f"{setting}: schemaEvidence candidate is not current: {candidate_id}"]
    if candidate.kind != "schema-reference-binding" or candidate.path != schema["path"] \
            or candidate.role != schema["pointer"] or candidate.expression != schema["reference"]:
        errors.append(f"{setting}: schemaEvidence does not match its reference candidate")
    if inventory_entry.get("setting") != setting:
        errors.append(f"{setting}: schemaEvidence candidate is assigned to another setting")
    try:
        resolved = json.loads(str(evidence_records.get(candidate.evidence_digest, "")))
    except json.JSONDecodeError:
        resolved = {}
    if resolved.get("resolutionError") is not None or resolved.get("resolved") is None:
        errors.append(f"{setting}: schemaEvidence reference is not a resolved local edge")
    if resolved.get("pointer") != schema["pointer"] or resolved.get("reference") != schema["reference"] \
            or resolved.get("required") is not schema["required"]:
        errors.append(f"{setting}: schemaEvidence pointer/reference/required metadata has drifted")
    return errors


def graph_platform_coverage_errors(root: Path, setting: str, contract: dict[str, object],
                                   entries: dict[str, dict[str, object]],
                                   discovered: dict[str, Candidate],
                                   evidence_records: dict[str, object],
                                   tracked_paths: set[Path]) -> list[str]:
    """Verify graph carrier coverage against exact current candidates and drift-test bodies."""
    if not setting.startswith("graph."):
        return []
    coverage = contract.get("coverageEvidence")
    candidate_fields = {
        "composeCandidateIds": ("compose.yaml", "environment-binding", None, 2),
        "helmValueCandidateIds": ("deploy/helm/ravenroot/values.yaml", "configuration-scalar", '""', 1),
        "helmTemplateCandidateIds": (
            "deploy/helm/ravenroot/templates/deployment.yaml", "environment-binding", None, 1),
        "helmSchemaEnvironmentCandidateIds": (
            "deploy/helm/ravenroot/values.schema.json", "environment-binding", None, 1),
        "helmSchemaReferenceCandidateIds": (
            "deploy/helm/ravenroot/values.schema.json", "schema-reference-binding",
            "#/definitions/graphBlank", 1),
        "rawKubernetesCandidateIds": (
            "deploy/kubernetes/ravenroot.yaml", "environment-binding", None, 1),
    }
    required = ("kind", "environment", "helmPath", "contractTestPath", "contractTestDigest",
                "shellTestPath", "shellTestDigest", *candidate_fields)
    if not isinstance(coverage, dict) or any(key not in coverage for key in required):
        return [f"{setting}: graph coverageEvidence requires {', '.join(required)}"]
    errors: list[str] = []
    if coverage["kind"] != "graph-platform-carriers-v1":
        errors.append(f"{setting}: unsupported graph coverageEvidence kind")
    environment = str(coverage["environment"])
    bindings = contract.get("bindings", [])
    if not isinstance(bindings, list) or bindings != [environment]:
        errors.append(f"{setting}: graph coverage environment must be the setting's sole binding")
    helm_leaf = str(coverage["helmPath"]).rsplit(".", 1)[-1]
    for field, (path, kind, fixed_expression, count) in candidate_fields.items():
        identifiers = coverage[field]
        if not isinstance(identifiers, list) or len(identifiers) != count \
                or len(set(str(identifier) for identifier in identifiers)) != count:
            errors.append(f"{setting}: {field} must contain {count} unique candidate ids")
            continue
        for identifier in identifiers:
            candidate = discovered.get(str(identifier))
            entry = entries.get(str(identifier))
            expected_expression = fixed_expression if fixed_expression is not None else environment
            if candidate is None or entry is None or entry.get("setting") != setting:
                errors.append(f"{setting}: {field} candidate is absent or assigned elsewhere: {identifier}")
                continue
            if candidate.path != path or candidate.kind != kind or candidate.expression != expected_expression:
                errors.append(f"{setting}: {field} candidate does not match its carrier: {identifier}")
            if field == "helmValueCandidateIds" and candidate.role != helm_leaf:
                errors.append(f"{setting}: Helm value candidate does not match helmPath: {identifier}")
            if field == "helmSchemaReferenceCandidateIds":
                try:
                    schema = json.loads(str(evidence_records.get(candidate.evidence_digest, "")))
                except json.JSONDecodeError:
                    schema = {}
                if schema.get("required") is not True or schema.get("resolutionError") is not None:
                    errors.append(f"{setting}: Helm schema reference must be required and locally resolved")
    for path_field, digest_field in (("contractTestPath", "contractTestDigest"),
                                     ("shellTestPath", "shellTestDigest")):
        relative = Path(str(coverage[path_field]))
        if relative.is_absolute() or ".." in relative.parts or relative not in tracked_paths:
            errors.append(f"{setting}: {path_field} must be a tracked in-repository file")
            continue
        actual_digest = hashlib.sha256((root / relative).read_bytes()).hexdigest()
        if actual_digest != coverage[digest_field]:
            errors.append(f"{setting}: {path_field} body digest has drifted")
    return errors


ROUTE_TABLE_AUTHORITY_ID = "route-table-all-v1"
ENVIRONMENT_REFERENCE_AUTHORITY_ID = "environment-reference-generator-v1"
ENVIRONMENT_REFERENCE_PATH = Path("scripts/publish_environment_reference.py")
ROUTE_TABLE_PATH = Path(
    "ravenroot/ravenroot-server/src/main/java/ai/ravenroot/server/spec/RouteTable.java")
ROUTE_DESCRIPTOR_PATH = Path(
    "ravenroot/ravenroot-server/src/main/java/ai/ravenroot/server/spec/RouteDescriptor.java")
OPENAPI_GENERATOR_PATH = Path(
    "ravenroot/ravenroot-server/src/main/java/ai/ravenroot/server/spec/OpenApiSpecGenerator.java")
ROUTE_TABLE_TEST_PATH = Path(
    "ravenroot/ravenroot-server/src/test/java/ai/ravenroot/server/spec/RouteTableSpecServerAgreementTest.java")
STABLE_EDGE_ID_PATH = Path(
    "ravenroot/ravenroot-application-api/src/main/java/ai/ravenroot/api/application/StableEdgeId.java")
EDGE_WIRE_BUDGET_PATH = Path(
    "ravenroot/ravenroot-application-api/src/main/java/ai/ravenroot/api/application/EdgeTraversalWireBudget.java")
STABLE_EDGE_TEST_PATH = Path(
    "ravenroot/ravenroot-application-api/src/test/java/ai/ravenroot/api/application/StableEdgeIdContractTest.java")
STABLE_EDGE_WIRE_TEST_PATH = Path(
    "ravenroot/ravenroot-server/src/test/java/ai/ravenroot/server/StableEdgeIdWireContractTest.java")
ROUTE_BOUND_CANDIDATES = {
    "oc-0b67657cac8e5b904054": ("StableEdgeId.MAX_UTF8_BYTES",),
    "oc-7ab123337eeb18906fc2":
        ("EdgeTraversalWireBudget.MAX_AUXILIARY_ESCAPED_VALUE_BYTES",),
    "oc-7bab59779a16e10b10d7": ("StableEdgeId.SSE_FRAME_MAX_BYTES",),
    "oc-418656067bc7b4ad0c5c": (
        "StableEdgeId.MAX_UTF8_BYTES",
        "EdgeTraversalWireBudget.MAX_AUXILIARY_ESCAPED_VALUE_BYTES",
    ),
    "oc-8eed875577d7d07c6447": ("StableEdgeId.SSE_FRAME_MAX_BYTES",),
}
ROUTE_BOUND_PATHS = {
    "oc-0b67657cac8e5b904054": "/v1/events",
    "oc-7ab123337eeb18906fc2": "/v1/events",
    "oc-7bab59779a16e10b10d7": "/v1/events",
    "oc-418656067bc7b4ad0c5c": "/v1/events/recent",
    "oc-8eed875577d7d07c6447": "/v1/events/recent",
}


def environment_reference_description_candidate_ids(
        root: Path, candidates: Iterable[Candidate]) -> set[str]:
    """Return source-derived atoms that route real production bindings into the reference page."""
    production_names: set[str] = set()
    source_root = root / "ravenroot"
    if source_root.is_dir():
        for source in sorted(source_root.rglob("src/main/java/**/*.java")):
            production_names.update(ENVIRONMENT_BINDING.findall(
                source.read_text(encoding="utf-8")))
    identifiers: set[str] = set()
    for candidate in candidates:
        expression = candidate.expression
        if len(expression) >= 2 and expression[0] == expression[-1] \
                and expression[0] in {'"', "'"}:
            expression = expression[1:-1]
        if candidate.path == ENVIRONMENT_REFERENCE_PATH.as_posix() \
                and candidate.symbol == "group" \
                and candidate.kind in {
                    "binding-default", "environment-binding", "inline-script-operational",
                } \
                and expression in production_names:
            identifiers.add(candidate.id)
    return identifiers
ASSISTANT_CONFIGURATION_PATH = Path(
    "ravenroot/ravenroot-server/src/main/java/ai/ravenroot/server/assistant/AssistantConfiguration.java")
ASSISTANT_CONFIGURATION_TEST_PATH = Path(
    "ravenroot/ravenroot-server/src/test/java/ai/ravenroot/server/assistant/AssistantConfigurationTest.java")
ASSISTANT_SERVICE_PATH = Path(
    "ravenroot/ravenroot-server/src/main/java/ai/ravenroot/server/assistant/AssistantService.java")
ASSISTANT_SERVICE_TEST_PATH = Path(
    "ravenroot/ravenroot-server/src/test/java/ai/ravenroot/server/assistant/AssistantGraphProposalTest.java")
ASSISTANT_PLATFORM_TEST_PATH = Path("scripts/tests/test_assistant_platform_configuration.sh")
ASSISTANT_LIMIT_FAMILY_ID = "assistant-operational-limits-v1"
ASSISTANT_LIMIT_COMPONENTS = (
    "enabled", "providerId", "endpoint", "model", "credential", "egressPolicy", "timeout",
    "maxOutputTokens", "maxToolIterations", "credentialSource", "allowLocalHttp",
)
ASSISTANT_LIMIT_SETTINGS = (
    {
        "setting": "assistant.max-output-tokens", "component": "maxOutputTokens",
        "componentIndex": 7, "environmentSymbol": "MAX_OUTPUT_TOKENS_VARIABLE",
        "environment": "RAVENROOT_ASSISTANT_MAX_OUTPUT_TOKENS",
        "defaultSymbol": "DEFAULT_MAX_OUTPUT_TOKENS", "defaultValue": 16_000,
        "helmField": "maxOutputTokens",
    },
    {
        "setting": "assistant.max-tool-iterations", "component": "maxToolIterations",
        "componentIndex": 8, "environmentSymbol": "MAX_TOOL_ITERATIONS_VARIABLE",
        "environment": "RAVENROOT_ASSISTANT_MAX_TOOL_ITERATIONS",
        "defaultSymbol": "DEFAULT_MAX_TOOL_ITERATIONS", "defaultValue": 8,
        "helmField": "maxToolIterations",
    },
)
ASSISTANT_CARRIER_PATHS = {
    "compose": frozenset({"compose.yaml"}),
    "deploymentExamples": frozenset({"docs/examples/assistant/compose.override.yaml"}),
    "helm": frozenset({
        "deploy/helm/ravenroot/values.yaml", "deploy/helm/ravenroot/values.schema.json",
        "deploy/helm/ravenroot/templates/deployment.yaml",
    }),
    "rawKubernetes": frozenset({"deploy/kubernetes/ravenroot.yaml"}),
}


def java_source_candidates(relative: Path, source: str) -> tuple[tuple[int, Candidate], ...]:
    """Reproduce stable candidate IDs and retain offsets for one Java source."""
    provisional = [
        (relative.as_posix(), line_number(source, offset), symbol, kind, role, expression,
         evidence, "java", False, offset)
        for offset, symbol, kind, role, expression, evidence
        in code_candidates(relative, source, "java")
    ]
    occurrences: Counter[tuple[str, str, str, str, str]] = Counter()
    result: list[tuple[int, Candidate]] = []
    for row in sorted(provisional, key=lambda item: item[:-1]):
        path, line, symbol, kind, role, expression, evidence, surface_name, fixture, offset = row
        key = (path, symbol, kind, role, expression)
        occurrence = occurrences[key]
        occurrences[key] += 1
        evidence_digest = hashlib.sha256(evidence.encode("utf-8")).hexdigest()
        material = "\0".join(
            (path, symbol, kind, role, expression, evidence_digest, str(occurrence)))
        candidate = Candidate(
            "oc-" + hashlib.sha256(material.encode("utf-8")).hexdigest()[:20],
            path, line, symbol, kind, role, expression,
            hashlib.sha256(expression.encode("utf-8")).hexdigest(), evidence,
            evidence_digest, surface_name, fixture,
        )
        result.append((offset, candidate))
    return tuple(result)


def java_string_value(expression: str) -> str | None:
    """Decode the bounded Java string-literal subset shared with JSON escaping."""
    try:
        value = json.loads(expression)
    except json.JSONDecodeError:
        return None
    return value if isinstance(value, str) else None


def java_literal_concatenation(expression: str) -> tuple[str, tuple[tuple[int, int], ...]] | None:
    """Read one expression made only from Java double-quoted literals and plus operators."""
    token = re.compile(r'"(?:\\.|[^"\\])*"')
    cursor = 0
    values: list[str] = []
    spans: list[tuple[int, int]] = []
    for match in token.finditer(expression):
        separator = expression[cursor:match.start()]
        if values:
            if re.fullmatch(r"\s*\+\s*", separator) is None:
                return None
        elif separator.strip():
            return None
        value = java_string_value(match.group())
        if value is None:
            return None
        values.append(value)
        spans.append(match.span())
        cursor = match.end()
    if not values or expression[cursor:].strip():
        return None
    return "".join(values), tuple(spans)


def direct_factory_arguments(expression: str, factory: str) \
        -> tuple[tuple[str, int, int], ...] | None:
    """Return arguments of one expression that is exactly `factory(...)`."""
    code = strip_c_comments_and_literals(expression)
    match = re.match(rf"\s*{re.escape(factory)}\s*\(", code)
    if match is None:
        return None
    opening = code.find("(", match.start())
    parsed = split_java_arguments(expression, code, opening)
    if parsed is None or code[parsed[1] + 1:].strip():
        return None
    return tuple(parsed[0])


def route_table_descriptors(source: str) -> tuple[tuple[tuple[str, int, int], ...], ...] | None:
    """Parse the direct RouteTable.ALL List.of initializer and its nine-argument descriptors."""
    span = java_type_span(source, "RouteTable")
    if span is None or java_package(source) != "ai.ravenroot.server.spec" \
            or not exact_import_identity(source, "java.util.List") \
            or not exact_import_identity(source, "java.util.Set") \
            or not same_package_type_identity(
                source, "RouteTable", "ai.ravenroot.server.spec.RouteDescriptor") \
            or not java_has_no_simple_name_shadow(source, "RouteTable", {"List", "Set"}):
        return None
    base, limit = span
    actual = source[base:limit]
    code = strip_c_comments_and_literals(source)[base:limit]
    depths = java_brace_depths(code)
    declarations = [
        match for match in re.finditer(
            r"\bpublic\s+static\s+final\s+List\s*<\s*RouteDescriptor\s*>\s+"
            r"ALL\s*=\s*List\s*\.\s*of\s*\(", code)
        if depths[match.start()] == 1
    ]
    if len(declarations) != 1:
        return None
    opening = code.find("(", declarations[0].start())
    parsed = split_java_arguments(actual, code, opening)
    if parsed is None or re.match(r"\s*;", code[parsed[1] + 1:]) is None:
        return None
    descriptors: list[tuple[tuple[str, int, int], ...]] = []
    for _descriptor, start, end in parsed[0]:
        descriptor = actual[start:end]
        descriptor_code = strip_c_comments_and_literals(descriptor)
        match = re.match(r"\s*new\s+RouteDescriptor\s*\(", descriptor_code)
        if match is None:
            return None
        descriptor_opening = descriptor_code.find("(", match.start())
        arguments = split_java_arguments(descriptor, descriptor_code, descriptor_opening)
        if arguments is None or len(arguments[0]) != 9 \
                or descriptor_code[arguments[1] + 1:].strip():
            return None
        descriptors.append(tuple(
            (argument, base + start + argument_start, base + start + argument_end)
            for argument, argument_start, argument_end in arguments[0]
        ))
    return tuple(descriptors)


def route_table_candidate_partitions(source: str) -> tuple[
        dict[str, list[str]], list[dict[str, object]], dict[str, Candidate]] | None:
    """Bind every supported RouteTable candidate to one typed constructor position."""
    descriptors = route_table_descriptors(source)
    if descriptors is None:
        return None
    occurrences = java_source_candidates(ROUTE_TABLE_PATH, source)
    by_id = {candidate.id: candidate for _offset, candidate in occurrences}
    partitions = {role: [] for role in ("methods", "path", "summary", "successStatuses")}
    details: list[dict[str, object]] = []

    def ids_in(start: int, end: int) -> list[str]:
        return [candidate.id for offset, candidate in occurrences if start <= offset < end]

    for ordinal, arguments in enumerate(descriptors, 1):
        methods = direct_factory_arguments(arguments[0][0], "Set.of")
        path = java_literal_concatenation(arguments[1][0])
        summary = java_literal_concatenation(arguments[2][0])
        statuses = direct_factory_arguments(arguments[5][0], "Set.of")
        if methods is None or not methods or path is None or len(path[1]) != 1 or summary is None:
            return None
        method_values: list[str] = []
        for argument, _start, _end in methods:
            value = java_literal_concatenation(argument)
            if value is None or len(value[1]) != 1 or value[0] not in {"GET", "POST", "DELETE"}:
                return None
            method_values.append(value[0])
        if not path[0].startswith("/") or not summary[0].strip():
            return None
        status_arguments = statuses if statuses is not None else ((arguments[5][0], 0, len(arguments[5][0])),)
        status_values: list[int] = []
        for status, _start, _end in status_arguments:
            if re.fullmatch(r"\s*[0-9](?:_?[0-9])*\s*", status) is None:
                return None
            value = int(status.strip().replace("_", ""))
            if not 200 <= value < 300:
                return None
            status_values.append(value)
        role_arguments = {
            "methods": arguments[0], "path": arguments[1],
            "summary": arguments[2], "successStatuses": arguments[5],
        }
        role_ids = {role: ids_in(argument[1], argument[2])
                    for role, argument in role_arguments.items()}
        if any(not role_ids[role] for role in role_ids):
            return None
        for role, identifiers in role_ids.items():
            partitions[role].extend(identifiers)
        details.append({
            "ordinal": ordinal, "path": path[0], "summary": summary[0],
            "statusValues": status_values, "candidateIds": role_ids,
        })
    return partitions, details, by_id


def current_route_table_authority(root: Path) -> dict[str, object]:
    """Build the exact closed RouteTable authority from current typed sources and runnable tests."""
    source = (root / ROUTE_TABLE_PATH).read_text(encoding="utf-8")
    parsed = route_table_candidate_partitions(source)
    if parsed is None:
        raise ValueError("cannot derive the current RouteTable positional authority")
    partitions, details, _candidates = parsed
    descriptor = (root / ROUTE_DESCRIPTOR_PATH).read_text(encoding="utf-8")
    generator = (root / OPENAPI_GENERATOR_PATH).read_text(encoding="utf-8")
    publication = (root / ROUTE_TABLE_TEST_PATH).read_text(encoding="utf-8")
    stable_test = (root / STABLE_EDGE_TEST_PATH).read_text(encoding="utf-8")
    wire_test = (root / STABLE_EDGE_WIRE_TEST_PATH).read_text(encoding="utf-8")
    compact = java_compact_constructor_span(descriptor, "RouteDescriptor")
    authority = {
        "kind": "java-route-descriptor-publication-v1",
        "candidateIdsByRole": partitions,
        "descriptorCandidateIds": [
            {"ordinal": item["ordinal"], "path": item["path"],
             "candidateIds": item["candidateIds"]}
            for item in details
        ],
        "consumerBodyDigests": {
            "routeDescriptorValidation": java_span_digest(descriptor, compact),
            "openApiGenerate": java_method_digest(generator, "OpenApiSpecGenerator", "generate"),
            "openApiPathEntry": java_method_digest(generator, "OpenApiSpecGenerator", "pathEntry"),
            "openApiOperationEntry": java_method_digest(generator, "OpenApiSpecGenerator", "operationEntry"),
            "openApiSuccessResponse": java_method_digest(generator, "OpenApiSpecGenerator", "successResponse"),
        },
        "publicationTestAuthority": {
            "testBodyDigest": java_method_digest(
                publication, "RouteTableSpecServerAgreementTest",
                "theCheckedInSpecMatchesWhatTheTableGeneratesRightNow"),
            "checkedInSpecBodyDigest": java_method_digest(
                publication, "RouteTableSpecServerAgreementTest", "checkedInSpec"),
        },
        "boundTestBodyDigests": {
            "StableEdgeIdContractTest": {
                method: java_method_digest(stable_test, "StableEdgeIdContractTest", method)
                for method in (
                    "acceptsTheExactUtf8BoundWithoutChangingIdentityAndRejectsOneByteMore",
                    "auxiliaryReserveIsEnforcedAsOneCombinedEscapedByteBudget",
                )
            },
            "StableEdgeIdWireContractTest": {
                method: java_method_digest(wire_test, "StableEdgeIdWireContractTest", method)
                for method in (
                    "worstCaseEscapedMaximumFitsTheCompleteRuntimeClientFrame",
                    "saturatedLiveAndLogFieldsStillFitWithTheMaximumEscapedIdentity",
                    "saturatedDurableProjectionAndPayloadStayInsideTheirExplicitBounds",
                )
            },
        },
        "publishedBoundClauses": {
            identifier: list(fields) for identifier, fields in ROUTE_BOUND_CANDIDATES.items()
        },
    }
    if any(value is None for value in authority["consumerBodyDigests"].values()) \
            or any(value is None for value in authority["publicationTestAuthority"].values()) \
            or any(value is None for methods in authority["boundTestBodyDigests"].values()
                   for value in methods.values()):
        raise ValueError("cannot derive complete RouteTable consumer/test evidence")
    return authority


def exact_import_identity(source: str, qualified: str) -> bool:
    """Require one exact normal import and no competing/local declaration of its simple name."""
    code = strip_c_comments_and_literals(source)
    simple = qualified.rsplit(".", 1)[-1]
    imports = re.findall(
        r"(?m)^\s*import\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+)\s*;", code)
    matching = [item for item in imports if item.rsplit(".", 1)[-1] == simple]
    return matching == [qualified] and re.search(
        rf"\b(?:class|record|enum|interface)\s+{re.escape(simple)}\b"
        rf"|@interface\s+{re.escape(simple)}\b", code,
    ) is None


def same_package_type_identity(source: str, type_symbol: str, qualified: str) -> bool:
    """Require a same-package simple type with no import, local type, or direct-value shadow."""
    package, simple = qualified.rsplit(".", 1)
    code = strip_c_comments_and_literals(source)
    imports = re.findall(
        r"(?m)^\s*import\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+)\s*;", code)
    return java_package(source) == package \
        and not any(item.rsplit(".", 1)[-1] == simple for item in imports) \
        and java_has_no_simple_name_shadow(source, type_symbol, {simple})


def java_has_no_simple_name_shadow(source: str, type_symbol: str,
                                   names: set[str]) -> bool:
    """Reject local type/direct-value/static-import bindings for reviewed simple type names."""
    code = strip_c_comments_and_literals(source)
    for name in names:
        if re.search(
                rf"\b(?:class|record|enum|interface)\s+{re.escape(name)}\b"
                rf"|@interface\s+{re.escape(name)}\b", code,
        ) is not None or java_type_declares_field(source, type_symbol, name):
            return False
    static_imports = re.findall(
        r"(?m)^\s*import\s+static\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.\*)"
        r"\s*;|^\s*import\s+static\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+)\s*;",
        code,
    )
    imported = [left or right for left, right in static_imports]
    return not any(item.endswith(".*") or item.rsplit(".", 1)[-1] in names for item in imported)


def java_has_exact_junit_assertions(source: str, type_symbol: str,
                                    names: set[str]) -> bool:
    """Bind reviewed assertion calls to exact JUnit methods and reject local method/value shadows."""
    code = strip_c_comments_and_literals(source)
    static_imports = re.findall(
        r"(?m)^\s*import\s+static\s+"
        r"([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*(?:\.\*)?)\s*;", code)
    if any(item.endswith(".*") for item in static_imports):
        return False
    for name in names:
        matching = [item for item in static_imports if item.rsplit(".", 1)[-1] == name]
        if matching != [f"org.junit.jupiter.api.Assertions.{name}"] \
                or java_direct_method_declaration_count(source, type_symbol, name) != 0 \
                or java_type_declares_field(source, type_symbol, name):
            return False
    return True


def java_span_uses_only_simple_receiver(source: str, span: tuple[int, int] | None,
                                        name: str) -> bool:
    """Require every occurrence of one supported imported name to be a dotted receiver."""
    if span is None:
        return False
    code = strip_c_comments_and_literals(source)[slice(*span)]
    return all(re.match(r"\s*\.", code[match.end():]) is not None
               for match in re.finditer(rf"\b{re.escape(name)}\b", code))


def java_direct_return_expression(source: str, type_symbol: str, method: str) -> str | None:
    """Return the one direct return expression from a supported method, without comments."""
    span = java_method_span(source, type_symbol, method)
    if span is None:
        return None
    actual = strip_c_comments(source[slice(*span)])
    code = strip_c_comments_and_literals(source[slice(*span)])
    depths = java_brace_depths(code)
    returns = [match for match in re.finditer(r"\breturn\b", code)
               if depths[match.start()] == 1]
    if len(returns) != 1:
        return None
    start = returns[0].end()
    semicolon = next((offset for offset in range(start, len(code))
                      if code[offset] == ";" and depths[offset] == 1), None)
    if semicolon is None:
        return None
    return normalized(actual[start:semicolon])


def java_direct_field_has_annotation(source: str, type_symbol: str, field: str,
                                     annotation: str) -> bool:
    span = java_type_span(source, type_symbol)
    if span is None:
        return False
    code = strip_c_comments_and_literals(source)[slice(*span)]
    depths = java_brace_depths(code)
    return any(depths[match.start()] == 1 for match in re.finditer(
        rf"@{re.escape(annotation)}\s+(?:[A-Za-z_$][\w$<>?,.\[\]]*\s+)+{re.escape(field)}\s*;",
        code,
    ))


def java_int_expression_value(expression: str, resolver) -> int | None:
    """Evaluate the small checked Java integer expression grammar used by wire-bound constants."""
    tokens = re.findall(r"[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*|[0-9](?:_?[0-9])*|[()+*/-]",
                        expression)
    if "".join(tokens) != re.sub(r"\s+", "", expression):
        return None
    position = 0

    def checked(value: int) -> int:
        if value < -2_147_483_648 or value > 2_147_483_647:
            raise ValueError
        return value

    def atom() -> int:
        nonlocal position
        if position >= len(tokens):
            raise ValueError
        token = tokens[position]
        position += 1
        if token == "(":
            value = add()
            if position >= len(tokens) or tokens[position] != ")":
                raise ValueError
            position += 1
            return value
        if re.fullmatch(r"[0-9](?:_?[0-9])*", token):
            return checked(int(token.replace("_", "")))
        resolved = resolver(token)
        if resolved is None:
            raise ValueError
        return checked(resolved)

    def multiply() -> int:
        nonlocal position
        value = atom()
        while position < len(tokens) and tokens[position] in {"*", "/"}:
            operator = tokens[position]
            position += 1
            right = atom()
            if operator == "/":
                if right == 0:
                    raise ValueError
                value = (abs(value) // abs(right)) * (-1 if (value < 0) != (right < 0) else 1)
            else:
                value *= right
            value = checked(value)
        return value

    def add() -> int:
        nonlocal position
        value = multiply()
        while position < len(tokens) and tokens[position] in {"+", "-"}:
            operator = tokens[position]
            position += 1
            right = multiply()
            value = checked(value + right if operator == "+" else value - right)
        return value

    try:
        value = add()
        return value if position == len(tokens) else None
    except ValueError:
        return None


def public_static_final_int_expression(source: str, type_symbol: str, field: str) -> str | None:
    initializer = java_static_final_initializer(source, type_symbol, field)
    span = java_type_span(source, type_symbol)
    if initializer is None or span is None:
        return None
    code = strip_c_comments_and_literals(source)[slice(*span)]
    depths = java_brace_depths(code)
    declarations = [match for match in re.finditer(
        rf"\bpublic\s+static\s+final\s+int\s+{re.escape(field)}\s*=", code,
    ) if depths[match.start()] == 1]
    return initializer[0] if len(declarations) == 1 else None


def route_bound_values(root: Path) -> dict[str, int] | None:
    sources = {
        "StableEdgeId": (STABLE_EDGE_ID_PATH, "StableEdgeId"),
        "EdgeTraversalWireBudget": (EDGE_WIRE_BUDGET_PATH, "EdgeTraversalWireBudget"),
    }
    if any(current_source_owner(root, f"{path.as_posix()}#{type_symbol}") is None
           for path, type_symbol in sources.values()):
        return None
    texts = {name: (root / path).read_text(encoding="utf-8")
             for name, (path, _type) in sources.items()}
    if not same_package_type_identity(
            texts["EdgeTraversalWireBudget"], "EdgeTraversalWireBudget",
            "ai.ravenroot.api.application.StableEdgeId"):
        return None
    cache: dict[str, int] = {}
    active: set[str] = set()

    def resolve(qualified: str, current: str | None = None) -> int | None:
        name = qualified if "." in qualified else f"{current}.{qualified}"
        if name in cache:
            return cache[name]
        if name in active or "." not in name:
            return None
        owner, field = name.split(".", 1)
        if owner not in sources or "." in field:
            return None
        expression = public_static_final_int_expression(texts[owner], owner, field)
        if expression is None:
            return None
        active.add(name)
        value = java_int_expression_value(expression, lambda token: resolve(token, owner))
        active.remove(name)
        if value is not None:
            cache[name] = value
        return value

    required = {qualified for fields in ROUTE_BOUND_CANDIDATES.values() for qualified in fields}
    result = {name: resolve(name) for name in required}
    return None if any(value is None for value in result.values()) else {
        name: int(value) for name, value in result.items()
    }


def route_table_consumer_errors(root: Path, authority: dict[str, object]) -> list[str]:
    errors: list[str] = []
    consumer_digests = authority.get("consumerBodyDigests")
    required_digests = {
        "routeDescriptorValidation", "openApiGenerate", "openApiPathEntry",
        "openApiOperationEntry", "openApiSuccessResponse",
    }
    if not isinstance(consumer_digests, dict) or set(consumer_digests) != required_digests:
        return ["RouteTable authority requires exact typed consumer body digests"]

    descriptor = (root / ROUTE_DESCRIPTOR_PATH).read_text(encoding="utf-8")
    expected_components = (
        "methods", "path", "summary", "authenticated", "registersContext", "successStatuses",
        "wireErrorCodes", "assistantPosture", "sideEffectFree",
    )
    compact = java_compact_constructor_span(descriptor, "RouteDescriptor")
    compact_code = normalized(strip_c_comments_and_literals(
        descriptor[slice(*compact)] if compact is not None else ""))
    if java_package(descriptor) != "ai.ravenroot.server.spec" \
            or java_record_components(descriptor, "RouteDescriptor") != expected_components \
            or java_span_digest(descriptor, compact) != consumer_digests["routeDescriptorValidation"]:
        errors.append("RouteTable RouteDescriptor typed component/validation digest has drifted")
    for required in (
        "path == null || path.isBlank()", "summary == null || summary.isBlank()",
        "status < 200 || status >= 300", "methods = Set.copyOf(methods)",
        "successStatuses = Set.copyOf(successStatuses)",
    ):
        if normalized(required) not in compact_code:
            errors.append(f"RouteTable RouteDescriptor validation lost {required}")
    descriptor_code = normalized(strip_c_comments_and_literals(descriptor))
    forwarding = normalized(
        "public RouteDescriptor(Set<String> methods, String path, String summary, "
        "boolean authenticated, boolean registersContext, int successStatus, "
        "List<String> wireErrorCodes, AssistantPosture assistantPosture, boolean sideEffectFree) { "
        "this(methods, path, summary, authenticated, registersContext, Set.of(successStatus), "
        "wireErrorCodes, assistantPosture, sideEffectFree); }")
    if forwarding not in descriptor_code:
        errors.append("RouteTable RouteDescriptor convenience constructor lost positional forwarding")

    generator = (root / OPENAPI_GENERATOR_PATH).read_text(encoding="utf-8")
    authoritative_methods = ("generate", "pathEntry", "operationEntry", "successResponse")
    receiver_names = {"JsonStrings", "Collectors"}
    receiver_values_are_unshadowed = all(
        java_span_uses_only_simple_receiver(
            generator, java_method_span(generator, "OpenApiSpecGenerator", method), receiver)
        for method in authoritative_methods for receiver in receiver_names
    )
    if not same_package_type_identity(
            generator, "OpenApiSpecGenerator", "ai.ravenroot.server.spec.RouteDescriptor"):
        errors.append("RouteTable OpenAPI consumer does not resolve the same-package RouteDescriptor type")
    if not exact_import_identity(generator, "ai.ravenroot.server.audit.JsonStrings") \
            or not exact_import_identity(generator, "java.util.List") \
            or not exact_import_identity(generator, "java.util.stream.Collectors") \
            or not java_has_no_simple_name_shadow(
                generator, "OpenApiSpecGenerator", receiver_names) \
            or not receiver_values_are_unshadowed:
        errors.append("RouteTable OpenAPI consumer import/receiver identity has drifted")
    generate_span = java_method_span(generator, "OpenApiSpecGenerator", "generate")
    generate_source = generator[slice(*generate_span)] if generate_span else ""
    generate_code = normalized(strip_c_comments_and_literals(generate_source))
    if java_method_header(generator, "OpenApiSpecGenerator", "generate") != \
            "public static String generate(List<RouteDescriptor> routes)" \
            or java_method_digest(generator, "OpenApiSpecGenerator", "generate") != \
            consumer_digests["openApiGenerate"]:
        errors.append("RouteTable OpenAPI generate signature/body has drifted")
    publication_chain = (
        "json.append(routes.stream().sorted(java.util.Comparator.comparing(RouteDescriptor::path))"
        ".map(OpenApiSpecGenerator::pathEntry).collect(Collectors.joining()));")
    generate_compact = re.sub(r"\s+", "", strip_c_comments_and_literals(generate_source))
    if publication_chain not in generate_compact:
        errors.append("RouteTable OpenAPI generate lost the routes-to-pathEntry append chain")
    if normalized("return json.toString()") not in generate_code:
        errors.append("RouteTable OpenAPI generate lost return json.toString()")
    for role, method, header, required in (
        ("openApiPathEntry", "pathEntry", "private static String pathEntry(RouteDescriptor route)",
         ("route.methods().stream().sorted().map(method -> operationEntry(route, method))",
          "JsonStrings.escape(route.path())", "operations")),
        ("openApiOperationEntry", "operationEntry",
         "private static String operationEntry(RouteDescriptor route, String method)",
         ("method.toLowerCase(java.util.Locale.ROOT)", "JsonStrings.escape(route.summary())",
          "route.successStatuses().stream().sorted().forEach(status -> responses.add(successResponse(route, method, status)))")),
    ):
        span = java_method_span(generator, "OpenApiSpecGenerator", method)
        code = normalized(strip_c_comments_and_literals(generator[slice(*span)] if span else ""))
        if java_method_header(generator, "OpenApiSpecGenerator", method) != header \
                or java_method_digest(generator, "OpenApiSpecGenerator", method) != consumer_digests[role]:
            errors.append(f"RouteTable typed consumer {method} signature/body has drifted")
        for expression in required:
            if normalized(expression) not in code:
                errors.append(f"RouteTable typed consumer {method} lost {expression}")
    success_span = java_method_span(generator, "OpenApiSpecGenerator", "successResponse")
    success_return = java_direct_return_expression(
        generator, "OpenApiSpecGenerator", "successResponse")
    status_prefix = normalized(
        '"          \\"" + status + "\\": {\\"description\\": \\"success\\"" +')
    if java_method_header(generator, "OpenApiSpecGenerator", "successResponse") != \
            "private static String successResponse(RouteDescriptor route, String method, int status)" \
            or java_method_digest(generator, "OpenApiSpecGenerator", "successResponse") != \
            consumer_digests["openApiSuccessResponse"] \
            or success_return is None or not success_return.startswith(status_prefix) \
            or normalized("schema == null ?") not in success_return \
            or normalized("+ schema +") not in success_return:
        errors.append("RouteTable OpenAPI successResponse lost status serialization")
    return errors


def route_publication_test_errors(root: Path, authority: dict[str, object]) -> list[str]:
    evidence = authority.get("publicationTestAuthority")
    required = {"testBodyDigest", "checkedInSpecBodyDigest"}
    if not isinstance(evidence, dict) or set(evidence) != required:
        return ["RouteTable authority requires exact publication test evidence"]
    source = (root / ROUTE_TABLE_TEST_PATH).read_text(encoding="utf-8")
    errors: list[str] = []
    test_type = "RouteTableSpecServerAgreementTest"
    method = "theCheckedInSpecMatchesWhatTheTableGeneratesRightNow"
    helper = "checkedInSpec"
    if java_package(source) != "ai.ravenroot.server.spec" \
            or not java_test_type_is_directly_runnable(source, test_type) \
            or not exact_import_identity(source, "org.junit.jupiter.api.Test") \
            or not exact_import_identity(source, "org.junit.jupiter.api.io.TempDir") \
            or not exact_import_identity(source, "java.nio.file.Path") \
            or not java_direct_field_has_annotation(source, test_type, "uiDirectory", "TempDir") \
            or not same_package_type_identity(
                source, test_type, "ai.ravenroot.server.spec.RouteTable") \
            or not same_package_type_identity(
                source, test_type, "ai.ravenroot.server.spec.OpenApiSpecGenerator") \
            or not java_has_exact_junit_assertions(
                source, test_type, {"assertEquals", "assertTrue"}):
        errors.append("RouteTable publication test type/import/TempDir identity has drifted")
    if java_method_header(source, test_type, method) != f"void {method}() throws Exception" \
            or java_method_annotations(source, test_type, method) != ("@Test",) \
            or java_method_digest(source, test_type, method) != evidence["testBodyDigest"]:
        errors.append("RouteTable publication parity test is not an exact runnable @Test")
    span = java_method_span(source, test_type, method)
    code = normalized(strip_c_comments_and_literals(source[slice(*span)] if span else ""))
    for expression in (
        "OpenApiSpecGenerator.generate(RouteTable.ALL)", "checkedInSpec()",
        "assertEquals(generatedNow, onDisk",
    ):
        if normalized(expression) not in code:
            errors.append(f"RouteTable publication parity test lost {expression}")
    helper_span = java_method_span(source, test_type, helper)
    helper_code = normalized(strip_c_comments_and_literals(
        source[slice(*helper_span)] if helper_span else ""))
    if java_method_header(source, test_type, helper) != \
            "private static String checkedInSpec() throws IOException" \
            or java_method_digest(source, test_type, helper) != evidence["checkedInSpecBodyDigest"] \
            or "getResourceAsStream(" not in helper_code or "readAllBytes()" not in helper_code:
        errors.append("RouteTable checkedInSpec helper closure has drifted")
    return errors


def route_bound_test_errors(root: Path, authority: dict[str, object]) -> list[str]:
    evidence = authority.get("boundTestBodyDigests")
    required = {
        "StableEdgeIdContractTest": {
            "acceptsTheExactUtf8BoundWithoutChangingIdentityAndRejectsOneByteMore",
            "auxiliaryReserveIsEnforcedAsOneCombinedEscapedByteBudget",
        },
        "StableEdgeIdWireContractTest": {
            "worstCaseEscapedMaximumFitsTheCompleteRuntimeClientFrame",
            "saturatedLiveAndLogFieldsStillFitWithTheMaximumEscapedIdentity",
            "saturatedDurableProjectionAndPayloadStayInsideTheirExplicitBounds",
        },
    }
    if not isinstance(evidence, dict) or set(evidence) != set(required):
        return ["RouteTable authority requires exact typed-bound test evidence"]
    errors: list[str] = []
    for test_type, methods in required.items():
        relative = STABLE_EDGE_TEST_PATH if test_type == "StableEdgeIdContractTest" \
            else STABLE_EDGE_WIRE_TEST_PATH
        source = (root / relative).read_text(encoding="utf-8")
        recorded = evidence.get(test_type)
        type_names = {"StableEdgeId", "EdgeTraversalWireBudget"}
        same_package = test_type == "StableEdgeIdContractTest"
        imports_are_exact = (java_package(source) == "ai.ravenroot.api.application"
                             and same_package_type_identity(
                                 source, test_type,
                                 "ai.ravenroot.api.application.StableEdgeId")
                             and same_package_type_identity(
                                 source, test_type,
                                 "ai.ravenroot.api.application.EdgeTraversalWireBudget")
                             if same_package else
                             exact_import_identity(source, "ai.ravenroot.api.application.StableEdgeId")
                             and exact_import_identity(
                                 source, "ai.ravenroot.api.application.EdgeTraversalWireBudget"))
        if not isinstance(recorded, dict) or set(recorded) != methods \
                or not java_test_type_is_directly_runnable(source, test_type) \
                or not exact_import_identity(source, "org.junit.jupiter.api.Test") \
                or not imports_are_exact \
                or not java_has_no_simple_name_shadow(source, test_type, type_names) \
                or not java_has_exact_junit_assertions(
                    source, test_type, {"assertEquals", "assertThrows", "assertTrue"}):
            errors.append(f"RouteTable typed-bound test authority {test_type} is incomplete")
            continue
        for method in methods:
            if java_method_header(source, test_type, method) != f"void {method}()" \
                    or java_method_annotations(source, test_type, method) != ("@Test",) \
                    or java_method_digest(source, test_type, method) != recorded[method]:
                errors.append(f"RouteTable typed-bound test {test_type}.{method} has drifted")
                continue
            span = java_method_span(source, test_type, method)
            code = normalized(strip_c_comments_and_literals(
                source[slice(*span)] if span is not None else ""))
            structural = {
                "acceptsTheExactUtf8BoundWithoutChangingIdentityAndRejectsOneByteMore": (
                    "StableEdgeId.MAX_UTF8_BYTES", "assertEquals", "assertThrows"),
                "auxiliaryReserveIsEnforcedAsOneCombinedEscapedByteBudget": (
                    "EdgeTraversalWireBudget.MAX_AUXILIARY_ESCAPED_VALUE_BYTES",
                    "requireLiveProjection", "requireDurableProjection", "assertThrows"),
                "worstCaseEscapedMaximumFitsTheCompleteRuntimeClientFrame": (
                    "StableEdgeId.MAX_UTF8_BYTES", "StableEdgeId.SSE_FRAME_MAX_BYTES",
                    "liveFrame.length", "assertTrue"),
                "saturatedLiveAndLogFieldsStillFitWithTheMaximumEscapedIdentity": (
                    "EdgeTraversalWireBudget.MAX_AUXILIARY_ESCAPED_VALUE_BYTES",
                    "StableEdgeId.SSE_FRAME_MAX_BYTES", "liveFrame.length", "logLine.length"),
                "saturatedDurableProjectionAndPayloadStayInsideTheirExplicitBounds": (
                    "EdgeTraversalWireBudget.MAX_AUXILIARY_ESCAPED_VALUE_BYTES",
                    "StableEdgeId.SSE_FRAME_MAX_BYTES", "durableFrame.length", "assertEquals"),
            }[method]
            for expression in structural:
                if expression not in code:
                    errors.append(
                        f"RouteTable typed-bound test {test_type}.{method} lost {expression}")
    return errors


def route_table_authority_errors(root: Path, authorities: object,
                                 entries: dict[str, dict[str, object]],
                                 discovered: dict[str, Candidate]) -> list[str]:
    """Verify the closed RouteTable retained-candidate family and publication evidence."""
    reviewed = [entry for entry in entries.values()
                if entry.get("path") == ROUTE_TABLE_PATH.as_posix()
                and entry.get("status") != "pending-review"]
    if not reviewed:
        return [] if authorities in (None, {}) else ["RouteTable authority exists without reviewed rows"]
    if not isinstance(authorities, dict) or set(authorities) != {ROUTE_TABLE_AUTHORITY_ID}:
        return ["reviewed RouteTable rows require the one closed RouteTable authority"]
    authority = authorities[ROUTE_TABLE_AUTHORITY_ID]
    required = {
        "kind", "candidateIdsByRole", "descriptorCandidateIds", "consumerBodyDigests",
        "publicationTestAuthority", "boundTestBodyDigests", "publishedBoundClauses",
    }
    if not isinstance(authority, dict) or set(authority) != required \
            or authority.get("kind") != "java-route-descriptor-publication-v1":
        return ["RouteTable authority has an unsupported or incomplete shape"]
    required_sources = {
        ROUTE_TABLE_PATH: "RouteTable", ROUTE_DESCRIPTOR_PATH: "RouteDescriptor",
        OPENAPI_GENERATOR_PATH: "OpenApiSpecGenerator",
        ROUTE_TABLE_TEST_PATH: "RouteTableSpecServerAgreementTest",
        STABLE_EDGE_ID_PATH: "StableEdgeId", EDGE_WIRE_BUDGET_PATH: "EdgeTraversalWireBudget",
        STABLE_EDGE_TEST_PATH: "StableEdgeIdContractTest",
        STABLE_EDGE_WIRE_TEST_PATH: "StableEdgeIdWireContractTest",
    }
    if any(current_source_owner(root, f"{path.as_posix()}#{symbol}") is None
           for path, symbol in required_sources.items()):
        return ["RouteTable authority has a missing tracked source/test owner"]
    source = (root / ROUTE_TABLE_PATH).read_text(encoding="utf-8")
    if not same_package_type_identity(
            source, "RouteTable", "ai.ravenroot.server.spec.RouteDescriptor"):
        return ["RouteTable.ALL does not resolve the same-package RouteDescriptor type"]
    parsed = route_table_candidate_partitions(source)
    if parsed is None:
        return ["RouteTable.ALL is not the supported direct RouteDescriptor table"]
    partitions, details, source_candidates = parsed
    errors: list[str] = []
    expected_counts = {"methods": 60, "path": 53, "summary": 348, "successStatuses": 54}
    if len(details) != 53 or {role: len(ids) for role, ids in partitions.items()} != expected_counts:
        errors.append("RouteTable authority no longer has the reviewed 53/515 positional shape")
    recorded = authority["candidateIdsByRole"]
    if not isinstance(recorded, dict) or set(recorded) != set(expected_counts) \
            or any(recorded.get(role) != partitions[role] for role in expected_counts):
        errors.append("RouteTable authority candidate positional partitions have drifted")
    descriptor_evidence = [
        {"ordinal": detail["ordinal"], "path": detail["path"],
         "candidateIds": detail["candidateIds"]}
        for detail in details
    ]
    if authority["descriptorCandidateIds"] != descriptor_evidence:
        errors.append("RouteTable authority descriptor ordinal/path candidate positions have drifted")
    all_ids = [identifier for values in partitions.values() for identifier in values]
    if len(all_ids) != len(set(all_ids)) or set(all_ids) != set(source_candidates):
        errors.append("RouteTable authority does not partition every RouteTable candidate exactly once")
    expected_classification = {
        **{identifier: "protocol-or-format-invariant"
           for role in ("methods", "path", "successStatuses") for identifier in partitions[role]},
        **{identifier: "published-contract-description" for identifier in partitions["summary"]},
    }
    for identifier, classification in expected_classification.items():
        entry = entries.get(identifier)
        if entry is None or entry.get("classification") != classification \
                or entry.get("status") != "retained" \
                or entry.get("retainedAuthority") != ROUTE_TABLE_AUTHORITY_ID:
            errors.append(f"RouteTable candidate {identifier} lacks its exact retained positional authority")
        if identifier not in discovered or discovered[identifier].path != ROUTE_TABLE_PATH.as_posix():
            errors.append(f"RouteTable candidate {identifier} is absent from current discovery")
    if set(expected_classification) != {str(entry.get("id")) for entry in reviewed}:
        errors.append("RouteTable reviewed rows do not equal the complete supported candidate family")

    clauses = authority["publishedBoundClauses"]
    expected_clauses = {identifier: list(fields)
                        for identifier, fields in ROUTE_BOUND_CANDIDATES.items()}
    if clauses != expected_clauses:
        errors.append("RouteTable published bound clauses do not use exact candidate-specific authorities")
    values = route_bound_values(root)
    by_path = {str(detail["path"]): str(detail["summary"]) for detail in details}
    if values is None:
        errors.append("RouteTable typed wire-bound constants are not resolvable")
    else:
        max_id = values["StableEdgeId.MAX_UTF8_BYTES"]
        auxiliary = values["EdgeTraversalWireBudget.MAX_AUXILIARY_ESCAPED_VALUE_BYTES"]
        frame = values["StableEdgeId.SSE_FRAME_MAX_BYTES"]
        typed_clauses = {
            "/v1/events": (
                f"edgeId is accepted unchanged up to {max_id} strict UTF-8 bytes;",
                f"all auxiliary traversal strings share a {auxiliary}-byte escaped budget",
                f"the complete frame below {frame} bytes.",
            ),
            "/v1/events/recent": (
                f"edgeId is accepted unchanged up to {max_id} strict UTF-8 bytes; "
                f"auxiliary traversal strings share a {auxiliary}-byte escaped budget",
                f"keeping each frame below {frame} bytes.",
            ),
        }
        for path, expected in typed_clauses.items():
            summary = by_path.get(path, "")
            for clause in expected:
                if summary.count(clause) != 1:
                    errors.append(f"RouteTable {path} summary lost typed bound clause: {clause}")
        for identifier, fields in ROUTE_BOUND_CANDIDATES.items():
            expected_path = ROUTE_BOUND_PATHS[identifier]
            detail = next((item for item in details
                           if item["path"] == expected_path
                           and identifier in item["candidateIds"]["summary"]), None)
            candidate = source_candidates.get(identifier)
            if detail is None or candidate is None or any(
                    str(values[field]) not in candidate.expression for field in fields):
                errors.append(
                    f"RouteTable typed bound candidate {identifier} is not the exact {expected_path} clause")
    errors.extend(route_table_consumer_errors(root, authority))
    errors.extend(route_publication_test_errors(root, authority))
    errors.extend(route_bound_test_errors(root, authority))
    return errors


def assistant_limit_binding_call(source: str, component: str) -> tuple[str, int, int] | None:
    call = java_constructor_component_call(
        source, "AssistantConfiguration", "fromEnvironment", "AssistantConfiguration",
        ASSISTANT_LIMIT_COMPONENTS, component,
    )
    return call


def java_identifier_write_count(code: str, identifier: str) -> int:
    """Count direct/compound writes in one already masked Java span."""
    name = re.escape(identifier)
    writes = re.findall(
        rf"\b{name}\s*(?:>>>=|>>=|<<=|=(?!=)|[+\-*/%&|^]=|\+\+|--)"
        rf"|(?:\+\+|--)\s*\b{name}\b",
        code,
    )
    return len(writes)


def assistant_limit_source_specs(source: str) -> list[dict[str, object]] | None:
    """Derive the checker-owned two symbol-bound AssistantConfiguration components."""
    if java_package(source) != "ai.ravenroot.server.assistant" \
            or java_record_components(source, "AssistantConfiguration") != ASSISTANT_LIMIT_COMPONENTS \
            or java_method_header(source, "AssistantConfiguration", "fromEnvironment") != \
            "public static AssistantConfiguration fromEnvironment(Map<String, String> environment)" \
            or not exact_import_identity(source, "java.util.Map") \
            or not java_has_no_simple_name_shadow(
                source, "AssistantConfiguration", {"Map"}):
        return None
    factory_span = java_method_span(source, "AssistantConfiguration", "fromEnvironment")
    factory_code = strip_c_comments_and_literals(source[slice(*factory_span)] if factory_span else "")
    if len(re.findall(r"\bboundedPositiveInteger\s*\(", factory_code)) != 2 \
            or len(re.findall(
                r"\bMap\s*<\s*String\s*,\s*String\s*>\s+env\s*=\s*"
                r"environment\s*==\s*null\s*\?\s*Map\.of\s*\(\s*\)\s*:\s*environment\s*;",
                factory_code,
            )) != 1:
        return None
    protected_symbols = {
        str(setting[key]) for setting in ASSISTANT_LIMIT_SETTINGS
        for key in ("environmentSymbol", "defaultSymbol")
    }
    if java_identifier_write_count(factory_code, "env") != 1 \
            or java_identifier_write_count(factory_code, "environment") != 0 \
            or any(java_identifier_write_count(factory_code, symbol) != 0
                   for symbol in protected_symbols):
        return None
    result: list[dict[str, object]] = []
    for expected in ASSISTANT_LIMIT_SETTINGS:
        call = assistant_limit_binding_call(source, str(expected["component"]))
        if call is None:
            return None
        argument, start, end = call
        pattern = re.fullmatch(
            r"boundedPositiveInteger\(env\.get\(([A-Za-z_$][\w$]*)\),\s*"
            r"([A-Za-z_$][\w$]*),\s*([A-Za-z_$][\w$]*)\)", argument,
        )
        if pattern is None or pattern.group(1) != pattern.group(2):
            return None
        env_symbol, default_symbol = pattern.group(1), pattern.group(3)
        env_initializer = java_static_final_initializer(source, "AssistantConfiguration", env_symbol)
        default_initializer = java_static_final_initializer(
            source, "AssistantConfiguration", default_symbol)
        type_span = java_type_span(source, "AssistantConfiguration")
        type_code = strip_c_comments_and_literals(source)[slice(*type_span)] \
            if type_span is not None else ""
        type_depths = java_brace_depths(type_code)
        env_declarations = [match for match in re.finditer(
            rf"\bpublic\s+static\s+final\s+String\s+{re.escape(env_symbol)}\s*=", type_code,
        ) if type_depths[match.start()] == 1]
        if env_initializer is None or default_initializer is None \
                or len(env_declarations) != 1 \
                or not re.fullmatch(rf'"{re.escape(str(expected["environment"]))}"', env_initializer[0]) \
                or public_static_final_int_expression(
                    source, "AssistantConfiguration", default_symbol) is None:
            return None
        default_value = java_int_expression_value(default_initializer[0], lambda _token: None)
        derived = dict(expected)
        derived.update({
            "call": argument, "callStart": start, "callEnd": end,
            "environmentSymbol": env_symbol, "environmentSpan": env_initializer[1:],
            "defaultSymbol": default_symbol, "defaultExpression": default_initializer[0],
            "defaultSpan": default_initializer[1:], "defaultValue": default_value,
        })
        if any(derived[key] != expected[key] for key in (
                "environmentSymbol", "defaultSymbol", "defaultValue")):
            return None
        result.append(derived)
    return result


def assistant_limit_carrier_errors(root: Path, spec: dict[str, object], evidence: object,
                                   entries: dict[str, dict[str, object]],
                                   discovered: dict[str, Candidate]) -> tuple[list[str], set[str]]:
    required = {"environment", "expectedCandidateIds"}
    setting = str(spec["setting"])
    if not isinstance(evidence, dict) or set(evidence) != required \
            or evidence.get("environment") != spec["environment"]:
        return ([f"{setting}: assistant carrier evidence has an unsupported shape"], set())
    groups = evidence["expectedCandidateIds"]
    if not isinstance(groups, dict) or set(groups) != set(ASSISTANT_CARRIER_PATHS):
        return ([f"{setting}: assistant carrier evidence must include every checker-owned group"], set())
    errors: list[str] = []
    accounted: set[str] = set()
    environment = str(spec["environment"])
    for group, paths in ASSISTANT_CARRIER_PATHS.items():
        actual = sorted(candidate.id for candidate in discovered.values()
                        if candidate.path in paths and candidate.kind == "environment-binding"
                        and candidate.expression == environment)
        if groups.get(group) != actual:
            errors.append(f"{setting}: assistant {group} candidate set has drifted")
        accounted.update(actual)
        if any(entries.get(identifier, {}).get("setting") != setting for identifier in actual):
            errors.append(f"{setting}: assistant carrier candidate is absent or assigned elsewhere")

    helm_field = str(spec["helmField"])
    maximum = int(spec["defaultValue"])
    values_source = (root / "deploy/helm/ravenroot/values.yaml").read_text(encoding="utf-8")
    if yaml_scalar_at_path(values_source, f"assistant.{helm_field}") != '""':
        errors.append(f"{setting}: Helm value must be an explicit blank default")
    try:
        schema = json.loads((root / "deploy/helm/ravenroot/values.schema.json").read_text(
            encoding="utf-8"))
        assistant = schema["properties"]["assistant"]
        expected_fields = {str(item["helmField"]) for item in ASSISTANT_LIMIT_SETTINGS}
        required_fields = assistant.get("required")
        properties = assistant.get("properties")
        if set(assistant) != {"type", "additionalProperties", "required", "properties"} \
                or assistant.get("type") != "object" \
                or assistant.get("additionalProperties") is not False \
                or not isinstance(required_fields, list) or len(required_fields) != 2 \
                or set(required_fields) != expected_fields \
                or not isinstance(properties, dict) or set(properties) != expected_fields:
            raise ValueError("unsupported assistant schema object")
        leaf = properties[helm_field]
        branches = leaf["oneOf"]
        if not isinstance(branches, list) or len(branches) != 2:
            raise ValueError("unsupported assistant schema branch set")
        integer = next(item for item in branches if item.get("type") == "integer")
        reference = next(item for item in branches if "$ref" in item)
        resolved_reference = resolved_json_schema_value(schema, reference)
        expected_reference = {
            "$ref": "#/definitions/graphBlank",
            "resolved": {
                "type": "string",
                "pattern": "^[\t-\r\x1c- \u1680\u2000-\u2006\u2008-\u200a"
                           "\u2028-\u2029\u205f\u3000]*$",
            },
        }
        if set(leaf) != {"x-ravenroot-environment", "oneOf"} \
                or branches != [integer, reference] \
                or helm_field not in assistant["required"] \
                or leaf.get("x-ravenroot-environment") != environment \
                or integer != {"type": "integer", "minimum": 1, "maximum": maximum} \
                or resolved_reference != expected_reference:
            errors.append(f"{setting}: Helm schema binding/range/blank reference has drifted")
    except (KeyError, TypeError, ValueError, StopIteration, json.JSONDecodeError):
        errors.append(f"{setting}: Helm schema binding/range/blank reference is unsupported")
    template = (root / "deploy/helm/ravenroot/templates/deployment.yaml").read_text(encoding="utf-8")
    expected_template = (f"- name: {environment}\n"
                         f"              value: {{{{ include \"ravenroot.graphLimitValue\" "
                         f".Values.assistant.{helm_field} }}}}")
    if template.count(expected_template) != 1:
        errors.append(f"{setting}: Helm template environment-to-value mapping has drifted")
    raw = (root / "deploy/kubernetes/ravenroot.yaml").read_text(encoding="utf-8")
    if len(re.findall(
            rf"(?m)^\s*- name:\s*{re.escape(environment)}\s*$\n\s*value:\s*\"\"\s*$", raw)) != 1:
        errors.append(f"{setting}: raw Kubernetes blank carrier has drifted")
    for relative in (Path("compose.yaml"), Path("docs/examples/assistant/compose.override.yaml")):
        text = (root / relative).read_text(encoding="utf-8")
        if text.count(f"{environment}: ${{{environment}:-}}") != 1:
            errors.append(f"{setting}: {relative.as_posix()} blank forwarding has drifted")
    return errors, accounted


def assistant_limit_conversion_errors(root: Path, spec: dict[str, object], conversion: object) \
        -> list[str]:
    required = {
        "kind", "issue", "beforeRevision", "afterRevision", "path", "ownerType", "method",
        "constructorType", "component", "componentIndex", "beforeArgument", "afterArgument",
        "environmentSymbol", "environment", "defaultSymbol",
    }
    setting = str(spec["setting"])
    if not isinstance(conversion, dict) or set(conversion) != required \
            or conversion.get("kind") != "java-constructor-binding-conversion-v1":
        return [f"{setting}: assistant conversion authority has an unsupported shape"]
    path = ASSISTANT_CONFIGURATION_PATH.as_posix()
    errors: list[str] = []
    transition, before_source, after_source = revision_transition_errors(
        root, setting, conversion, path=path, symbol="AssistantConfiguration", label="conversion")
    errors.extend(transition)
    before = str(conversion["beforeRevision"])
    after = str(conversion["afterRevision"])
    if commit_exists(root, before) and commit_exists(root, after):
        parent = subprocess.run(
            ["git", "rev-parse", f"{after}^"], cwd=root, capture_output=True, text=True)
        if parent.returncode != 0 or parent.stdout.strip() != before:
            errors.append(f"{setting}: assistant conversion revisions must be direct parent/child")
    metadata = {
        "path": path, "ownerType": "AssistantConfiguration", "method": "fromEnvironment",
        "constructorType": "AssistantConfiguration", "component": spec["component"],
        "componentIndex": spec["componentIndex"], "environmentSymbol": spec["environmentSymbol"],
        "environment": spec["environment"], "defaultSymbol": spec["defaultSymbol"],
    }
    if any(conversion.get(key) != value for key, value in metadata.items()):
        errors.append(f"{setting}: assistant conversion metadata does not match its source family")
    if before_source is not None and after_source is not None:
        before_call = assistant_limit_binding_call(before_source, str(spec["component"]))
        after_call = assistant_limit_binding_call(after_source, str(spec["component"]))
        expected_before = str(spec["defaultSymbol"])
        expected_after = (
            f"boundedPositiveInteger(env.get({spec['environmentSymbol']}), "
            f"{spec['environmentSymbol']}, {spec['defaultSymbol']})")
        if before_call is None or before_call[0] != expected_before \
                or conversion.get("beforeArgument") != expected_before \
                or after_call is None or after_call[0] != expected_after \
                or conversion.get("afterArgument") != expected_after:
            errors.append(f"{setting}: assistant conversion constructor arguments have drifted")
        before_env = java_static_final_initializer(
            before_source, "AssistantConfiguration", str(spec["environmentSymbol"]))
        after_env = java_static_final_initializer(
            after_source, "AssistantConfiguration", str(spec["environmentSymbol"]))
        before_default = java_static_final_initializer(
            before_source, "AssistantConfiguration", str(spec["defaultSymbol"]))
        after_default = java_static_final_initializer(
            after_source, "AssistantConfiguration", str(spec["defaultSymbol"]))
        if before_env is not None or after_env is None \
                or after_env[0] != f'"{spec["environment"]}"' \
                or before_default is None or after_default is None \
                or before_default[0] != after_default[0]:
            errors.append(f"{setting}: assistant conversion declaration/default transition has drifted")
    return errors


def assistant_limit_resolver_errors(root: Path, resolver: object) -> list[str]:
    required = {
        "kind", "path", "type", "factoryMethod", "factoryBodyDigest", "integerMethod",
        "integerBodyDigest", "dependencyBodyDigests", "testPath", "testType", "testBodyDigests",
        "testHelperBodyDigests",
    }
    if not isinstance(resolver, dict) or set(resolver) != required \
            or resolver.get("kind") != "java-symbol-bounded-positive-integer-resolver-v1":
        return ["assistant operational limits require one exact resolver authority"]
    errors: list[str] = []
    path = ASSISTANT_CONFIGURATION_PATH
    test_path = ASSISTANT_CONFIGURATION_TEST_PATH
    if resolver.get("path") != path.as_posix() or resolver.get("type") != "AssistantConfiguration" \
            or resolver.get("factoryMethod") != "fromEnvironment" \
            or resolver.get("integerMethod") != "boundedPositiveInteger":
        errors.append("assistant resolver source identity has drifted")
    source = (root / path).read_text(encoding="utf-8")
    if resolver.get("factoryBodyDigest") != java_method_digest(
            source, "AssistantConfiguration", "fromEnvironment") \
            or resolver.get("integerBodyDigest") != java_method_digest(
                source, "AssistantConfiguration", "boundedPositiveInteger"):
        errors.append("assistant resolver factory/helper digest has drifted")
    dependencies = resolver.get("dependencyBodyDigests")
    expected_dependencies = {
        method: java_method_digest(source, "AssistantConfiguration", method)
        for method in ("trimmed", "boundedIntegerRefusal")
    }
    integer_span = java_method_span(source, "AssistantConfiguration", "boundedPositiveInteger")
    integer_code = normalized(strip_c_comments(
        source[slice(*integer_span)] if integer_span else ""))
    expected_integer_code = normalized("""
        boundedPositiveInteger(String value, String variable, int defaultAndMaximum) {
            String normalized = trimmed(value);
            if (normalized == null) { return defaultAndMaximum; }
            int parsed;
            try { parsed = Integer.parseInt(normalized); }
            catch (NumberFormatException invalid) {
                throw boundedIntegerRefusal(variable, defaultAndMaximum);
            }
            if (parsed < 1 || parsed > defaultAndMaximum) {
                throw boundedIntegerRefusal(variable, defaultAndMaximum);
            }
            return parsed;
        }
    """)
    if dependencies != expected_dependencies \
            or java_method_header(source, "AssistantConfiguration", "boundedPositiveInteger") != \
            "private static int boundedPositiveInteger(String value, String variable, int defaultAndMaximum)" \
            or integer_code != expected_integer_code:
        errors.append("assistant resolver helper closure/contract has drifted")
    trimmed_span = java_method_span(source, "AssistantConfiguration", "trimmed")
    trimmed_code = normalized(strip_c_comments(
        source[slice(*trimmed_span)] if trimmed_span else ""))
    refusal = java_direct_return_expression(
        source, "AssistantConfiguration", "boundedIntegerRefusal")
    expected_trimmed_code = normalized("""
        trimmed(String value) {
            if (value == null) { return null; }
            String stripped = value.strip();
            return stripped.isEmpty() ? null : stripped;
        }
    """)
    normal_imports = re.findall(
        r"(?m)^\s*import\s+(?!static\s)([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+)\s*;",
        strip_c_comments_and_literals(source),
    )
    java_lang_names = {"Integer", "NumberFormatException", "IllegalArgumentException"}
    if trimmed_code != expected_trimmed_code \
            or java_method_header(source, "AssistantConfiguration", "trimmed") != \
            "private static String trimmed(String value)" \
            or refusal is None \
            or refusal != normalized(
                'new IllegalArgumentException(variable + " must be a whole number from 1 to " + maximum)') \
            or java_method_header(source, "AssistantConfiguration", "boundedIntegerRefusal") != \
            "private static IllegalArgumentException boundedIntegerRefusal(String variable, int maximum)" \
            or any(item.rsplit(".", 1)[-1] in java_lang_names for item in normal_imports) \
            or not java_has_no_simple_name_shadow(
                source, "AssistantConfiguration", java_lang_names):
        errors.append("assistant resolver blank/refusal dependency structure has drifted")
    if resolver.get("testPath") != test_path.as_posix() \
            or resolver.get("testType") != "AssistantConfigurationTest":
        errors.append("assistant resolver test identity has drifted")
        return errors
    test_source = (root / test_path).read_text(encoding="utf-8")
    roles = (
        "assistantOperationalLimitsDefaultAndTightenIndependently",
        "invalidAssistantOperationalLimitsAreCauseFreeAndDoNotEchoValues",
        "compactConstructorKeepsItsCompatibilityFallbacks",
    )
    recorded = resolver.get("testBodyDigests")
    helper_recorded = resolver.get("testHelperBodyDigests")
    if not isinstance(recorded, dict) or set(recorded) != set(roles) \
            or not java_test_type_is_directly_runnable(test_source, "AssistantConfigurationTest") \
            or not exact_import_identity(test_source, "org.junit.jupiter.api.Test") \
            or not java_has_exact_junit_assertions(
                test_source, "AssistantConfigurationTest",
                {"assertEquals", "assertFalse", "assertNull", "assertThrows", "assertTrue"}):
        errors.append("assistant resolver runnable test authority is incomplete")
    for method in roles:
        if java_method_header(test_source, "AssistantConfigurationTest", method) != f"void {method}()" \
                or java_method_annotations(test_source, "AssistantConfigurationTest", method) != ("@Test",) \
                or not isinstance(recorded, dict) \
                or recorded.get(method) != java_method_digest(
                    test_source, "AssistantConfigurationTest", method):
            errors.append(f"assistant resolver test role {method} has drifted")
    default_span = java_method_span(
        test_source, "AssistantConfigurationTest",
        "assistantOperationalLimitsDefaultAndTightenIndependently")
    default_code = normalized(strip_c_comments_and_literals(
        test_source[slice(*default_span)] if default_span else ""))
    required_default_clauses = (
        "AssistantConfiguration.fromEnvironment(Map.of())",
        "AssistantConfiguration.DEFAULT_MAX_OUTPUT_TOKENS",
        "defaults.maxOutputTokens()",
        "AssistantConfiguration.DEFAULT_MAX_TOOL_ITERATIONS",
        "defaults.maxToolIterations()",
        "for (String blank : new String[]",
        "blankConfiguration.maxOutputTokens()",
        "blankConfiguration.maxToolIterations()",
        "outputOnly.maxOutputTokens()", "outputOnly.maxToolIterations()",
        "iterationsOnly.maxOutputTokens()", "iterationsOnly.maxToolIterations()",
        "maxima.maxOutputTokens()", "maxima.maxToolIterations()",
    )
    invalid_span = java_method_span(
        test_source, "AssistantConfigurationTest",
        "invalidAssistantOperationalLimitsAreCauseFreeAndDoNotEchoValues")
    invalid_code = normalized(strip_c_comments_and_literals(
        test_source[slice(*invalid_span)] if invalid_span else ""))
    if any(normalized(clause) not in default_code for clause in required_default_clauses) \
            or invalid_code.count("assertInvalidLimit(") != 2 \
            or "AssistantConfiguration.MAX_OUTPUT_TOKENS_VARIABLE" not in invalid_code \
            or "AssistantConfiguration.MAX_TOOL_ITERATIONS_VARIABLE" not in invalid_code \
            or "AssistantConfiguration.DEFAULT_MAX_OUTPUT_TOKENS" not in invalid_code \
            or "AssistantConfiguration.DEFAULT_MAX_TOOL_ITERATIONS" not in invalid_code:
        errors.append("assistant resolver runnable test clauses have drifted")
    helper_method = "assertInvalidLimit"
    helper_span = java_method_span(test_source, "AssistantConfigurationTest", helper_method)
    helper_code = normalized(strip_c_comments_and_literals(
        test_source[slice(*helper_span)] if helper_span else ""))
    if helper_recorded != {helper_method: java_method_digest(
            test_source, "AssistantConfigurationTest", helper_method)} \
            or java_method_header(test_source, "AssistantConfigurationTest", helper_method) != \
            "private static void assertInvalidLimit(String variable, int maximum, String... invalidValues)" \
            or any(expression not in helper_code for expression in (
                "for (String invalid : invalidValues)",
                "AssistantConfiguration.fromEnvironment(Map.of(variable, invalid))",
                "variable + + maximum", "failure.getCause()", "failure.getMessage().contains(",
            )):
        errors.append("assistant resolver invalid-value test helper closure has drifted")
    return errors


def assistant_limit_consumer_errors(root: Path, consumer: object) -> list[str]:
    required = {"path", "type", "method", "bodyDigest", "testPath", "testType", "testBodyDigest"}
    if not isinstance(consumer, dict) or set(consumer) != required:
        return ["assistant operational limits require exact live consumer evidence"]
    errors: list[str] = []
    source = (root / ASSISTANT_SERVICE_PATH).read_text(encoding="utf-8")
    if consumer.get("path") != ASSISTANT_SERVICE_PATH.as_posix() \
            or consumer.get("type") != "AssistantService" or consumer.get("method") != "send" \
            or consumer.get("bodyDigest") != java_method_digest(source, "AssistantService", "send") \
            or not exact_import_identity(
                source, "ai.ravenroot.server.assistant.provider.AssistantProvider") \
            or not java_has_no_simple_name_shadow(
                source, "AssistantService", {"AssistantProvider"}):
        errors.append("assistant live consumer source identity/digest has drifted")
    span = java_method_span(source, "AssistantService", "send")
    actual = source[slice(*span)] if span else ""
    code = strip_c_comments_and_literals(actual)
    loops = list(re.finditer(
        r"for\s*\(\s*int\s+iteration\s*=\s*0\s*;\s*iteration\s*<\s*"
        r"configuration\.maxToolIterations\s*\(\s*\)\s*;\s*iteration\+\+\s*\)\s*\{", code))
    if len(loops) != 1:
        errors.append("assistant live consumer has no exact active configured provider loop")
    else:
        opening = code.find("{", loops[0].start())
        closing = matching_delimiter(code, opening, "{", "}")
        loop_actual = actual[opening + 1:closing] if closing is not None else ""
        loop_code = code[opening + 1:closing] if closing is not None else ""
        calls = list(re.finditer(r"\bturnProvider\.complete\s*\(", loop_code))
        if len(calls) != 1:
            errors.append("assistant live consumer provider call is not uniquely inside the configured loop")
        else:
            call_open = loop_code.find("(", calls[0].start())
            complete = split_java_arguments(loop_actual, loop_code, call_open)
            request = complete[0][0][0] if complete is not None and len(complete[0]) == 1 else ""
            request_code = strip_c_comments_and_literals(request)
            request_match = re.match(r"\s*new\s+AssistantProvider\.Request\s*\(", request_code)
            request_args = (split_java_arguments(
                request, request_code, request_code.find("(", request_match.start()))
                if request_match is not None else None)
            if request_args is None or len(request_args[0]) != 5 \
                    or request_args[0][4][0] != "configuration.maxOutputTokens()":
                errors.append("assistant live consumer output limit is not the exact provider request argument")
    if span is None or not java_span_uses_only_simple_receiver(source, span, "configuration"):
        errors.append("assistant live consumer configuration receiver is shadowed or unsupported")
    if normalized("AssistantProvider turnProvider = providerFor(context.subject());") not in \
            normalized(code) or code.count("AssistantProvider turnProvider") != 1:
        errors.append("assistant live consumer provider selection has drifted")

    test_source = (root / ASSISTANT_SERVICE_TEST_PATH).read_text(encoding="utf-8")
    test_type = "AssistantGraphProposalTest"
    method = "configuredOperationalLimitsReachEveryRequestAndStopTheProviderLoop"
    if consumer.get("testPath") != ASSISTANT_SERVICE_TEST_PATH.as_posix() \
            or consumer.get("testType") != test_type \
            or consumer.get("testBodyDigest") != java_method_digest(test_source, test_type, method) \
            or not java_test_type_is_directly_runnable(test_source, test_type) \
            or not exact_import_identity(test_source, "org.junit.jupiter.api.Test") \
            or not java_has_exact_junit_assertions(
                test_source, test_type, {"assertEquals", "assertInstanceOf", "assertTrue"}) \
            or java_method_header(test_source, test_type, method) != f"void {method}()" \
            or java_method_annotations(test_source, test_type, method) != ("@Test",) \
            or not same_package_type_identity(
                test_source, test_type,
                "ai.ravenroot.server.assistant.AssistantHarness") \
            or not same_package_type_identity(
                test_source, test_type,
                "ai.ravenroot.server.assistant.AssistantOutcome"):
        errors.append("assistant live consumer runnable test authority has drifted")
    test_span = java_method_span(test_source, test_type, method)
    test_code = normalized(strip_c_comments_and_literals(
        test_source[slice(*test_span)] if test_span else ""))
    for expression in (
        "readyConfiguration(17, 2)", "callingTool", "answering",
        "AssistantOutcome.Reason.TOOL_LOOP_EXHAUSTED", "assertEquals(2, provider.callCount())",
        "assertEquals(2, provider.received().size())", "request.maxTokens() == 17",
    ):
        if normalized(expression) not in test_code:
            errors.append(f"assistant live consumer test lost {expression}")
    if test_code.count("callingTool(") != 2 or test_code.count("answering(") != 1:
        errors.append("assistant live consumer test lost its two-call/third-sentinel structure")
    chain = re.compile(
        r"new\s+AssistantHarness\.ScriptedProviderView\s*\(\s*\)\s*"
        r"\.callingTool\s*\([^)]*\)\s*\.callingTool\s*\([^)]*\)\s*"
        r"\.answering\s*\([^)]*\)", re.S)
    if len(chain.findall(strip_c_comments(test_source[slice(*test_span)] if test_span else ""))) != 1:
        errors.append("assistant live consumer test lost its ordered provider script")
    return errors


def assistant_limit_compatibility_errors(root: Path, compatibility: object) -> list[str]:
    required = {"constructorBodyDigest", "testBodyDigest"}
    if not isinstance(compatibility, dict) or set(compatibility) != required:
        return ["assistant operational limits require exact compact-constructor compatibility evidence"]
    source = (root / ASSISTANT_CONFIGURATION_PATH).read_text(encoding="utf-8")
    span = java_compact_constructor_span(source, "AssistantConfiguration")
    constructor = normalized(strip_c_comments_and_literals(source[slice(*span)] if span else ""))
    errors: list[str] = []
    if compatibility.get("constructorBodyDigest") != java_span_digest(source, span) \
            or java_identifier_write_count(constructor, "maxOutputTokens") != 1 \
            or java_identifier_write_count(constructor, "maxToolIterations") != 1 \
            or constructor.count(normalized(
                "maxOutputTokens = maxOutputTokens > 0 ? maxOutputTokens : DEFAULT_MAX_OUTPUT_TOKENS;")) != 1 \
            or constructor.count(normalized(
                "maxToolIterations = maxToolIterations > 0 ? maxToolIterations : DEFAULT_MAX_TOOL_ITERATIONS;")) != 1:
        errors.append("assistant compact-constructor field-specific compatibility has drifted")
    test_source = (root / ASSISTANT_CONFIGURATION_TEST_PATH).read_text(encoding="utf-8")
    method = "compactConstructorKeepsItsCompatibilityFallbacks"
    test_span = java_method_span(test_source, "AssistantConfigurationTest", method)
    test_code = normalized(strip_c_comments_and_literals(
        test_source[slice(*test_span)] if test_span else ""))
    required_test_clauses = (
        "new AssistantConfiguration(true, null, null, null, null, OutboundHttpPolicy.disabled(), Duration.ZERO, 0, -1)",
        "AssistantConfiguration.DEFAULT_MAX_OUTPUT_TOKENS",
        "configuration.maxOutputTokens()",
        "AssistantConfiguration.DEFAULT_MAX_TOOL_ITERATIONS",
        "configuration.maxToolIterations()",
        "AssistantConfiguration.DEFAULT_MAX_OUTPUT_TOKENS + 1",
        "positiveValues.maxOutputTokens()",
        "AssistantConfiguration.DEFAULT_MAX_TOOL_ITERATIONS + 1",
        "positiveValues.maxToolIterations()",
    )
    if compatibility.get("testBodyDigest") != java_method_digest(
            test_source, "AssistantConfigurationTest", method) \
            or any(normalized(clause) not in test_code for clause in required_test_clauses):
        errors.append("assistant compact-constructor compatibility test has drifted")
    return errors


def assistant_limit_family_index(authorities: object) -> dict[str, dict[str, object]]:
    """Return only the checker-owned, exact two-setting family metadata."""
    if not isinstance(authorities, dict) or set(authorities) != {ASSISTANT_LIMIT_FAMILY_ID}:
        return {}
    family = authorities[ASSISTANT_LIMIT_FAMILY_ID]
    required = {
        "kind", "settings", "resolverAuthority", "conversionAuthorities", "carrierEvidence",
        "consumerAuthority", "compatibilityAuthority", "platformTestDigest",
    }
    if not isinstance(family, dict) or set(family) != required \
            or family.get("kind") != "assistant-symbol-operational-limits-v1" \
            or not isinstance(family.get("settings"), list) or len(family["settings"]) != 2 \
            or not isinstance(family.get("conversionAuthorities"), dict) \
            or not isinstance(family.get("carrierEvidence"), dict):
        return {}
    settings = {str(item.get("setting")): item
                for item in family["settings"] if isinstance(item, dict)}
    expected = {str(item["setting"]) for item in ASSISTANT_LIMIT_SETTINGS}
    if set(settings) != expected or set(family["conversionAuthorities"]) != expected \
            or set(family["carrierEvidence"]) != expected:
        return {}
    return {
        setting: {
            "settingAuthority": settings[setting],
            "conversion": family["conversionAuthorities"][setting],
            "carrierEvidence": family["carrierEvidence"][setting],
        }
        for setting in expected
    }


def assistant_limit_expected_entry_ids(source: str, spec: dict[str, object],
                                       discovered: dict[str, Candidate]) -> tuple[
                                           list[str], list[str], set[str]]:
    source_rows = java_source_candidates(ASSISTANT_CONFIGURATION_PATH, source)
    declaration_ids = sorted(
        candidate.id for offset, candidate in source_rows
        if spec["environmentSpan"][0] <= offset < spec["environmentSpan"][1])
    default_ids = candidate_ids_in_source_span(
        ASSISTANT_CONFIGURATION_PATH, source, *spec["defaultSpan"],
        "fixed-declaration", str(spec["defaultSymbol"]), discovered)
    carrier_ids = {
        candidate.id for candidate in discovered.values()
        if any(candidate.path in paths for paths in ASSISTANT_CARRIER_PATHS.values())
        and candidate.kind == "environment-binding"
        and candidate.expression == spec["environment"]
    }
    return declaration_ids, default_ids, set(declaration_ids) | set(default_ids) | carrier_ids


def assistant_limit_entry_adapter_errors(root: Path, setting: str,
                                         setting_entries: list[dict[str, object]],
                                         entries: dict[str, dict[str, object]],
                                         discovered: dict[str, Candidate],
                                         authorities: object) -> list[str]:
    """Tie generic inventory rows to the fixed Assistant family without an opt-out flag."""
    index = assistant_limit_family_index(authorities)
    family = index.get(setting)
    source = (root / ASSISTANT_CONFIGURATION_PATH).read_text(encoding="utf-8")
    specs = assistant_limit_source_specs(source) or []
    spec = next((item for item in specs if item["setting"] == setting), None)
    if family is None or spec is None:
        return [f"{setting}: assistant inventory row has no exact family authority"]
    setting_authority = family["settingAuthority"]
    if not isinstance(setting_authority, dict):
        return [f"{setting}: assistant family setting authority is malformed"]
    declaration_ids, default_ids, expected_ids = assistant_limit_expected_entry_ids(
        source, spec, discovered)
    actual_ids = {str(entry["id"]) for entry in setting_entries}
    errors: list[str] = []
    if actual_ids != expected_ids:
        errors.append(f"{setting}: assistant inventory rows do not equal the source-derived partition")
    expected_binding = setting_authority.get("bindingAuthority")
    expected_default = setting_authority.get("defaultAuthority")
    expected_carrier = family["carrierEvidence"]
    expected_conversion = family["conversion"]
    for entry in setting_entries:
        identifier = str(entry["id"])
        if entry.get("bindingAuthority") != expected_binding \
                or entry.get("defaultAuthority") != expected_default \
                or entry.get("carrierEvidence") != expected_carrier \
                or entry.get("conversion") != expected_conversion:
            errors.append(f"{identifier}: assistant row authority metadata differs from its family")
        if entry.get("defaultEvidence") != default_ids:
            errors.append(f"{identifier}: assistant defaultEvidence differs from its direct default atom")
        if entry.get("owner") != f"{ASSISTANT_CONFIGURATION_PATH.as_posix()}#AssistantConfiguration" \
                or entry.get("field") != spec["component"] \
                or entry.get("bindings") != [spec["environment"]] \
                or entry.get("default") != str(spec["defaultValue"]):
            errors.append(f"{identifier}: assistant generic setting metadata has drifted")
    return errors


def assistant_limit_authority_errors(root: Path, authorities: object,
                                     entries: dict[str, dict[str, object]],
                                     discovered: dict[str, Candidate]) -> list[str]:
    """Verify the closed two-setting assistant limit family before public classification."""
    source_owner = f"{ASSISTANT_CONFIGURATION_PATH.as_posix()}#AssistantConfiguration"
    if current_source_owner(root, source_owner) is None:
        return [] if authorities in (None, {}) else ["assistant limit authority exists without its source family"]
    source = (root / ASSISTANT_CONFIGURATION_PATH).read_text(encoding="utf-8")
    derived = assistant_limit_source_specs(source)
    if derived is None:
        return ["AssistantConfiguration operational-limit source family has drifted"]
    if not isinstance(authorities, dict) or set(authorities) != {ASSISTANT_LIMIT_FAMILY_ID}:
        return ["AssistantConfiguration operational limits require one closed family authority"]
    authority = authorities[ASSISTANT_LIMIT_FAMILY_ID]
    required = {
        "kind", "settings", "resolverAuthority", "conversionAuthorities", "carrierEvidence",
        "consumerAuthority", "compatibilityAuthority", "platformTestDigest",
    }
    if not isinstance(authority, dict) or set(authority) != required \
            or authority.get("kind") != "assistant-symbol-operational-limits-v1":
        return ["assistant operational-limit family authority has an unsupported shape"]
    errors: list[str] = []
    family_index = assistant_limit_family_index(authorities)
    settings = authority["settings"]
    if not isinstance(settings, list) or len(settings) != 2:
        return ["assistant operational-limit authority must contain exactly two settings"]
    contracts = {str(item.get("setting")): item for item in settings if isinstance(item, dict)}
    if set(contracts) != {str(item["setting"]) for item in ASSISTANT_LIMIT_SETTINGS}:
        return ["assistant operational-limit setting family is incomplete"]
    conversions = authority["conversionAuthorities"]
    carriers = authority["carrierEvidence"]
    if not isinstance(conversions, dict) or set(conversions) != set(contracts) \
            or not isinstance(carriers, dict) or set(carriers) != set(contracts):
        errors.append("assistant conversion/carrier authorities must cover both settings")
    expected_entry_ids: set[str] = set()
    for spec in derived:
        setting = str(spec["setting"])
        contract = contracts[setting]
        if set(contract) != {"setting", "bindingAuthority", "defaultAuthority"}:
            errors.append(f"{setting}: assistant setting authority has an unsupported shape")
            continue
        binding = contract["bindingAuthority"]
        default = contract["defaultAuthority"]
        binding_keys = {
            "kind", "sourceOwner", "method", "constructorType", "component", "componentIndex",
            "helper", "environmentSymbol", "environment", "environmentCandidateId",
            "declarationCandidateIds", "callDigest", "resolverAuthority",
        }
        default_keys = {
            "kind", "owner", "field", "componentIndex", "constant", "sourceExpression",
            "candidateIds", "evaluatedDefault",
        }
        if not isinstance(binding, dict) or set(binding) != binding_keys \
                or binding.get("kind") != "java-symbol-environment-constructor-v1" \
                or not isinstance(default, dict) or set(default) != default_keys \
                or default.get("kind") != "java-static-final-int-default-v1":
            errors.append(f"{setting}: assistant binding/default authority has an unsupported shape")
            continue
        expected_binding = {
            "sourceOwner": source_owner, "method": "fromEnvironment",
            "constructorType": "AssistantConfiguration", "component": spec["component"],
            "componentIndex": spec["componentIndex"], "helper": "boundedPositiveInteger",
            "environmentSymbol": spec["environmentSymbol"], "environment": spec["environment"],
            "resolverAuthority": "assistant-bounded-positive-integer-v1",
        }
        expected_default = {
            "owner": source_owner, "field": spec["component"],
            "componentIndex": spec["componentIndex"], "constant": spec["defaultSymbol"],
            "sourceExpression": spec["defaultExpression"], "evaluatedDefault": spec["defaultValue"],
        }
        if contract.get("setting") != setting \
                or any(binding.get(key) != value for key, value in expected_binding.items()) \
                or any(default.get(key) != value for key, value in expected_default.items()) \
                or binding.get("callDigest") != hashlib.sha256(
                    str(spec["call"]).encode("utf-8")).hexdigest():
            errors.append(f"{setting}: assistant symbol binding/default metadata has drifted")
        env_ids = candidate_ids_in_source_span(
            ASSISTANT_CONFIGURATION_PATH, source, *spec["environmentSpan"],
            "environment-binding", str(spec["environment"]), discovered)
        declaration_ids = sorted(
            candidate.id for candidate in discovered.values()
            if candidate.path == ASSISTANT_CONFIGURATION_PATH.as_posix()
            and spec["environmentSpan"][0] <= next(
                (offset for offset, item in java_source_candidates(ASSISTANT_CONFIGURATION_PATH, source)
                 if item.id == candidate.id), -1) < spec["environmentSpan"][1]
        )
        default_ids = candidate_ids_in_source_span(
            ASSISTANT_CONFIGURATION_PATH, source, *spec["defaultSpan"],
            "fixed-declaration", str(spec["defaultSymbol"]), discovered)
        if binding.get("environmentCandidateId") not in env_ids or env_ids != [
                binding.get("environmentCandidateId")]:
            errors.append(f"{setting}: assistant environment declaration binding has drifted")
        if binding.get("declarationCandidateIds") != declaration_ids \
                or default.get("candidateIds") != default_ids:
            errors.append(f"{setting}: assistant declaration/default candidate partition has drifted")
        carrier_errors, carrier_ids = assistant_limit_carrier_errors(
            root, spec, carriers.get(setting) if isinstance(carriers, dict) else None,
            entries, discovered)
        errors.extend(carrier_errors)
        assigned = set(declaration_ids) | set(default_ids) | carrier_ids
        expected_entry_ids.update(assigned)
        if any(entries.get(identifier, {}).get("setting") != setting for identifier in assigned):
            errors.append(f"{setting}: assistant proof candidates are absent or assigned elsewhere")
        if any(entries.get(identifier, {}).get("status") != "converted"
               or entries.get(identifier, {}).get("classification") != "operator-configurable"
               for identifier in assigned):
            errors.append(f"{setting}: assistant proof candidates must all be converted operator settings")
        errors.extend(assistant_limit_conversion_errors(
            root, spec, conversions.get(setting) if isinstance(conversions, dict) else None))
    errors.extend(assistant_limit_resolver_errors(root, authority["resolverAuthority"]))
    errors.extend(assistant_limit_consumer_errors(root, authority["consumerAuthority"]))
    errors.extend(assistant_limit_compatibility_errors(root, authority["compatibilityAuthority"]))
    platform = (root / ASSISTANT_PLATFORM_TEST_PATH).read_text(encoding="utf-8")
    if authority["platformTestDigest"] != hashlib.sha256(platform.encode("utf-8")).hexdigest() \
            or any(platform.count(
                f"{spec['environment']} {spec['component']} {spec['defaultSymbol']} "
                f"assistant.{spec['helmField']}") != 1 for spec in ASSISTANT_LIMIT_SETTINGS):
        errors.append("assistant platform carrier test source evidence has drifted")
    fixed_settings = set(family_index)
    actual_entry_ids = {
        identifier for identifier, entry in entries.items()
        if entry.get("setting") in fixed_settings
    }
    if actual_entry_ids != expected_entry_ids:
        errors.append("assistant inventory assignments do not equal the independently derived family rows")
    specialized_rows = {
        identifier for identifier, entry in entries.items()
        if isinstance(entry.get("bindingAuthority"), dict)
        and entry["bindingAuthority"].get("kind") == "java-symbol-environment-constructor-v1"
    }
    if any(entries[identifier].get("setting") not in fixed_settings for identifier in specialized_rows):
        errors.append("assistant specialized authority cannot declare an unknown setting")
    return errors


def inventory_errors(root: Path, document: dict[str, object], candidates: tuple[Candidate, ...]) -> list[str]:
    errors: list[str] = []
    migration_history = document.get("migrationHistory", [])
    if isinstance(migration_history, list):
        for migration in migration_history:
            if not isinstance(migration, dict) \
                    or "sourceRevision" not in migration or "sourcePath" not in migration:
                continue
            source_revision = migration["sourceRevision"]
            source_path = migration["sourcePath"]
            if not historical_source_locator_is_safe(source_revision, source_path):
                errors.append(
                    f"inventory migration source is not locally resolvable: "
                    f"{source_revision}:{source_path}")
    # Reject unsafe Git arguments before source-owner or family validation can invoke Git.
    if errors:
        return errors
    raw_entries = document["entries"]
    assert isinstance(raw_entries, list)
    entries: dict[str, dict[str, object]] = {}
    evidence_records = document.get("evidenceRecords", {})
    if not isinstance(evidence_records, dict):
        errors.append("inventory evidenceRecords must be an object")
        evidence_records = {}
    for entry in raw_entries:
        if not isinstance(entry, dict) or not isinstance(entry.get("id"), str):
            errors.append("inventory entry has no string id")
            continue
        identifier = str(entry["id"])
        if identifier in entries:
            errors.append(f"duplicate inventory id: {identifier}")
        entries[identifier] = entry

    discovered = {candidate.id: candidate for candidate in candidates}
    environment_reference_descriptions = environment_reference_description_candidate_ids(
        root, candidates)
    assistant_authorities = document.get("assistantLimitAuthorities")
    assistant_settings = {str(item["setting"]) for item in ASSISTANT_LIMIT_SETTINGS}
    assistant_family = assistant_limit_family_index(assistant_authorities)
    for identifier, candidate in discovered.items():
        entry = entries.get(identifier)
        if entry is None:
            errors.append(
                f"unclassified operational candidate: {candidate.path}:{candidate.line} "
                f"{candidate.symbol} {candidate.kind} {candidate.role} {candidate.expression}; "
                f"evidence: {candidate.evidence}"
            )
            continue
        for key, value in candidate.source_fields().items():
            if entry.get(key) != value:
                errors.append(f"stale inventory metadata for {identifier}: {key} is {entry.get(key)!r}, expected {value!r}")
        if evidence_records.get(candidate.evidence_digest) != candidate.evidence:
            errors.append(f"stale or missing full evidence for {identifier}: {candidate.evidence_digest}")
        status = entry.get("status")
        classification = entry.get("classification")
        rationale = entry.get("rationale")
        if status not in STATUSES:
            errors.append(f"{identifier}: invalid status {status!r}")
        if status == "pending-review":
            if classification is not None:
                errors.append(f"{identifier}: pending-review candidate must not guess a classification")
        elif classification not in CLASSIFICATIONS:
            errors.append(f"{identifier}: reviewed candidate has invalid classification {classification!r}")
        elif status not in CLASSIFICATION_STATUSES[classification]:
            errors.append(f"{identifier}: {status!r} is invalid for classification {classification!r}")
        if status != "pending-review" and classification != "test-fixture" \
                and (not isinstance(rationale, str) or not rationale.strip()):
            errors.append(f"{identifier}: classification rationale is required")
        if candidate.surface == "test-fixture" and (
                classification != "test-fixture" or status != "retained"):
            errors.append(f"{identifier}: test-fixture surface must remain a retained test-fixture")
        if classification == "test-fixture" and candidate.surface != "test-fixture":
            errors.append(f"{identifier}: only a test-fixture surface may use the test-fixture classification")
        if classification == "published-contract-description":
            route_publication = candidate.path == ROUTE_TABLE_PATH.as_posix() \
                and entry.get("retainedAuthority") == ROUTE_TABLE_AUTHORITY_ID
            environment_publication = identifier in environment_reference_descriptions \
                and entry.get("retainedAuthority") == ENVIRONMENT_REFERENCE_AUTHORITY_ID
            if not route_publication and not environment_publication:
                errors.append(
                    f"{identifier}: published-contract-description requires a closed publication authority")
        if classification == "operator-configurable" and status != "pending-review":
            unresolved_authority = entry.get("authorityStatus") == "unresolved"
            for field in ("setting", "default", "validation", "scope", "pinning", "coverage"):
                if not isinstance(entry.get(field), str) or not str(entry[field]).strip():
                    errors.append(f"{identifier}: reviewed operator setting requires {field}")
            bindings = entry.get("bindings")
            if not isinstance(bindings, list) or any(
                    not isinstance(binding, str) or not binding.strip() for binding in bindings):
                errors.append(f"{identifier}: reviewed operator setting requires a string bindings array")
            default_evidence = entry.get("defaultEvidence")
            if not isinstance(default_evidence, list) or not default_evidence or any(
                    not isinstance(evidence_id, str) or not evidence_id.strip()
                    for evidence_id in default_evidence):
                errors.append(f"{identifier}: reviewed operator setting requires defaultEvidence candidate ids")
            if unresolved_authority:
                if status != "deferred":
                    errors.append(f"{identifier}: unresolved configuration authority must remain deferred")
                for field in ("prospectiveOwner", "unresolvedEvidence"):
                    if not isinstance(entry.get(field), str) or not str(entry[field]).strip():
                        errors.append(f"{identifier}: unresolved configuration authority requires {field}")
                if "owner" in entry or "field" in entry:
                    errors.append(f"{identifier}: unresolved configuration authority must not claim an owner or field")
            else:
                for field in ("owner", "field"):
                    if not isinstance(entry.get(field), str) or not str(entry[field]).strip():
                        errors.append(f"{identifier}: reviewed operator setting requires {field}")
                owner = str(entry.get("owner", ""))
                helm_authority = entry.get("helmAuthority")
                if helm_authority is not None:
                    if helm_authority != HELM_AUTHORITY_ID \
                            or not owner.startswith(HELM_VALUES_PATH + "#"):
                        errors.append(f"{identifier}: unsupported Helm authority owner: {owner}")
                elif entry.get("persistenceAuthority") == PERSISTENCE_POLICY_AUTHORITY_ID:
                    pass
                elif current_source_owner(root, owner) is None:
                    errors.append(f"{identifier}: owner is not a tracked in-repository path#symbol: {owner}")
                elif not current_source_field(root, owner, str(entry.get("field", ""))):
                    errors.append(f"{identifier}: field is not declared by its typed owner: {entry.get('field')}")
            if status == "converted":
                conversion = entry.get("conversion")
                setting = str(entry.get("setting", ""))
                if setting in assistant_settings:
                    family = assistant_family.get(setting)
                    if family is None or conversion != family["conversion"]:
                        errors.append(
                            f"{identifier}: converted assistant row must cite its exact family conversion")
                    continue
                if setting == MANIFEST_PIN_ATTEMPTS_SETTING \
                        and isinstance(conversion, dict) \
                        and conversion.get("kind") == "java-manifest-pin-attempts-conversion-v1":
                    errors.extend(manifest_pin_attempt_conversion_errors(
                        root, identifier, entry, conversion))
                    continue
                required = ("issue", "beforeRevision", "afterRevision", "path", "symbol",
                            "binding", "bindingSymbol", "field", "beforeExpression", "afterExpression")
                if not isinstance(conversion, dict) or any(
                        not isinstance(conversion.get(field), str) or not str(conversion[field]).strip()
                        for field in required):
                    errors.append(
                        f"{identifier}: converted setting requires setting-specific source-verifiable "
                        "conversion provenance"
                    )
                else:
                    transition, before_source, after_source = revision_transition_errors(
                        root, identifier, conversion, path=str(conversion["path"]),
                        symbol=str(conversion["symbol"]), label="conversion",
                    )
                    errors.extend(transition)
                    errors.extend(conversion_evidence_errors(
                        identifier, entry, conversion, before_source, after_source,
                    ))
                    if str(conversion["field"]) != str(entry.get("field", "")):
                        errors.append(f"{identifier}: conversion field does not match the setting field")
        if status == "deferred" and not re.search(r"(?:#\d+|https://)", str(entry.get("followUp", ""))):
            errors.append(f"{identifier}: deferred candidate requires a concrete linked followUp")

    for identifier, entry in entries.items():
        if identifier not in discovered:
            errors.append(f"stale inventory entry: {identifier} ({entry.get('path', 'unknown path')})")

    reappearance_errors, allowed_reappearances = normalized_identity_reappearance_errors(
        root, document, candidates)
    errors.extend(reappearance_errors)

    retired_entries = document.get("retiredEntries", [])
    assert isinstance(retired_entries, list)
    retired_ids: set[str] = set()
    for entry in retired_entries:
        if not isinstance(entry, dict) or not isinstance(entry.get("id"), str):
            errors.append("retired inventory entry has no string id")
            continue
        identifier = str(entry["id"])
        if identifier in retired_ids \
                or (identifier in entries and identifier not in allowed_reappearances):
            errors.append(f"duplicate active/retired inventory id: {identifier}")
        retired_ids.add(identifier)
        if not isinstance(entry.get("retirementRationale"), str) \
                or not str(entry["retirementRationale"]).strip():
            errors.append(f"{identifier}: retired entry requires retirementRationale")
        if entry.get("status") == "duplicate-removed":
            removal = entry.get("removal")
            required = ("issue", "beforeRevision", "afterRevision", "replacementOwner")
            if not isinstance(removal, dict) or any(
                    not isinstance(removal.get(field), str) or not str(removal[field]).strip()
                    for field in required):
                errors.append(f"{identifier}: duplicate removal requires source-verifiable removal provenance")
            elif removal.get("kind") == "yaml-default-authority-v1":
                errors.extend(yaml_default_removal_errors(root, identifier, entry, removal, entries))
            else:
                transition, before_source, after_source = revision_transition_errors(
                    root, identifier, removal, path=str(entry.get("path", "")),
                    symbol=str(entry.get("symbol", "")), label="removal",
                )
                # A removed duplicate may delete its authority or change that source, but cannot leave
                # the exact old expression in place and merely relabel its inventory row.
                errors.extend(error for error in transition
                              if "path/symbol is absent from afterRevision" not in error)
                if before_source is not None and after_source is not None \
                        and str(entry.get("evidence", "")) in after_source:
                    errors.append(f"{identifier}: removed duplicate evidence remains in afterRevision")
                replacement = str(removal["replacementOwner"])
                if "#" not in replacement:
                    errors.append(f"{identifier}: replacementOwner must be path#symbol")
                else:
                    replacement_path, replacement_symbol = replacement.rsplit("#", 1)
                    replacement_source = committed_source(
                        root, str(removal["afterRevision"]), replacement_path,
                    )
                    if replacement_source is None or not re.search(
                            rf"\b{re.escape(replacement_symbol)}\b", replacement_source):
                        errors.append(f"{identifier}: replacementOwner is absent from afterRevision")

    migration_history = document.get("migrationHistory", [])
    assert isinstance(migration_history, list)
    for migration in migration_history:
        required = ("fromSchema", "toSchema", "sourceRevision", "sourcePath", "sourceFileDigest",
                    "candidateCount", "statusCounts", "rationale")
        if not isinstance(migration, dict) or any(field not in migration for field in required):
            errors.append("inventory migration record is incomplete")
            continue
        source_revision = str(migration["sourceRevision"])
        source_path = str(migration["sourcePath"])
        source = committed_source(root, source_revision, source_path)
        if source is None:
            errors.append(f"inventory migration source is not locally resolvable: {source_revision}:{source_path}")
            continue
        digest = hashlib.sha256(source.encode("utf-8")).hexdigest()
        if digest != migration["sourceFileDigest"]:
            errors.append(f"inventory migration source digest mismatch: {source_revision}:{source_path}")
        try:
            source_document = json.loads(source)
        except json.JSONDecodeError:
            errors.append(f"inventory migration source is not JSON: {source_revision}:{source_path}")
            continue
        source_entries = source_document.get("entries", [])
        source_counts = Counter(str(entry.get("status")) for entry in source_entries
                                if isinstance(entry, dict))
        if len(source_entries) != migration["candidateCount"] or dict(sorted(source_counts.items())) != migration["statusCounts"]:
            errors.append(f"inventory migration source counts mismatch: {source_revision}:{source_path}")

    authorities: dict[str, tuple[str, tuple[object, ...]]] = {}
    unresolved_groups: dict[str, list[dict[str, object]]] = defaultdict(list)
    authority_fields = ("authorityStatus", "owner", "field", "prospectiveOwner",
                        "unresolvedEvidence", "default", "validation", "scope", "pinning", "coverage")
    for identifier, entry in entries.items():
        if entry.get("classification") != "operator-configurable" or entry.get("status") == "pending-review":
            continue
        owner = str(entry.get("owner", "")).strip()
        setting = str(entry.get("setting", "")).strip()
        if not setting:
            continue
        if entry.get("authorityStatus") == "unresolved":
            unresolved_groups[setting].append(entry)
            continue
        metadata = tuple(entry.get(field) for field in authority_fields) + (
            tuple(entry.get("bindings", [])), tuple(entry.get("defaultEvidence", [])),
            json.dumps(entry.get("bindingAuthority"), sort_keys=True),
            json.dumps(entry.get("defaultAuthority"), sort_keys=True),
            json.dumps(entry.get("schemaEvidence"), sort_keys=True),
            json.dumps(entry.get("coverageEvidence"), sort_keys=True),
            json.dumps(entry.get("carrierEvidence"), sort_keys=True),
            entry.get("helmAuthority"),
            entry.get("persistenceAuthority"),
        )
        previous = authorities.get(setting)
        if previous is not None and previous[1] != metadata:
            errors.append(
                f"inconsistent configuration authority metadata for {setting}: "
                f"{previous[0]} and {identifier}"
            )
        else:
            authorities[setting] = (identifier, metadata)

    for setting, setting_entries in unresolved_groups.items():
        expected_ids = {str(entry["id"]) for entry in setting_entries}
        expected_bindings = {str(entry["expression"]) for entry in setting_entries
                             if entry.get("kind") == "environment-binding"}
        common_fields = (
            "authorityStatus", "prospectiveOwner", "unresolvedEvidence", "default",
            "validation", "scope", "pinning", "coverage", "followUp", "rationale",
        )
        first = setting_entries[0]
        common_metadata = tuple(first.get(field) for field in common_fields) + (
            tuple(first.get("bindings", [])), tuple(first.get("defaultEvidence", [])),
        )
        for entry in setting_entries:
            metadata = tuple(entry.get(field) for field in common_fields) + (
                tuple(entry.get("bindings", [])), tuple(entry.get("defaultEvidence", [])),
            )
            if metadata != common_metadata:
                errors.append(
                    f"inconsistent unresolved configuration metadata for {setting}: "
                    f"{first['id']} and {entry['id']}")
            if len(setting_entries) > 1 and (
                    not isinstance(entry.get("sourceFact"), str)
                    or not str(entry["sourceFact"]).strip()):
                errors.append(f"{entry['id']}: multi-row unresolved setting requires a sourceFact")
            if set(entry.get("defaultEvidence", [])) != expected_ids:
                errors.append(
                    f"{entry['id']}: unresolved {setting} defaultEvidence must equal its exact setting rows")
            if set(entry.get("bindings", [])) != expected_bindings:
                errors.append(
                    f"{entry['id']}: unresolved {setting} bindings must equal its exact environment evidence")

    resolver_authorities = document.get("resolverAuthorities")
    if resolver_authorities is not None:
        errors.extend(resolver_authority_errors(root, resolver_authorities))
    errors.extend(route_table_authority_errors(
        root, document.get("routeTableAuthorities"), entries, discovered,
    ))
    errors.extend(assistant_limit_authority_errors(
        root, assistant_authorities, entries, discovered,
    ))
    errors.extend(graph_limit_authority_errors(
        root, document.get("graphLimitAuthorities"), entries, discovered,
    ))
    errors.extend(helm_authority_errors(
        root, document.get("helmAuthorities"), entries, candidates,
    ))
    errors.extend(persistence_policy_authority_errors(
        root, document.get("persistencePolicyAuthorities"), entries, discovered,
    ))

    tracked_paths = set(tracked_files(root))
    representatives: dict[str, dict[str, object]] = {}
    for setting in authorities:
        setting_entries = [entry for entry in entries.values() if entry.get("setting") == setting]
        setting_ids = {str(entry["id"]) for entry in setting_entries}
        representative = entries[authorities[setting][0]]
        representatives[setting] = representative
        if representative.get("authorityStatus") == "unresolved":
            continue
        if representative.get("helmAuthority") == HELM_AUTHORITY_ID:
            for entry in setting_entries:
                evidence_ids = entry.get("defaultEvidence", [])
                if isinstance(evidence_ids, list):
                    for evidence_id in evidence_ids:
                        if evidence_id not in setting_ids:
                            errors.append(
                                f"{entry['id']}: defaultEvidence {evidence_id} is not assigned to {setting}")
            continue
        if representative.get("persistenceAuthority") == PERSISTENCE_POLICY_AUTHORITY_ID:
            continue
        bindings = {str(binding) for entry in setting_entries for binding in entry.get("bindings", [])}
        if representative.get("bindingAuthority") is None:
            evidenced_bindings = {str(entry.get("expression")) for entry in setting_entries
                                  if entry.get("kind") == "environment-binding"}
            for binding in sorted(bindings - evidenced_bindings):
                errors.append(f"{setting}: binding {binding} has no same-setting environment-binding candidate")
        if setting in assistant_settings:
            errors.extend(assistant_limit_entry_adapter_errors(
                root, setting, setting_entries, entries, discovered, assistant_authorities,
            ))
        else:
            errors.extend(binding_authority_errors(
                root, setting, representative, setting_entries, entries, discovered,
                resolver_authorities,
            ))
            errors.extend(default_authority_errors(
                root, setting, representative, entries, discovered))
        errors.extend(schema_evidence_errors(setting, representative, entries, discovered, evidence_records))
        errors.extend(graph_platform_coverage_errors(
            root, setting, representative, entries, discovered, evidence_records, tracked_paths,
        ))
        for entry in setting_entries:
            evidence_ids = entry.get("defaultEvidence", [])
            if isinstance(evidence_ids, list):
                for evidence_id in evidence_ids:
                    if evidence_id not in setting_ids:
                        errors.append(f"{entry['id']}: defaultEvidence {evidence_id} is not assigned to {setting}")
    errors.extend(environment_resolver_group_errors(root, representatives, resolver_authorities))
    errors.extend(reconciliation_history_errors(root, document, candidates))
    errors.extend(remediation_domain_errors(document))
    return errors


def render_report(document: dict[str, object]) -> str:
    entries = document["entries"]
    assert isinstance(entries, list)
    typed = [entry for entry in entries if isinstance(entry, dict)]
    statuses = Counter(str(entry.get("status")) for entry in typed)
    classifications = Counter(str(entry.get("classification")) for entry in typed
                              if entry.get("classification") is not None)
    retained_classifications = Counter(
        str(entry.get("classification")) for entry in typed
        if entry.get("status") == "retained" and entry.get("classification") is not None)
    surfaces = Counter(str(entry.get("surface")) for entry in typed)
    reviewed = len(typed) - statuses["pending-review"]
    operator_entries = [entry for entry in typed if entry.get("classification") == "operator-configurable"]
    operator_settings = {str(entry["setting"]) for entry in operator_entries if entry.get("setting")}
    converted_settings = {str(entry["setting"]) for entry in operator_entries
                          if entry.get("status") == "converted" and entry.get("setting")}
    operator = len(operator_settings)
    converted = len(converted_settings)
    retired = document.get("retiredEntries", [])
    assert isinstance(retired, list)
    migrations = document.get("migrationHistory", [])
    assert isinstance(migrations, list)
    reconciliations = document.get("reconciliationHistory", [])
    assert isinstance(reconciliations, list)
    reappearances = document.get("normalizedIdentityReappearanceHistory", [])
    assert isinstance(reappearances, list)
    duplicate_settings = {str(entry["setting"]) for entry in retired if isinstance(entry, dict)
                          and entry.get("status") == "duplicate-removed" and entry.get("setting")}
    duplicates = len(duplicate_settings)
    deferred = statuses["deferred"]
    hardcoded = statuses["confirmed-hardcoded"]
    helm_authorities = document.get("helmAuthorities")
    helm_authority = helm_authorities.get(HELM_AUTHORITY_ID) \
        if isinstance(helm_authorities, dict) else None
    helm_contracts = helm_authority.get("contracts", []) \
        if isinstance(helm_authority, dict) else []
    helm_source_fields = len(helm_contracts) if isinstance(helm_contracts, list) else 0
    helm_inventory_fields = sum(
        1 for contract in helm_contracts
        if isinstance(contract, dict) and isinstance(contract.get("candidateIds"), list)
        and bool(contract["candidateIds"]))
    complete = statuses["pending-review"] == 0 and deferred == 0 and hardcoded == 0
    lines = [
        "# Operational configuration audit", "",
        "<!-- Generated by scripts/audit_operational_configuration.py; do not edit directly. -->", "",
        "This report is generated from the checked operational-configuration inventory. The bounded",
        "lexical scanner records candidates matched by its documented patterns; a candidate is not an",
        "operator setting until its semantic classification says so.", "",
        f"**Audit state:** {'complete' if complete else 'in progress'}. "
        f"{statuses['pending-review']} candidate(s) still require semantic review, {deferred} are deferred, "
        f"and {hardcoded} confirmed hard-coded candidates remain unresolved.", "",
        "## Coverage", "",
        "Scanned production surfaces: Java `src/main`, browser `src` and `public`, runtime/release Python",
        "and shell scripts, Dockerfiles, Compose, Helm, raw deployment manifests, and the two supported",
        "Compose override examples. The scanner also records testkit `src/main` and script verification",
        "constants as test fixtures.", "",
        "Excluded from production counts: ordinary `src/test`, UI test/e2e fixtures, generated build output",
        "(`target`, `dist`), dependency trees (`node_modules`), audit-tool implementation, ordinary prose,",
        "and vendored content.", "",
        "Each row represents one atomic fixed value or binding. Its full containing expression is retained as",
        "evidence without excerpt truncation. Candidate identity is `path + containing symbol + candidate",
        "kind/semantic role + normalized atomic value + normalized full-expression digest + lexical duplicate",
        "index`; source line remains checked metadata.", "",
        "The scanner is deliberately lexical: it covers declared constants, known policy constructors,",
        "and `get`, `await`, `tryAcquire`, `tryLock`, `waitFor`, and `awaitTermination` calls whose supported",
        "forms use a simple or fully qualified explicit `TimeUnit` constant. Other method names, dynamic or",
        "statically imported units, receiver-type inference, and values assembled only through reflection,",
        "generated sources, or arbitrary data flow remain outside this bounded pattern. Environment bindings,",
        "deployment scalars, and container identity/port directives are covered separately;",
        "semantic review and focused source inventories remain required for those boundaries.", "",
        "## Reproducible counts", "",
        "| Measure | Count |", "|---|---:|",
        f"| Atomic operational candidates discovered | {len(typed)} |",
        f"| Source-proven Helm operator fields | {helm_source_fields} |",
        f"| Helm operator fields represented by lexical inventory rows | {helm_inventory_fields} |",
        f"| Source-proven Helm fields outside lexical candidate patterns | {helm_source_fields - helm_inventory_fields} |",
        f"| Reviewed | {reviewed} |",
        f"| Pending review | {statuses['pending-review']} |",
        f"| Confirmed hard-coded candidates awaiting remediation | {hardcoded} |",
        f"| Unique confirmed operator-configurable parameters | {operator} |",
        f"| Unique parameters converted to centralized configuration | {converted} |",
        f"| Duplicate authorities removed | {duplicates} |",
        f"| Retained security ceilings or defaults | {classifications['security-ceiling-or-default']} |",
        f"| Retained protocol or format invariants | {classifications['protocol-or-format-invariant']} |",
        f"| Retained published contract descriptions | {retained_classifications['published-contract-description']} |",
        f"| Retained presentation text | {retained_classifications['presentation-text']} |",
        f"| Retained derived values | {classifications['derived']} |",
        f"| Test fixtures | {classifications['test-fixture']} |",
        f"| Intentionally deferred | {deferred} |", "",
        f"Retired source candidates preserved in inventory history: {len(retired)}.", "",
        f"Approved normalized-identity reappearances: {len(reappearances)}. Active candidates and",
        "retired historical payloads remain counted separately; an approval records identity reuse only.", "",
        f"Checked inventory-schema migrations: {len(migrations)}. Validation requires the recorded source",
        "revision to be present locally; CI must fetch that history before enabling this gate.", "",
        f"Checked source reconciliations: {len(reconciliations)}.", "",
        "Surface counts are derived from the same inventory:", "",
    ]
    lines.extend(f"- `{name}`: {count}" for name, count in sorted(surfaces.items()))
    if reconciliations:
        latest = reconciliations[-1]
        mappings = latest.get("mappings", [])
        retirements = latest.get("retirements", [])
        additions = latest.get("additions", [])
        source_count = len(typed) - len(additions) + len(retirements)
        unchanged = source_count - len(mappings) - len(retirements)
        lines.extend(("", "## Latest reconciliation", "",
                      "The current inventory was reconciled from a committed source inventory. Every changed",
                      "identity and retirement has its own approved record in the machine-readable inventory.", "",
                      "| Partition | Count |", "|---|---:|",
                      f"| Source inventory candidates | {source_count} |",
                      f"| Unchanged identities | {unchanged} |",
                      f"| Approved identity migrations | {len(mappings)} |",
                      f"| Approved retirements | {len(retirements)} |",
                      f"| Semantically classified additions | {len(additions)} |",
                      f"| Current candidates | {len(typed)} |"))
    domain_map = document.get("remediationDomains", {})
    domains = domain_map.get("domains", []) if isinstance(domain_map, dict) else []
    lines.extend(("", "## Follow-up domain ownership", "",
                  "This map covers inherited pending review and confirmed unresolved operator settings only.",
                  "Retained invariants, fixtures, descriptions, and presentation text are outside remediation ownership.", "",
                  "| Issue | Domain | Candidates | Inherited pending review | Confirmed unresolved settings |",
                  "|---|---|---:|---:|---:|"))
    for domain in domains:
        lines.append(f"| {domain['issue']} | {domain['title']} | {len(domain['candidateIds'])} | "
                     f"{domain['inheritedPendingReview']} | {domain['confirmedUnresolvedOperatorSettings']} |")
    lines.extend(("", "## Operator settings", "",
        "Every reviewed operator setting must name one typed owner, bindings, default, validation,",
                  "scope, pinning policy, and deployment/reference coverage. Pending candidates do not appear",
                  "in this table.", "",
                  "| Setting | State | Owner | Field | Bindings | Default | Source facts | Validation | Scope | Pinning | Coverage |", "|---|---|---|---|---|---|---|---|---|---|---|"))
    if operator_entries:
        canonical: dict[str, list[dict[str, object]]] = {}
        for entry in operator_entries:
            canonical.setdefault(str(entry["setting"]), []).append(entry)
        for setting, setting_entries in sorted(canonical.items()):
            setting_entries.sort(key=lambda item: str(item["id"]))
            entry = setting_entries[0]
            item_states = {str(item["status"]) for item in setting_entries}
            states = "converted" if "converted" in item_states else ", ".join(sorted(item_states))
            bindings = ", ".join(f"`{value}`" for value in entry.get("bindings", []))
            unresolved = entry.get("authorityStatus") == "unresolved"
            owner = entry.get("prospectiveOwner", "") if unresolved else entry.get("owner", "")
            field = "unresolved" if unresolved else entry.get("field", "")
            source_facts = "<br>".join(
                f"`{item['id']}`: {str(item.get('sourceFact', item.get('expression', ''))).replace('|', '&#124;')}"
                for item in setting_entries
            )
            lines.append("| {setting} | {status} | `{owner}` | `{field}` | {bindings} | {default} | {source_facts} | {validation} | {scope} | {pinning} | {coverage} |".format(
                setting=setting, status=states, owner=owner,
                field=field,
                bindings=bindings or "none", default=entry.get("default", ""),
                source_facts=source_facts,
                validation=entry.get("validation", ""),
                scope=entry.get("scope", ""), pinning=entry.get("pinning", ""),
                coverage=entry.get("coverage", "")))
    else:
        lines.append("| _None reviewed yet_ |  |  |  |  |  |  |  |  |  |  |")
    persistence_authorities = document.get("persistencePolicyAuthorities", {})
    persistence_authority = (persistence_authorities.get(PERSISTENCE_POLICY_AUTHORITY_ID)
                             if isinstance(persistence_authorities, dict) else None)
    persistence_contracts = (persistence_authority.get("contracts", [])
                             if isinstance(persistence_authority, dict) else [])
    lines.extend(("", "## Source-proven persistence policy", "",
                  "The closed roster includes typed programmatic fields even when the lexical scanner finds",
                  "no candidate atom for that field. Candidate counts therefore describe inventory evidence,",
                  "not the number of supported policy fields.", "",
                  "| Setting | Typed owner | Field | Bindings | Default source | Inventory candidates |",
                  "|---|---|---|---|---|---:|"))
    if isinstance(persistence_contracts, list) and persistence_contracts:
        for contract in sorted(persistence_contracts, key=lambda item: str(item.get("setting", ""))):
            bindings = ", ".join(f"`{item}`" for item in contract.get("bindings", [])) or "none"
            lines.append(
                f"| {contract.get('setting', '')} | `{contract.get('owner', '')}` | "
                f"`{contract.get('field', '')}` | {bindings} | "
                f"`{contract.get('defaultExpression', '')}` | "
                f"{len(contract.get('candidateIds', []))} |")
    else:
        lines.append("| _No source-proven persistence policy_ |  |  |  |  |  |")
    lines.extend(("", "## Deferred values", "", "| Candidate | Follow-up | Rationale |", "|---|---|---|"))
    deferred_entries = sorted(
        (entry for entry in typed if entry.get("status") == "deferred"),
        key=lambda item: (str(item["path"]), int(item["line"]), str(item["id"])),
    )
    if deferred_entries:
        for entry in deferred_entries:
            lines.append(f"| `{entry['path']}:{entry['line']}` | {entry.get('followUp', 'missing')} | {entry['rationale']} |")
    else:
        lines.append("| _None classified yet_ |  |  |")
    pending_files = Counter(str(entry["path"]) for entry in typed if entry.get("status") == "pending-review")
    lines.extend(("", "## Pending-review distribution", "",
                  "The machine-readable inventory retains every pending expression and its digest. This compact",
                  "view identifies where semantic review remains without copying thousands of source excerpts",
                  "into the operator report.", "", "| Source | Candidates |", "|---|---:|"))
    for path, count in sorted(pending_files.items()):
        lines.append(f"| `{path}` | {count} |")
    lines.extend(("", "## Reviewed candidate ledger", "",
                  "The machine-readable inventory is authoritative; this table shows reviewed non-fixture entries.", "",
                  "| ID | Source | Surface | State | Classification | Rationale |", "|---|---|---|---|---|---|"))
    reviewed_entries = [entry for entry in typed if entry.get("status") != "pending-review"
                        and entry.get("classification") != "test-fixture"]
    for entry in sorted(reviewed_entries, key=lambda item: (str(item["path"]), int(item["line"]), str(item["id"]))):
        rationale = str(entry["rationale"]).replace("|", "\\|").replace("\n", " ")
        lines.append(f"| `{entry['id']}` | `{entry['path']}:{entry['line']}` `{entry['symbol']}` | "
                     f"{entry['surface']} | {entry['status']} | {entry.get('classification') or '—'} | {rationale} |")
    if not reviewed_entries:
        lines.append("| _No reviewed non-fixture candidates yet_ |  |  |  |  |  |")
    lines.extend(("", "## Validation", "",
                  "Run `python3 scripts/audit_operational_configuration.py --check`. It rejects a new",
                  "unclassified value, binding, directive, or inline operational call; a changed classified",
                  "expression; stale inventory metadata; invalid classification/provenance; inconsistent setting",
                  "authorities; unresolved hard-coded settings; or report drift.", ""))
    return "\n".join(lines)


def refresh_inventory(root: Path, inventory_path: Path = INVENTORY, report_path: Path = REPORT,
                      *, accept_retired_pending: bool = False,
                      reconciliation_plan: dict[str, object] | None = None) -> tuple[list[str], dict[str, int]]:
    """Refresh source metadata without discarding a semantic review decision.

    A changed expression has a new stable ID. Every retired entry needs an in-inventory
    ``retirement`` object with ``approved: true`` and a nonblank row-specific rationale. The
    command flag records intent only; it never supplies approval evidence. Every retired row stays
    in history.
    """
    document = load_inventory(
        inventory_path, allow_previous_schema=True, allow_unreconciled=True)
    raw_entries = document["entries"]
    assert isinstance(raw_entries, list)
    candidates = discover(root)
    if reconciliation_plan is not None:
        refreshed, plan_errors = apply_reconciliation(root, document, candidates, reconciliation_plan)
        summary = {
            "added": len(reconciliation_plan.get("additions", [])),
            "retired": len(reconciliation_plan.get("retirements", [])),
            "migrated": len(reconciliation_plan.get("mappings", [])),
            "preserved": len(raw_entries) - len(reconciliation_plan.get("retirements", [])),
        }
        if plan_errors or refreshed is None:
            return plan_errors, summary
        refreshed["remediationDomains"] = build_remediation_domains(
            entry for entry in refreshed["entries"] if isinstance(entry, dict))
        validation_errors = inventory_errors(root, refreshed, candidates)
        if validation_errors:
            return validation_errors, summary
        inventory_temporary = inventory_path.with_suffix(inventory_path.suffix + ".tmp")
        report_temporary = report_path.with_suffix(report_path.suffix + ".tmp")
        inventory_temporary.write_text(
            json.dumps(refreshed, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        report_temporary.write_text(render_report(refreshed), encoding="utf-8")
        inventory_temporary.replace(inventory_path)
        report_temporary.replace(report_path)
        return [], summary
    if document.get("reconciliationRequired") is not False:
        return ["source identity changes require an explicit reconciliation plan"], {
            "added": 0, "retired": 0, "preserved": len(raw_entries),
        }
    migrating_schema = document.get("schemaVersion") != SCHEMA_VERSION
    migration_record: dict[str, object] | None = None
    if migrating_schema:
        revision = subprocess.run(["git", "rev-parse", "HEAD"], cwd=root, check=True,
                                  capture_output=True, text=True).stdout.strip()
        source_path = inventory_path.resolve().relative_to(root.resolve()).as_posix()
        committed = subprocess.run(["git", "show", f"{revision}:{source_path}"], cwd=root,
                                   capture_output=True)
        if committed.returncode != 0 or committed.stdout != inventory_path.read_bytes():
            raise ValueError("schema migration requires the source inventory to be committed unchanged")
        source_counts = Counter(str(entry.get("status")) for entry in raw_entries
                                if isinstance(entry, dict))
        migration_record = {
            "fromSchema": document.get("schemaVersion"), "toSchema": SCHEMA_VERSION,
            "sourceRevision": revision, "sourcePath": source_path,
            "sourceFileDigest": hashlib.sha256(committed.stdout).hexdigest(),
            "candidateCount": len(raw_entries), "statusCounts": dict(sorted(source_counts.items())),
            "rationale": "Candidate identity schema changed; pending and mechanical fixture rows were reissued without claiming semantic review.",
        }
    old = {str(entry["id"]): entry for entry in raw_entries if isinstance(entry, dict) and "id" in entry}
    discovered = {candidate.id: candidate for candidate in candidates}
    removed = [entry for identifier, entry in old.items() if identifier not in discovered]
    errors: list[str] = []
    for entry in removed:
        identifier = str(entry["id"])
        if entry.get("classification") == "test-fixture" and entry.get("status") == "retained":
            continue
        retirement = entry.get("retirement")
        approved = isinstance(retirement, dict) and retirement.get("approved") is True \
            and isinstance(retirement.get("rationale"), str) and str(retirement["rationale"]).strip()
        if approved:
            continue
        if entry.get("status") == "pending-review":
            errors.append(
                f"refresh would retire pending candidate {identifier} {entry.get('path')}:{entry.get('line')}; "
                "add retirement.approved=true and a row-specific retirement.rationale to that exact entry"
            )
        else:
            errors.append(
                f"refresh would retire reviewed candidate {identifier} {entry.get('path')}:{entry.get('line')}; "
                "add retirement.approved=true and retirement.rationale to that exact inventory entry"
            )
    if errors:
        return errors, {"added": sum(identifier not in old for identifier in discovered),
                        "retired": len(removed), "preserved": sum(identifier in old for identifier in discovered)}

    merged: list[dict[str, object]] = []
    added = 0
    updated = 0
    for candidate in candidates:
        entry = old.get(candidate.id)
        if entry is None:
            merged.append(candidate.inventory_entry())
            added += 1
            continue
        preserved = dict(entry)
        preserved.pop("evidence", None)
        if preserved.get("status") == "pending-review":
            preserved.pop("rationale", None)
        source_fields = candidate.source_fields()
        if any(preserved.get(key) != value for key, value in source_fields.items()):
            updated += 1
        preserved.update(source_fields)
        merged.append(preserved)

    retired_history = list(document.get("retiredEntries", []))
    semantic_removed = [entry for entry in removed if entry.get("status") != "pending-review"
                        and entry.get("classification") != "test-fixture"]
    archive_rows = semantic_removed if migrating_schema else removed
    for entry in archive_rows:
        archived = dict(entry)
        retirement = archived.pop("retirement", None)
        if entry.get("classification") == "test-fixture":
            rationale = (f"Candidate identity retired during inventory schema v{SCHEMA_VERSION} migration."
                         if migrating_schema else "Mechanically classified test-fixture candidate retired after source refresh.")
        elif entry.get("status") == "pending-review":
            assert isinstance(retirement, dict)
            rationale = str(retirement["rationale"]).strip()
        else:
            assert isinstance(retirement, dict)
            rationale = str(retirement["rationale"]).strip()
        archived["retirementRationale"] = rationale
        if "evidence" not in archived:
            old_evidence = document.get("evidenceRecords", {})
            if isinstance(old_evidence, dict):
                archived["evidence"] = old_evidence.get(str(archived.get("evidenceDigest")), "")
        retired_history.append(archived)

    refreshed = dict(document)
    refreshed["schemaVersion"] = SCHEMA_VERSION
    refreshed["reconciliationRequired"] = False
    refreshed["entries"] = merged
    refreshed["retiredEntries"] = retired_history
    migration_history = list(document.get("migrationHistory", []))
    if migration_record is not None:
        migration_history.append(migration_record)
    refreshed["migrationHistory"] = migration_history
    refreshed["evidenceRecords"] = {
        digest: evidence for digest, evidence in sorted({
            candidate.evidence_digest: candidate.evidence for candidate in candidates
        }.items())
    }
    validation_errors = inventory_errors(root, refreshed, candidates)
    if validation_errors:
        return validation_errors, {"added": added, "retired": len(removed),
                                   "preserved": len(merged) - added, "metadataUpdated": updated}
    rendered = render_report(refreshed)
    inventory_temporary = inventory_path.with_suffix(inventory_path.suffix + ".tmp")
    report_temporary = report_path.with_suffix(report_path.suffix + ".tmp")
    inventory_temporary.write_text(json.dumps(refreshed, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    report_temporary.write_text(rendered, encoding="utf-8")
    inventory_temporary.replace(inventory_path)
    report_temporary.replace(report_path)
    return [], {"added": added, "retired": len(removed), "preserved": len(merged) - added,
                "metadataUpdated": updated}


def bootstrap(root: Path, inventory_path: Path, report_path: Path) -> None:
    if inventory_path.exists():
        raise ValueError(f"refusing to overwrite existing inventory: {inventory_path}")
    candidates = discover(root)
    document = {
        "schemaVersion": SCHEMA_VERSION,
        "reconciliationRequired": False,
        "description": "Machine-reviewed fixed operational candidates; counts and report are generated.",
        "entries": [candidate.inventory_entry() for candidate in candidates],
        "evidenceRecords": {
            digest: evidence for digest, evidence in sorted({
                candidate.evidence_digest: candidate.evidence for candidate in candidates
            }.items())
        },
    }
    inventory_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    inventory_path.write_text(json.dumps(document, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    report_path.write_text(render_report(document), encoding="utf-8")


def check(root: Path, inventory_path: Path = INVENTORY, report_path: Path = REPORT,
          *, require_complete: bool = True, allow_unreconciled: bool = False) -> list[str]:
    try:
        document = load_inventory(inventory_path, allow_unreconciled=allow_unreconciled)
    except ValueError as invalid:
        return [str(invalid)]
    candidates = discover(root)
    errors = inventory_errors(root, document, candidates)
    if require_complete:
        entries = document["entries"]
        assert isinstance(entries, list)
        pending = sum(isinstance(entry, dict) and entry.get("status") == "pending-review"
                      for entry in entries)
        deferred = sum(isinstance(entry, dict) and entry.get("status") == "deferred"
                       for entry in entries)
        hardcoded = sum(isinstance(entry, dict) and entry.get("status") == "confirmed-hardcoded"
                        for entry in entries)
        if pending or deferred or hardcoded:
            errors.append(
                f"audit is incomplete: {pending} pending-review, {deferred} deferred, and "
                f"{hardcoded} confirmed-hardcoded candidate(s); "
                "use --check-inventory only while completing reviewed remediation waves"
            )
    expected = render_report(document)
    if not report_path.is_file() or report_path.read_text(encoding="utf-8") != expected:
        errors.append(f"generated audit report has drifted: {report_path.relative_to(root)}")
    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true",
                      help="require a complete inventory and current generated report (the default)")
    mode.add_argument("--check-inventory", action="store_true",
                      help="check incremental inventory integrity while pending review remains")
    mode.add_argument("--refresh-inventory", action="store_true",
                      help="preserve reviews while adding and retiring changed source candidates")
    mode.add_argument("--bootstrap", action="store_true", help="create the initial inventory and report")
    parser.add_argument("--accept-retired-pending", action="store_true",
                        help="with --refresh-inventory, explicitly archive removed pending entries")
    parser.add_argument("--reconciliation-plan", type=Path,
                        help="with --refresh-inventory, apply an explicit reviewed reconciliation JSON")
    args = parser.parse_args(argv)
    if args.accept_retired_pending and not args.refresh_inventory:
        parser.error("--accept-retired-pending requires --refresh-inventory")
    if args.reconciliation_plan is not None and not args.refresh_inventory:
        parser.error("--reconciliation-plan requires --refresh-inventory")
    root = args.root.resolve()
    inventory = root / "scripts" / INVENTORY.name
    report = root / "docs" / "architecture" / REPORT.name
    try:
        if args.bootstrap:
            bootstrap(root, inventory, report)
            print(f"Bootstrapped {len(discover(root))} operational candidates.")
            return 0
        if args.refresh_inventory:
            reconciliation_plan = None
            if args.reconciliation_plan is not None:
                loaded_plan = json.loads(args.reconciliation_plan.read_text(encoding="utf-8"))
                if not isinstance(loaded_plan, dict):
                    raise ValueError("reconciliation plan must be a JSON object")
                reconciliation_plan = loaded_plan
            errors, summary = refresh_inventory(
                root, inventory, report, accept_retired_pending=args.accept_retired_pending,
                reconciliation_plan=reconciliation_plan,
            )
            if errors:
                for error in errors:
                    print(f"ERROR: {error}", file=sys.stderr)
                print("Refresh plan: " + ", ".join(f"{key}={value}" for key, value in summary.items()),
                      file=sys.stderr)
                return 1
            print("Refreshed operational inventory ("
                  + ", ".join(f"{key}={value}" for key, value in summary.items()) + ").")
            return 0
        errors = check(root, inventory, report, require_complete=not args.check_inventory)
    except (OSError, ValueError, subprocess.CalledProcessError) as failure:
        print(f"ERROR: {failure}", file=sys.stderr)
        return 1
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    document = load_inventory(inventory)
    print(f"Operational configuration inventory is current ({len(document['entries'])} candidates).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
