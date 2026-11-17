-- OpenGuard: failed_unlock_attempt security event type.
-- Idempotent: safe to paste into the Neon SQL Editor more than once.
--
-- The app records a failed_unlock_attempt event on each real-keyguard
-- failure (DeviceAdminReceiver.onPasswordFailed) and, once the App
-- Lock round lands, on each wrong app-lock password on the Lost Mode
-- screen. The security_events CHECK constraint lists the allowed
-- event types, so add it there or the bulk insert rejects the whole
-- batch. The old
-- CHECK is dropped by definition (any CHECK on security_events
-- mentioning event_type), not by assumed name, so this works
-- regardless of what Postgres auto-named it — and because the
-- constraint this file re-adds also mentions event_type, a second
-- run drops it again before re-adding it.
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
ALTER TABLE security_events ADD CONSTRAINT security_events_event_type_check CHECK (event_type IN ('sim_changed','admin_disabled','location_permission_revoked','location_disabled','low_battery','sync_gap','sms_command','chaos_started','failed_unlock_attempt'));
