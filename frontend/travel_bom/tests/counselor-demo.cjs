// Run against the read-only counselor demo with Playwright Core and Chrome.
const assert = require('node:assert/strict')
const { chromium } = require('playwright-core')

function waitForFirstPage(page, status) {
  return page.waitForResponse(response => {
    const { pathname, searchParams } = new URL(response.url())
    return response.request().method() === 'GET' && pathname === '/demo/cases' &&
      searchParams.get('page') === '0' && searchParams.get('status') === status
  })
}

function caseRow(page, caseId) {
  return page.getByRole('row').filter({hasText: caseId})
}

async function holdChatHistory(page, caseId, items) {
  let release
  const pending = new Promise(resolve => { release = resolve })
  await page.route(`**/demo/cases/${caseId}/messages`, async route => {
    await pending
    return route.fulfill({json: {
      caseId, items, pagination: {hasMoreBefore: false, nextBeforeMessageId: null},
    }})
  })
  return release
}

async function checkDashboard() {
  const browser = await chromium.launch({ channel: 'chrome' })
  try {
    const viewport = { width: 1440, height: 900 }
    const port = Number(process.env.PORT || 3000)
    const url = `http://127.0.0.1:${port}/cases`
    const live = await browser.newPage({ viewport })
    const [liveResponse] = await Promise.all([waitForFirstPage(live, null), live.goto(url)])
    assert.equal(liveResponse.status(), 200)
    const liveList = await liveResponse.json()
    const table = live.getByRole('table')
    await table.waitFor()
    assert.deepEqual((await table.getByRole('columnheader').allTextContents()).map(text => (
      text.trim().replace(/\s*·\s*/g, '·')
    )), ['긴급도', '사건·예약', '확인된 문제', '처리 상태', '다음 업무·기한', '사건 열기'])
    for (const name of ['전체', '접수', '처리 중', '확인 대기']) {
      await live.getByRole('button', {name, exact:true}).waitFor()
    }
    if (liveList.pagination.totalElements === 0) {
      const empty = table.getByText('현재 처리할 상담 사건이 없습니다')
      await empty.waitFor()
      await live.locator('main > header').getByText('0건', {exact:true}).waitFor()
      const [filteredResponse] = await Promise.all([
        waitForFirstPage(live, 'OPEN'),
        live.getByRole('button', {name: '접수', exact:true}).click(),
      ])
      assert.equal(filteredResponse.status(), 200)
      assert.equal((await filteredResponse.json()).pagination.totalElements, 0)
      await empty.waitFor()
      await live.getByRole('button', {name: '전체 보기', exact:true}).waitFor()
      console.log('PASS live empty case list and OPEN filter')
    }

    if (liveList.items.some(item => item.caseId === 'C-001')) {
      await caseRow(live, 'C-001').getByRole('button', {name: /사건 열기/}).click()
      await live.getByText(/^YB-001 · 사건 OPEN · 공급사 계약 CONFIRMED$/).waitFor()
      await live.getByText('2026-11-10 · UNAVAILABLE / VERIFIED', {exact:true}).waitFor()
      await live.getByText('2026-11-11 · UNKNOWN / UNCONFIRMED', {exact:true}).waitFor()
      await live.getByText('2026-11-12 · UNKNOWN / UNCONFIRMED', {exact:true}).waitFor()
      await live.getByText(/CONTACT_CUSTOMER · TODO · 기한 미정/).waitFor()
      const hotel = live.locator('section').filter({has: live.locator('strong', {hasText: 'HOTEL_API'})})
      await hotel.getByText('응답 원문', {exact:true}).click()
      const evidence = JSON.parse(await hotel.locator('pre').innerText())
      assert.equal(evidence.propertyId, 'HOTEL-001')
      console.log('PASS live C-001 detail')
    } else {
      console.log('SKIP live C-001 detail: C-001 is absent from the active first page')
    }
    await live.close()

    const page = await browser.newPage({ viewport })
    const deadline = '2026-11-10T01:00:00.000Z'
    const items = Array.from({length: 21}, (_, i) => ({
      caseId: 'C-' + String(101 + i), bookingId: 'YB-test', hotelName: '테스트 호텔',
      priority: i === 0 ? 'URGENT' : 'NORMAL', status: 'OPEN', summary: '페이지 이동 확인',
      nextAction: i < 2 ? {
        taskId: `TASK-${i + 1}`, type: 'CONTACT_CUSTOMER', status: 'TODO', dueAt: i === 0 ? null : deadline,
      } : null,
    }))
    let failNextPage = true
    await page.route(/\/demo\/cases\?/, route => {
      const { searchParams } = new URL(route.request().url())
      const index = Number(searchParams.get('page'))
      const filtered = searchParams.has('status') ? [] : items
      if (index === 1 && failNextPage) {
        failNextPage = false
        return route.fulfill({status: 502, body: ''})
      }
      return route.fulfill({json: {
        items: filtered.slice(index * 20, (index + 1) * 20),
        pagination: {page: index, size: 20, totalElements: filtered.length, hasNext: (index + 1) * 20 < filtered.length},
      }})
    })
    await page.goto(url)
    const first = caseRow(page, 'C-101')
    const last = caseRow(page, 'C-121')
    const next = page.getByRole('button', {name: '다음', exact:true})
    await first.waitFor()
    await page.locator('main > header').getByText('21건', {exact:true}).waitFor()
    for (const label of ['긴급', '접수', '고객 연락', '기한 미정']) {
      await first.getByText(label, {exact:true}).waitFor()
    }
    const taskDeadline = caseRow(page, 'C-102').locator('time')
    assert.equal(await taskDeadline.getAttribute('datetime'), deadline)
    assert.match(await taskDeadline.innerText(), /10:00/)
    await next.click()
    await page.getByRole('alert').filter({hasText: '조회 실패 (HTTP 502)'}).waitFor()
    assert.equal(await first.count(), 0)
    await page.getByRole('button', {name: '다시 시도'}).click()
    await last.waitFor()
    await last.getByText('미완료 업무 없음', {exact:true}).waitFor()
    assert.equal(await next.isDisabled(), true)
    assert.equal(await first.count(), 0)
    await page.getByRole('button', {name: '이전', exact:true}).click()
    await first.waitFor()
    assert.equal(await last.count(), 0)
    await next.click()
    await last.waitFor()
    await Promise.all([
      waitForFirstPage(page, 'WAITING'),
      page.getByRole('button', {name: '확인 대기', exact:true}).click(),
    ])
    await page.getByRole('table').getByText('현재 처리할 상담 사건이 없습니다').waitFor()
    assert.equal(await page.getByRole('navigation', {name: '사건 목록 페이지'}).count(), 0)
    await Promise.all([
      waitForFirstPage(page, null),
      page.getByRole('button', {name: '전체 보기', exact:true}).click(),
    ])
    await first.waitFor()
    await page.route(/\/demo\/cases\/C-10[123]$/, route => route.fulfill({json: {
      caseId: new URL(route.request().url()).pathname.split('/').at(-1), status: 'OPEN',
      booking: {bookingId: 'YB-test', hotel: {name: '테스트 호텔'}, supplierStatus: 'CONFIRMED'},
      nights: [], nextAction: null, evidence: [], customerReports: [],
    }}))

    const firstChat = [
      {messageId: 'MSG-Z', body: '첫 사건 대화 1', sentAt: deadline, senderType: 'CUSTOMER', messageType: 'TEXT', offer: null},
      {messageId: 'MSG-A', body: '첫 사건 대화 2', sentAt: deadline, senderType: 'COUNSELOR', messageType: 'TEXT', offer: null},
      {messageId: 'MSG-B', body: '첫 사건 대화 3', sentAt: '2026-11-10T01:01:00.000Z', senderType: 'SYSTEM', messageType: 'SYSTEM', offer: null},
      {
        messageId: 'MSG-OFFER', body: null, sentAt: '2026-11-10T01:02:00.000Z',
        senderType: 'COUNSELOR', messageType: 'OFFER', offer: {
          offerId: 'OFFER-1', status: 'SUPERSEDED',
          items: ['A', 'B', 'C'].map((suffix, index) => ({
            quoteId: `QUOTE-${suffix}`, displayOrder: index + 1, propertyName: `테스트 제안 ${suffix}`,
            distanceMeters: 800, checkInDate: '2026-11-10', checkOutDate: '2026-11-13', adults: 2, rooms: 1,
            totalAmount: 480000 + index * 10000, currency: 'KRW', priceDifference: null,
            checkedAt: deadline, expiresAt: '2026-11-10T02:00:00.000Z',
          })),
        },
      },
    ]
    const chatPanel = page.getByRole('region', {name: '고객과의 대화', exact:true})
    const history = chatPanel.getByRole('region', {name: '대화 내역', exact:true})
    const firstMessages = history.getByText(/^첫 사건 대화 [123]$/)
    const releaseFirstChat = await holdChatHistory(page, 'C-101', firstChat)
    try {
      const [request] = await Promise.all([
        page.waitForRequest('**/demo/cases/C-101/messages'),
        first.getByRole('button', {name: 'C-101 사건 열기'}).click(),
      ])
      assert.equal(request.method(), 'GET')
      assert.equal(request.headers()['x-demo-read'], '1')
      await page.getByRole('heading', {name: 'C-101 · 테스트 호텔', exact:true}).waitFor()
      await history.getByText('대화 내역을 불러오는 중…', {exact:true}).waitFor()
      assert.equal(await firstMessages.count(), 0)
    } finally {
      releaseFirstChat()
    }
    console.log('PASS case rows, pagination, retry, filter reset and detail with intercepted responses')

    await firstMessages.last().waitFor()
    assert.deepEqual(await firstMessages.allTextContents(), firstChat.flatMap(message => message.body ?? []))
    await history.getByText('이전 대체 숙소 제안', {exact:true}).waitFor()
    assert.deepEqual(await history.getByText(/^테스트 제안 [ABC]$/).allTextContents(), ['테스트 제안 A', '테스트 제안 B', '테스트 제안 C'])
    await history.getByText('480,000원', {exact:true}).waitFor()
    assert.equal(await chatPanel.getByRole('button', {name: '전송', exact:true}).isDisabled(), true)

    const releaseEmptyChat = await holdChatHistory(page, 'C-102', [])
    try {
      await page.getByRole('button', {name: /사건 목록/}).click()
      await caseRow(page, 'C-102').getByRole('button', {name: 'C-102 사건 열기'}).click()
      await history.getByText('대화 내역을 불러오는 중…', {exact:true}).waitFor()
      assert.equal(await firstMessages.count(), 0)
    } finally {
      releaseEmptyChat()
    }
    await history.getByText('표시할 대화가 없습니다.', {exact:true}).waitFor()

    await page.route('**/demo/cases/C-103/messages', route => route.fulfill({status: 502, body: ''}))
    await page.getByRole('button', {name: /사건 목록/}).click()
    await caseRow(page, 'C-103').getByRole('button', {name: 'C-103 사건 열기'}).click()
    await history.getByRole('alert').filter({hasText: '대화 내역을 불러오지 못했습니다.'}).waitFor()
    assert.equal(await firstMessages.count(), 0)
    assert.equal(await history.getByText('표시할 대화가 없습니다.', {exact:true}).count(), 0)
    console.log('PASS case chat loading, server order, case isolation, empty history and request failure')
  } finally {
    await browser.close()
  }
}

checkDashboard().catch(error => { console.error(error); process.exitCode = 1 })
