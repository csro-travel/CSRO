// Run with Playwright Core and Chrome. All case API requests are intercepted.
const assert = require('node:assert/strict')
const { chromium } = require('playwright-core')

async function checkChatSend() {
  let releaseHistory, releaseSend
  const pendingHistory = new Promise(resolve => { releaseHistory = resolve })
  const pendingSend = new Promise(resolve => { releaseSend = resolve })
  const browser = await chromium.launch({ channel: 'chrome' })
  try {
    const port = Number(process.env.PORT || 3100)
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
    const caseIds = ['C-101', 'C-102']
    const body = '  예약 확인했습니다.\n다시 안내드리겠습니다.  '
    const failedDraft = '  남겨야 하는 입력 내용\n두 번째 줄  '
    const uncertainDraft = '  저장 결과를 다시 확인할 메시지  '
    const error = '테스트 메시지를 전송할 수 없습니다.'
    const sent = {
      messageId: 'MSG-SENT', senderType: 'COUNSELOR', messageType: 'TEXT',
      body, sentAt: '2026-11-10T01:01:00.000Z', offer: null,
    }
    const previous = {
      ...sent, messageId: 'MSG-PREVIOUS', senderType: 'CUSTOMER',
      body: '기존 메시지', sentAt: '2026-11-10T01:00:00.000Z',
    }
    const savedAfterTimeout = {
      ...sent, messageId: 'MSG-SAVED', body: uncertainDraft, sentAt: '2026-11-10T01:02:00.000Z',
    }
    const submissions = []
    let historyReads = 0

    await page.route(/\/demo\/cases(?:[/?]|$)/, async route => {
      const request = route.request()
      const { pathname } = new URL(request.url())
      if (pathname === '/demo/cases') {
        return route.fulfill({ json: {
          items: caseIds.map(caseId => ({
            caseId, bookingId: 'YB-test', hotelName: '테스트 호텔',
            priority: 'NORMAL', status: 'OPEN', summary: '테스트 사건', nextAction: null,
          })),
          pagination: { page: 0, size: 20, totalElements: 2, hasNext: false },
        } })
      }

      const caseId = pathname.split('/')[3]
      if (pathname.endsWith('/messages')) {
        if (request.method() === 'POST') {
          submissions.push({ caseId, body: request.postDataJSON() })
          if (submissions.length === 1) {
            await pendingSend
            return route.fulfill({ status: 201, json: sent })
          }
          if (submissions.length === 3) return route.fulfill({ status: 502 })
          return route.fulfill({
            status: 400, contentType: 'application/problem+json',
            json: { code: 'INVALID_MESSAGE_BODY', detail: error },
          })
        }
        // The initial history can finish after the send and include the same message ID.
        const items = []
        if (caseId === 'C-101') {
          historyReads++
          await pendingHistory
          items.push(previous, sent)
          if (submissions.length >= 3) items.push(savedAfterTimeout)
        }
        return route.fulfill({ json: {
          caseId, items,
          pagination: { hasMoreBefore: false, nextBeforeMessageId: null },
        } })
      }

      return route.fulfill({ json: {
        caseId, status: caseId === 'C-102' ? 'CLOSED' : 'OPEN',
        booking: { bookingId: 'YB-test', hotel: { name: '테스트 호텔' }, supplierStatus: 'CONFIRMED' },
        nights: [], nextAction: null, evidence: [], customerReports: [],
      } })
    })

    await page.goto(`http://127.0.0.1:${port}/cases`)
    const openCase = caseId => page.getByRole('button', { name: `${caseId} 사건 열기`, exact: true }).click()
    await openCase('C-101')
    const panel = page.getByRole('region', { name: '채팅', exact: true })
    const history = panel.getByRole('region', { name: '대화 내역', exact: true })
    const draft = panel.getByRole('textbox', { name: '메시지', exact: true })
    const send = panel.getByRole('button', { name: /^전송(?: 중…)?$/ })
    const rendered = history.getByText(body, { exact: true })
    await history.getByText('대화 내역을 불러오는 중…', { exact: true }).waitFor()

    assert.equal(await send.isDisabled(), true)
    await draft.fill('  \n\t  ')
    assert.equal(await send.isDisabled(), true)
    await draft.fill(body)
    const [request] = await Promise.all([
      page.waitForRequest(request => request.method() === 'POST' && request.url().endsWith('/C-101/messages')),
      send.click({ clickCount: 2 }),
    ])
    assert.deepEqual(request.postDataJSON(), { body })
    assert.equal(await send.isDisabled(), true)
    assert.equal(await draft.inputValue(), body)
    releaseSend()
    await rendered.waitFor()
    assert.equal(await draft.inputValue(), '')
    assert.equal(await rendered.textContent(), body)
    assert.equal(await rendered.count(), 1)

    releaseHistory()
    await history.getByText(previous.body, { exact: true }).waitFor()
    assert.equal(await rendered.count(), 1)
    assert.deepEqual(submissions, [{ caseId: 'C-101', body: { body } }])

    await draft.fill(failedDraft)
    await send.click()
    await panel.getByRole('alert').filter({ hasText: error }).waitFor()
    assert.equal(await draft.inputValue(), failedDraft)
    assert.equal(await send.isEnabled(), true)
    assert.equal(await history.getByText(failedDraft, { exact: true }).count(), 0)

    await draft.fill(uncertainDraft)
    await send.click()
    await panel.getByRole('alert').filter({ hasText: '전송 결과를 확인하지 못했습니다.' }).waitFor()
    await history.getByText(uncertainDraft, { exact: true }).waitFor()
    assert.equal(await draft.inputValue(), uncertainDraft)
    assert.equal(historyReads, 2)

    await page.getByRole('button', { name: /사건 목록/ }).click()
    await openCase('C-102')
    await history.getByText('표시할 대화가 없습니다.', { exact: true }).waitFor()
    assert.equal(await draft.isDisabled(), true)
    assert.equal(await send.isDisabled(), true)
    assert.deepEqual(submissions, [
      { caseId: 'C-101', body: { body } },
      { caseId: 'C-101', body: { body: failedDraft } },
      { caseId: 'C-101', body: { body: uncertainDraft } },
    ])
    console.log('PASS chat send raw text, duplicate guard, history merge, failure recovery and closed case')
  } finally {
    releaseSend()
    releaseHistory()
    await browser.close()
  }
}

checkChatSend().catch(error => { console.error(error); process.exitCode = 1 })
