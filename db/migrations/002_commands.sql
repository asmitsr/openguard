-- OpenGuard Phase 2: remote commands, security events, lost mode state.
-- Idempotent: safe to paste into the Neon SQL Editor more than once.
CREATE TABLE IF NOT EXISTS commands (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  device_id uuid NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  action text NOT NULL CHECK (action IN ('ring','lock','lost_mode_on','lost_mode_off')),
  payload jsonb,
  status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','delivered','acked','failed','expired')),
  nonce text NOT NULL UNIQUE,
  idempotency_key text UNIQUE,
  issued_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  acked_at timestamptz,
  result text
);
CREATE INDEX IF NOT EXISTS idx_commands_device_status ON commands (device_id, status);
CREATE INDEX IF NOT EXISTS idx_commands_expires ON commands (expires_at) WHERE status = 'pending';
CREATE TABLE IF NOT EXISTS security_events (
  id bigserial PRIMARY KEY,
  device_id uuid NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  event_type text NOT NULL CHECK (event_type IN ('sim_changed','admin_disabled','location_permission_revoked','location_disabled','low_battery','sync_gap')),
  details jsonb,
  confirmed boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_security_events_device_created ON security_events (device_id, created_at DESC);
ALTER TABLE devices ADD COLUMN IF NOT EXISTS lost_mode boolean NOT NULL DEFAULT false;
ALTER TABLE devices ADD COLUMN IF NOT EXISTS lost_message text;
ALTER TABLE devices ADD COLUMN IF NOT EXISTS location_permission text;
ALTER TABLE devices ADD COLUMN IF NOT EXISTS location_enabled boolean;
