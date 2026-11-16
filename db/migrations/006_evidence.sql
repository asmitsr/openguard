-- OpenGuard evidence clips (Workstream A): METADATA ONLY. Clip bytes
-- live in the owner's own Google Drive or on the device, never on this
-- server (Neon Free is 1 GB and there is no object storage).
-- Idempotent: safe to paste into the Neon SQL Editor more than once.
-- This file also holds the Workstream B commands CHECK (v3) and the
-- Workstream C device-health columns, merged in by the coordinator.
--
-- camera: which lens recorded the clip — 'front' | 'back' | 'audio'
--   ('audio' = audio-only clip, recorded while device admin had the
--   camera disabled during Lost Mode).
-- storage: 'drive' = bytes in the owner's Drive (drive_file_id/link
--   set, uploaded_at set); 'local' = bytes still on the device only.
CREATE TABLE IF NOT EXISTS evidence_clips (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  device_id uuid NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  recorded_at timestamptz NOT NULL,
  camera text NOT NULL CHECK (camera IN ('front', 'back', 'audio')),
  duration_s int NOT NULL CHECK (duration_s > 0 AND duration_s <= 60),
  size_bytes bigint NOT NULL CHECK (size_bytes >= 0 AND size_bytes <= 200000000),
  storage text NOT NULL CHECK (storage IN ('local', 'drive')),
  drive_file_id text,
  drive_link text,
  uploaded_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now()
);

-- Clips are listed per device, newest first.
CREATE INDEX IF NOT EXISTS idx_evidence_clips_device_recorded
  ON evidence_clips (device_id, recorded_at DESC);

-- Workstream B: extend the commands action CHECK to the Remote Control
-- Center actions. Idempotent: safe to paste more than once.
DO $$
BEGIN
  ALTER TABLE commands DROP CONSTRAINT IF EXISTS commands_action_check_v2;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'commands_action_check_v3') THEN
    ALTER TABLE commands ADD CONSTRAINT commands_action_check_v3
      CHECK (action IN ('ring','lock','lost_mode_on','lost_mode_off','erase',
        'set_volume','set_brightness','set_auto_brightness','torch_on','torch_off',
        'set_ringer_mode','set_dnd','set_screen_timeout','set_auto_rotate',
        'speak_message','wake_screen','set_wallpaper','bluetooth_on','evidence_capture'));
  END IF;
END $$;

-- Workstream C: device health columns reported by the heartbeat.
-- Idempotent: safe to paste into the Neon SQL Editor more than once.
ALTER TABLE devices ADD COLUMN IF NOT EXISTS storage_total_bytes bigint;
ALTER TABLE devices ADD COLUMN IF NOT EXISTS storage_free_bytes bigint;
ALTER TABLE devices ADD COLUMN IF NOT EXISTS ram_total_bytes bigint;
ALTER TABLE devices ADD COLUMN IF NOT EXISTS ram_free_bytes bigint;
ALTER TABLE devices ADD COLUMN IF NOT EXISTS battery_temperature_c real;
ALTER TABLE devices ADD COLUMN IF NOT EXISTS booted_at timestamptz;
ALTER TABLE devices ADD COLUMN IF NOT EXISTS notifications_enabled boolean;
