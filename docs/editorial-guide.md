# Ravenroot documentation editorial guide

## The manual is the release contract

Public documentation states what Ravenroot does in the documented release: meanings, procedures, authority, defaults, limits, failure behavior, and observable evidence. It never narrates engineering progress or offers speculative alternatives.

## Admission rule

A behavior enters the manual when its product contract is fixed by the release specification. Truly undecided subjects are absent. Internal tracking identifiers, implementation status, migration notes, and engineering repositories are not reader-facing product facts.

## Page types and ownership

- Tutorials end in a working result.
- How-to guides perform one bounded task for a named audience.
- Concepts explain invariants and relationships.
- Reference pages enumerate machine contracts, fields, defaults, limits, and states.
- Runbooks use symptom → diagnosis → action → verification, adding rollback when an action changes durable state.

User actions belong in the User guide; administration in the Operator guide; host composition in the Integrator guide; invariants in Architecture; controls in Security; exact enumeration in Reference; recovery in Troubleshooting; contribution mechanics in the Developer guide.

## Required evidence

Operational instructions name prerequisites, commands or UI actions, expected responses, authority, failure signals, and verification. Examples are copy-pastable and contain no secrets. A page links to its exact reference and recovery path instead of repeating either incompletely.

## Diagrams and graphical notation

Generic explanatory diagrams, architecture views, sequences, state machines, flows, and other
illustrative graphs in Markdown use fenced `mermaid` blocks. Do not represent diagrams with ASCII
boxes, arrows, connector lines, or other text art.

This rule does not replace an authentic Ravenroot graph. When the subject is a Ravenroot executable
graph, use its actual GraphML, a verified Ravenroot UI rendering, or the approved product asset rather
than redrawing it in Mermaid. Literal terminal output, source code, file formats, and directory trees
remain code examples when they are evidence rather than illustrative diagrams.

## Language and publication

English under this repository's `docs/` directory is the canonical public contract and the only
language used for release documentation here. Every public-documentation change is complete in its
own right: affected pages, examples, cross-references, commands, endpoint names, fields, defaults,
limits, errors, and product meaning are written, verified, reviewed, approved, committed, and
published together.

Translations, personal reading aids, internal reports, and decision-support material remain outside
this repository and do not form a completeness, review, approval, or publication gate.

## Review gates

Review product accuracy, technical accuracy, information architecture, links, duplicate prose, and
English-language consistency. Verify the complete public change in one review. Add security review
for identity, credentials, egress, executable artifacts, AI, embed, audit, or deployment material.

Return to the [documentation home](index.md) or read the repository [contribution guide](../CONTRIBUTING.md).
