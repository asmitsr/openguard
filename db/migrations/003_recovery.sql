-- OpenGuard Phase 3: remote recovery — recovery codes, step-up tokens, erase.
-- Idempotent: safe to paste into the Neon SQL Editor more than once.
CREATE TABLE IF NOT EXISTS recovery_codes (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  -- SHA-256 of the normalized code (uppercase, dashes stripped). The code
  -- itself is never stored — it is shown once, at generation.
  code_hash text UNIQUE NOT NULL,
  used boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  used_at timestamptz
);
CREATE INDEX IF NOT EXISTS idx_recovery_codes_user ON recovery_codes (user_id);
CREATE TABLE IF NOT EXISTS step_up_tokens (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  -- SHA-256 of the opaque token. Single-use: the row is deleted on validation.
  token_hash text UNIQUE NOT NULL,
  expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_step_up_tokens_user ON step_up_tokens (user_id);
-- Extend the commands action CHECK to include 'erase'. The original
-- constraint is auto-named commands_action_check; replace it idempotently.
DO $$
BEGIN
  ALTER TABLE commands DROP CONSTRAINT IF EXISTS commands_action_check;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'commands_action_check_v2') THEN
    ALTER TABLE commands ADD CONSTRAINT commands_action_check_v2
      CHECK (action IN ('ring','lock','lost_mode_on','lost_mode_off','erase'));
  END IF;
END $$;
