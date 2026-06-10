// FR-VR-04 E2E — 버전 릴리즈 노트 미리보기 + 클립보드 복사 happy path
//
// 선결 조건.
//   - T5/T6 완료: versionHandlers 에 getReleaseNotesHandler, VersionRow 에 "릴리즈 노트" 버튼,
//     ReleaseNotesDialog 컴포넌트가 배선됨.
//   - MSW versionStore(stateful) + releaseNotesSeedStore 활용.
//
// 커버 시나리오.
//   RN-1. 버전 행의 "릴리즈 노트" 버튼 클릭 → dialog 열림 + markdown 미리보기 노출
//   RN-2. 복사 버튼 클릭 → "복사됨" 표시 확인 (클립보드 권한 granted)
//   RN-3. dialog 닫기 → dialog 사라짐
//
// 교훈 반영.
//   - e2e-loginasalice-fixture-fr-au-07-regression: session-fixtures.ts 의 loginAsAlice 사용
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 금지 — playwright.config.ts 그대로
//   - msw-derived-behavior-shared-store: releaseNotesSeedStore는 브라우저 내 fetch로 시드
//     (addInitScript 아닌 page.evaluate + 전용 시드 엔드포인트 대신 versionStore 생성 후
//     X-MSW-Seed-ReleaseNotes 헤더 패턴 사용)
//   - msw-mutation-stateful-refetch: versionStore 생성 후 목록 refetch 반영 대기
//   - playwright-getbyrole-exact-strict-mode: aria-label("버전명 + 라벨") 한정 셀렉터
//   - worktree-stale-base-rebase-and-e2e-msw-traps: dialog 로딩 대기(toBeVisible)
//   - ui-pr-defer-e2e-regression-latent: 기존 version E2E 회귀 동반 실행 필수
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/session-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

const PROJECT_KEY = 'ATLAS'
const SETTINGS_URL = `/projects/${PROJECT_KEY}/settings/versions`

/** version-labels.ts 에서 가져온 라벨 (import 없이 문자열 상수로 관리) */
const labels = {
  pageHeading: '버전 관리',
  addButton: '버전 추가',
  saveButton: '저장',
  nameLabel: '이름',
  // 릴리즈 노트 관련 라벨 (versionLabels.actions.releaseNotesButton)
  releaseNotesButton: '릴리즈 노트',
  // 다이얼로그 콘텐츠 aria-label (versionLabels.releaseNotes.contentAriaLabel)
  contentAriaLabel: '릴리즈 노트 내용',
  // 복사 버튼 (versionLabels.releaseNotes.copyButtonAriaLabel)
  copyButtonAriaLabel: '릴리즈 노트 복사',
  // 복사 완료 텍스트 (versionLabels.releaseNotes.copiedText)
  copiedText: '복사됨',
  // 닫기 버튼 (versionLabels.releaseNotes.closeButton)
  closeButton: '닫기',
}

// ─────────────────────────────────────────────────────────────────────────────
// E2E 테스트용 릴리즈 노트 시드 픽스처
// ─────────────────────────────────────────────────────────────────────────────

/** E2E 전용 릴리즈 노트 markdown 샘플 — 이슈 키/제목 포함 */
const RELEASE_NOTES_MARKDOWN = `## VR04-E2E-1.0.0 릴리즈 노트

### Bug

- ATLAS-10 로그인 오류 수정 (Fixed)

### Story

- ATLAS-11 버전 관리 UI 추가`

const RELEASE_NOTES_ISSUE_COUNT = 2

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — MSW versionStore 초기화
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MSW versionStore 를 초기화한다.
 * X-MSW-Reset-Versions: true 헤더를 포함해 GET 목록을 호출하면
 * listVersionsHandler 가 resetVersionStore() 를 실행한다.
 */
async function resetVersionStore(page: import('@playwright/test').Page): Promise<void> {
  await page.evaluate(async (projectKey: string) => {
    await fetch(`/api/v1/projects/${projectKey}/versions`, {
      headers: { 'X-MSW-Reset-Versions': 'true' },
    })
  }, PROJECT_KEY)
}

/**
 * MSW releaseNotesSeedStore 에 릴리즈 노트 시드를 등록한다.
 *
 * version-handlers.ts 의 getReleaseNotesHandler 는 X-MSW-Seed-ReleaseNotes: true 헤더를
 * 감지하면 X-MSW-Seed-Markdown-B64 (Base64) / X-MSW-Seed-Issue-Count 헤더값을
 * releaseNotesSeedStore 에 등록한다.
 * (메모리 msw-derived-behavior-shared-store — 브라우저 내 공유 store 에서 읽기)
 *
 * HTTP 헤더는 ISO-8859-1 만 허용하므로 한글 markdown 은 Base64 로 인코딩해 전달한다.
 */
async function seedReleaseNotesInStore(
  page: import('@playwright/test').Page,
  versionId: string,
  markdown: string,
  issueCount: number,
): Promise<void> {
  await page.evaluate(
    async ([projectKey, id, md, count]: [string, string, string, number]) => {
      // 한글 포함 문자열을 Base64 로 인코딩 (ISO-8859-1 헤더 제약 우회)
      const mdB64 = btoa(unescape(encodeURIComponent(md)))
      const res = await fetch(`/api/v1/projects/${projectKey}/versions/${id}/release-notes`, {
        method: 'GET',
        headers: {
          'X-MSW-Seed-ReleaseNotes': 'true',
          'X-MSW-Seed-Markdown-B64': mdB64,
          'X-MSW-Seed-Issue-Count': String(count),
        },
      })
      if (!res.ok) {
        console.warn(`릴리즈 노트 시드 등록 실패 (status=${res.status}) — 기본값 사용`)
      }
    },
    [PROJECT_KEY, versionId, markdown, issueCount] as [string, string, string, number],
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 suite
// ─────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────
// UI 헬퍼 — 버전 생성 (version-management.spec.ts / version-status.spec.ts 동형)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * VersionFormDialog 를 통해 버전을 생성하고 목록에 이름이 나타날 때까지 대기한다.
 * 이미 SETTINGS_URL 에 있는 상태에서 호출해야 한다.
 */
async function createVersionViaUI(
  page: import('@playwright/test').Page,
  name: string,
): Promise<void> {
  await page.getByRole('button', { name: labels.addButton }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await page.getByLabel(labels.nameLabel).fill(name)
  await page.getByRole('button', { name: labels.saveButton }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  // msw-mutation-stateful-refetch 교훈 — invalidateQueries 후 목록에 이름 노출 확인
  await expect(page.getByText(name, { exact: true })).toBeVisible()
}

test.describe('버전 릴리즈 노트 (FR-VR-04)', () => {
  test.beforeEach(async ({ page }) => {
    // alice 로 로그인 후 버전 설정 페이지 진입 (version-management.spec.ts / version-status.spec.ts 패턴)
    await loginAsAlice(page)
    await page.goto(SETTINGS_URL)
    await expect(page.getByRole('heading', { name: labels.pageHeading, level: 1 })).toBeVisible()
    // versionStore 격리 — 각 테스트 시작 시 초기화
    await resetVersionStore(page)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // RN-1 — 릴리즈 노트 dialog 열림 + markdown 미리보기 노출
  //
  // Given  "VR04-E2E-1.0.0" 버전이 목록에 있고 releaseNotesSeedStore 에 시드됨
  // When   행의 "릴리즈 노트" 버튼 클릭
  // Then   dialog 열림 + 릴리즈 노트 내용 영역이 노출됨
  //        markdown 본문(이슈 키/제목)이 pre 태그 안에 표시됨
  // ───────────────────────────────────────────────────────────────────────────
  test('RN-1 dialog 열림 — "릴리즈 노트" 클릭 시 markdown 미리보기 노출', async ({ page }) => {
    const versionName = 'VR04-E2E-1.0.0'

    // 사전조건: UI 를 통해 버전 생성 (이미 SETTINGS_URL 에 있음 — beforeEach)
    await createVersionViaUI(page, versionName)

    // 생성된 버전의 id 를 GET 목록 API 로 조회 (seedReleaseNotes 호출에 필요)
    const versionId = await page.evaluate(
      async ([projectKey, name]: [string, string]) => {
        const res = await fetch(`/api/v1/projects/${projectKey}/versions`)
        if (!res.ok) throw new Error(`목록 조회 실패: ${res.status}`)
        const json = await res.json() as { data: Array<{ id: string; name: string }> }
        const found = json.data.find((v) => v.name === name)
        if (!found) throw new Error(`버전을 찾을 수 없음: ${name}`)
        return found.id
      },
      [PROJECT_KEY, versionName] as [string, string],
    )

    // 릴리즈 노트 시드 등록 — Base64 인코딩으로 한글 포함 마크다운을 헤더에 전달
    await seedReleaseNotesInStore(page, versionId, RELEASE_NOTES_MARKDOWN, RELEASE_NOTES_ISSUE_COUNT)

    // 버전 행의 "릴리즈 노트" 버튼 — aria-label="VR04-E2E-1.0.0 릴리즈 노트"
    // (playwright-getbyrole-exact-strict-mode — 버전명 포함 aria-label 한정)
    await page.getByRole('button', { name: `${versionName} ${labels.releaseNotesButton}` }).click()

    // dialog 열림 대기 (worktree-stale-base-rebase-and-e2e-msw-traps — dialog 로딩 대기)
    await expect(page.getByRole('dialog')).toBeVisible()

    // 릴리즈 노트 내용 영역 렌더 대기 — role="region" aria-label="릴리즈 노트 내용"
    const contentRegion = page.getByRole('region', { name: labels.contentAriaLabel })
    await expect(contentRegion).toBeVisible()

    // markdown 본문에 이슈 키가 포함돼야 함
    await expect(contentRegion.locator('pre')).toContainText('ATLAS-10')
    await expect(contentRegion.locator('pre')).toContainText('ATLAS-11')
  })

  // ───────────────────────────────────────────────────────────────────────────
  // RN-2 — 복사 버튼 클릭 → "복사됨" 표시 확인
  //
  // Given  "VR04-E2E-2.0.0" 버전의 릴리즈 노트 dialog 가 열려있음
  //        클립보드 권한 granted (context.grantPermissions)
  // When   "릴리즈 노트 복사" 버튼 클릭
  // Then   버튼 텍스트가 "복사됨"으로 변경됨
  // ───────────────────────────────────────────────────────────────────────────
  test('RN-2 복사 버튼 — 클릭 시 "복사됨" 표시', async ({ page, context }) => {
    // 클립보드 권한 부여 (plan devex-review 명시 — localhost는 secure-context)
    await context.grantPermissions(['clipboard-read', 'clipboard-write'])

    const versionName = 'VR04-E2E-2.0.0'

    // 사전조건: UI 를 통해 버전 생성 (이미 SETTINGS_URL 에 있음 — beforeEach)
    await createVersionViaUI(page, versionName)

    // "릴리즈 노트" 버튼 클릭 → dialog 열기
    await page.getByRole('button', { name: `${versionName} ${labels.releaseNotesButton}` }).click()
    await expect(page.getByRole('dialog')).toBeVisible()

    // 릴리즈 노트 내용 로드 대기
    const contentRegion = page.getByRole('region', { name: labels.contentAriaLabel })
    await expect(contentRegion).toBeVisible()

    // 복사 버튼 클릭 — aria-label="릴리즈 노트 복사"
    const copyButton = page.getByRole('button', { name: labels.copyButtonAriaLabel })
    await expect(copyButton).toBeVisible()
    await copyButton.click()

    // "복사됨" 텍스트로 변경됨 (msw-mutation-stateful-refetch — 클립보드 write 성공 시)
    await expect(copyButton).toHaveText(labels.copiedText)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // RN-3 — dialog 닫기
  //
  // Given  "VR04-E2E-3.0.0" 버전의 릴리즈 노트 dialog 가 열려있음
  // When   "닫기" 버튼 클릭
  // Then   dialog 가 DOM 에서 사라짐
  // ───────────────────────────────────────────────────────────────────────────
  test('RN-3 dialog 닫기 — "닫기" 버튼 클릭 시 dialog 사라짐', async ({ page }) => {
    const versionName = 'VR04-E2E-3.0.0'

    // 사전조건: UI 를 통해 버전 생성 (이미 SETTINGS_URL 에 있음 — beforeEach)
    await createVersionViaUI(page, versionName)

    // "릴리즈 노트" 버튼 클릭 → dialog 열기
    await page.getByRole('button', { name: `${versionName} ${labels.releaseNotesButton}` }).click()
    await expect(page.getByRole('dialog')).toBeVisible()

    // "닫기" 버튼 클릭
    await page.getByRole('button', { name: labels.closeButton, exact: true }).click()

    // dialog 사라짐 대기
    await expect(page.getByRole('dialog')).toHaveCount(0)
  })
})
