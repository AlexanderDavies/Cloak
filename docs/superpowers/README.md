# Cloak — specs & plans index

Orientation for any session (planning **or** execution). Start here.

- **Specs** (`specs/`) describe *what* and *why* — validated designs from brainstorming.
- **Plans** (`plans/`) describe *how* — bite-sized, TDD, no-placeholder implementation steps.
- The **roadmap spec is the spine**: it lists the ordered MVP slices and their acceptance criteria. Everything else hangs off it.

## Flow

`brainstorming` → spec (`specs/`) → `writing-plans` → plan (`plans/`) → execute (`subagent-driven-development` / `executing-plans`) → PR → `/code-review` + manual review → merge to `main`.

Per-slice: branch `feature/<slice>` off `main`, squash + rebase before PR (root `CLAUDE.md` → Engineering workflow & quality gates).

## Specs

| Spec | Scope | Status |
|------|-------|--------|
| [`specs/2026-06-08-cloak-mvp-roadmap-design.md`](specs/2026-06-08-cloak-mvp-roadmap-design.md) | MVP decomposition: Phase 0 walking skeleton + 9 vertical slices; locked decisions (Signal Protocol, JWKS auth, Avro/Kafka, etc.) | ✅ Approved |
| [`specs/2026-06-10-cloak-design-system-design.md`](specs/2026-06-10-cloak-design-system-design.md) | Visual design system (Royal & Spring): colour tokens (light/dark), all-SF type, radius/spacing, components, message-status states, accessibility → `OneUI` | ✅ Approved |

## Plans

| Plan | Builds | Needs | Status |
|------|--------|-------|--------|
| [`plans/2026-06-10-phase-0-server-foundation.md`](plans/2026-06-10-phase-0-server-foundation.md) | Quality gates (Spotless/Checkstyle/JaCoCo), `integrationTest` source set, Flyway→`db/migrations`, Testcontainers harness, `docs/contracts/` seam | Docker | ⬜ Not started |
| [`plans/2026-06-10-phase-0-server-skeleton.md`](plans/2026-06-10-phase-0-server-skeleton.md) | Keycloak seed users + JWKS auth, ciphertext-only `Message`, JPA persistence, authenticated WebSocket, Avro/Schema-Registry Kafka round-trip + delivery | Docker; Plan 1 merged | ⬜ Not started |
| [`plans/2026-06-10-phase-0-ios-skeleton.md`](plans/2026-06-10-phase-0-ios-skeleton.md) | XcodeGen + SwiftLint/coverage gates, AppAuth OIDC login, libsignal key-gen, opaque-blob send/receive vs mocks | macOS + Xcode 16+ | ⬜ Not started |

## The MVP slices (roadmap spine)

Phase 0 (walking skeleton) → **Slice 1** Account onboarding + device key registration → 2 X3DH first message → 3 Double Ratchet + history → 4 Offline delivery → 5 Multi-device → 6 Long-poll fallback → 7 APNs push → 8 Delivery/read receipts → 9 Prekey replenishment + rotation. (TBC future: group chat, on-device AI, media, Android/web.)

Each slice gets its own brainstorm → spec → plan when started, **planned against the merged code it builds on**.

## Resume anchors

1. `main` — specs, plans, standards, scaffolding (the shared baseline).
2. The auto-loaded **memory checkpoint** — current status + next step (loads only in the original repo folder; see below).
3. This index + the roadmap spec.

## Running it in a fresh Claude Code instance

`CLAUDE.md` and the committed `.claude/settings.json` (which enables the superpowers plugin) travel with the repo on every branch and in every worktree. Each plan file is **self-contained** (assumes zero prior context). So a fresh instance has everything it needs to execute — the memory checkpoint is a *planning* convenience, not an *execution* dependency.

**Memory scope:** the memory checkpoint auto-loads only when Claude Code is opened in the **same project directory** (`/Users/alexanderdavies/Workspace/Cloak`). A git worktree or separate clone is a *different* path, so the checkpoint does **not** auto-load there — but `CLAUDE.md` + the plan file do.

### Execute a Phase 0 plan (recommended: isolated worktree)

```bash
cd /path/to/Cloak && git checkout main && git pull
git worktree add ../cloak-server-foundation -b feature/phase-0-server-foundation main
cd ../cloak-server-foundation
claude
```
Then prompt: **"Use superpowers to execute `docs/superpowers/plans/2026-06-10-phase-0-server-foundation.md`."** Claude reads the plan header (which names the required sub-skill — `subagent-driven-development` or `executing-plans`) and runs it task-by-task; `CLAUDE.md` supplies the squash+rebase / two-stage-review / quality-gate rules at PR time. Repeat with a separate worktree per plan. (Or say "set up a worktree and execute the plan" to use the `using-git-worktrees` skill.)

### Resume planning (Slice 1+)

Do this from the **original repo folder** so the memory checkpoint auto-loads (or `claude --resume` there). Pull latest `main`, branch `feature/planning-slice-1`, then `brainstorming` → `writing-plans`.

| Goal | Where | Loads | Kick off with |
|------|-------|-------|---------------|
| Execute a plan | A worktree (own folder) | `CLAUDE.md` + plan + superpowers | "Use superpowers to execute `<plan path>`" |
| Resume planning | The **main** repo folder | memory checkpoint + `CLAUDE.md` | `brainstorming` → `writing-plans` for the next slice |

> Prerequisite on a **new machine**: install the `claude-plugins-official` marketplace + the superpowers plugin first; the committed setting then enables it.
