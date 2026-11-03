### Skill: Code Navigation and Project Management Protocol

#### 1. Code Exploration and Reading Rules
- **Structure Exploration:** Use `find .` or `find /path` to inspect directory structures. **NEVER** use `find /`.
- **Code Reading:** Always read the full code using available tools. Never read partial sections unless the file exceeds 5,000 lines. 
- **Targeted Search:** Once an exact function name is identified, use it for subsequent searches and references.

#### 2. State Tracking via `MAIN.md`
- **Trigger:** Whenever invoking any tool **other than** reading or searching information, you must create or update the `MAIN.md` file.
- **Simultaneous Tool Execution:** If subsequent steps require using other tools, invoke those tools simultaneously in the exact same turn as editing `MAIN.md` (e.g., executing `edit(MAIN.md)` and `edit(code.txt)` together).
- **Structure of `MAIN.md`:**

  GOAL:
  [<IN_PROCESS|COMPLETED>] current goal (one line, version tag)

  PLAN:
  [<IN_PROCESS|COMPLETED>] active steps, or "(no active plan)"

  ARCHIVE:
  <version> <one-line summary>
  (newest first; all past completed goals collapsed here)

  NOTE:
  - operational facts relevant to the next session only

- **Size cap:** MAIN.md must stay under 60 lines total. When a goal completes:
  1. Move it from GOAL to ARCHIVE as a single one-line entry: `v<N> <what shipped>`
  2. Do NOT write prose paragraphs or multi-line explanations for completed work
  3. Do NOT keep plan steps after the goal is archived
  4. Prune NOTE entries that are no longer operationally relevant

- **When starting new work:** clear the previous GOAL and PLAN (move to ARCHIVE), write the new GOAL as one line, add PLAN steps only for in-progress work.

- **What goes in NOTE:** environment quirks, pending operator decisions, build/deploy state that the next session needs. What does NOT go in NOTE: completed achievements, measurement history, commit SHAs (those are in git log).
