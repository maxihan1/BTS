// FR-PJ PR-5 D7 E2E — 프로젝트 CRUD happy-path (Task 8, 스펙 S1·S3·S5·S6)
//
// 시나리오 개요.
//   S1  목록 표시         — /projects 진입 → 활성 프로젝트(ATLAS·MIDDLE·ZETA)만 테이블 표시, 아카이브(NOVA)는 숨김
//   S2  생성 성공         — "새 프로젝트" → /projects/new → key·name 입력 → 제출 → /projects/{key}/board 이동
//   S3  아카이브 토글     — "아카이브된 프로젝트 표시" 토글 → 아카이브 프로젝트(NOVA)만 표시로 전환
//   S4  설정 name 변경    — 프로젝트 설정 details에서 이름 수정 → 저장 → 재조회로 반영 확정
//   S5  아카이브/해제 왕복 — danger zone에서 아카이브 → 해제, 매 단계 최종 DOM 상태로 확정
//
// 설계 결정.
//   - "새 프로젝트" 버튼/생성 폼은 whoami.canCreateProject===true 일 때만 노출된다
//     (src/routes/projects.index.tsx·projects.new.tsx, src/api/schemas.ts). 공유 whoami MSW
//     mock(src/mocks/auth-handlers.ts)은 canCreateProject 필드를 전혀 채우지 않는다(AUTH_USERS
//     어느 fixture도 이 필드 없음 — 부재는 optional 스키마상 false로 해석). 핸들러 수정은 구현
//     코드라 qa-engineer 범위 밖(profile.spec.ts S6 "핸들러 수정은 구현 코드라 qa-engineer 범위
//     밖" 선례와 동일 판단) — 대신 이 spec 안에서만(overlayCanCreateProject, S2 전용)
//     window.fetch 를 addInitScript로 감싸 whoami 응답에 canCreateProject:true 를 사후
//     오버레이한다. page.route()는 활성 MSW ServiceWorker가 이미 처리한 요청을 가로채지
//     못한다는 기존 확인(issue-components.spec.ts·profile.spec.ts 등)과 달리, 이 방식은 실제
//     fetch 호출 자체를 감싸 "응답이 돌아온 뒤" 사후 가공하므로 SW 개입 여부와 무관하게
//     동작한다. addInitScript는 Page 인스턴스 단위라 이 spec 밖 다른 파일에는 전혀 영향이
//     없다(공유 handler 전역 변경 없음, 실제로 src/ 파일을 전혀 건드리지 않았다).
//   - archive/설정(danger zone) 액션은 loginAsSystemAdmin 헬퍼로 로그인한다
//     (fixtures/workflow-scheme-fixtures.ts, __bts_e2e_is_system_admin 플래그 — settings-admin-hub.spec.ts
//     ·audit-logs.spec.ts 선례와 동형). 이 spec 전체에서 일관되게 사용한다.
//   - GET /api/v1/projects 는 project-handlers.ts(stateful, archived 쿼리 지원)가 유일 정본으로
//     응답한다 — project-list-handlers.ts는 이 핸들러를 재노출하는 shim(하위호환용, vitest 4파일
//     전용)이라 src/mocks/handlers.ts에는 project-handlers.ts만 등록된다(중복 등록 없음). 시드.
//     ATLAS·MIDDLE·ZETA(활성) + NOVA(아카이브, 2026-01-01T00:00:00Z).
//   - 매 test는 독립 브라우저 context(=독립 페이지 모듈 인스턴스)로 시작해 격리된다(reset
//     헬퍼 호출 불필요). 단, 같은 페이지 안에서도 새 전체 네비게이션(page.goto)은 MSW
//     handler 모듈의 인메모리 state(projectStore 등)를 초기 시드로 되돌린다(SPA client-side
//     라우팅과 달리 문서 전체가 다시 로드되며 모듈이 재평가됨, issue-components.spec.ts 학습과
//     동형) — 그래서 각 시나리오는 "로그인 → 목적 경로로 1회 goto → 그 안에서만 상호작용"
//     구조를 유지하고, seed 이후 추가 전체 네비게이션을 하지 않는다(S2의 새 프로젝트 생성 후
//     이동은 앱 내부 useNavigate 호출 — SPA 네비게이션이라 안전).
//   - stateful mutation(archive/unarchive/rename/create) 후에는 최종 DOM 상태만으로
//     확정한다(중간 URL 관측 금지, e2e-flaky-timeout-masks-transient-url-race). sleep/
//     waitForTimeout 미사용, await expect(...).toBeVisible() 만 사용.
//   - reload 금지(store 리셋 은폐) — SPA goto/click만 사용, page.reload() 미호출.
//   - playwright-getbyrole-exact-strict-mode — '새 프로젝트'/'새 프로젝트 만들기'처럼 substring이
//     겹칠 수 있는 라벨은 항상 { exact: true }를 명시한다.
import { test, expect, type Page } from '@playwright/test'
import { loginAsSystemAdmin } from './fixtures/workflow-scheme-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// canCreateProject 오버레이 — S2(생성 성공) 전용, 이 spec 밖에는 영향 없음
// ─────────────────────────────────────────────────────────────────────────────

/**
 * whoami 응답에 canCreateProject:true 를 페이지 컨텍스트에서 오버레이한다.
 *
 * 반드시 loginAsSystemAdmin(내부적으로 page.goto('/login') 수행) 호출 **전**에 실행해야
 * addInitScript가 첫 문서 로드부터 적용되어, 로그인 흐름 중 최초 whoami fetch 시점부터
 * 패치가 적용된다. 이후 추가 네비게이션이 있어도 addInitScript는 Page의 모든 문서 로드에
 * 재적용되므로 계속 유효하다.
 *
 * @param page Playwright Page 객체
 */
async function overlayCanCreateProject(page: Page): Promise<void> {
  await page.addInitScript(() => {
    const originalFetch = window.fetch.bind(window)
    window.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
      const response = await originalFetch(input, init)
      const url = input instanceof Request ? input.url : String(input)
      if (!url.includes('/api/v1/users/me/whoami') || !response.ok) {
        return response
      }
      const body = (await response.clone().json()) as Record<string, unknown>
      return new Response(JSON.stringify({ ...body, canCreateProject: true }), {
        status: response.status,
        statusText: response.statusText,
        headers: response.headers,
      })
    }) as typeof window.fetch
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// S1 — 목록 표시
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S1 프로젝트 목록 표시 (FR-PJ PR-5 Task 8)', () => {
  test('Given SYSTEM_ADMIN 로그인 When /projects 진입 Then 활성 프로젝트(ATLAS·MIDDLE·ZETA)만 테이블 표시', async ({
    page,
  }) => {
    // Given. SYSTEM_ADMIN alice 로그인
    await loginAsSystemAdmin(page)

    // When. /projects 진입
    await page.goto('/projects')

    // Then. PageHeader 제목 '프로젝트'
    await expect(page.getByRole('heading', { name: '프로젝트', exact: true })).toBeVisible()

    // Then. 활성 시드 ATLAS·MIDDLE·ZETA 행이 모두 표시된다
    const table = page.getByRole('table')
    await expect(table).toBeVisible()

    const atlasRow = table.getByRole('row', { name: /ATLAS/ })
    await expect(atlasRow).toBeVisible()
    await expect(atlasRow).toContainText('Atlas 프로젝트')

    const middleRow = table.getByRole('row', { name: /MIDDLE/ })
    await expect(middleRow).toBeVisible()
    await expect(middleRow).toContainText('Middle 프로젝트')

    const zetaRow = table.getByRole('row', { name: /ZETA/ })
    await expect(zetaRow).toBeVisible()
    await expect(zetaRow).toContainText('Zeta 프로젝트')

    // Then. 아카이브 시드 NOVA는 기본(활성) 뷰에 표시되지 않는다
    await expect(page.getByRole('link', { name: 'NOVA', exact: true })).toHaveCount(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2 — 생성 성공
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S2 프로젝트 생성 성공 (FR-PJ PR-5 Task 8)', () => {
  test('Given canCreateProject 사용자 When 새 프로젝트 생성 폼 제출 Then 보드로 이동', async ({
    page,
  }) => {
    // Given. whoami canCreateProject:true 오버레이(이 test 전용) + SYSTEM_ADMIN 로그인
    await overlayCanCreateProject(page)
    await loginAsSystemAdmin(page)
    await page.goto('/projects')

    // When. '새 프로젝트' 링크 클릭 → /projects/new 이동
    await page.getByRole('link', { name: '새 프로젝트', exact: true }).click()
    await page.waitForURL('**/projects/new')
    await expect(page.getByRole('heading', { name: '새 프로젝트', exact: true })).toBeVisible()

    // When. key·name 입력 후 제출
    await page.getByLabel('프로젝트 키').fill('QAFLOW')
    await page.getByLabel('프로젝트 이름').fill('QA 플로우 프로젝트')
    await page.getByRole('button', { name: '프로젝트 생성', exact: true }).click()

    // Then. 생성된 프로젝트의 보드로 이동 + 보드 화면이 실제로 로드된다(빈 보드 CTA로 확정)
    await page.waitForURL('**/projects/QAFLOW/board')
    expect(new URL(page.url()).pathname).toBe('/projects/QAFLOW/board')
    await expect(page.getByText('보드가 없습니다', { exact: true })).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3 — 아카이브 토글
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S3 아카이브 토글 (FR-PJ PR-5 Task 8)', () => {
  test('Given /projects 진입(활성 뷰) When 아카이브 토글 클릭 Then 아카이브 프로젝트(NOVA)만 표시', async ({
    page,
  }) => {
    // Given. SYSTEM_ADMIN alice 로그인 + /projects 진입(기본 활성 뷰, ATLAS만 표시)
    await loginAsSystemAdmin(page)
    await page.goto('/projects')
    await expect(page.getByRole('link', { name: 'ATLAS', exact: true })).toBeVisible()
    await expect(page.getByRole('link', { name: 'NOVA', exact: true })).toHaveCount(0)

    // When. '아카이브된 프로젝트 표시' 토글 클릭
    await page.getByRole('switch', { name: '아카이브된 프로젝트 표시' }).click()

    // Then. NOVA(아카이브)만 표시, ATLAS(활성)는 숨겨진다
    const table = page.getByRole('table')
    const novaRow = table.getByRole('row', { name: /NOVA/ })
    await expect(novaRow).toBeVisible()
    await expect(novaRow).toContainText('Nova 프로젝트')
    await expect(novaRow).toContainText('아카이브')
    await expect(page.getByRole('link', { name: 'ATLAS', exact: true })).toHaveCount(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4 — 설정 name 변경
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S4 프로젝트 설정 이름 변경 (FR-PJ PR-5 Task 8)', () => {
  test('Given ATLAS 설정 진입 When 이름 수정 후 저장 Then 재조회로 새 이름 반영', async ({
    page,
  }) => {
    // Given. SYSTEM_ADMIN alice 로그인 + ATLAS 프로젝트 설정(details) 진입
    await loginAsSystemAdmin(page)
    await page.goto('/projects/ATLAS/settings/details')
    await expect(page.getByRole('heading', { name: 'Atlas 프로젝트', exact: true })).toBeVisible()

    // When. 이름 입력값을 비우고 새 이름으로 채운 뒤 저장
    const nameInput = page.getByLabel('프로젝트 이름')
    await nameInput.fill('Atlas 프로젝트 개편')
    await page.getByRole('button', { name: '저장', exact: true }).click()

    // Then. 저장 성공 안내 표시
    await expect(page.getByRole('status')).toHaveText('이름이 변경되었습니다.')

    // Then. 단건 재조회(invalidate) 결과로 PageHeader 제목이 새 이름으로 반영된다
    await expect(
      page.getByRole('heading', { name: 'Atlas 프로젝트 개편', exact: true }),
    ).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S5 — 아카이브/해제 왕복
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S5 아카이브/해제 왕복 (FR-PJ PR-5 Task 8)', () => {
  test('Given ATLAS 설정 진입 When 아카이브 후 해제 Then 매 단계 최종 상태로 반영', async ({
    page,
  }) => {
    // Given. SYSTEM_ADMIN alice 로그인 + ATLAS 프로젝트 설정(details) 진입(활성 상태)
    await loginAsSystemAdmin(page)
    await page.goto('/projects/ATLAS/settings/details')
    await expect(page.getByRole('heading', { name: 'Atlas 프로젝트', exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: '아카이브', exact: true })).toBeVisible()

    // When. 아카이브 클릭 → 인라인 확인 → 확인 클릭
    await page.getByRole('button', { name: '아카이브', exact: true }).click()
    await expect(page.getByText('이 프로젝트를 아카이브할까요?', { exact: true })).toBeVisible()
    await page.getByRole('button', { name: '확인', exact: true }).click()

    // Then. 아카이브 반영 — danger zone 버튼이 '아카이브 해제'로 전환 + 이름 폼이 잠긴다
    await expect(page.getByRole('button', { name: '아카이브 해제', exact: true })).toBeVisible()
    await expect(
      page.getByText('아카이브된 프로젝트는 설정을 변경할 수 없습니다', { exact: true }),
    ).toBeVisible()

    // When. 아카이브 해제 클릭
    await page.getByRole('button', { name: '아카이브 해제', exact: true }).click()

    // Then. 해제 반영 — danger zone 버튼이 다시 '아카이브'로 전환 + 이름 폼 잠금 해제
    await expect(page.getByRole('button', { name: '아카이브', exact: true })).toBeVisible()
    await expect(
      page.getByText('아카이브된 프로젝트는 설정을 변경할 수 없습니다', { exact: true }),
    ).toHaveCount(0)
  })
})
