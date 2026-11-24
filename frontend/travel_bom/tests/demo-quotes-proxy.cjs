const assert = require('node:assert/strict')
const { once } = require('node:events')
const http = require('node:http')
const { randomUUID } = require('node:crypto')
const { test } = require('node:test')

const host = '127.0.0.1'
const quotePath = '/demo/cases/C-029/quotes'
const searchPath = '/demo/cases/C-029/quote-searches'
const token = randomUUID()

function request(port, path, { method = 'GET', headers = {}, body } = {}) {
  return new Promise((resolve, reject) => {
    const req = http.request({ host, port, path, method, headers: {
      Host: host + ':3100', 'X-Demo-Read': '1', 'Sec-Fetch-Site': 'same-origin', ...headers,
    } }, response => {
      const chunks = []
      response.on('data', chunk => chunks.push(chunk))
      response.on('end', () => resolve({ status: response.statusCode, headers: response.headers, body: Buffer.concat(chunks).toString() }))
      response.on('error', reject)
    })
    req.on('error', reject)
    req.end(body)
  })
}

async function proxy(t, overrides = {}) {
  const { createDemoServer } = await import('../demo-server.mjs')
  const forwarded = []
  const server = createDemoServer({
    port: 3100, backendPort: 18082, token, quoteWrites: true,
    handle: () => assert.fail('데모 API가 Next.js로 우회했습니다'),
    async fetchUpstream(url, options) {
      let body = ''
      if (options.body) for await (const chunk of options.body) body += chunk
      forwarded.push({ url, method: options.method, headers: options.headers, body })
      return new Response(JSON.stringify({ caseId: 'C-029', searchJobId: 'QS-test', status: 'PENDING' }), {
        status: options.method === 'POST' ? 202 : 200,
        headers: { 'Content-Type': 'application/json' },
      })
    },
    ...overrides,
  })
  server.listen(0, host)
  await once(server, 'listening')
  t.after(() => new Promise(resolve => server.close(resolve)))
  return { port: server.address().port, forwarded }
}

function post(port, { path = searchPath, headers = {}, body = '{}' } = {}) {
  return request(port, path, { method: 'POST', body, headers: {
    Origin: 'http://' + host + ':3100', 'Content-Type': 'application/json',
    'Idempotency-Key': 'search-uuid-1', Authorization: 'Bearer browser-controlled',
    'X-Demo-Subject': 'untrusted', ...headers,
  } })
}

test('API-009 읽기와 API-010 접수에 한정해 서버 토큰과 동일 멱등키를 전달한다', async t => {
  const { port, forwarded } = await proxy(t)
  const read = await request(port, quotePath, { headers: { Authorization: 'Bearer browser-value' } })
  assert.equal(read.status, 200)
  const first = await post(port)
  const retry = await post(port)
  assert.equal(first.status, 202)
  assert.equal(retry.status, 202)
  assert.equal(first.headers['cache-control'], 'no-store')
  assert.equal(first.body.includes(token), false)
  assert.equal(forwarded.length, 3)
  assert.deepEqual(forwarded[0], {
    url: 'http://127.0.0.1:18082/api/v1/cases/C-029/quotes', method: 'GET',
    headers: { Authorization: 'Bearer ' + token }, body: '',
  })
  for (const call of forwarded.slice(1)) assert.deepEqual(call, {
    url: 'http://127.0.0.1:18082/api/v1/cases/C-029/quote-searches', method: 'POST',
    headers: { Authorization: 'Bearer ' + token, 'Idempotency-Key': 'search-uuid-1', 'Content-Type': 'application/json' }, body: '{}',
  })
})

test('검색 쓰기의 명시적 옵션·정확한 경로·같은 출처·멱등키 경계를 유지한다', async t => {
  const { port, forwarded } = await proxy(t)
  for (const headers of [
    { Host: 'localhost:3100' }, { Origin: 'http://localhost:3100' }, { Origin: '' },
    { 'Sec-Fetch-Site': 'cross-site' }, { 'X-Forwarded-Host': '127.0.0.1:3100' },
  ]) assert.equal((await post(port, { headers })).status, 403)
  for (const path of [searchPath + '?other=1', '/demo/cases/C-029/offers', '/demo/cases/C-029/messages', '/demo/cases/C-%30%32%39/quote-searches']) {
    assert.equal((await post(port, { path })).status, 403)
  }
  assert.equal((await post(port, { headers: { 'Content-Type': 'text/plain' } })).status, 415)
  for (const key of ['', 'bad key', 'x'.repeat(101), ['duplicate', 'key']]) {
    assert.equal((await post(port, { headers: { 'Idempotency-Key': key } })).status, 400)
  }
  assert.equal((await request(port, quotePath + '?other=1')).status, 403)
  assert.equal((await request(port, quotePath, { headers: { 'X-Demo-Read': '0' } })).status, 403)
  assert.equal((await request(port, quotePath, { method: 'DELETE' })).status, 403)
  assert.deepEqual(forwarded, [])

  const readOnly = await proxy(t, { quoteWrites: false })
  assert.equal((await post(readOnly.port)).status, 403)
  assert.equal((await request(readOnly.port, quotePath)).status, 200)
  const chatOnly = await proxy(t, { quoteWrites: false, chatWrites: true })
  assert.equal((await post(chatOnly.port)).status, 403)
  const wrongBackend = await proxy(t, { backendPort: 18080 })
  assert.equal((await post(wrongBackend.port)).status, 403)
  const wrongFrontend = await proxy(t, { port: 3000 })
  assert.equal((await post(wrongFrontend.port, { headers: { Host: host + ':3000', Origin: 'http://' + host + ':3000' } })).status, 403)
})
