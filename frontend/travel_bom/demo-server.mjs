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
    (allowedPage || /^\/demo\/cases(?:\/C-[0-9]{3,})?$/.test(req.url) ||
      /^\/demo\/cases\/C-[0-9]{3,38}\/quotes$/.test(req.url) || allowsMessageList(req.url))
}

function allowsCustomerRead(req) {
  return req.method === 'GET' && req.headers['x-demo-read'] === '1' &&
    req.headers['sec-fetch-site'] === 'same-origin' &&
    /^\/demo\/cases\/C-[0-9]{3,38}\/messages(?:\?|$)/.test(req.url) && allowsMessageList(req.url)
}

function allowsReplacementRead(req) {
  return req.method === 'GET' && req.headers['x-demo-read'] === '1' &&
    req.headers['sec-fetch-site'] === 'same-origin' &&
    /^\/demo\/replacement-bookings\/BRQ-[a-f0-9]{64}$/.test(req.url)
}

function validateCustomerConfig(customerMode, port, backendPort, token) {
  if (customerMode && (port !== 3101 || backendPort !== 18082 || typeof token !== 'string' || !token)) {
    throw Error('Explicit isolated customer demo configuration required')
  }
}

export function createDemoServer({ port, backendPort, token, chatWrites = false, quoteWrites = false, offerWrites = false, customerMode = false, replacementAccess = false, incidentWrites = false, closureWrites = false, handle, fetchUpstream = fetch }) {
  validateCustomerConfig(customerMode, port, backendPort, token)
  const authority = host + ':' + port
  const origin = 'http://' + authority
  const counselorMode = !customerMode && port === 3100 && backendPort === 18082
  const canSendMessages = chatWrites && counselorMode
  const canSearchQuotes = quoteWrites && counselorMode
  const canSendOffers = offerWrites && counselorMode
  const canRequestReplacement = replacementAccess && counselorMode
  const canReadReplacement = replacementAccess && (counselorMode || customerMode)
  const canReportIncident = incidentWrites && customerMode
  const canCloseCases = closureWrites && counselorMode
  const canConfirmCheckin = closureWrites && customerMode

  return http.createServer(async (req, res) => {
    if (req.socket.remoteAddress !== host || req.headers.host !== authority ||
        Object.keys(req.headers).some(key => key === 'forwarded' || key.startsWith('x-forwarded-'))) {
      res.writeHead(403).end()
      return
    }
    res.setHeader('Content-Security-Policy', "frame-ancestors 'none'")
    if (!req.url.startsWith('/demo/cases') && !req.url.startsWith('/demo/replacement-bookings') && !req.url.startsWith('/demo/bookings')) return handle(req, res)

    const headers = { Authorization: 'Bearer ' + token }
    const options = { method: req.method, headers, redirect: 'error', signal: AbortSignal.timeout(5000) }
    const replacement = req.method === 'POST' && canRequestReplacement && /^\/demo\/cases\/C-[0-9]{3,38}\/replacement-bookings$/.test(req.url)
    if (req.method === 'POST' || req.method === 'PUT' || req.method === 'PATCH') {
      const message = req.method === 'POST' && canSendMessages && /^\/demo\/cases\/C-[0-9]{3,38}\/messages$/.test(req.url)
      const quoteSearch = req.method === 'POST' && canSearchQuotes && /^\/demo\/cases\/C-[0-9]{3,38}\/quote-searches$/.test(req.url)
      const offer = req.method === 'POST' && canSendOffers && /^\/demo\/cases\/C-[0-9]{3,38}\/offers$/.test(req.url)
      const selection = req.method === 'PUT' && customerMode && /^\/demo\/cases\/C-[0-9]{3,38}\/selection$/.test(req.url)
      const incident = req.method === 'POST' && canReportIncident && /^\/demo\/bookings\/[A-Za-z0-9][A-Za-z0-9._-]{0,39}\/incident-reports$/.test(req.url)
      const task = req.method === 'PATCH' && canCloseCases && /^\/demo\/cases\/C-[0-9]{3,38}\/tasks\/TASK-[1-9][0-9]{0,17}$/.test(req.url)
      const closure = req.method === 'POST' && canCloseCases && /^\/demo\/cases\/C-[0-9]{3,38}\/closures$/.test(req.url)
      const checkin = req.method === 'POST' && canConfirmCheckin && /^\/demo\/replacement-bookings\/BRQ-[a-f0-9]{64}\/checkin-confirmations$/.test(req.url)
      if ((!message && !quoteSearch && !offer && !selection && !replacement && !incident && !task && !closure && !checkin) || req.headers.origin !== origin || req.headers['sec-fetch-site'] !== 'same-origin') {
        res.writeHead(403).end()
        return
      }
      const contentType = req.headers['content-type']
      if (contentType?.split(';', 1)[0].trim().toLowerCase() !== 'application/json') {
        res.writeHead(415).end()
        return
      }
      if (quoteSearch || offer || selection || replacement || incident || closure || checkin) {
        const key = req.headers['idempotency-key']
        if (typeof key !== 'string' || !/^[A-Za-z0-9._:-]{1,100}$/.test(key)) {
          res.writeHead(400).end()
          return
        }
        headers['Idempotency-Key'] = key
      }
      headers['Content-Type'] = contentType
      options.body = req
      options.duplex = 'half'
    } else if (!(canReadReplacement && allowsReplacementRead(req)) && !(customerMode ? allowsCustomerRead(req) : allowsCaseRead(req))) {
      res.writeHead(403).end()
      return
    }

    try {
      const upstream = await fetchUpstream('http://' + host + ':' + backendPort + req.url.replace('/demo/', '/api/v1/'), options)
      const body = await upstream.text()
      const responseHeaders = { 'Content-Type': upstream.headers.get('content-type') || 'application/json', 'Cache-Control': 'no-store' }
      if (replacement && upstream.status === 202) {
        const location = upstream.headers.get('location')
        if (/^\/api\/v1\/replacement-bookings\/BRQ-[a-f0-9]{64}$/.test(location)) {
          responseHeaders.Location = location.replace('/api/v1/', '/demo/')
        }
      }
      res.writeHead(upstream.status, responseHeaders).end(body)
    } catch {
      res.writeHead(502).end()
    }
  })
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  const port = Number(process.env.PORT || 3000)
  const backendPort = Number(process.env.CSRO_DEMO_SPRING_PORT || 18080)
  const customerMode = process.env.CSRO_CUSTOMER_DEMO_PROXY === 'true'
  const token = customerMode ? process.env.CSRO_CUSTOMER_DEMO_TOKEN : process.env.CSRO_DEMO_TOKEN
  if (process.env.CSRO_LOCAL_DEMO !== 'true' || !token) throw Error('Explicit local demo configuration required')
  validateCustomerConfig(customerMode, port, backendPort, token)
  const app = next({ dev: false, hostname: host, port })
  await app.prepare()
  createDemoServer({
    port, backendPort, token, handle: app.getRequestHandler(),
    chatWrites: process.env.CSRO_CHAT_DEMO_PROXY === 'true',
    quoteWrites: process.env.CSRO_QUOTE_DEMO_PROXY === 'true',
    offerWrites: process.env.CSRO_OFFER_DEMO_PROXY === 'true',
    customerMode,
    replacementAccess: process.env.CSRO_REPLACEMENT_DEMO_PROXY === 'true',
    incidentWrites: process.env.CSRO_INCIDENT_DEMO_PROXY === 'true',
    closureWrites: process.env.CSRO_CLOSURE_DEMO_PROXY === 'true',
  }).listen(port, host)
}
