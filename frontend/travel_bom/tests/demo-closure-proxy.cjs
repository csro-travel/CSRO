const assert = require('node:assert/strict')
const { randomUUID } = require('node:crypto')
const { once } = require('node:events')
const http = require('node:http')
const { test } = require('node:test')

const host = '127.0.0.1'
const counselorToken = randomUUID()
const customerToken = randomUUID()
const taskPath = '/demo/cases/C-029/tasks/TASK-3'
const closurePath = '/demo/cases/C-029/closures'
const checkinPath = '/demo/replacement-bookings/BRQ-' + 'a1'.repeat(32) + '/checkin-confirmations'

function request(instance, target, { method, headers = {}, body }) {
  const suppliedHeaders = {
    Host: instance.authority, Origin: 'http://' + instance.authority, 'Sec-Fetch-Site': 'same-origin',
    'Content-Type': 'application/json', Authorization: 'Bearer browser-controlled', ...headers,
  }
  for (const key of Object.keys(suppliedHeaders)) if (suppliedHeaders[key] === undefined) delete suppliedHeaders[key]
  return new Promise((resolve, reject) => {
    const req = http.request({ host, port: instance.port, path: target, method, headers: suppliedHeaders }, res => {
      const chunks = []
      res.on('data', chunk => chunks.push(chunk))
      res.on('error', reject)
      res.on('end', () => resolve({ status: res.statusCode, body: Buffer.concat(chunks).toString('utf8') }))
    })
    req.on('error', reject)
    req.end(body)
  })
}

async function proxy(t, overrides = {}) {
  const { createDemoServer } = await import('../demo-server.mjs')
  const forwarded = []
  const config = { port: 3100, backendPort: 18082, token: counselorToken, closureWrites: true, ...overrides }
  const server = createDemoServer({
    ...config,
    handle(req, res) { res.writeHead(418).end() },
    async fetchUpstream(url, options) {
      let body = ''
      if (options.body) for await (const chunk of options.body) body += chunk
      forwarded.push({ url, method: options.method, headers: options.headers, body })
      return new Response('{}', { status: options.method === 'PATCH' ? 200 : 201, headers: { 'Content-Type': 'application/json' } })
    },
  })
  server.listen(0, host)
  await once(server, 'listening')
  t.after(() => new Promise(resolve => server.close(resolve)))
  return { port: server.address().port, authority: host + ':' + config.port, forwarded }
}

test('counselor proxy forwards API-008 completion, keyed API-018 closure and API-006 evidence with server credentials only', async t => {
  const instance = await proxy(t)
  const task = await request(instance, taskPath, { method: 'PATCH', body: '{"status":"DONE","completionNote":"done"}' })
  assert.equal(task.status, 200)
  const closure = await request(instance, closurePath, { method: 'POST', body: '{}', headers: { 'Idempotency-Key': 'close:1' } })
  assert.equal(closure.status, 201)
  const evidence = await request(instance, '/demo/cases/C-029/evidence', { method: 'POST', body: '{}', headers: { 'Idempotency-Key': 'call:1' } })
  assert.equal(evidence.status, 201)
  assert.deepEqual(instance.forwarded.map(item => [item.method, item.url, item.headers.Authorization, item.headers['Idempotency-Key']]), [
    ['PATCH', 'http://127.0.0.1:18082/api/v1/cases/C-029/tasks/TASK-3', 'Bearer ' + counselorToken, undefined],
    ['POST', 'http://127.0.0.1:18082/api/v1/cases/C-029/closures', 'Bearer ' + counselorToken, 'close:1'],
    ['POST', 'http://127.0.0.1:18082/api/v1/cases/C-029/evidence', 'Bearer ' + counselorToken, 'call:1'],
  ])
})

test('closure requires an idempotency key, same-origin browser headers and the explicit proxy flag', async t => {
  const instance = await proxy(t)
  assert.equal((await request(instance, closurePath, { method: 'POST', body: '{}' })).status, 400)
  assert.equal((await request(instance, closurePath, { method: 'POST', body: '{}', headers: { 'Idempotency-Key': 'k', 'Sec-Fetch-Site': 'cross-site' } })).status, 403)
  assert.equal((await request(instance, '/demo/cases/C-029/tasks/TASK-0', { method: 'PATCH', body: '{}' })).status, 403)
  assert.equal((await request(instance, checkinPath, { method: 'POST', body: '{}', headers: { 'Idempotency-Key': 'k' } })).status, 403)
  const disabled = await proxy(t, { closureWrites: false })
  assert.equal((await request(disabled, closurePath, { method: 'POST', body: '{}', headers: { 'Idempotency-Key': 'k' } })).status, 403)
  assert.equal(instance.forwarded.length + disabled.forwarded.length, 0)
})

test('customer proxy forwards only API-017 check-in and never counselor commands', async t => {
  const instance = await proxy(t, { port: 3101, token: customerToken, customerMode: true })
  const checkin = await request(instance, checkinPath, { method: 'POST', body: '{}', headers: { 'Idempotency-Key': 'checkin:1' } })
  assert.equal(checkin.status, 201)
  assert.equal((await request(instance, taskPath, { method: 'PATCH', body: '{}' })).status, 403)
  assert.equal((await request(instance, closurePath, { method: 'POST', body: '{}', headers: { 'Idempotency-Key': 'k' } })).status, 403)
  assert.equal((await request(instance, '/demo/cases/C-029/evidence', { method: 'POST', body: '{}', headers: { 'Idempotency-Key': 'k' } })).status, 403)
  assert.deepEqual(instance.forwarded.map(item => [item.url, item.headers.Authorization]), [
    ['http://127.0.0.1:18082' + checkinPath.replace('/demo/', '/api/v1/'), 'Bearer ' + customerToken],
  ])
})

test('customer proxy reads only its booking and counselor list accepts CLOSED and a search term', async t => {
  const customer = await proxy(t, { port: 3101, token: customerToken, customerMode: true })
  const read = (instance, target) => request(instance, target, { method: 'GET', headers: { 'X-Demo-Read': '1', Origin: undefined, 'Content-Type': undefined } })
  assert.equal((await read(customer, '/demo/bookings/YB-001')).status, 201)
  assert.equal((await read(customer, '/demo/cases?page=0')).status, 403)
  const counselor = await proxy(t)
  assert.equal((await read(counselor, '/demo/cases?page=0&status=CLOSED&q=' + encodeURIComponent('제주'))).status, 201)
  assert.equal((await read(counselor, '/demo/cases?page=0&status=DELETED')).status, 403)
  assert.deepEqual(customer.forwarded.map(item => item.url), ['http://127.0.0.1:18082/api/v1/bookings/YB-001'])
})
