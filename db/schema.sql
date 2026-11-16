-- OpenGuard schema (Neon Postgres).
-- Apply once, e.g. via the Neon SQL editor. gen_random_uuid() is built into
-- Postgres 13+, so no pgcrypto extension is needed.

CREATE TABLE IF NOT EXISTS users (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  email text UNIQUE NOT NULL,
  -- Client-side KDF (Workers Free allows only 10 ms CPU/request, so the
  -- server cannot run PBKDF2): the client derives a 32-byte key with
  -- PBKDF2-SHA256 (600k iterations) from the password + salt, and the server
  -- stores hex(SHA-256(derived_key)). A DB leak therefore yields neither the
  -- password nor a login-capable value. Salt is hex, 16 bytes.
  password_hash text NOT NULL,
  salt text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS sessions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash text UNIQUE NOT NULL,
  refresh_token_hash text UNIQUE NOT NULL,
  user_agent text,
  ip text,
  created_at timestamptz NOT NULL DEFAULT now(),
  last_used_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL
);
-- expires_at is the access-token expiry (15 min). The refresh token is valid
-- for 30 days from created_at; created_at is renewed on each rotation, so the
-- refresh window slides with use.

-- Sessions are listed per user (GET /auth/sessions).
CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions (user_id);

CREATE TABLE IF NOT EXISTS devices (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name text NOT NULL,
  model text,
  os_version text,
  token_hash text UNIQUE NOT NULL,
  last_seen_at timestamptz,
  battery_level int,
  battery_charging boolean,
  network_type text,
  created_at timestamptz NOT NULL DEFAULT now()
);

-- Devices are listed per user and ownership-checked per request.
CREATE INDEX IF NOT EXISTS idx_devices_user ON devices (user_id);

CREATE TABLE IF NOT EXISTS locations (
  id bigserial PRIMARY KEY,
  device_id uuid NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  latitude double precision NOT NULL,
  longitude double precision NOT NULL,
  accuracy double precision NOT NULL,
  recorded_at timestamptz NOT NULL,
  provider text,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_locations_device_recorded
  ON locations (device_id, recorded_at DESC);

CREATE TABLE IF NOT EXISTS audit_events (
  id bigserial PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  device_id uuid, -- intentionally no FK: the row survives device unlink
  event text NOT NULL,
  detail jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_audit_user_created
  ON audit_events (user_id, created_at DESC);
