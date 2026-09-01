# Change fragments

Change fragments let each pull request record its public release-note entry without editing a shared
changelog section.

## File name

Create one Markdown file named:

```text
.changes/<issue-or-short-slug>.<kind>.md
```

Use one of these kinds:

| Kind | Release meaning before 1.0 |
|---|---|
| `breaking` | Incompatible public-contract change; requires at least a minor increment and prominent migration notes |
| `feature` | Backward-compatible capability; requires a minor increment |
| `fix` | Backward-compatible defect correction; requires a patch increment |
| `security` | Security correction; normally requires a patch increment and coordinated disclosure when necessary |
| `docs` | User-relevant documentation change; does not determine a version increment by itself |
| `other` | User-relevant maintenance change; the release pull request classifies its version impact explicitly |

Examples are `.changes/142.fix.md` and `.changes/grid-editor.feature.md`.

## Content

Write one concise, user-facing Markdown paragraph. State the outcome, not implementation progress, and
include migration or operator action only when it is required. Do not include private issue history,
paths, credentials, or project-management metadata.

The release pull request collects the fragments into the changelog and release notes, applies the
highest required version increment, and removes the consumed fragment files. A pull request may contain
more than one fragment when it contains independently useful release-note entries.
