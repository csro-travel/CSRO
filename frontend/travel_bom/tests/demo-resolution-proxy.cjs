const assert = require('node:assert/strict')
const { randomUUID } = require('node:crypto')
const { once } = require('node:events')
const http = require('node:http')
const { test } = require('node:test')

const host = '127.0.0.1'
const replacementId = 'BRQ-' + 'a1'.repeat(32)
const replacementPath = '/demo/replacement-bookings/' + replacementId
const counselorToken = randomUUID()
const customerToken = randomUUID()
const checkin = {
  target: replacementPath + '/checkin-confirmations', method: 'POST', body: '{ "checkedIn": true }',
}
const task = {
  target: '/demo/cases/C-029/tasks/TASK-7', method: 'PATCH',
  body: '{"status":"DONE","completionNote":"후속 조치 확인","expectedVersion":3}',
}
const closure = {
  target: '/demo/cases/C-029/closures', method: 'POST', body: '{"reason":"처리 완료","expectedVersion":4}',
}

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

async function proxy(t, overrides = {}, upstreamReply = {}) {
  const { createDemoServer } = await import('../demo-server.mjs')
  const forwarded = []
  const handled = []
  const config = {
    port: overrides.customerMode ? 3101 : 3100, backendPort: 18082,
    token: overrides.customerMode ? customerToken : counselorToken,
    resolutionAccess: true, ...overrides,
  }
  const server = createDemoServer({
    ...config,
    handle(req, res) { handled.push(req.url); res.writeHead(418).end() },
    async fetchUpstream(url, options) {
      let body = ''
      if (options.body) for await (const chunk of options.body) body += chunk
      forwarded.push({ url, method: options.method, headers: options.headers, body })
      return new Response(JSON.stringify(upstreamReply.body || { accepted: true }), {
        status: upstreamReply.status ?? (options.method === 'POST' ? 201 : 200),
        headers: {
          'Content-Type': upstreamReply.contentType || 'application/json',
          ...(upstreamReply.location ? { Location: upstreamReply.location } : {}),
        },
      })
    },
  })
  server.listen(0, host)
  await once(server, 'listening')
  t.after(() => new Promise(resolve => server.close(resolve)))
  return { port: server.address().port, authority: host + ':' + config.port, forwarded, handled }
}

function write(instance, command, { target = command.target, method = command.method, body = command.body, headers = {} } = {}) {
  return request(instance, target, { method, body, headers: {
    Origin: 'http://' + instance.authority, 'Content-Type': 'application/json; charset=utf-8',
    'Idempotency-Key': 'resolution.retry:1', Authorization: 'Bearer browser-controlled',
    'X-Demo-Subject': 'browser-controlled', ...headers,
  } })
}

test('resolution writes and retries forward exact bodies and keys with the correct server role credential', async t => {
  for (const [overrides, commands, token] of [
    [{}, [task, closure], counselorToken], [{ customerMode: true }, [checkin], customerToken],
  ]) {
    const instance = await proxy(t, overrides)
    const expected = []
    for (const command of commands) for (let retry = 0; retry < 2; retry++) {
      const response = await write(instance, command)
      assert.equal(response.status, command.method === 'POST' ? 201 : 200)
      assert.equal(response.headers['cache-control'], 'no-store')
      assert.equal(response.body.includes(token), false)
      expected.push({
        url: 'http://127.0.0.1:18082' + command.target.replace('/demo/', '/api/v1/'),
        method: command.method, body: command.body,
        headers: {
          Authorization: 'Bearer ' + token, 'Idempotency-Key': 'resolution.retry:1',
          'Content-Type': 'application/json; charset=utf-8',
        },
      })
    }
    assert.deepEqual(instance.forwarded, expected)
  }
  const instance = await proxy(t)
  const key = 'A'.repeat(96) + '._:-'
  assert.equal((await write(instance, task, {
    target: '/demo/cases/C-' + '9'.repeat(38) + '/tasks/TASK-' + '1'.repeat(19),
    headers: { 'Idempotency-Key': key },
  })).status, 200)
  assert.equal(instance.forwarded[0].headers['Idempotency-Key'], key)
})

test('resolution commands require the new option and isolated role ports', async t => {
  for (const overrides of [
    { resolutionAccess: undefined }, { resolutionAccess: false }, { port: 3000 },
    { port: 3101 }, { backendPort: 18080 },
  ]) {
    const instance = await proxy(t, overrides)
    for (const command of [task, closure, checkin]) assert.equal((await write(instance, command)).status, 403)
    assert.deepEqual(instance.forwarded, [])
  }
  for (const resolutionAccess of [undefined, false]) {
    const instance = await proxy(t, { customerMode: true, resolutionAccess })
    assert.equal((await write(instance, checkin)).status, 403)
    assert.deepEqual(instance.forwarded, [])
  }
})

test('resolution access keeps check-in customer-only and tasks/closures counselor-only', async t => {
  const flags = { chatWrites: true, quoteWrites: true, offerWrites: true, replacementAccess: true }
  const counselor = await proxy(t, flags)
  assert.equal((await write(counselor, checkin)).status, 403)
  const customer = await proxy(t, { ...flags, customerMode: true })
  for (const command of [task, closure]) assert.equal((await write(customer, command)).status, 403)
  for (const target of [
    '/demo/cases', '/demo/cases/C-029', '/demo/cases/C-029/quotes',
  ]) assert.equal((await request(customer, target)).status, 403)
  assert.deepEqual(counselor.forwarded, [])
  assert.deepEqual(customer.forwarded, [])
  for (const [instance, targets] of [
    [counselor, ['/demo/cases/C-029', '/demo/cases/C-029/messages', replacementPath]],
    [customer, ['/demo/cases/C-029/messages?limit=12&beforeMessageId=MSG-old', replacementPath]],
  ]) for (const target of targets) assert.equal((await request(instance, target)).status, 200)
})

test('resolution writes enforce origin, fetch metadata, JSON and idempotency keys', async t => {
  const counselor = await proxy(t)
  const customer = await proxy(t, { customerMode: true })
  for (const [instance, commands] of [[counselor, [task, closure]], [customer, [checkin]]]) {
    for (const command of commands) {
      for (const headers of [
        { Host: instance.authority.replace(host, 'localhost') },
        { Origin: 'http://' + instance.authority.replace(host, 'localhost') }, { Origin: undefined },
        { 'Sec-Fetch-Site': 'cross-site' }, { 'Sec-Fetch-Site': undefined },
        { Forwarded: 'for=127.0.0.1' }, { 'X-Forwarded-Host': instance.authority },
      ]) assert.equal((await write(instance, command, { headers })).status, 403)
      for (const contentType of [undefined, 'text/plain']) {
        assert.equal((await write(instance, command, { headers: { 'Content-Type': contentType } })).status, 415)
      }
      for (const key of [undefined, '', 'bad key', 'x'.repeat(101), ['duplicate', 'key']]) {
        assert.equal((await write(instance, command, { headers: { 'Idempotency-Key': key } })).status, 400)
      }
    }
    assert.deepEqual(instance.forwarded, [])
  }
})

test('resolution allowlists reject wrong methods, queries, escaping and extra path segments', async t => {
  const counselor = await proxy(t)
  const customer = await proxy(t, { customerMode: true })
  for (const [instance, commands] of [[counselor, [task, closure]], [customer, [checkin]]]) {
    for (const command of commands) {
      for (const suffix of ['?', '?other=1', '/', '/extra', '#fragment']) {
        assert.equal((await write(instance, command, { target: command.target + suffix })).status, 403)
      }
      for (const method of ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS'].filter(value => value !== command.method)) {
        assert.equal((await write(instance, command, { method })).status, 403)
      }
    }
  }
  for (const target of [
    '/demo/cases/C-29/tasks/TASK-7', '/demo/cases/C-' + '1'.repeat(39) + '/tasks/TASK-7',
    '/demo/cases/C-029/tasks/TASK-', '/demo/cases/C-029/tasks/TASK-' + '1'.repeat(20),
    '/demo/cases/C-029/tasks/TASK-x', '/demo/cases/C-029/tasks/TASK-%37',
    '/demo/cases/C-%30%32%39/tasks/TASK-7', '/demo/cases/C-029/../C-029/tasks/TASK-7',
  ]) assert.equal((await write(counselor, task, { target })).status, 403)
  for (const target of ['/demo/cases/C-%30%32%39/closures', '/demo/cases/C-029/%63losures']) {
    assert.equal((await write(counselor, closure, { target })).status, 403)
  }
  for (const target of [
    checkin.target.replace('BRQ-a', 'BRQ-%61'), checkin.target.replace('BRQ-a', 'BRQ-A'),
    '/demo/replacement-bookings/BRQ-' + 'a'.repeat(63) + '/checkin-confirmations',
    '/demo/replacement-bookings/BRQ-' + 'a'.repeat(65) + '/checkin-confirmations',
    replacementPath + '/%63heckin-confirmations',
  ]) assert.equal((await write(customer, checkin, { target })).status, 403)
  for (const instance of [counselor, customer]) {
    assert.deepEqual(instance.forwarded, [])
    assert.deepEqual(instance.handled, [])
  }
})

test('resolution errors pass through and new routes never expose replacement Location headers', async t => {
  const reply = {
    status: 409, body: { code: 'CASE_NOT_RESOLVED' }, contentType: 'application/problem+json',
    location: '/api/v1/replacement-bookings/' + replacementId,
  }
  const counselor = await proxy(t, {}, reply)
  const conflict = await write(counselor, closure)
  assert.equal(conflict.status, 409)
  assert.deepEqual(JSON.parse(conflict.body), reply.body)
  assert.equal(conflict.headers['content-type'], 'application/problem+json')
  assert.equal(conflict.headers.location, undefined)
  reply.status = 202
  for (const command of [task, closure]) assert.equal((await write(counselor, command)).headers.location, undefined)
  const customer = await proxy(t, { customerMode: true }, reply)
  assert.equal((await write(customer, checkin)).headers.location, undefined)
})
