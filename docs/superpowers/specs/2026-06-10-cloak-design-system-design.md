# Cloak Design System — Design

- **Date:** 2026-06-10
- **Status:** Approved (design); to be implemented in the `OneUI` module per `app/CLAUDE.md`
- **Branch:** `feature/planning`
- **Scope:** The visual language for the Cloak iOS app — colour, typography, shape, spacing, core components, and message-status states. Detailed per-screen design happens per-slice during implementation; this is the shared foundation those screens draw from.

## Principles

- **Apple-native.** Follow Apple Human Interface Guidelines: system font, Dynamic Type, system appearance (light/dark), standard control metaphors.
- **Clean & spaced.** Generous, consistent whitespace on an 8-pt grid; hierarchy from size/weight, not decoration.
- **Slightly rounded.** Soft corners everywhere; never sharp.
- **Two accents, restrained.** Royal purple is the primary/action colour; vibrant green signals positive/secure states (online, success, read). Neutrals (black/white/grey) do the rest.
- **Privacy-appropriate.** Status and meaning never rely on colour alone (shape + text labels too), and security artifacts (safety numbers) get their own typographic treatment.

## Appearance

Cloak supports **both light and dark**. It follows the **system setting** by default and offers an in-app override: **System · Light · Dark**. Dark is the "hero" appearance but both are first-class and fully designed.

## Colour tokens

Semantic tokens (implement as SwiftUI color-set assets with Any/Dark appearances in `OneUI`). Raw hex is the source of truth below.

### Brand & status (both appearances)

| Token | Hex | Use |
|-------|-----|-----|
| `primary` | `#6D28D9` | Primary actions, outgoing bubbles, links, selected state |
| `primary-pressed` | `#5B21B6` | Pressed/active primary |
| `on-primary` | `#FFFFFF` | Text/icons on `primary` |
| `success` | `#22C55E` | Online, sent/delivered/read indicators, success |
| `success-text-on-light` | `#16A34A` | Green text/glyphs on light surfaces (contrast) |
| `danger` | `#EF4444` | Failed state, destructive actions (dark) |
| `danger-text-on-light` | `#DC2626` | Danger text on light surfaces |

### Neutrals

| Token | Dark | Light | Use |
|-------|------|-------|-----|
| `canvas` | `#0B0B0F` | `#FFFFFF` | App background |
| `surface` | `#1C1C22` | `#F5F5F7` | Raised surfaces, incoming bubbles, inputs |
| `surface-2` | `#2A2A32` | `#FFFFFF` | Cards on grouped backgrounds, hover |
| `separator` | `rgba(255,255,255,.08)` | `rgba(0,0,0,.08)` | Hairlines, borders |
| `text-primary` | `#F5F5F7` | `#1D1D1F` | Primary text |
| `text-secondary` | `#9CA3AF` | `#6B7280` | Secondary text, timestamps |
| `text-tertiary` | `#6B7280` | `#9CA3AF` | Placeholders, disabled |

## Typography

**SF Pro (system font) + Dynamic Type — all-SF.** No bundled UI font (custom wordmark allowed for the logo only). **SF Mono** is reserved for security codes (safety numbers / key fingerprints). Hierarchy comes from Apple's text styles:

| Style | Size | Weight | Use |
|-------|------|--------|-----|
| Large Title | 34 | Bold | Screen titles ("Chats") |
| Title 2 | 22 | Bold | Sheet/section titles ("New Message") |
| Headline | 17 | Semibold | Contact names, emphasis |
| Body | 17 | Regular | Message text, primary content |
| Callout | 16 | Regular | Secondary content |
| Subheadline | 15 | Regular | List previews, supporting text |
| Footnote | 13 | Regular | Timestamps, metadata |
| Caption | 12 / 11 | Regular | Smallest labels |
| SF Mono | 13 | Regular | Safety numbers / key fingerprints |

All styles scale with Dynamic Type.

## Shape — corner radius

| Token | Value | Use |
|-------|-------|-----|
| `radius-xs` | 6 | Chips, badges, small controls |
| `radius-sm` | 10 | Buttons, text inputs |
| `radius-md` | 16 | Cards, message bubbles |
| `radius-lg` | 22 | Sheets, large surfaces |
| `radius-full` | ∞ | Avatars, pills, FAB, segmented controls |

Message bubbles use `radius-md` with a single tightened corner (5) on the "tail" side: outgoing `16 16 5 16`, incoming `16 16 16 5`.

## Spacing — 8-pt grid

`4` is the micro half-step; everything else is a multiple of 8.

| Token | Value | Use |
|-------|-------|-----|
| `space-1` | 4 | Icon ↔ label |
| `space-2` | 8 | Between related elements |
| `space-3` | 12 | Inside controls, bubble padding |
| `space-4` | 16 | **Default** screen margins, list-row padding |
| `space-6` | 24 | Between sections |
| `space-8` | 32 | Large section breaks |
| `space-12` | 48 | Hero / empty-state breathing room |

## Core components (initial set)

- **Button — primary:** `primary` fill, `on-primary` text, `radius-sm`, `space-3` vertical padding, Headline/Body weight. Pressed → `primary-pressed`.
- **Button — secondary:** `surface` fill or tinted `primary` at low opacity; `primary` text.
- **Text input:** `surface` fill, `separator` border, `radius-sm`, `text-tertiary` placeholder.
- **Message bubble:** outgoing = `primary`/`on-primary`; incoming = `surface`/`text-primary`; radii per Shape.
- **Avatar:** `radius-full`; initials on `primary` when no image.
- **Chip / badge:** `radius-xs`; e.g. "Encrypted" = `success` text on `success` @15% fill.
- **List row (conversation):** avatar + Headline name + Subheadline preview + Footnote timestamp; `space-4` padding; status glyph before preview.
- **Nav bar:** `surface` background, Headline title, `primary` chevrons/actions.

## Message-status states

The outgoing-message lifecycle, using a **progressive green fill** so the language is coherent. Colour is never the only signal — each state has a distinct **shape** and a **text label** (and a VoiceOver label).

| State | Glyph | Label | Colour |
|-------|-------|-------|--------|
| Sending… | hollow grey ring | "Sending…" | `text-tertiary` |
| Sent | green **ring** (empty) | "Sent" | `success` / `success-text-on-light` |
| Delivered | green ring with **center dot** | "Delivered" | `success` / `success-text-on-light` |
| Read | **filled** green circle | "Read" | `success` / `success-text-on-light` |
| Failed | red circle with **!** | "Failed to send" + **Retry** | `danger` / `danger-text-on-light` |

## Accessibility

- **Contrast:** meet WCAG AA. Body text uses `text-primary` (not accent colours). On light surfaces, green/red use the `-on-light` variants for legibility.
- **Colour is never the sole signal:** status uses shape + text label; the green status escalation is reinforced by the fill progression and words.
- **Dynamic Type:** all text styles scale; layouts reflow (no clipped text).
- **Touch targets:** ≥ 44×44 pt.
- **Reduce Motion / VoiceOver:** honor Reduce Motion; every status and control has a clear accessibility label (e.g., "Message read").

## Implementation notes

- Tokens and components live in the **`OneUI`** Foundation module (`app/CLAUDE.md`): color-set assets with Any/Dark appearances, a typography helper over `Font.system(...)` text styles, and `radius`/`space` constants.
- Semantic names only in feature code — never raw hex. This keeps light/dark and future retheming a single-source change.
- The in-app appearance override (System/Light/Dark) is a Settings concern; the design system just exposes the tokens.

## Relationship to the build

This is the design-system foundation. The MVP roadmap (`2026-06-08-cloak-mvp-roadmap-design.md`) handles UX per-slice as a screen inventory; each slice's screens are built from these tokens and components. Establishing `OneUI` with these tokens is a natural early task (alongside or just after Phase 0).
