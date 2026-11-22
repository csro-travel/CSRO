import http from 'node:http'
import { pathToFileURL } from 'node:url'
import next from 'next'

const host = '127.0.0.1'

function allowsMessageList(url) {
  const match = /^\/demo\/cases\/C-[0-9]{3,}\/messages(?:\?([^#]*))?$/.exec(url)
  if (!match) return false

  // Spring API-011 validates values and returns its documented errors.
  const keys = [...new URLSearchParams(match[1]).keys()]
  return keys.length === new Set(keys).size &&
    keys.every(key => key === 'limit' || key === 'beforeMessageId')
}

function allowsCaseRead(req) {
  const listPage = /^\/demo\/cases\?page=(0|[1-9][0-9]{0,9})(?:&status=(?:OPEN|IN_PROGRESS|WAITING))?$/.exec(req.url)
  const allowedPage = listPage !== null && Number(listPage[1]) <= 2147483647
  return req.method === 'GET' && req.headers['x-demo-read'] === '1' &&
    req.headers['sec-fetch-site'] === 'same-origin' &&
    (allowedPage || /^\/demo\/cases(?:\/C-[0-9]{3,})?$/.test(req.url) || allowsMessageList(req.url))
}

export function createDemoServer({ port, backendPort, token, chatWrites = false, handle, fetchUpstream = fetch }) {
  const authority = host + ':' + port
  const origin = 'http://' + authority
  const canSendMessages = chatWrites && port === 3100 && backendPort === 18082

  return http.createServer(async (req, res) => {
    if (req.socket.remoteAddress !== host || req.headers.host !== authority ||
        Object.keys(req.headers).some(key => key === 'forwarded' || key.startsWith('x-forwarded-'))) {
      res.writeHead(403).end()
      return
    }
    res.setHeader('Content-Security-Policy', "frame-ancestors 'none'")
    if (!req.url.startsWith('/demo/cases')) return handle(req, res)

    const headers = { Authorization: 'Bearer ' + token }
    const options = { method: req.method, headers, redirect: 'error', signal: AbortSignal.timeout(5000) }
    if (req.method === 'POST') {
      if (!canSendMessages || req.headers.origin !== origin || req.headers['sec-fetch-site'] !== 'same-origin' ||
          !/^\/demo\/cases\/C-[0-9]{3,38}\/messages$/.test(req.url)) {
        res.writeHead(403).end()
        return
      }
      const contentType = req.headers['content-type']
      if (contentType?.split(';', 1)[0].trim().toLowerCase() !== 'application/json') {
        res.writeHead(415).end()
        return
      }
      headers['Content-Type'] = contentType
      options.body = req
      options.duplex = 'half'
    } else if (!allowsCaseRead(req)) {
      res.writeHead(403).end()
      return
    }

    try {
      const upstream = await fetchUpstream('http://' + host + ':' + backendPort + req.url.replace('/demo/', '/api/v1/'), options)
      const body = await upstream.text()
      res.writeHead(upstream.status, { 'Content-Type': upstream.headers.get('content-type') || 'application/json', 'Cache-Control': 'no-store' }).end(body)
    } catch {
      res.writeHead(502).end()
    }
  })
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  const port = Number(process.env.PORT || 3000)
  const backendPort = Number(process.env.CSRO_DEMO_SPRING_PORT || 18080)
  const token = process.env.CSRO_DEMO_TOKEN
  if (process.env.CSRO_LOCAL_DEMO !== 'true' || !token) throw Error('Explicit local demo configuration required')
  const app = next({ dev: false, hostname: host, port })
  await app.prepare()
  createDemoServer({
    port, backendPort, token, handle: app.getRequestHandler(),
    chatWrites: process.env.CSRO_CHAT_DEMO_PROXY === 'true',
  }).listen(port, host)
}
