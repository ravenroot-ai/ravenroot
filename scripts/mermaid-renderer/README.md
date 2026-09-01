# Mermaid renderer for repository ADRs

This directory owns the integrity-locked Node dependencies used to render Mermaid diagrams while
maintaining repository ADRs. It is separate from Ravenroot's UI dependency graph.

Install the renderer and its lock-pinned browser from the repository root:

```bash
npm ci --ignore-scripts --prefix scripts/mermaid-renderer
npm --prefix scripts/mermaid-renderer run install-browser
npm --prefix scripts/mermaid-renderer run verify-browser
```

The dependency installation deliberately disables lifecycle scripts. Browser provisioning is an
explicit operation whose exact Chrome for Testing and Chrome Headless Shell builds are selected by
the lock-pinned Puppeteer package. It does not use an ambient browser. Mermaid CLI uses Headless
Shell; Chrome is installed alongside it so Puppeteer can deterministically install the required
Ubuntu system dependencies. On an Ubuntu CI runner, run `install-browser:ci` with the root
privileges required by `apt-get`.

`browser-path.mjs` verifies that both executables supplied by the pinned Puppeteer installation are
present and executable, then reports their paths.
