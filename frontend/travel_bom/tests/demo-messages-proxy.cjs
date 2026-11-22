const assert = require('node:assert/strict')
const { spawn } = require('node:child_process')
const { randomUUID } = require('node:crypto')
const { once } = require('node:events')
const { existsSync } = require('node:fs')
const http = require('node:http')
const path = require('node:path')
const { test } = require('node:test')
const { setTimeout: delay } = require('node:timers/promises')

const host = '127.0.0.1'

async function listen(server) {
  server.listen(0, host)
  await once(server, 'listening')
  return server.address().port
}

function close(server) {
  return new Promise(resolve => server.close(resolve))
}

async function request(port, target, options) {
  return (await requestResponse(port, target, options)).status
}

function requestResponse(port, target, { method = 'GET', headers, body } = {}) {
  return new Promise((resolve, reject) => {
    const req = http.request({
      host, port, path: target, method, timeout: 6000,
      headers: {
        'X-Demo-Read': '1',
        'Sec-Fetch-Site': 'same-origin',
        ...headers,
      },
    }, res => {
      const chunks = []
      res.on('data', chunk => chunks.push(chunk))
      res.on('error', reject)
      res.on('end', () => resolve({
        status: res.statusCode,
        headers: res.headers,
        body: Buffer.concat(chunks).toString('utf8'),
      }))
    })
    req.on('error', reject)
    req.on('timeout', () => req.destroy(new Error('시연 프록시 응답 시간 초과')))
    req.end(body)
  })
}

async function waitForProxy(proxy, port) {
  const deadline = Date.now() + 8000
  while (Date.now() < deadline && proxy.exitCode === null && proxy.signalCode === null) {
    try {
      await request(port, '/demo/cases/invalid')
      return
    } catch (error) {
      if (error.code !== 'ECONNREFUSED') throw error
      await delay(50)
    }
  }
  assert.fail('시연 프록시 시작 실패')
}

test('API-011 시연 프록시가 메시지 조회와 커서 쿼리를 전달한다', { timeout: 30_000 }, async t => {
  const frontend = path.resolve(__dirname, '..')
  assert.ok(existsSync(path.join(frontend, '.next', 'BUILD_ID')), 'Next.js 빌드를 먼저 실행하세요')

  const token = randomUUID()
  const forwarded = []
  const upstream = http.createServer((req, res) => {
    forwarded.push({
      target: req.url,
      method: req.method,
      authorized: req.headers.authorization === `Bearer ${token}`,
    })
    const status = req.url.endsWith('?limit=') ? 400 : 200
    res.writeHead(status).end()
  })
  let proxy
  t.after(async () => {
    if (proxy?.pid && proxy.exitCode === null && proxy.signalCode === null) {
      const exited = once(proxy, 'exit')
      proxy.kill()
      await exited
    }
    await close(upstream)
  })

  const upstreamPort = await listen(upstream)
  const reservation = http.createServer()
  const port = await listen(reservation)
  await close(reservation)
  proxy = spawn(process.execPath, ['demo-server.mjs'], {
    cwd: frontend, stdio: 'ignore', windowsHide: true,
    env: {
      ...process.env,
      PORT: String(port),
      CSRO_DEMO_SPRING_PORT: String(upstreamPort),
      CSRO_LOCAL_DEMO: 'true',
      CSRO_DEMO_TOKEN: token,
    },
  })
  await once(proxy, 'spawn')
  await waitForProxy(proxy, port)

  const messagePath = '/demo/cases/C-001/messages'
  const cursor = encodeURIComponent('MSG +/?&한글')
  const reads = [
    [messagePath, '/api/v1/cases/C-001/messages', 200],
    [`${messagePath}?limit=12`, '/api/v1/cases/C-001/messages?limit=12', 200],
    [`${messagePath}?beforeMessageId=${cursor}`, `/api/v1/cases/C-001/messages?beforeMessageId=${cursor}`, 200],
    [`${messagePath}?limit=12&beforeMessageId=${cursor}`, `/api/v1/cases/C-001/messages?limit=12&beforeMessageId=${cursor}`, 200],
    [`${messagePath}?limit=`, '/api/v1/cases/C-001/messages?limit=', 400],
    ['/demo/cases?page=0&status=OPEN', '/api/v1/cases?page=0&status=OPEN', 200],
    ['/demo/cases/C-001', '/api/v1/cases/C-001', 200],
  ]
  for (const [target, , status] of reads) {
    assert.equal(await request(port, target, { headers: { Authorization: 'Bearer client-value' } }), status)
  }
  assert.deepEqual(forwarded, reads.map(([, target]) => ({ target, method: 'GET', authorized: true })))

  assert.equal(await request(port, `${messagePath}?other=1`), 403)
  assert.equal(await request(port, `${messagePath}?limit=12&limit=20`), 403)
  assert.equal(await request(port, messagePath, { method: 'POST' }), 403)
  assert.equal(await request(port, messagePath, { headers: { Host: `localhost:${port}` } }), 403)
  assert.equal(await request(port, messagePath, { headers: { 'X-Demo-Read': '0' } }), 403)
  assert.equal(await request(port, messagePath, { headers: { 'Sec-Fetch-Site': 'cross-site' } }), 403)
  assert.equal(forwarded.length, reads.length)
})

test('API-012 시연 프록시가 같은 출처의 전용 채팅 요청을 전달한다', { timeout: 30_000 }, async t => {
  const { createDemoServer } = await import('../demo-server.mjs')
  const token = randomUUID()
  const messagePath = '/demo/cases/C-001/messages'
  const origin = `http://${host}:3100`
  const body = '{ "body": "  첫 줄\\n둘째 줄  " }'
  const invalidBody = '{"body":""}'
  const sent = { messageId: 'MSG-test', body: JSON.parse(body).body }
  const problem = { code: 'INVALID_MESSAGE_BODY' }
  const forwarded = []
  const upstream = http.createServer(async (req, res) => {
    const chunks = []
    for await (const chunk of req) chunks.push(chunk)
    const body = Buffer.concat(chunks).toString('utf8')
    forwarded.push({
      target: req.url,
      method: req.method,
      authorized: req.headers.authorization === `Bearer ${token}`,
      contentType: req.headers['content-type'],
      body,
    })
    if (body === invalidBody) {
      res.writeHead(400, { 'Content-Type': 'application/problem+json' }).end(JSON.stringify(problem))
    } else {
      res.writeHead(201, { 'Content-Type': 'application/json' }).end(JSON.stringify(sent))
    }
  })
  const upstreamPort = await listen(upstream)
  t.after(() => close(upstream))

  async function startProxy(overrides) {
    const server = createDemoServer({
      port: 3100,
      backendPort: 18082,
      chatWrites: true,
      token,
      handle: () => assert.fail('시연 API 경로를 Next.js가 처리했습니다'),
      fetchUpstream(url, options) {
        const target = new URL(url)
        assert.equal(target.origin, `http://${host}:18082`)
        target.port = String(upstreamPort)
        return fetch(target, options)
      },
      ...overrides,
    })
    const listener = await listen(server)
    t.after(() => close(server))
    return listener
  }

  function post(listener, { target = messagePath, headers, body = invalidBody } = {}) {
    return requestResponse(listener, target, {
      method: 'POST',
      body,
      headers: {
        Host: `${host}:3100`,
        Origin: origin,
        Authorization: 'Bearer client-value',
        'Content-Type': 'application/json; charset=utf-8',
        'X-Demo-Read': '0',
        ...headers,
      },
    })
  }

  const listener = await startProxy()
  for (const headers of [
    { Origin: 'http://localhost:3100' },
    { Origin: '' },
    { 'Sec-Fetch-Site': 'cross-site' },
    { 'X-Forwarded-Host': `${host}:3100` },
  ]) {
    assert.equal((await post(listener, { headers })).status, 403)
  }
  assert.equal((await post(listener, { target: `${messagePath}?limit=1` })).status, 403)
  assert.equal((await post(listener, { target: `/demo/cases/C-${'1'.repeat(39)}/messages` })).status, 403)
  assert.equal((await post(listener, { headers: { 'Content-Type': 'text/plain' } })).status, 415)
  assert.equal((await post(await startProxy({ chatWrites: false }))).status, 403)
  assert.equal((await post(await startProxy({ backendPort: 18080 }))).status, 403)
  assert.equal((await post(await startProxy({ port: 3000 }), {
    headers: { Host: `${host}:3000`, Origin: `http://${host}:3000` },
  })).status, 403)
  assert.deepEqual(forwarded, [])

  const success = await post(listener, { body })
  assert.equal(success.status, 201)
  assert.deepEqual(JSON.parse(success.body), sent)
  const failure = await post(listener)
  assert.equal(failure.status, 400)
  assert.equal(failure.headers['content-type'], 'application/problem+json')
  assert.deepEqual(JSON.parse(failure.body), problem)
  assert.deepEqual(forwarded, [body, invalidBody].map(body => ({
    target: '/api/v1/cases/C-001/messages',
    method: 'POST',
    authorized: true,
    contentType: 'application/json; charset=utf-8',
    body,
  })))
})
