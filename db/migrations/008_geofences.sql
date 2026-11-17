-- OpenGuard geofences: a circular boundary per device. The phone watches
-- its own position and reports enter/exit transitions to the server; the
-- server never tracks location itself for this feature.
-- Idempotent: safe to paste into the Neon SQL Editor more than once.
--
-- action: what the device does on exit (and on enter for 'notify') —
--   'notify' = alert the owner, 'lock' = lock the device,
--   'lost_mode' = enable Lost Mode.
-- transition: 'enter' | 'exit', reported by the device.
CREATE TABLE IF NOT EXISTS geofences (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  device_id uuid NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  name text NOT NULL,
  latitude double precision NOT NULL CHECK (latitude >= -90 AND latitude <= 90),
  longitude double precision NOT NULL CHECK (longitude >= -180 AND longitude <= 180),
  radius_m int NOT NULL CHECK (radius_m >= 50 AND radius_m <= 5000),
  action text NOT NULL CHECK (action IN ('notify', 'lock', 'lost_mode')),
  created_at timestamptz NOT NULL DEFAULT now()
);

-- Geofences are listed per device.
CREATE INDEX IF NOT EXISTS idx_geofences_device ON geofences (device_id);

CREATE TABLE IF NOT EXISTS geofence_events (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  geofence_id uuid NOT NULL REFERENCES geofences(id) ON DELETE CASCADE,
  device_id uuid NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  transition text NOT NULL CHECK (transition IN ('enter', 'exit')),
  recorded_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

-- Events are listed per geofence and per device, newest first.
CREATE INDEX IF NOT EXISTS idx_geofence_events_geofence_recorded
  ON geofence_events (geofence_id, recorded_at DESC);
CREATE INDEX IF NOT EXISTS idx_geofence_events_device_recorded
  ON geofence_events (device_id, recorded_at DESC);
