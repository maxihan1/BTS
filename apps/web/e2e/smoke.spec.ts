// 대상 서버가 응답하는지만 보는 스모크 테스트 — MSW 없이도 도는 유일한 스펙
//
// ## ★`@prod` 태그의 뜻
//
// 「이 스펙은 **실서버**(MSW 없음 · dev 시드 없음)에서도 돌 수 있다」이다.
// `Jenkinsfile.e2e` 가 실서버를 대상으로 할 때 `--grep @prod` 로 이 집합만 고른다.
//
// ## 왜 필요했나 — 2026-09-11 적발
//
// 배포 후 E2E 가 스펙 168개를 **전량** 실서버에 돌리게 배선돼 있었다. 그런데
//   · MSW 는 `import.meta.env.DEV` 에서만 뜬다(`apps/web/src/main.tsx`) — 배포본엔 없다
//   · 스펙 168개 중 **146개가 `alice`** 로 로그인한다(dev 시드 계정)
//   · 실서버에는 그 계정이 없다 — `ProdDevSeedAbsenceBootTest` 가 부재를 단언한다
//
// 즉 **구조적으로 전량 red** 였고, 그것이 3시간 동안 executor 1개를 점유한다.
// 「배포가 잘못됐다」로 보이지만 실제로는 「스펙이 이 대상에서 돌 수 없다」이다.
//
// ★태그를 붙일 때의 계약. `loginAsAlice` 나 `src/mocks` 를 쓰면 안 된다.
//   `e2e-prod-target.test.ts` 가 그 차집합을 강제한다.
import { test, expect } from '@playwright/test';

test('대상 서버가 응답한다 @prod', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('html')).toBeVisible();
});
