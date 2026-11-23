// Run against the frontend demo with Playwright Core and Chrome. Case API requests use fixtures.
const assert = require('node:assert/strict')
const { chromium } = require('playwright-core')

const viewport = { width: 1366, height: 768 }
const caseId = 'C-101'
const bookingId = 'YB-101'
const hotelName = '서울 한강 스테이'
const detailPath = `/demo/cases/${caseId}`
const messagesPath = `${detailPath}/messages`
const firstSentAt = Date.parse('2026-11-10T00:00:00Z')
const historyMessages = Array.from({ length: 24 }, (_, index) => ({
  messageId: `MSG-${index + 1}`,
  senderType: index % 2 === 0 ? 'CUSTOMER' : 'COUNSELOR',
  messageType: 'TEXT',
  body: `객실 확인 대화 ${index + 1}`,
  sentAt: new Date(firstSentAt + index * 60_000).toISOString(),
  offer: null,
}))
const reply = {
  messageId: 'MSG-REPLY', senderType: 'COUNSELOR', messageType: 'TEXT',
  body: '호텔에 확인한 뒤 답변드리겠습니다.',
  sentAt: '2026-11-10T01:00:00.000Z', offer: null,
}

async function assertInViewport(locator, label) {
  const { top, right, bottom, left, width, height } = await locator.evaluate(
    element => element.getBoundingClientRect().toJSON(),
  )
  assert(width > 0 && height > 0, `${label} has no visible area`)
  assert(top >= 0 && bottom <= viewport.height && left >= 0 && right <= viewport.width,
    `${label} is outside the viewport: ${JSON.stringify({ top, right, bottom, left })}`)
}

async function checkChatViewport() {
  const browser = await chromium.launch({ channel: 'chrome' })
  try {
    const port = Number(process.env.PORT || 3100)
    const page = await browser.newPage({ viewport })
    const submissions = []
    await page.route(/\/demo\/cases(?:[/?]|$)/, route => {
      const request = route.request()
      const { pathname } = new URL(request.url())
      if (pathname === '/demo/cases' && request.method() === 'GET') {
        return route.fulfill({ json: {
          items: [{
            caseId, bookingId, hotelName, priority: 'NORMAL', status: 'OPEN',
            summary: '객실 제공 여부 확인', nextAction: null,
          }],
          pagination: { page: 0, size: 20, totalElements: 1, hasNext: false },
        } })
      }
      if (pathname === detailPath && request.method() === 'GET') {
        return route.fulfill({ json: {
          caseId, status: 'OPEN',
          booking: { bookingId, hotel: { name: hotelName }, supplierStatus: 'CONFIRMED' },
          nights: Array.from({ length: 20 }, (_, index) => ({
            stayDate: `2026-11-${10 + index}`, fulfillmentStatus: 'UNKNOWN', factStatus: 'UNCONFIRMED',
          })),
          nextAction: null, evidence: [], customerReports: [],
        } })
      }
      if (pathname === messagesPath && request.method() === 'GET') {
        return route.fulfill({ json: {
          caseId, items: historyMessages,
          pagination: { hasMoreBefore: false, nextBeforeMessageId: null },
        } })
      }
      if (pathname === messagesPath && request.method() === 'POST') {
        submissions.push(request.postDataJSON())
        return route.fulfill({ status: 201, json: reply })
      }
      return route.fulfill({ status: 404 })
    })

    await page.goto(`http://127.0.0.1:${port}/cases`)
    await page.getByRole('button', { name: `${caseId} 사건 열기`, exact: true }).click()
    const panel = page.getByRole('region', { name: '채팅', exact: true })
    const history = panel.getByRole('region', { name: '대화 내역', exact: true })
    const draft = panel.getByRole('textbox', { name: '메시지', exact: true })
    const send = panel.getByRole('button', { name: '전송', exact: true })
    await history.getByText(historyMessages[0].body, { exact: true }).waitFor()
    await page.evaluate(() => document.fonts.ready)

    assert.equal(await page.evaluate(() => window.scrollY), 0)
    assert(await page.evaluate(() => document.documentElement.scrollHeight > window.innerHeight))
    await assertInViewport(draft, 'Message input')
    await assertInViewport(send, 'Send button')

    const initialScroll = await history.evaluate(element => ({
      top: element.scrollTop, height: element.clientHeight, total: element.scrollHeight,
    }))
    assert(initialScroll.total > initialScroll.height, 'History has no scrollable overflow')
    const fixedElements = [panel, draft, send]
    const initialPositions = await Promise.all(fixedElements.map(element => element.boundingBox()))
    await history.hover()
    const remainingScroll = initialScroll.total - initialScroll.height - initialScroll.top
    await page.mouse.wheel(0, remainingScroll > 1 ? 160 : -160)
    await page.waitForFunction(
      initialTop => document.querySelector('[aria-label="대화 내역"]').scrollTop !== initialTop,
      initialScroll.top,
    )
    assert.equal(await page.evaluate(() => window.scrollY), 0)
    assert.deepEqual(await Promise.all(fixedElements.map(element => element.boundingBox())), initialPositions)

    await draft.fill(reply.body)
    assert.equal(await page.evaluate(() => window.scrollY), 0)
    await assertInViewport(send, 'Send button with a draft')
    const [response] = await Promise.all([
      page.waitForResponse(response =>
        response.request().method() === 'POST' && new URL(response.url()).pathname === messagesPath),
      send.click(),
    ])
    assert.equal(response.status(), 201)
    assert.deepEqual(submissions, [{ body: reply.body }])
    await history.getByText(reply.body, { exact: true }).waitFor()
    await assertInViewport(draft, 'Message input after sending')
    await assertInViewport(send, 'Send button after sending')
    assert.equal(await draft.inputValue(), '')
    assert.equal(await page.evaluate(() => window.scrollY), 0)
    console.log('PASS chat composer stays in the 1366×768 viewport while long history scrolls and a reply is sent')
  } finally {
    await browser.close()
  }
}

checkChatViewport().catch(error => { console.error(error); process.exitCode = 1 })
