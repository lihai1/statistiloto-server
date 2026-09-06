-- Add result_json column to store the full SimulateResultResponse (draws + summary)
-- so saved simulations can render the same rich results view as the Simulate tab.

ALTER TABLE app.saved_simulations ADD COLUMN IF NOT EXISTS result_json JSONB;
