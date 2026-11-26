// Run with Playwright Core and Chrome. Set CHAT_DISPLAY_SCREENSHOT to capture the fixture.
const assert = require('node:assert/strict')
const { chromium } = require('playwright-core')

const caseId = 'C-101'
const bookingId = 'YB-24018'
const hotelName = '서울 한강 스테이'
const messages = [
  {
    messageId: 'MSG-101', senderType: 'CUSTOMER', messageType: 'TEXT',
    body: '오늘 체크인인데 호텔에서 객실이 없다고 연락받았어요. 예약을 확인해 주세요.',
    sentAt: '2026-11-09T23:57:00.000Z', offer: null,
  },
  {
    messageId: 'MSG-102', senderType: 'COUNSELOR', messageType: 'TEXT',
    body: '호텔에 예약 상태를 확인하고 있습니다. 같은 일정에 이용할 수 있는 대체 숙소도 함께 알아보겠습니다.',
    sentAt: '2026-11-10T00:04:00.000Z', offer: null,
  },
]
const nextAction = {
  taskId: 'TASK-101', type: 'CONTACT_CUSTOMER', status: 'TODO', dueAt: '2026-11-10T01:00:00.000Z',
}

async function checkChatDisplay() {
  const browser = await chromium.launch({ channel: 'chrome' })
  try {
    const port = Number(process.env.PORT || 3100)
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
    await page.route(/\/demo\/cases(?:[/?]|$)/, async route => {
      const request = route.request()
      const { pathname } = new URL(request.url())
      assert.equal(request.method(), 'GET')
      if (pathname === '/demo/cases') {
        return route.fulfill({ json: {
          items: [{
            caseId, bookingId, hotelName, priority: 'URGENT', status: 'OPEN',
            summary: '체크인 당일 객실 제공 여부 확인 필요', nextAction,
          }],
          pagination: { page: 0, size: 20, totalElements: 1, hasNext: false },
        } })
      }
      if (pathname === `/demo/cases/${caseId}/messages`) {
        return route.fulfill({ json: {
          caseId, items: messages, pagination: { hasMoreBefore: false, nextBeforeMessageId: null },
        } })
      }
      if (pathname === `/demo/cases/${caseId}`) {
        return route.fulfill({ json: {
          caseId, status: 'OPEN',
          booking: { bookingId, hotel: { name: hotelName }, supplierStatus: 'CONFIRMED' },
          nights: [
            { stayDate: '2026-11-10', fulfillmentStatus: 'UNKNOWN', factStatus: 'UNCONFIRMED' },
            { stayDate: '2026-11-11', fulfillmentStatus: 'UNKNOWN', factStatus: 'UNCONFIRMED' },
          ],
          nextAction, evidence: [], customerReports: [],
        } })
      }
      return route.fulfill({ status: 404 })
    })

    await page.goto(`http://127.0.0.1:${port}/cases`)
    await page.getByRole('button', { name: `${caseId} 대응 건 열기`, exact: true }).click()
    const panel = page.getByRole('region', { name: '채팅', exact: true })
    const transcript = panel.getByRole('region', { name: '대화 내역', exact: true })
    const customer = transcript.getByText(messages[0].body, { exact: true })
    const counselor = transcript.getByText(messages[1].body, { exact: true })
    await customer.waitFor()
    await counselor.waitFor()
    assert.equal(await panel.getByRole('heading').count(), 0)

    for (const label of ['고객', '상담사']) {
      assert.equal(await transcript.getByText(label, { exact: true }).count(), 1)
    }
    // The UTC dates differ, but these two timestamps belong to the same day in Seoul.
    assert.equal(await transcript.getByText('2026년 11월 10일 화요일', { exact: true }).count(), 1)
    assert.equal(await transcript.getByText('2026년 11월 9일 월요일', { exact: true }).count(), 0)
    for (const [index, label] of ['오전 8:57', '오전 9:04'].entries()) {
      const timestamp = transcript.locator(`time[datetime="${messages[index].sentAt}"]`)
      assert.equal((await timestamp.textContent()).trim(), label)
    }

    await page.evaluate(() => document.fonts.ready)
    const [transcriptBox, customerBox, counselorBox] = await Promise.all([
      transcript.boundingBox(), customer.boundingBox(), counselor.boundingBox(),
    ])
    const center = box => box.x + box.width / 2
    assert(center(customerBox) < center(transcriptBox), '고객 메시지가 대화 영역 왼쪽에 표시됩니다')
    assert(center(counselorBox) > center(transcriptBox), '상담사 메시지가 대화 영역 오른쪽에 표시됩니다')
    assert(customerBox.y < counselorBox.y)

    if (process.env.CHAT_DISPLAY_SCREENSHOT) {
      await page.screenshot({ path: process.env.CHAT_DISPLAY_SCREENSHOT, animations: 'disabled' })
    }
    console.log('PASS chat bubble alignment, sender labels, shared Seoul date and time-only timestamps')
  } finally {
    await browser.close()
  }
}

checkChatDisplay().catch(error => { console.error(error); process.exitCode = 1 })
