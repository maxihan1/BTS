// 임시 눈확인용 — 새 프로젝트 헤더+탭바 배치를 캡처한다. 커밋 전 삭제한다.
import { test } from '@playwright/test'
import { loginAsAlice } from './fixtures/auth-fixtures'

const OUT = '/Users/maxi.moff/.claude/jobs/ec0a485c/tmp'

test('보드 화면 배치', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await loginAsAlice(page)
  await page.goto('/projects/ATLAS/board')
  await page.waitForTimeout(2500)
  await page.screenshot({ path: `${OUT}/shot-board.png` })
})

test('이슈 탭 화면 배치 (편차 X9 폐기)', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await loginAsAlice(page)
  await page.goto('/projects/ATLAS/issues')
  await page.waitForTimeout(2500)
  await page.screenshot({ path: `${OUT}/shot-issues.png` })
})

test('캘린더 탭 화면 배치 (탭바가 남는다)', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await loginAsAlice(page)
  await page.goto('/projects/ATLAS/calendar')
  await page.waitForTimeout(2500)
  await page.screenshot({ path: `${OUT}/shot-calendar.png` })
})
