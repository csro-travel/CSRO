const assert = require('node:assert/strict')
const { spawnSync } = require('node:child_process')
const { randomUUID } = require('node:crypto')
const { once } = require('node:events')
const http = require('node:http')
const path = require('node:path')
const { test } = require('node:test')

const host = '127.0.0.1'
const offerPath = '/demo/cases/C-029/offers'
const selectionPath = '/demo/cases/C-029/selection'
const messagePath = '/demo/cases/C-029/messages'
const counselorToken = randomUUID()
const customerToken = randomUUID()
const messagePage = {
  caseId: 'C-029', items: [{ messageId: 'MSG-older', messageType: 'OFFER' }],
  pagination: { hasMore: false, nextBeforeMessageId: null },
  activeOfferId: 'OFFER-current', currentSelection: null,
}

function request(port, target, { method = 'GET', headers = {}, body } = {}) {
  return new Promise((resolve, reject) => {
    const req = http.request({ host, port, path: target, method, headers: {
      Host: host + ':3100', 'X-Demo-Read': '1', 'Sec-Fetch-Site': 'same-origin', ...headers,
    } }, res => {
      const chunks = []
      res.on('data', chunk => chunks.push(chunk))
      res.on('error', reject)
      res.on('end', () => resolve({
        status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks).toString('utf8'),
      }))
    })
    req.on('error', reject)
    req.end(body)
  })
}

async function proxy(t, overrides = {}) {
  const { createDemoServer } = await import('../demo-server.mjs')
  const forwarded = []
  const config = {
    port: 3100, backendPort: 18082, token: counselorToken, offerWrites: true,
    ...overrides,
  }
  const server = createDemoServer({
    ...config,
    handle: () => assert.fail('데모 API가 Next.js로 우회했습니다'),
    async fetchUpstream(url, options) {
      let body = ''
      if (options.body) for await (const chunk of options.body) body += chunk
      forwarded.push({ url, method: options.method, headers: options.headers, body })
      const status = options.method === 'POST' ? 201 : 200
      const result = options.method === 'GET' ? messagePage : { caseId: 'C-029', offerId: 'OFFER-current' }
      return new Response(JSON.stringify(result), { status, headers: { 'Content-Type': 'application/json' } })
    },
  })
  server.listen(0, host)
  await once(server, 'listening')
  t.after(() => new Promise(resolve => server.close(resolve)))
  return {
    port: server.address().port, forwarded,
    headers: { Host: host + ':' + config.port, Origin: 'http://' + host + ':' + config.port },
  }
}

function write(instance, { target = offerPath, method = 'POST', headers = {}, body = '{}' } = {}) {
  return request(instance.port, target, { method, body, headers: {
    ...instance.headers, 'Content-Type': 'application/json; charset=utf-8',
    'Idempotency-Key': 'offer-or-selection.retry:1',
    Authorization: 'Bearer browser-controlled', 'X-Demo-Subject': 'browser-counselor',
    ...headers,
  } })
}

test('API-013 offer writes forward the server credential, exact body and retry key', async t => {
  const instance = await proxy(t)
  const body = '{"quoteIds":["QUOTE-A","QUOTE-B","QUOTE-C"]}'
  for (let retry = 0; retry < 2; retry++) {
    const result = await write(instance, { body })
    assert.equal(result.status, 201)
    assert.equal(result.headers['cache-control'], 'no-store')
    assert.equal(result.body.includes(counselorToken), false)
  }
  assert.deepEqual(instance.forwarded, [0, 1].map(() => ({
    url: 'http://127.0.0.1:18082/api/v1/cases/C-029/offers', method: 'POST', body,
    headers: {
      Authorization: 'Bearer ' + counselorToken,
      'Idempotency-Key': 'offer-or-selection.retry:1',
      'Content-Type': 'application/json; charset=utf-8',
    },
  })))
})

test('offer writes require the option, isolated counselor ports and exact method/path', async t => {
  const instance = await proxy(t)
  for (const target of [
    offerPath + '?other=1', offerPath + '/', offerPath + '#fragment',
    '/demo/cases/C-29/offers', '/demo/cases/C-' + '1'.repeat(39) + '/offers',
    '/demo/cases/C-%30%32%39/offers', '/demo/cases/C-029/selection',
    '/demo/cases/C-029/quote-searches', messagePath,
  ]) assert.equal((await write(instance, { target })).status, 403)
  for (const method of ['GET', 'PUT', 'PATCH', 'DELETE', 'OPTIONS', 'HEAD']) {
    assert.equal((await write(instance, { method })).status, 403)
  }
  assert.deepEqual(instance.forwarded, [])
  for (const overrides of [
    { offerWrites: false }, { backendPort: 18080 }, { port: 3000 },
  ]) assert.equal((await write(await proxy(t, overrides))).status, 403)

  const { createDemoServer } = await import('../demo-server.mjs')
  const defaultServer = createDemoServer({
    port: 3100, backendPort: 18082, token: counselorToken,
    handle: () => assert.fail('데모 API가 Next.js로 우회했습니다'),
    fetchUpstream: () => assert.fail('기본 프록시가 offer 쓰기를 전달했습니다'),
  })
  defaultServer.listen(0, host)
  await once(defaultServer, 'listening')
  t.after(() => new Promise(resolve => defaultServer.close(resolve)))
  assert.equal((await write({ ...instance, port: defaultServer.address().port })).status, 403)
})

test('offer and customer selection writes enforce origin, fetch metadata, JSON and keys', async t => {
  const counselor = await proxy(t)
  const customer = await proxy(t, { customerMode: true, port: 3101, token: customerToken })
  for (const [instance, options] of [
    [counselor, {}], [customer, { target: selectionPath, method: 'PUT' }],
  ]) {
    for (const headers of [
      { Host: 'localhost:' + (instance === customer ? 3101 : 3100) },
      { Origin: 'http://localhost:' + (instance === customer ? 3101 : 3100) },
      { Origin: '' }, { 'Sec-Fetch-Site': 'cross-site' },
      { Forwarded: 'for=127.0.0.1' }, { 'X-Forwarded-Host': instance.headers.Host },
    ]) assert.equal((await write(instance, { ...options, headers })).status, 403)
    assert.equal((await write(instance, { ...options, headers: { 'Content-Type': 'text/plain' } })).status, 415)
    for (const key of ['', 'bad key', 'x'.repeat(101), ['duplicate', 'key']]) {
      assert.equal((await write(instance, { ...options, headers: { 'Idempotency-Key': key } })).status, 400)
    }
    assert.deepEqual(instance.forwarded, [])
  }
})

test('customer mode forwards only the server customer credential and supports older message pages', async t => {
  const instance = await proxy(t, { customerMode: true, port: 3101, token: customerToken })
  const cursor = encodeURIComponent('MSG +/?&한글')
  for (const target of [messagePath, messagePath + '?limit=12&beforeMessageId=' + cursor]) {
    const response = await request(instance.port, target, { headers: {
      ...instance.headers, Authorization: 'Bearer browser-counselor', 'X-Demo-Subject': 'untrusted',
    } })
    assert.equal(response.status, 200)
    assert.deepEqual(JSON.parse(response.body), messagePage)
  }
  const body = '{"offerId":"OFFER-current","quoteId":"QUOTE-A"}'
  const selection = await write(instance, { target: selectionPath, method: 'PUT', body })
  assert.equal(selection.status, 200)
  assert.equal(selection.body.includes(customerToken), false)
  assert.equal(selection.headers['cache-control'], 'no-store')
  assert.deepEqual(instance.forwarded, [
    { url: 'http://127.0.0.1:18082/api/v1/cases/C-029/messages', method: 'GET', body: '', headers: { Authorization: 'Bearer ' + customerToken } },
    { url: 'http://127.0.0.1:18082/api/v1/cases/C-029/messages?limit=12&beforeMessageId=' + cursor, method: 'GET', body: '', headers: { Authorization: 'Bearer ' + customerToken } },
    { url: 'http://127.0.0.1:18082/api/v1/cases/C-029/selection', method: 'PUT', body, headers: {
      Authorization: 'Bearer ' + customerToken,
      'Idempotency-Key': 'offer-or-selection.retry:1',
      'Content-Type': 'application/json; charset=utf-8',
    } },
  ])
})

test('customer mode rejects counselor routes even when all counselor write flags are enabled', async t => {
  const instance = await proxy(t, {
    customerMode: true, port: 3101, token: customerToken,
    chatWrites: true, quoteWrites: true, offerWrites: true,
  })
  for (const target of [
    '/demo/cases', '/demo/cases?page=0&status=OPEN', '/demo/cases/C-029',
    '/demo/cases/C-029/quotes', '/demo/cases/C-029/quote-searches', offerPath, selectionPath,
    '/demo/cases/C-%30%32%39/messages', '/demo/cases/C-' + '1'.repeat(39) + '/messages',
    messagePath + '/', messagePath + '?other=1', messagePath + '?limit=1&limit=2',
    messagePath + '?limit=1&%6cimit=2', messagePath + '?beforeMessageId=a&beforeMessageId=b',
    messagePath + '#fragment',
  ]) assert.equal((await request(instance.port, target, { headers: instance.headers })).status, 403)
  for (const headers of [{ 'X-Demo-Read': '0' }, { 'Sec-Fetch-Site': 'cross-site' }]) {
    assert.equal((await request(instance.port, messagePath, { headers: { ...instance.headers, ...headers } })).status, 403)
  }
  for (const target of [messagePath, offerPath, '/demo/cases/C-029/quote-searches', selectionPath]) {
    assert.equal((await write(instance, { target })).status, 403)
  }
  for (const method of ['POST', 'PATCH', 'DELETE', 'OPTIONS', 'HEAD']) {
    assert.equal((await write(instance, { target: selectionPath, method })).status, 403)
  }
  for (const target of [selectionPath + '?other=1', selectionPath + '/', '/demo/cases/C-%30%32%39/selection']) {
    assert.equal((await write(instance, { target, method: 'PUT' })).status, 403)
  }
  assert.deepEqual(instance.forwarded, [])
})

test('unsafe customer configuration is rejected before serving requests', async () => {
  const { createDemoServer } = await import('../demo-server.mjs')
  for (const overrides of [{ port: 3100 }, { backendPort: 18080 }, { token: undefined }, { token: '' }]) {
    assert.throws(() => createDemoServer({
      port: 3101, backendPort: 18082, customerMode: true, token: customerToken, ...overrides,
    }), /Explicit isolated customer demo configuration required/)
  }

  for (const overrides of [
    { PORT: '3100' }, { CSRO_DEMO_SPRING_PORT: '18080' }, { CSRO_CUSTOMER_DEMO_TOKEN: '' },
  ]) {
    const result = spawnSync(process.execPath, ['demo-server.mjs'], {
      cwd: path.resolve(__dirname, '..'), encoding: 'utf8', timeout: 10_000,
      env: {
        ...process.env, CSRO_LOCAL_DEMO: 'true', PORT: '3101', CSRO_DEMO_SPRING_PORT: '18082',
        CSRO_CUSTOMER_DEMO_PROXY: 'true', CSRO_CUSTOMER_DEMO_TOKEN: customerToken,
        CSRO_DEMO_TOKEN: counselorToken, ...overrides,
      },
    })
    assert.ifError(result.error)
    assert.equal(result.status, 1)
    assert.match(result.stderr, /Explicit (?:isolated customer )?local demo configuration required|Explicit isolated customer demo configuration required/)
    assert.equal(result.stderr.includes(counselorToken), false)
    assert.equal(result.stderr.includes(customerToken), false)
  }
})
