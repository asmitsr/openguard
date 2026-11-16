import { db, type Env } from './db'

// Append-only audit log. Events: register, login, logout, session_revoke,
// sessions_revoke_others, step_up, recovery_codes_generate, password_recover,
// device_register, device_relink, device_unlink, device_sign_out,
// command_create, command_ack, command_reject.
// (Account deletion has no surviving audit row: audit_events.user_id cascades,
// so the row would be deleted with the user.)
export async function audit(
  env: Env,
  userId: string,
  event: string,
  deviceId: string | null = null,
  detail: Record<string, unknown> | null = null,
): Promise<void> {
  const sql = db(env)
  await sql`
    INSERT INTO audit_events (user_id, device_id, event, detail)
    VALUES (${userId}, ${deviceId}, ${event}, ${detail ? JSON.stringify(detail) : null}::jsonb)`
}
