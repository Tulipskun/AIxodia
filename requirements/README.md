# AIxodia Requirements

Source of truth for AIxodia product behavior. Read before changing code.

## Files

- `product.md` — purpose and goals.
- `functional.md` — stable functional requirements (AX-xxx).
- `constraints.md` — non-negotiable limits (AXC-xxx).
- `decisions.md` — accepted architecture decisions.
- `changes.md` — append-only change history (AXCH-xxx).

## Change rule

New request conflicting with an existing requirement is a spec change:
identify conflict, update requirement, record in `changes.md`,
re-evaluate architecture/code/tests before done.
