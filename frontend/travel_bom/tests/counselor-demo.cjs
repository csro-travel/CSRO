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
    await page.route('**/demo/cases', route => route.fulfill({status: 502, body: ''}))
    await page.getByRole('button', {name: /사건 목록/}).click()
    await page.getByRole('alert').filter({hasText: '조회 실패 (HTTP 502)'}).waitFor()
    assert.equal(await page.getByRole('button', {name: /C-001/}).count(), 0)
    console.log('PASS counselor list, detail, hotel evidence and failed lookup')
  } finally {
    await browser.close()
  }
}

checkDashboard().catch(error => { console.error(error); process.exitCode = 1 })
