-- OpenGuard passkeys: WebAuthn credentials for passwordless login.
-- Idempotent: safe to paste into the Neon SQL Editor more than once.
--
-- credential_id: base64url credential ID, UNIQUE across all users — one
--   authenticator credential belongs to exactly one account.
-- public_key: base64url-encoded credential public key (COSE, as returned
--   by @simplewebauthn/server). Never exposed by the API.
-- counter: authenticator signature counter; updated on every login and
--   checked against replay (a counter that goes backwards rejects login).
-- transports: JSON string of transports, e.g. '["usb","nfc"]'.
-- name: user-facing label, e.g. 'Pixel 8' or 'Passkey'.
-- Deleting a user cascades and deletes their passkeys.
CREATE TABLE IF NOT EXISTS passkeys (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  credential_id text NOT NULL UNIQUE,
  public_key text NOT NULL,
  counter bigint NOT NULL DEFAULT 0,
  transports text,
  name text NOT NULL DEFAULT 'Passkey',
  created_at timestamptz NOT NULL DEFAULT now(),
  last_used_at timestamptz
);

-- Passkeys are listed per user, newest first.
CREATE INDEX IF NOT EXISTS idx_passkeys_user ON passkeys (user_id);
