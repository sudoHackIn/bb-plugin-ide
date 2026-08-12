# bb-plugin-ide

Experimental IDE service for BB: a shared, host-local IntelliJ IDEA backend
for Java/Kotlin plus language-specific providers such as TypeScript.

This repository currently contains the validated headless IDEA launcher
prototype and the product/architecture notes. It is a bootstrap repository,
not an installable BB plugin yet.

## Contents

- [`scripts/jb-backend`](scripts/jb-backend) — starts, reuses, inspects and
  stops one isolated headless IDEA JVM.
- [`scripts/jb-backend-starter`](scripts/jb-backend-starter) — minimal IDEA
  `ApplicationStarter` used by the launcher.
- [`docs/jetbrains-idea-backend-for-bb.md`](docs/jetbrains-idea-backend-for-bb.md)
  — results of the headless IDEA experiment.
- [`docs/bb-ide-service-plan.md`](docs/bb-ide-service-plan.md) — product idea,
  provider architecture, resource ownership model, CLI and implementation
  plan.

## Prototype usage

The current launcher targets macOS on Apple Silicon and IntelliJ IDEA 2025.3+.
It also requires `jq`, `curl`, `lsof`, `rg`, and a compatible signed
`jetbrains-index-mcp-plugin` ZIP.

```sh
export BB_IDEA_MCP_PLUGIN_ZIP=/absolute/path/to/jetbrains-index-mcp-plugin.zip

scripts/jb-backend start /absolute/path/to/project
scripts/jb-backend status /absolute/path/to/project
scripts/jb-backend log /absolute/path/to/project
scripts/jb-backend stop /absolute/path/to/project
```

The MCP endpoint binds to `127.0.0.1` and must not be exposed directly to a
network.

## Next step

Create the initial BB plugin backend with the `bb ide` control-plane CLI,
package a prebuilt starter JAR, and introduce host-managed services before
adding editor UI.
