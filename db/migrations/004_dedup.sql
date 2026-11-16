-- OpenGuard map + offline round: re-link a reinstalled phone instead of
-- duplicating it. The app sends its ANDROID_ID as hardware_id on register;
-- the server returns the existing device row (history preserved) with a
-- fresh device token. Idempotent: safe to paste into the Neon SQL Editor
-- more than once.
--
-- Note: rows registered before this migration have hardware_id NULL, so an
-- existing duplicate is NOT auto-merged — unlink the extra row once from
-- the app or dashboard; reinstalls after that re-link automatically.
ALTER TABLE devices ADD COLUMN IF NOT EXISTS hardware_id text;
CREATE UNIQUE INDEX IF NOT EXISTS idx_devices_user_hardware
  ON devices (user_id, hardware_id) WHERE hardware_id IS NOT NULL;
