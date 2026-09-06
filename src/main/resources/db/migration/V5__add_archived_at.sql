-- Add archived_at column to all user-owned tables for soft-archive on
-- account deletion. NULL = active, non-NULL = archived (admin-auditable).
-- On re-login, ensureProfile reactivates the profile with fresh defaults;
-- old child records stay archived and invisible to active queries.

ALTER TABLE app.user_profile ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ DEFAULT NULL;
ALTER TABLE app.saved_numbers ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ DEFAULT NULL;
ALTER TABLE app.saved_simulations ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ DEFAULT NULL;
ALTER TABLE app.feedback ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ DEFAULT NULL;

-- Indexes for admin archive queries (filter WHERE archived_at IS NOT NULL)
CREATE INDEX IF NOT EXISTS idx_user_profile_archived ON app.user_profile (archived_at) WHERE archived_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_saved_numbers_archived ON app.saved_numbers (user_sub, archived_at) WHERE archived_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_saved_simulations_archived ON app.saved_simulations (user_sub, archived_at) WHERE archived_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_feedback_archived ON app.feedback (user_sub, archived_at) WHERE archived_at IS NOT NULL;
