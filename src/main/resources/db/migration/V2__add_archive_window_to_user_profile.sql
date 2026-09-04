-- Persist each user's preferred archive date range so it survives
-- sessions and devices. NULL means "use the defaults" (2004-02-12 / today).

ALTER TABLE app.user_profile
    ADD COLUMN IF NOT EXISTS archive_from DATE,
    ADD COLUMN IF NOT EXISTS archive_to   DATE;
