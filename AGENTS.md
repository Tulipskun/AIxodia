# Shared agent coordination

All cross-session state, handoffs, code maps, checkpoints, and agent coordination rules are maintained in the private repository [Tulipskun/Agents](https://github.com/Tulipskun/Agents).

At the beginning of each session, the agent working in this project must install or update the coordination repository **in its own environment**:
- Local clone: `$HOME/.agents/Agents`
- Remote: `https://github.com/Tulipskun/Agents.git`
- Use `gh auth status`, then clone if absent or fetch + fast-forward if present.
- Read the latest `$HOME/.agents/Agents/AGENTS.md` and this project's `projects/<project>/` state, handoff, code map, and task files when present.
- Do not ask another agent to install this on its machine; each agent bootstraps itself.
- Agents are authorized to improve shared rules in the central repository under its rule-evolution policy.

This file contains only project-specific rules. The central repository governs shared coordination; local rules below govern this project's technical workflow. In a conflict, explicit operator instructions take precedence, then project-specific technical constraints, then shared defaults.

## Project-specific rules

- Explore repository structure with `find .` or a targeted path; never use `find /`.
- Prefer targeted code reads using the central code map and checkpoint locations. Read only the relevant regions; expand the search only when coordinates are stale or evidence requires it.
- Once an exact function or symbol is identified, use it for subsequent searches and references.
- Keep project requirements and task-specific handoff details in this repository's `task.md`; keep cross-agent coordination state in Tulipskun/Agents.
