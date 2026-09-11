// 실서버에서도 도는 스모크 — MSW·dev 시드 없이 검증 가능한 것만 (@prod)
//
// ## ★`@prod` 태그의 계약
//
// 「이 스펙은 **실서버**(MSW 없음 · dev 시드 없음)에서도 돌 수 있다」이다.
// `Jenkinsfile.e2e` 가 실서버를 대상으로 할 때 `--grep @prod` 로 이 집합만 고른다.
//
// **`loginAsAlice` 나 `src/mocks` 를 쓰면 안 된다.** `e2e-prod-target.test.ts` 가
// 그 차집합을 강제한다 — 어기면 red 다.
//
// ## 왜 이 파일이 필요했나 — 2026-09-11
//
// 배포 후 E2E 가 스펙 168개를 **전량** 실서버에 돌리게 배선돼 있었는데, 그중 146개가
// `alice`(dev 시드 계정)로 로그인한다. MSW 는 `import.meta.env.DEV` 에서만 뜨고
// 배포본은 `vite build` 라 DEV=false 다. 실서버에는 그 계정이 없다
// (`ProdDevSeedAbsenceBootTest` 가 부재를 단언한다).
//
// 즉 **구조적으로 전량 red** 였고, 그 3시간이 executor 1개를 점유해 `bts-ci` 가 멈춘다.
// 젠킨스 화면에는 「배포 검증 실패」로 보이지만 실제로는 「이 대상에서 돌 수 없는 스펙」이다.
//
// ## 여기서 무엇을 확인하나
//
// 로그인 **이전**에 도달 가능한 것만 본다 — 그것이 인증 없이 확인 가능한 전부다.
//   ① 앱 셸이 뜬다            배포본이 실제로 서빙되는가
//   ② 로그인 화면이 렌더된다   번들이 실행되고 라우팅이 도는가(정적 파일 200 과 다르다)
//   ③ SPA fallback 이 산다    없는 경로가 서버 오류가 아니라 앱으로 떨어지는가
//
// ★②가 핵심이다. ①만으로는 「index.html 은 오는데 JS 가 깨진 배포」를 못 잡는다 —
//   nginx 가 없는 경로도 200 + HTML 로 주므로 HTTP 상태만으로는 아무것도 구별되지 않는다.
//   그 함정은 `bts-deploy.sh` 와 `Jenkinsfile.e2e` 가 이미 각각 경고해 뒀다.
import { test, expect } from '@playwright/test'
import { loginPageStrings } from '../src/i18n/ko'

test('앱 셸이 응답한다 @prod', async ({ page }) => {
  const res = await page.goto('/')
  expect(res?.status(), '루트가 200 이 아니다 — 배포본이 안 서빙된다.').toBe(200)
  await expect(page.locator('html')).toBeVisible()
})

test('로그인 화면이 렌더된다 @prod', async ({ page }) => {
  // ★인증 없이 도달 가능한 유일한 화면이다. 여기까지 오면 번들이 실행되고
  //   라우터가 돌았다는 뜻이라, 「정적 파일은 오는데 앱이 안 뜨는 배포」를 잡는다.
  await page.goto('/login')
  await expect(
    page.getByRole('heading', { name: loginPageStrings.heading }),
    '로그인 화면 제목이 안 보인다 — 번들이 실행되지 않았거나 라우팅이 깨졌다.',
  ).toBeVisible({ timeout: 15_000 })
})

test('없는 경로가 앱으로 떨어진다 (SPA fallback) @prod', async ({ page }) => {
  // ★nginx 의 `try_files $uri $uri/ /index.html` 가 사는지 본다. 이것이 죽으면
  //   새로고침한 딥링크가 전부 404 가 된다 — 사용자에게 가장 먼저 보이는 고장이다.
  const res = await page.goto('/this-route-does-not-exist-e2e-probe')
  expect(res?.status(), 'SPA fallback 이 죽었다 — 딥링크 새로고침이 전부 깨진다.').toBe(200)
  await expect(page.locator('html')).toBeVisible()
})
