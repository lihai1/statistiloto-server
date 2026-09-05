-- Saved simulation results — stores the request and summary JSON for each
-- simulation a user bookmarks. Owned by the Java BFF (app schema).

CREATE TABLE IF NOT EXISTS app.saved_simulations (
    id           BIGSERIAL PRIMARY KEY,
    user_sub     VARCHAR(255) NOT NULL,
    request_json JSONB NOT NULL,
    summary_json JSONB NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_saved_sim_user FOREIGN KEY (user_sub) REFERENCES app.user_profile(sub) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_saved_simulations_user_sub ON app.saved_simulations(user_sub);
