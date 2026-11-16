import { Hono } from 'hono'
import dashboardHtml from '../public/index.html'
import auth from './routes/auth'
import devices from './routes/devices'
import locations from './routes/locations'
import misc from './routes/misc'
import type { Env } from './lib/db'

const app = new Hono<{ Bindings: Env }>()

// Dashboard (placeholder until the dashboard agent overwrites public/index.html)
app.get('/', (c) => c.html(dashboardHtml))
app.get('/api/v1/health', (c) => c.json({ ok: true }))

app.route('/api/v1/auth', auth)
app.route('/api/v1/devices', devices)
app.route('/api/v1/devices', locations)
app.route('/api/v1', misc)

app.notFound((c) => c.json({ error: 'not found' }, 404))
app.onError((err, c) => {
  // Never log request bodies here: they may carry derived keys or locations.
  console.error(err)
  return c.json({ error: 'internal error' }, 500)
})

export default app
