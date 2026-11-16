import { Hono } from 'hono'
import dashboardHtml from '../web/dist/index.html'
import auth from './routes/auth'
import commands from './routes/commands'
import devices from './routes/devices'
import locations from './routes/locations'
import misc from './routes/misc'
import type { Env } from './lib/db'

const app = new Hono<{ Bindings: Env }>()

// Dashboard: React app built by backend/web (npm run build there first);
// the built single file is inlined by esbuild --loader:.html=text.
app.get('/', (c) => c.html(dashboardHtml))
app.get('/api/v1/health', (c) => c.json({ ok: true }))

app.route('/api/v1/auth', auth)
app.route('/api/v1/devices', devices)
app.route('/api/v1/devices', locations)
app.route('/api/v1', commands)
app.route('/api/v1', misc)

app.notFound((c) => c.json({ error: 'not found' }, 404))
app.onError((err, c) => {
  // Never log request bodies here: they may carry derived keys or locations.
  console.error(err)
  return c.json({ error: 'internal error' }, 500)
})

export default app
