-- OpenGuard fast updates: per-device push (ntfy) topic + sms_command event type.
-- Idempotent: safe to paste into the Neon SQL Editor more than once.
--
-- push_topic: the device generates its own random unguessable ntfy topic
-- ("og-" + 128 random bits) and registers it via POST /devices/:id/push-topic.
-- The server publishes only an opaque wake-up id there when a command is
-- created; the topic itself is never returned by any endpoint. Sign-out
-- clears it (see routes/devices.ts). Devices without a topic simply skip
-- push and keep the 15-minute poll.
ALTER TABLE devices ADD COLUMN IF NOT EXISTS push_topic text;
CREATE UNIQUE INDEX IF NOT EXISTS idx_devices_push_topic
  ON devices (push_topic) WHERE push_topic IS NOT NULL;
-- The security_events CHECK constraint lists the allowed event types; add
-- sms_command (accepted SMS commands, recorded by the app) and
-- chaos_started (SIM removed during Lost Mode) to it. The old CHECK is
-- dropped by definition (any CHECK on security_events mentioning
-- event_type), not by assumed name, so this works regardless of what
-- Postgres auto-named it in 002_commands.sql.
DO $$
DECLARE r record;
BEGIN
  FOR r IN SELECT conname FROM pg_constraint
           WHERE conrelid = 'security_events'::regclass
             AND contype = 'c'
             AND pg_get_constraintdef(oid) LIKE '%event_type%'
  LOOP
    EXECUTE format('ALTER TABLE security_events DROP CONSTRAINT %I', r.conname);
  END LOOP;
END $$;
ALTER TABLE security_events ADD CONSTRAINT security_events_event_type_check CHECK (event_type IN ('sim_changed','admin_disabled','location_permission_revoked','location_disabled','low_battery','sync_gap','sms_command','chaos_started'));
