-- AX-095: the footer of one answer travels with the turn.
-- Apply to a database created before this change:
--   wrangler d1 execute aixodia --file=worker/migrations/0002_turn_footer.sql
-- Adding a column is all it takes: old rows keep an empty footer, and the app
-- simply shows no numbers for them.
ALTER TABLE turns ADD COLUMN model TEXT NOT NULL DEFAULT '';
ALTER TABLE turns ADD COLUMN input_tokens INTEGER NOT NULL DEFAULT 0;
ALTER TABLE turns ADD COLUMN output_tokens INTEGER NOT NULL DEFAULT 0;
ALTER TABLE turns ADD COLUMN duration_ms INTEGER NOT NULL DEFAULT 0;
