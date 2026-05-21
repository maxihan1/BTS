// 프론트엔드 dev server 기본 응답을 검증하는 스모크 테스트
import { test, expect } from '@playwright/test';

test('frontend dev server 응답', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('html')).toBeVisible();
});
