-- Feedback table — stores user feedback and lottery suggestions.
-- Admins can view, filter, and manage feedback via the admin UI.

CREATE TABLE IF NOT EXISTS app.feedback (
    id         BIGSERIAL PRIMARY KEY,
    user_sub   VARCHAR(255),
    type       VARCHAR(50) NOT NULL DEFAULT 'general',
    status     VARCHAR(20) NOT NULL DEFAULT 'new',
    page       VARCHAR(255),
    language   VARCHAR(10),
    tier       VARCHAR(20),
    message    TEXT NOT NULL,
    extra      JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_feedback_status_created ON app.feedback (status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_feedback_user_sub ON app.feedback (user_sub);
