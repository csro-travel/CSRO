const assert = require('node:assert/strict')
const { randomUUID } = require('node:crypto')
const { once } = require('node:events')
const http = require('node:http')
const { test } = require('node:test')

const host = '127.0.0.1'
const casePath = '/demo/cases/C-029/replacement-bookings'
const replacementId = 'BRQ-' + 'a1'.repeat(32)
const resultPath = '/demo/replacement-bookings/' + replacementId
const counselorToken = randomUUID()
const customerToken = randomUUID()
const accepted = { caseId: 'C-029', selectionId: 'SEL-current', replacementBookingId: replacementId }
const savedResult = { ...accepted, status: 'RESULT_UNKNOWN', manualRequired: false }

function request(instance, target, { method = 'GET', headers = {}, body } = {}) {
  const suppliedHeaders = {
    Host: instance.authority, 'X-Demo-Read': '1', 'Sec-Fetch-Site': 'same-origin', ...headers,
  }
  for (const key of Object.keys(suppliedHeaders)) if (suppliedHeaders[key] === undefined) delete suppliedHeaders[key]
  return new Promise((resolve, reject) => {
    const req = http.request({ host, port: instance.port, path: target, method, headers: suppliedHeaders }, res => {
      const chunks = []
      res.on('data', chunk => chunks.push(chunk))
      res.on('error', reject)
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks).toString('utf8') }))
    })
    req.on('error', reject)
    req.end(body)
  })
}

async function proxy(t, overrides = {}, upstreamResponse = {}) {
  const { createDemoServer } = await import('../demo-server.mjs')
  const forwarded = []
  const handled = []
  const config = { port: 3100, backendPort: 18082, token: counselorToken, replacementAccess: true, ...overrides }
  const server = createDemoServer({
    ...config,
    handle(req, res) { handled.push(req.url); res.writeHead(418).end() },
    async fetchUpstream(url, options) {
      let body = ''
      if (options.body) for await (const chunk of options.body) body += chunk
      forwarded.push({ url, method: options.method, headers: options.headers, body, redirect: options.redirect })
      const headers = { 'Content-Type': 'application/json' }
      if (upstreamResponse.location !== undefined) headers.Location = upstreamResponse.location
      return new Response(JSON.stringify(options.method === 'POST' ? accepted : savedResult), {
        status: upstreamResponse.status ?? (options.method === 'POST' ? 202 : 200), headers,
      })
    },
  })
  server.listen(0, host)
  await once(server, 'listening')
  t.after(() => new Promise(resolve => server.close(resolve)))
  return { port: server.address().port, authority: host + ':' + config.port, forwarded, handled }
}

function post(instance, { target = casePath, method = 'POST', headers = {}, body = '{"selectionId":"SEL-current"}' } = {}) {
  return request(instance, target, { method, body, headers: {
    Origin: 'http://' + instance.authority, 'Content-Type': 'application/json; charset=utf-8',
    'Idempotency-Key': 'replacement.retry:1', Authorization: 'Bearer browser-controlled',
    'X-Demo-Subject': 'browser-counselor', ...headers,
  } })
}

test('API-015 forwards counselor requests and retries with only server credentials', async t => {
  const instance = await proxy(t)
  const body = '{ "selectionId": "SEL-current" }'
  for (let retry = 0; retry < 2; retry++) {
    const result = await post(instance, { body })
    assert.equal(result.status, 202)
    assert.deepEqual(JSON.parse(result.body), accepted)
    assert.equal(result.headers['cache-control'], 'no-store')
    assert.equal(result.body.includes(counselorToken), false)
  }
  assert.deepEqual(instance.forwarded, [0, 1].map(() => ({
    url: 'http://127.0.0.1:18082/api/v1/cases/C-029/replacement-bookings', method: 'POST', body,
    headers: {
      Authorization: 'Bearer ' + counselorToken, 'Idempotency-Key': 'replacement.retry:1',
      'Content-Type': 'application/json; charset=utf-8',
    }, redirect: 'error',
  })))
  const boundaryKey = 'A'.repeat(96) + '._:-'
  assert.equal((await post(instance, {
    target: '/demo/cases/C-' + '9'.repeat(38) + '/replacement-bookings',
    headers: { 'Idempotency-Key': boundaryKey },
  })).status, 202)
  assert.equal(instance.forwarded[2].headers['Idempotency-Key'], boundaryKey)
})

test('only API-015 202 responses translate an exact internal replacement Location', async t => {
  const internalLocation = '/api/v1/replacement-bookings/' + replacementId
  const upstreamResponse = { location: internalLocation, status: 202 }
  const instance = await proxy(t, { offerWrites: true, quoteWrites: true, chatWrites: true }, upstreamResponse)
  const acceptedResponse = await post(instance)
  assert.equal(acceptedResponse.status, 202)
  assert.equal(acceptedResponse.headers.location, resultPath)

  for (const location of [
    undefined, '', 'https://untrusted.example' + internalLocation, '//untrusted.example' + internalLocation,
    'http://127.0.0.1:18082' + internalLocation, internalLocation + '?next=untrusted',
    internalLocation + '#fragment', internalLocation + '/', internalLocation + '0',
    '/api/v1/cases/C-029', '/api/v1/replacement-bookings/BRQ-' + 'A'.repeat(64),
    internalLocation.replace('BRQ-a', 'BRQ-%61'), '/api/v1/replacement-bookings/../replacement-bookings/' + replacementId,
  ]) {
    upstreamResponse.location = location
    assert.equal((await post(instance)).headers.location, undefined)
  }
  upstreamResponse.location = internalLocation
  for (const status of [200, 201, 302, 400, 409, 500]) {
    upstreamResponse.status = status
    const response = await post(instance)
    assert.equal(response.status, status)
    assert.equal(response.headers.location, undefined)
  }
  upstreamResponse.status = 202
  assert.equal((await request(instance, resultPath)).headers.location, undefined)
  for (const target of ['/demo/cases/C-029/offers', '/demo/cases/C-029/quote-searches', '/demo/cases/C-029/messages']) {
    assert.equal((await post(instance, { target })).headers.location, undefined)
  }
  const customer = await proxy(t, { customerMode: true, port: 3101, token: customerToken }, upstreamResponse)
  assert.equal((await post(customer, {
    target: '/demo/cases/C-029/selection', method: 'PUT',
    body: '{"offerId":"OFFER-current","quoteId":"QUOTE-A"}',
  })).headers.location, undefined)
})

test('API-015 requires exact paths, POST, origin, fetch metadata, JSON and a valid key', async t => {
  const instance = await proxy(t)
  for (const target of [
    casePath + '?', casePath + '?other=1', casePath + '/', casePath + '#fragment',
    '/demo/cases/C-29/replacement-bookings', '/demo/cases/C-' + '1'.repeat(39) + '/replacement-bookings',
    '/demo/cases/C-%30%32%39/replacement-bookings', '/demo/cases/C-029/%72eplacement-bookings',
    '/demo/cases/C-029/../C-029/replacement-bookings', resultPath,
  ]) assert.equal((await post(instance, { target })).status, 403)
  for (const method of ['GET', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS']) {
    assert.equal((await post(instance, { method })).status, 403)
  }
  for (const headers of [
    { Host: 'localhost:3100' }, { Origin: 'http://localhost:3100' }, { Origin: undefined },
    { 'Sec-Fetch-Site': undefined }, { 'Sec-Fetch-Site': 'cross-site' },
    { Forwarded: 'for=127.0.0.1' }, { 'X-Forwarded-Host': instance.authority },
  ]) assert.equal((await post(instance, { headers })).status, 403)
  for (const contentType of ['text/plain', undefined]) {
    assert.equal((await post(instance, { headers: { 'Content-Type': contentType } })).status, 415)
  }
  for (const key of [undefined, '', 'bad key', 'x'.repeat(101), ['duplicate', 'key']]) {
    assert.equal((await post(instance, { headers: { 'Idempotency-Key': key } })).status, 400)
  }
  assert.deepEqual(instance.forwarded, [])
  assert.deepEqual(instance.handled, [])
})

test('API-016 forwards pure reads for both isolated roles using their server tokens', async t => {
  for (const overrides of [{}, { customerMode: true, port: 3101, token: customerToken }]) {
    const instance = await proxy(t, overrides)
    for (let refresh = 0; refresh < 2; refresh++) {
      const response = await request(instance, resultPath, { headers: {
        Authorization: 'Bearer browser-counselor', 'X-Demo-Subject': 'untrusted',
        'Idempotency-Key': 'untrusted', Cookie: 'untrusted=value',
      } })
      assert.equal(response.status, 200)
      assert.deepEqual(JSON.parse(response.body), savedResult)
      assert.equal(response.headers['cache-control'], 'no-store')
    }
    assert.deepEqual(instance.forwarded, [0, 1].map(() => ({
      url: 'http://127.0.0.1:18082/api/v1/replacement-bookings/' + replacementId,
      method: 'GET', headers: { Authorization: 'Bearer ' + (overrides.token || counselorToken) },
      body: '', redirect: 'error',
    })))
  }
})

test('API-016 rejects queries, escaping, invalid IDs, methods and read metadata before Next', async t => {
  for (const overrides of [{}, { customerMode: true, port: 3101, token: customerToken }]) {
    const instance = await proxy(t, overrides)
    for (const target of [
      '/demo/replacement-bookings', '/demo/replacement-bookings/', '/demo/replacement-bookings-suffix',
      '/demo/replacement-bookings/BRQ-' + 'a'.repeat(63), '/demo/replacement-bookings/BRQ-' + 'a'.repeat(65),
      '/demo/replacement-bookings/BRQ-' + 'A'.repeat(64), '/demo/replacement-bookings/BRQ-' + 'g'.repeat(64),
      resultPath + '?', resultPath + '?other=1', resultPath + '/', resultPath + '#fragment',
      resultPath.replace('BRQ-a', 'BRQ-%61'), '/demo/replacement-bookings/../replacement-bookings/' + replacementId,
    ]) assert.equal((await request(instance, target)).status, 403)
    for (const method of ['POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS']) {
      assert.equal((await request(instance, resultPath, { method })).status, 403)
    }
    for (const headers of [
      { 'X-Demo-Read': undefined }, { 'X-Demo-Read': '0' }, { 'Sec-Fetch-Site': 'cross-site' },
      { 'Sec-Fetch-Site': undefined }, { Host: 'localhost:' + (overrides.port || 3100) },
      { Forwarded: 'for=127.0.0.1' }, { 'X-Forwarded-For': host },
    ]) assert.equal((await request(instance, resultPath, { headers })).status, 403)
    assert.deepEqual(instance.forwarded, [])
    assert.deepEqual(instance.handled, [])
    assert.equal((await request(instance, '/customer/cases/C-029')).status, 418)
    assert.deepEqual(instance.handled, ['/customer/cases/C-029'])
  }
})

test('replacement access is opt-in and only works on the isolated counselor/customer ports', async t => {
  for (const overrides of [
    { replacementAccess: undefined }, { replacementAccess: false },
    { backendPort: 18080 }, { port: 3000 }, { port: 3101 },
    { customerMode: true, port: 3101, token: customerToken, replacementAccess: false },
  ]) {
    const instance = await proxy(t, overrides)
    assert.equal((await post(instance)).status, 403)
    assert.equal((await request(instance, resultPath)).status, 403)
    assert.deepEqual(instance.forwarded, [])
    assert.deepEqual(instance.handled, [])
  }
})

test('customer replacement reads do not grant counselor routes or replacement POST access', async t => {
  const instance = await proxy(t, {
    customerMode: true, port: 3101, token: customerToken,
    chatWrites: true, quoteWrites: true, offerWrites: true, replacementAccess: true,
  })
  for (const target of [
    casePath, '/demo/cases/C-029/messages', '/demo/cases/C-029/offers', '/demo/cases/C-029/quote-searches',
  ]) assert.equal((await post(instance, { target })).status, 403)
  for (const target of [
    '/demo/cases', '/demo/cases?page=0&status=OPEN', '/demo/cases/C-029', '/demo/cases/C-029/quotes',
  ]) assert.equal((await request(instance, target)).status, 403)
  assert.deepEqual(instance.forwarded, [])
  assert.equal((await request(instance, '/demo/cases/C-029/messages?limit=12&beforeMessageId=MSG-older')).status, 200)
  assert.equal((await post(instance, {
    target: '/demo/cases/C-029/selection', method: 'PUT',
    body: '{"offerId":"OFFER-current","quoteId":"QUOTE-A"}',
  })).status, 200)
  assert.deepEqual(instance.forwarded.map(call => call.method), ['GET', 'PUT'])
  for (const call of instance.forwarded) assert.equal(call.headers.Authorization, 'Bearer ' + customerToken)
})
