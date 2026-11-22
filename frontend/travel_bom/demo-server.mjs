import http from 'node:http'
import next from 'next'

const host = '127.0.0.1'
const port = Number(process.env.PORT || 3000)
const backendPort = Number(process.env.CSRO_DEMO_SPRING_PORT || 18080)
const token = process.env.CSRO_DEMO_TOKEN
if (process.env.CSRO_LOCAL_DEMO !== 'true' || !token) throw Error('Explicit local demo configuration required')
const app = next({ dev: false, hostname: host, port })
await app.prepare()
const handle = app.getRequestHandler()
http.createServer(async (req, res) => {
  if (req.socket.remoteAddress !== host || req.headers.host !== host + ':' + port ||
      Object.keys(req.headers).some(key => key === 'forwarded' || key.startsWith('x-forwarded-'))) {
    res.writeHead(403).end()
    return
  }
  res.setHeader('Content-Security-Policy', "frame-ancestors 'none'")
  if (!req.url.startsWith('/demo/cases')) return handle(req, res)
  if (req.method !== 'GET' || req.headers['x-demo-read'] !== '1' ||
      req.headers['sec-fetch-site'] !== 'same-origin' ||
      !/^\/demo\/cases(?:\/C-[0-9]{3,})?$/.test(req.url)) {
    res.writeHead(403).end()
    return
  }
  try {
    const upstream = await fetch('http://' + host + ':' + backendPort + req.url.replace('/demo/', '/api/v1/'),
      { headers: { Authorization: 'Bearer ' + token }, redirect: 'error', signal: AbortSignal.timeout(5000) })
    const body = await upstream.text()
    res.writeHead(upstream.status, { 'Content-Type': upstream.headers.get('content-type') || 'application/json', 'Cache-Control': 'no-store' }).end(body)
  } catch {
    res.writeHead(502).end()
  }
}).listen(port, host)
