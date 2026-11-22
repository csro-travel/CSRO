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

function request(port, target, { method = 'GET', headers } = {}) {
  return new Promise((resolve, reject) => {
    const req = http.request({
      host, port, path: target, method, timeout: 6000,
      headers: {
        'X-Demo-Read': '1',
        'Sec-Fetch-Site': 'same-origin',
        ...headers,
      },
    }, res => {
      res.resume()
      res.on('error', reject)
      res.on('end', () => resolve(res.statusCode))
    })
    req.on('error', reject)
    req.on('timeout', () => req.destroy(new Error('시연 프록시 응답 시간 초과')))
    req.end()
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
