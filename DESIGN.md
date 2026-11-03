---
name: AIxodia
description: The night-console control surface for one self-hosted AI engine.
colors:
  page-black: "#000000"
  ink-on-page: "#F7F9FB"
  navy-recess: "#0B1220"
  navy-sunken: "#0E131C"
  navy-base: "#111720"
  navy-card: "#151D29"
  navy-pill: "#19202B"
  navy-line: "#2A3544"
  outline-strong: "#71809A"
  accent: "#7FB2FF"
  accent-ink: "#0A0F2C"
  bubble: "#17305A"
  bubble-text: "#D9E7FF"
  send: "#2F6FED"
  online: "#3DDC84"
  connecting: "#FFB020"
  offline: "#E5484D"
  error-text: "#FFB4AB"
typography:
  title-medium: { fontFamily: "Roboto, sans-serif", fontSize: "17sp", fontWeight: 600, lineHeight: "24sp" }
  title-small: { fontFamily: "Roboto, sans-serif", fontSize: "15sp", fontWeight: 600, lineHeight: "22sp", letterSpacing: "0.1sp" }
  body-medium: { fontFamily: "Roboto, sans-serif", fontSize: "15sp", fontWeight: 400, lineHeight: "22sp", letterSpacing: "0.15sp" }
  label-medium: { fontFamily: "Roboto, sans-serif", fontSize: "13sp", fontWeight: 400, lineHeight: "18sp", letterSpacing: "0.3sp" }
  label-small: { fontFamily: "Roboto, sans-serif", fontSize: "11sp", fontWeight: 400, lineHeight: "16sp", letterSpacing: "0.4sp" }
  mono-readout: { fontFamily: "monospace" }
rounded:
  xs: "6dp"
  sm: "10dp"
  md: "16dp"
  lg: "22dp"
  xl: "28dp"
spacing:
  row-h: "16dp"
  stack-sm: "4dp"
  stack-md: "14dp"
components:
  disc-control:
    backgroundColor: "{colors.navy-pill}"
    textColor: "{colors.ink-on-page}"
    rounded: "50%"
    size: "48dp"
  disc-control-primary:
    backgroundColor: "{colors.send}"
    textColor: "{colors.ink-on-page}"
    rounded: "50%"
    size: "48dp"
  pill-control:
    backgroundColor: "{colors.navy-pill}"
    textColor: "{colors.ink-on-page}"
    rounded: "50%"
    height: "48dp"
  user-bubble:
    backgroundColor: "{colors.bubble}"
    textColor: "{colors.bubble-text}"
    rounded: "{colors.lg}"
  hairline:
    backgroundColor: "{colors.navy-line}"
    height: "1dp"
---

# Design System: AIxodia

## Overview

**Creative North Star: "The Night Console"**

AIxodia is a control surface for one technical operator, and it looks like the
tools that operator already trusts at 2am: a pure-black page, every raised
surface the same navy taken to increasing strength, and a single soft sky-blue
accent that marks what is interactive or alive. Density and precision are the
identity — Discord's drawer, Telegram's bubbles, a terminal's monospace
readouts — so nothing decorative competes with the work on screen.

Depth is tonal, never cast. No shadow is drawn anywhere in the app; a surface
only has to sit far enough above black to be seen, and the higher it sits the
louder it reads. That navy ramp (recess → sunken → base → card → pill) is a
reading order, not decoration: a code block sits in the lowest step, chat
bubbles on the middle steps, controls and pills at the top.

The light scheme exists as Material's courtesy (paper background, deep-teal
primary), but the product's centre of gravity is dark: system bars are declared
dark in every appearance, and the terminal keeps its own fixed ink palette
regardless of theme because it renders a real shell, not themed chrome.

**Key Characteristics:**
- Pure black page; navy-only tonal ramp for all elevation.
- One accent (`#7FB2FF`) for interactive/alive; status colours are reserved roles, never accents.
- Monospace for every machine readout (seq, pid, cost, token counts).
- 48dp circular discs and full-round pills as the only button silhouettes in the composer.
- Hairline borders (`#2A3544`) do the separating; shadows do nothing.

## Colors

The palette is a night sky over black glass: one blue family for structure, four
reserved signal colours for state, and nothing else.

### Primary
- **Sky Accent** (#7FB2FF): icons, cursor, the slash on the command button, active tab markers — anything interactive or alive. User-customisable via Settings (`primary` hex overrides `primary` + `surfaceTint`).
- **Deep Signal Blue** (#2F6FED): the send disc only. Deeper than the accent so the one control meant to be hit without reading stands apart from accent-tinted icons.

### Secondary
- **Bubble Navy** (#17305A) with **Bubble Text** (#D9E7FF): the user's own words, a blue deep enough to read as the page turned.

### Tertiary
- **Amber Set** (#F5C36B / #432C00 / #5F4100 / #FFE1A3): warning-tier containers, used sparingly where the daemon reports something degraded but not dead.

### Neutral (the navy ramp — this is the whole UI)
- **Page Black** (#000000): background and surface.
- **Navy Recess** (#0B1220) → **Sunken** (#0E131C) → **Base** (#111720) → **Card** (#151D29) → **Pill** (#19202B): the five-step container ladder; each step is the old navy at k=0.55 increments above black.
- **Hairline** (#2A3544, `outlineVariant`): keeps two darks meeting each other from reading as one.
- **Steel Outline** (#71809A): stronger separators where a hairline is too quiet.
- **Ink On Page** (#F7F9FB): body text. **Variant Ink** (#C8DCEE): secondary labels.

### State signals (non-role, `MaterialTheme.aixodia`)
- **Online** (#3DDC84), **Connecting** (#FFB020), **Offline** (#E5484D): the connection dot, recognisable before reading. Light variants darken for contrast (#12833F / #8A5A00 / #B3261E).
- **Error Tier** (#FFB4AB on #93000A): Material error roles, reserved for failures.

**The Reserved-Signal Rule.** Green, amber and red mean online/connecting/offline and nothing else. A brand element may never borrow them.

**The One-Voice Rule.** Sky Accent marks the living thing on screen. If an element is neither interactive nor alive, it wears the navy ramp.

## Typography

**Display/Body Font:** Roboto (system default; nothing remote — the look ships with the app).
**Label/Mono Font:** Monospace, for every machine readout.

**Character:** utilitarian and legible at arm's length; the type scale stops short of tiny because the earlier 8–9sp readouts made the session's cost unreadable.

### Hierarchy
- **titleMedium** (600, 17sp/24sp): screen and card titles.
- **titleSmall** (600, 15sp/22sp, 0.1sp): section headers, knob labels.
- **bodyMedium** (400, 15sp/22sp, 0.15sp): message text — the app's core content.
- **labelMedium** (400, 13sp/18sp, 0.3sp): chips, buttons, metadata.
- **labelSmall** (400, 11sp/16sp, 0.4sp): timestamps, footer stats. Never below 11sp; legend readouts floor at 12sp.
- **mono readout**: seq/pid/cost/token figures and terminal grid; monospace is semantic, not stylistic.

**The Readout Floor Rule.** No rendered text below 11sp; anything carrying information a glance must catch (cost strip, legends) is ≥12sp.

## Layout

Edge-to-edge with per-surface inset handling: the thread takes the whole window while the app bar and composer each consume their own system-bar + IME insets (`navigationBars.union(ime)`). Structure is messenger-shaped by commitment — a ModalNavigationDrawer with anchored footer (Providers + Settings rows), a pinned composer under a LazyColumn thread, a day-grouped lazy session list keyed by id. Composer rows space their discs on a 16dp horizontal rhythm; stacked cards use 14dp vertical gaps, tight label groups 4dp. Touch targets are 48dp minimum everywhere the harness can enforce it (discs, pills, terminal keys).

## Elevation & Depth

No shadows exist in this system. Depth is entirely tonal: the five-step navy container ramp plus hairline borders. A raised surface is a *brighter* navy, never a lifted black card. The terminal panel opts out with its own fixed ink palette (#0B0F14 → #1E2A38) because a shell's colours belong to the shell.

**The Flat-By-Tone Rule.** Elevation = brightness within one hue. If you reach for a shadow, you want the next navy step up.

## Shapes

Two geometries only: circles and the corner ladder. Discs and pills are true circles (full round clip); surfaces climb extraSmall 6dp → small 10dp → medium 16dp → large 22dp → extraLarge 28dp, with small/large/xl derived proportionally (×0.55/×1.2/×1.5) from a user-settable base radius (default 12dp, clamped 0–32). Bubbles take the large step; the pill silhouette is reserved for controls that open sheets.

## Components

### Buttons — DiscControl
- **Shape:** true circle, box *is* the hit area (48dp).
- **Primary (send):** Deep Signal Blue fill, white icon; disabled drops to Pill navy at 38% alpha tint.
- **Secondary/quiet:** surfaceContainerHighest (navy pill) fill, onSurface icon.
- **Outlined variant:** hairline ring (`outlineVariant`) says "this opens something."
- Semantics carry the label as `contentDescription` + `Role.Button`.

### Buttons — PillControl
- Full-round label chip, 48dp tall, navy-pill fill + hairline border, titleSmall-weight label. Quieter than discs; opens sheets rather than acting at once.

### Chips
- FilterChip (Material) for model/thinking selection inside settings sheets; selected wears primary tint. Session-strip legend chips are mono text on navy-pill.

### Cards / Containers
- Corner: medium/large ladder; background: the navy ramp by importance (code wells at recess, cards at card, floating controls at pill). No shadow, no gradient; hairlines separate neighbours.

### Inputs / Fields
- OutlinedTextField (Material) with theme outline; composer field sits between its discs, holds draft via `rememberSaveable`, and clears per-chat. Terminal command line uses the ink palette with `InkDim` placeholder.

### Navigation
- ModalNavigationDrawer, Discord-shaped: grouped lazy session list (Today/Yesterday/Last 7 days), search field, anchored footer with Providers key-row and Settings update-row. Footer rows are 16dp/10dp padded icon+labelLarge lines.

### Signature: the session strip
- Mono legend above the composer: `cached / new / out` counts at 12sp with dim labels, one shared style function so figures and words never drift apart.

### Signature: the terminal panel
- Fixed ink world (never themed), tab bar with active marker in Steel/Sky, TalkBack fed by `spokenTail()` of the buffer.

## Do's and Don'ts

### Do:
- **Do** express elevation as the next navy step up (`surfaceContainer*`), separated by the hairline #2A3544 when two darks meet.
- **Do** keep machine readouts monospace and ≥12sp.
- **Do** reserve green/amber/red for connection state and the send blue for the send disc.
- **Do** give raw touch surfaces the 48dp floor and a `Role.Button` semantic.
- **Do** let the user retune the accent (`#RRGGBB` → primary+surfaceTint) and base radius (0–32dp) — everything else stays structural.

### Don't:
- **Don't** draw shadows, gradients, or glows; the console is flat by tone.
- **Don't** introduce a second palette in the UI layer — non-role colours live in `AixodiaColors` beside the scheme.
- **Don't** put the terminal's ink chrome under theme control; a shell renders the shell's colours.
- **Don't** ship iOS-shaped components (bottom-only nav, Cupertino switches) or hijack system Back.
- **Don't** drop any label below 11sp to save a line.
