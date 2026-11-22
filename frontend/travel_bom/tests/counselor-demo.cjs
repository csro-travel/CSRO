// Run against the isolated YB-001 demo with Playwright Core and Chrome.
const assert = require('node:assert/strict')
const { chromium } = require('playwright-core')

async function checkDashboard() {
  const browser = await chromium.launch({ channel: 'chrome' })
  try {
    const page = await browser.newPage()
    const port = Number(process.env.PORT || 3000)
    await page.goto(`http://127.0.0.1:${port}/cases`)
    await page.getByRole('button', { name: /C-001/ }).click()
    await page.getByText(/^YB-001 · 사건 OPEN · 공급사 계약 CONFIRMED$/).waitFor()
    await page.getByText('2026-11-10 · UNAVAILABLE / VERIFIED', {exact:true}).waitFor()
    await page.getByText('2026-11-11 · UNKNOWN / UNCONFIRMED', {exact:true}).waitFor()
    await page.getByText('2026-11-12 · UNKNOWN / UNCONFIRMED', {exact:true}).waitFor()
    await page.getByText(/CONTACT_CUSTOMER · TODO · 기한 미정/).waitFor()
    const hotel = page.locator('section').filter({has: page.locator('strong', {hasText: 'HOTEL_API'})})
    await hotel.getByText('응답 원문', {exact:true}).click()
    const evidence = JSON.parse(await hotel.locator('pre').innerText())
    assert.equal(evidence.propertyId, 'HOTEL-001')
    const items = Array.from({length: 21}, (_, i) => ({
      caseId: 'C-' + String(101 + i), bookingId: 'YB-test', hotelName: '테스트 호텔',
      priority: 'NORMAL', summary: '페이지 이동 확인',
    }))
    let failNextPage = true
    await page.route(/\/demo\/cases\?page=[01]$/, route => {
      const index = Number(new URL(route.request().url()).searchParams.get('page'))
      if (index === 1 && failNextPage) {
        failNextPage = false
        return route.fulfill({status: 502, body: ''})
      }
      return route.fulfill({json: {
        items: items.slice(index * 20, (index + 1) * 20),
        pagination: {page: index, size: 20, totalElements: items.length, hasNext: index === 0},
      }})
    })
    await page.getByRole('button', {name: /사건 목록/}).click()
    const first = page.getByRole('button', {name: /C-101/})
    const last = page.getByRole('button', {name: /C-121/})
    const next = page.getByRole('button', {name: '다음', exact:true})
    await first.waitFor()
    await next.click()
    await page.getByRole('alert').filter({hasText: '조회 실패 (HTTP 502)'}).waitFor()
    assert.equal(await first.count(), 0)
    await page.getByRole('button', {name: '다시 시도'}).click()
    await last.waitFor()
    assert.equal(await next.isDisabled(), true)
    assert.equal(await first.count(), 0)
    await page.getByRole('button', {name: '이전', exact:true}).click()
    await first.waitFor()
    assert.equal(await last.count(), 0)
    console.log('PASS live C-001, pagination, stale list removal and retry')
  } finally {
    await browser.close()
  }
}

checkDashboard().catch(error => { console.error(error); process.exitCode = 1 })
