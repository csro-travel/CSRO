// Run against the frontend demo with Playwright Core and Chrome. Case API requests use fixtures.
const assert = require('node:assert/strict')
const { chromium } = require('playwright-core')

const caseId = 'C-101'
const bookingId = 'YB-101'
const hotelName = '서울 한강 스테이'
const detailPath = `/demo/cases/${caseId}`
const historyPath = `${detailPath}/messages`
const conversation = Array.from({ length: 31 }, (_, index) => ({
  messageId: `MSG-${String(index + 4).padStart(3, '0')}`,
  senderType: index % 2 === 0 ? 'CUSTOMER' : 'COUNSELOR',
  messageType: 'TEXT',
  body: `객실 문의 대화 ${index + 4}`,
  sentAt: new Date(Date.UTC(2026, 10, 10, 0, index)).toISOString(),
  offer: null,
}))
const earlier = conversation.slice(0, 8)
const initial = conversation.slice(7)
const fresh = {
  ...conversation[0],
  messageId: 'MSG-035',
  body: '프런트에서 아직 답변을 기다리고 있어요.',
  sentAt: '2026-11-10T01:00:00.000Z',
}

function messagePage(items, nextBeforeMessageId = null) {
  return { caseId, items, pagination: { hasMoreBefore: nextBeforeMessageId !== null, nextBeforeMessageId } }
}

function positionInHistory(history, body) {
  return history.evaluate((element, body) => {
    const message = Array.from(element.querySelectorAll('p')).find(item => item.textContent === body)
    return message.getBoundingClientRect().top - element.getBoundingClientRect().top
  }, body)
}

async function checkChatHistory() {
  const browser = await chromium.launch({ channel: 'chrome' })
  try {
    const port = Number(process.env.PORT || 3100)
    const page = await browser.newPage({ viewport: { width: 1366, height: 768 } })
    const historyRequests = []
    await page.route(/\/demo\/cases(?:[/?]|$)/, route => {
      const request = route.request()
      const { pathname, searchParams } = new URL(request.url())
      assert.equal(request.method(), 'GET')
      if (pathname === '/demo/cases') {
        return route.fulfill({ json: {
          items: [{
            caseId, bookingId, hotelName, priority: 'NORMAL', status: 'OPEN',
            summary: '객실 제공 여부 확인', nextAction: null,
          }],
          pagination: { page: 0, size: 20, totalElements: 1, hasNext: false },
        } })
      }
      if (pathname === detailPath) {
        return route.fulfill({ json: {
          caseId, status: 'OPEN',
          booking: { bookingId, hotel: { name: hotelName }, supplierStatus: 'CONFIRMED' },
          nights: [], nextAction: null, evidence: [], customerReports: [],
        } })
      }
      if (pathname === historyPath) {
        const cursor = searchParams.get('beforeMessageId')
        historyRequests.push(cursor)
        // Overlap one ID to exercise deduplication at the history boundary.
        if (cursor) return route.fulfill({ json: messagePage(earlier) })
        if (historyRequests.length === 1) {
          return route.fulfill({ json: messagePage(initial, initial[0].messageId) })
        }
        return route.fulfill({ json: messagePage([...initial.slice(1), fresh], initial[1].messageId) })
      }
      return route.fulfill({ status: 404 })
    })

    await page.goto(`http://127.0.0.1:${port}/cases`)
    await page.getByRole('button', { name: `${caseId} 사건 열기`, exact: true }).click()
    const panel = page.getByRole('region', { name: '채팅', exact: true })
    const history = panel.getByRole('region', { name: '대화 내역', exact: true })
    const earlierButton = panel.getByRole('button', { name: '이전 대화 불러오기', exact: true })
    const refresh = panel.getByRole('button', { name: '새 메시지 확인', exact: true })
    const draft = panel.getByRole('textbox', { name: '메시지', exact: true })
    const matchMessage = message => history.getByText(message.body, { exact: true })
    await matchMessage(initial.at(-1)).waitFor()
    await page.evaluate(() => document.fonts.ready)
    await page.waitForFunction(() => {
      const transcript = document.querySelector('[aria-label="대화 내역"]')
      const maxScrollTop = transcript.scrollHeight - transcript.clientHeight
      return maxScrollTop > 100 && Math.abs(transcript.scrollTop - maxScrollTop) < 2
    })

    await history.evaluate(element => { element.scrollTop = 0 })
    const anchor = initial[1]
    const initialTop = await positionInHistory(history, anchor.body)
    assert(initialTop >= 0 && initialTop < await history.evaluate(element => element.clientHeight))
    await earlierButton.click()
    await matchMessage(earlier[0]).waitFor({ state: 'attached' })
    assert(Math.abs(await positionInHistory(history, anchor.body) - initialTop) < 2)
    assert.equal(await matchMessage(earlier.at(-1)).count(), 1)
    assert.deepEqual(historyRequests, [null, initial[0].messageId])

    await draft.fill('  객실 확인 후 안내드리겠습니다.\n잠시만 기다려 주세요.  ')
    const originalDraft = await draft.inputValue()
    await refresh.click()
    await matchMessage(fresh).waitFor({ state: 'attached' })
    assert(Math.abs(await positionInHistory(history, anchor.body) - initialTop) < 2)
    assert.equal(await draft.inputValue(), originalDraft)
    assert.equal(await matchMessage(earlier[0]).count(), 1)
    assert.equal(await matchMessage(anchor).count(), 1)
    assert.equal(await matchMessage(fresh).count(), 1)
    assert.deepEqual(historyRequests, [null, initial[0].messageId, null])
    console.log('PASS chat cursor prepends older messages without shifting the reader and refresh preserves the draft')
  } finally {
    await browser.close()
  }
}

checkChatHistory().catch(error => { console.error(error); process.exitCode = 1 })
