# Mermaid renderer for repository ADRs

This directory owns the integrity-locked Node dependencies used to render Mermaid diagrams while
maintaining repository ADRs. It is separate from Ravenroot's UI dependency graph.

Install the renderer and its lock-pinned browser from the repository root:

```bash
npm ci --prefix scripts/mermaid-renderer
```

The installation is local to this directory and does not use ambient Mermaid or browser packages.
`browser-path.mjs` reports the executable supplied by the lock-pinned Puppeteer installation for
tools that need to launch that exact browser.
