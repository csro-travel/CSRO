const assert = require('node:assert/strict')
const { randomUUID } = require('node:crypto')
const { once } = require('node:events')
const http = require('node:http')
const { test } = require('node:test')

const host = '127.0.0.1'
const reportPath = '/demo/bookings/YB-001/incident-reports'
const customerToken = randomUUID()
const payload = JSON.stringify({ issueType: 'CHECKIN_FAILED', atHotel: false, message: '도착 전에 호텔에서 예약을 찾을 수 없다고 안내했습니다.' })

function request(port, target, { method = 'POST', headers = {}, body = payload } = {}) {
  return new Promise((resolve, reject) => {
    const req = http.request({ host, port, path: target, method, headers: {
      Host: host + ':3101', Origin: 'http://' + host + ':3101', 'Sec-Fetch-Site': 'same-origin',
      'Content-Type': 'application/json; charset=utf-8', 'Idempotency-Key': 'incident.retry:1',
      Authorization: 'Bearer browser-controlled', 'X-Demo-Subject': 'untrusted', ...headers,
    } }, res => {
      const chunks = []
      res.on('data', chunk => chunks.push(chunk))
      res.on('error', reject)
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks).toString('utf8') }))
    })
    req.on('error', reject)
    req.end(body)
  })
}

async function proxy(t, overrides = {}, upstreamStatus = 201) {
  const { createDemoServer } = await import('../demo-server.mjs')
  const forwarded = []
  const config = { port: 3101, backendPort: 18082, token: customerToken, customerMode: true, incidentWrites: true, ...overrides }
  const server = createDemoServer({
    ...config,
    handle: () => assert.fail('데모 API가 Next.js로 우회했습니다'),
    async fetchUpstream(url, options) {
      let body = ''
      for await (const chunk of options.body) body += chunk
      forwarded.push({ url, method: options.method, headers: options.headers, body })
      const result = upstreamStatus === 201
        ? { bookingId: 'YB-001', reportId: 'IR-001', case: { caseId: 'C-029', created: true, status: 'OPEN', priority: 'URGENT' }, chatMessageId: 'MSG-001', verificationStatus: 'PENDING', receivedAt: '2026-11-25T12:00:00+09:00' }
        : { type: 'about:blank', title: 'Request rejected', status: upstreamStatus, code: 'TEST_ERROR', detail: '테스트 오류', traceId: randomUUID() }
      return new Response(JSON.stringify(result), { status: upstreamStatus, headers: { 'Content-Type': upstreamStatus === 201 ? 'application/json' : 'application/problem+json' } })
    },
  })
  server.listen(0, host)
  await once(server, 'listening')
  t.after(() => new Promise(resolve => server.close(resolve)))
  return { port: server.address().port, forwarded, authority: host + ':' + config.port }
}

test('API-002 forwards the exact body and retry key with only the customer server credential', async t => {
  const instance = await proxy(t)
  for (let retry = 0; retry < 2; retry++) {
    const response = await request(instance.port, reportPath)
    assert.equal(response.status, 201)
    assert.equal(response.headers['cache-control'], 'no-store')
    assert.equal(JSON.parse(response.body).verificationStatus, 'PENDING')
    assert.equal(response.body.includes(customerToken), false)
  }
  assert.deepEqual(instance.forwarded, [0, 1].map(() => ({
    url: 'http://127.0.0.1:18082/api/v1/bookings/YB-001/incident-reports', method: 'POST', body: payload,
    headers: { Authorization: 'Bearer ' + customerToken, 'Idempotency-Key': 'incident.retry:1', 'Content-Type': 'application/json; charset=utf-8' },
  })))
})

test('incident proxy requires opt-in customer mode and exact path, method, origin and request key', async t => {
  const instance = await proxy(t)
  for (const target of [
    reportPath + '?extra=1', reportPath + '/', reportPath + '#fragment',
    '/demo/bookings/YB-%30%30%31/incident-reports', '/demo/bookings/' + 'b'.repeat(41) + '/incident-reports',
    '/demo/bookings/YB-001', '/demo/bookings/YB-001/cancel', '/demo/cases/C-029/incident-reports',
  ]) assert.equal((await request(instance.port, target)).status, 403)
  for (const method of ['GET', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS']) {
    assert.equal((await request(instance.port, reportPath, { method })).status, 403)
  }
  for (const headers of [
    { Host: 'localhost:3101' }, { Origin: 'http://localhost:3101' }, { Origin: '' },
    { 'Sec-Fetch-Site': 'cross-site' }, { Forwarded: 'for=127.0.0.1' }, { 'X-Forwarded-Host': host + ':3101' },
  ]) assert.equal((await request(instance.port, reportPath, { headers })).status, 403)
  for (const key of ['', 'bad key', 'x'.repeat(101), ['duplicate', 'key']]) {
    assert.equal((await request(instance.port, reportPath, { headers: { 'Idempotency-Key': key } })).status, 400)
  }
  assert.equal((await request(instance.port, reportPath, { headers: { 'Content-Type': 'text/plain' } })).status, 415)
  assert.deepEqual(instance.forwarded, [])
  const disabled = await proxy(t, { incidentWrites: false })
  assert.equal((await request(disabled.port, reportPath)).status, 403)
  const counselor = await proxy(t, { customerMode: false, port: 3100 })
  assert.equal((await request(counselor.port, reportPath, { headers: { Host: host + ':3100', Origin: 'http://' + host + ':3100' } })).status, 403)
  assert.deepEqual(disabled.forwarded, [])
  assert.deepEqual(counselor.forwarded, [])
})

test('documented incident rejection responses pass through without success rewriting or token exposure', async t => {
  for (const status of [400, 401, 403, 404, 409, 500, 503]) {
    const instance = await proxy(t, {}, status)
    const response = await request(instance.port, reportPath)
    assert.equal(response.status, status)
    assert.equal(response.headers['content-type'], 'application/problem+json')
    assert.equal(JSON.parse(response.body).code, 'TEST_ERROR')
    assert.equal(response.body.includes(customerToken), false)
  }
})
