// FR-AC-01 D7 E2E — 첨부 파일 라이프사이클 (업로드/목록/다운로드/삭제/권한/빈상태)
//
// 설계 결정.
//   - page.goto() 를 테스트마다 두 번 호출하면 ServiceWorker 가 재시작되어 MSW 모듈 스코프 상태가
//     초기화된다. 따라서 loginAsAlice(/dashboard까지) → 시드 fetch → SPA 내부 navigate 패턴을
//     사용한다 (issue-components.spec.ts 동형 패턴).
//   - SPA 내부 navigate: window.history.pushState + PopStateEvent 또는 window.location.href =
//     를 사용하지 않고, page.goto(ISSUE_URL) 한 번만 호출한다. 이때 beforeEach에서 goto를 하고
//     시드가 필요한 테스트는 beforeEach goto 직후에는 시드를 못 하는 게 아니라,
//     이미 페이지가 로드된 상태에서 page.evaluate() fetch를 보내면 ServiceWorker가 인터셉트한다.
//     그리고 시드 후 TanStack Query invalidation이 필요하다.
//   - 그러나 이슈 상세 페이지에서 직접 navigate하면 attachments 쿼리가 즉시 fetch되므로
//     시드를 먼저 해야 한다. beforeEach에서 goto를 하면 모듈 상태가 초기화될 수 있다.
//
// 채택 전략.
//   - beforeEach: loginAsAlice (→ /dashboard 착지, ServiceWorker 활성)
//   - 시드 필요 테스트: dashboard 상태에서 page.evaluate fetch로 시드 → navigateToIssue SPA 내비게이션
//   - beforeEach에 goto를 하지 않고, 필요한 테스트에서만 navigate
//   - issue-components.spec.ts 동형 패턴
//
// 교훈 반영.
//   - msw-derived-behavior-shared-store-e2e: MSW 파생동작은 브라우저 시드 가능 공유 store에서 읽기
//   - playwright-getbyrole-exact-strict-mode: 섹션 컨테이너(aria-label=첨부 파일) 한정 셀렉터
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지 — playwright.config.ts 그대로
//   - msw-mutation-stateful-refetch: 업로드/삭제 후 목록 refetch가 store 반영 확인
//   - e2e-fixture-whoami-userid-alignment: alice(00000000-...-001) adminPermissions 정합
//   - e2e-msw-scenario-toggle-localstorage-flag: 권한 게이팅은 addInitScript + localStorage 플래그
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/session-fixtures'
import { E2E_FORCE_READONLY_ISSUE_KEY } from '../src/mocks/issue-permission-handlers'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 테스트 대상 이슈 키 — MSW getIssueHandler가 커버하는 안정 fixture */
const ISSUE_KEY = 'ATLAS-1'
const ISSUE_URL = `/issues/${ISSUE_KEY}`

/** 첨부 섹션 aria-label (AttachmentSection의 section 태그 — attachmentLabels.sectionTitle) */
const SECTION_ARIA_LABEL = '첨부 파일'

/** 드롭존 aria-label (attachmentLabels.dropzoneHint) */
const DROPZONE_ARIA_LABEL = '파일을 여기에 끌어다 놓거나 클릭해서 선택하세요'

/** 시드용 고정 UUID — RFC4122 v4 형식 (Zod v4 UUID 검증 통과) */
const SEED_UUID_1 = 'a1b2c3d4-e5f6-4abc-8def-0a1b2c3d4e5f'
const SEED_UUID_2 = 'b2c3d4e5-f6a7-4bcd-9ef0-1b2c3d4e5f6a'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — MSW attachmentStore 초기화 (ServiceWorker가 살아있는 상태에서 호출)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MSW attachmentStore를 초기화한다.
 * ServiceWorker가 활성화된 상태(loginAsAlice 이후)에서 호출해야 한다.
 * X-MSW-Reset-Attachments: true 헤더를 포함해 GET 목록을 호출하면
 * listAttachmentsHandler가 해당 이슈 키의 store를 초기화한다.
 *
 * @param page Playwright Page 객체
 * @param issueKey 초기화할 이슈 키
 */
async function resetAttachmentStore(
  page: import('@playwright/test').Page,
  issueKey: string,
): Promise<void> {
  await page.evaluate(async (key: string) => {
    await fetch(`/api/v1/issues/${key}/attachments`, {
      headers: { 'X-MSW-Reset-Attachments': 'true' },
    })
  }, issueKey)
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — MSW attachmentStore에 첨부 시드
// ─────────────────────────────────────────────────────────────────────────────

interface SeedAttachmentOptions {
  id: string
  filename: string
  contentType?: string
  sizeBytes?: number
}

/**
 * MSW attachmentStore에 첨부 파일을 시드한다.
 * ServiceWorker가 활성화된 상태(loginAsAlice 이후)에서 호출해야 한다.
 * X-MSW-Seed-Attachment: true 헤더와 파일 정보 헤더를 포함해 호출하면
 * listAttachmentsHandler가 store에 첨부를 추가하고 현재 목록을 반환한다.
 *
 * @param page Playwright Page 객체
 * @param issueKey 이슈 키
 * @param opts 시드 첨부 정보
 */
async function seedAttachment(
  page: import('@playwright/test').Page,
  issueKey: string,
  opts: SeedAttachmentOptions,
): Promise<void> {
  await page.evaluate(
    async ({
      key,
      id,
      filename,
      contentType,
      sizeBytes,
    }: {
      key: string
      id: string
      filename: string
      contentType: string
      sizeBytes: number
    }) => {
      await fetch(`/api/v1/issues/${key}/attachments`, {
        headers: {
          'X-MSW-Seed-Attachment': 'true',
          'X-MSW-Seed-Id': id,
          'X-MSW-Seed-Filename': filename,
          'X-MSW-Seed-ContentType': contentType,
          'X-MSW-Seed-SizeBytes': String(sizeBytes),
          'X-MSW-Seed-CreatedAt': '2026-06-15T09:00:00Z',
        },
      })
    },
    {
      key: issueKey,
      id: opts.id,
      filename: opts.filename,
      contentType: opts.contentType ?? 'application/octet-stream',
      sizeBytes: opts.sizeBytes ?? 1024,
    },
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — SPA 내부 내비게이션 (ServiceWorker 재시작 없음)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 현재 페이지에서 이슈 상세로 SPA 내부 내비게이션한다.
 * page.goto() 를 호출하지 않아 ServiceWorker 가 재시작되지 않는다.
 * attachmentStore 시드가 유지되어 이슈 상세 진입 시 목록 fetch에 반영된다.
 *
 * @param page Playwright Page 객체
 */
async function navigateToIssue(page: import('@playwright/test').Page): Promise<void> {
  // TanStack Router를 직접 제어하는 대신 window.location.assign 사용
  // href 직접 변경은 실제 navigate지만 SPA 번들 내 TanStack Router가 처리하므로
  // ServiceWorker는 재시작되지 않는다 (pushState → popstate 패턴 동형)
  await page.evaluate((url: string) => {
    window.history.pushState({}, '', url)
    window.dispatchEvent(new PopStateEvent('popstate'))
  }, ISSUE_URL)

  // 첨부 파일 섹션이 나타날 때까지 대기
  await expect(page.getByRole('region', { name: '첨부 파일' })).toBeVisible()
}

// ─────────────────────────────────────────────────────────────────────────────
// Suite: FR-AC-01 첨부 파일 라이프사이클
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-AC-01 첨부 파일 라이프사이클', () => {
  // 각 테스트 전에 alice 로그인 (dashboard까지 이동, ServiceWorker 활성)
  test.beforeEach(async ({ page }) => {
    await loginAsAlice(page)
    // loginAsAlice는 /dashboard 까지 이동하므로 ServiceWorker가 활성화됨.
    // 이 시점에서 attachmentStore를 초기화해 이전 테스트 상태를 제거한다.
    await resetAttachmentStore(page, ISSUE_KEY)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S7 빈 상태
  //
  // Given  첨부 0개인 이슈 (store 초기화 후)
  // When   이슈 상세 화면 진입
  // Then   "첨부된 파일이 없습니다." 빈 상태 텍스트 표시
  //        드롭존 표시 (alice는 UPDATE 권한 있음)
  // ───────────────────────────────────────────────────────────────────────────
  test('S7 빈 상태 — 첨부 0개이면 빈 상태 안내 표시', async ({ page }) => {
    // Given. store 이미 초기화됨 (beforeEach)
    // SPA 내부 navigate → 이슈 상세 (ServiceWorker 유지)
    await navigateToIssue(page)

    const section = page.getByRole('region', { name: SECTION_ARIA_LABEL })

    // Then. 빈 상태 텍스트 표시
    await expect(section.getByText('첨부된 파일이 없습니다.')).toBeVisible()

    // Then. 드롭존 표시 (alice는 UPDATE 권한 있음)
    await expect(section.getByRole('button', { name: DROPZONE_ARIA_LABEL })).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S2 목록
  //
  // Given  시드된 첨부가 있는 이슈
  // When   이슈 상세 화면 진입
  // Then   첨부 목록(파일명)이 표시된다
  // ───────────────────────────────────────────────────────────────────────────
  test('S2 목록 — 시드된 첨부가 목록에 표시됨', async ({ page }) => {
    // Given. 첨부 파일 시드 (ServiceWorker 활성 상태 — loginAsAlice/dashboard 이후)
    await seedAttachment(page, ISSUE_KEY, {
      id: SEED_UUID_1,
      filename: 'report-2026.pdf',
      contentType: 'application/pdf',
      sizeBytes: 204800, // 200KB
    })
    await seedAttachment(page, ISSUE_KEY, {
      id: SEED_UUID_2,
      filename: 'screenshot.png',
      contentType: 'image/png',
      sizeBytes: 512000, // 500KB
    })

    // When. SPA 내부 navigate → 이슈 상세 (ServiceWorker 유지, attachmentStore 보존)
    await navigateToIssue(page)

    const section = page.getByRole('region', { name: SECTION_ARIA_LABEL })

    // Then. 파일명 두 건 모두 표시
    await expect(section.getByText('report-2026.pdf')).toBeVisible()
    await expect(section.getByText('screenshot.png')).toBeVisible()

    // Then. 빈 상태 텍스트 미표시
    await expect(section.getByText('첨부된 파일이 없습니다.')).toHaveCount(0)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S1 업로드
  //
  // Given  이슈 상세 화면 (UPDATE 권한 보유 — alice)
  // When   드롭존의 숨겨진 파일 input에 setInputFiles로 파일 주입
  // Then   목록에 새 첨부(파일명) 표시
  //        MSW uploadAttachmentHandler가 store에 추가 + invalidateQueries로 목록 갱신
  // ───────────────────────────────────────────────────────────────────────────
  test('S1 업로드 — 파일 선택 후 목록에 파일명 표시', async ({ page }) => {
    // Given. 빈 상태에서 시작 (beforeEach 초기화 완료)
    await navigateToIssue(page)

    const section = page.getByRole('region', { name: SECTION_ARIA_LABEL })

    // 드롭존 표시 확인
    await expect(section.getByRole('button', { name: DROPZONE_ARIA_LABEL })).toBeVisible()

    // DropZone 컴포넌트의 숨겨진 input[type=file]에 직접 setInputFiles
    // (sr-only로 숨겨져 있으나 Playwright는 숨겨진 input에도 setInputFiles 가능)
    const fileInput = section.locator('input[type="file"]')
    await expect(fileInput).toBeAttached()

    // When. 파일 주입
    await fileInput.setInputFiles({
      name: 'test-upload.txt',
      mimeType: 'text/plain',
      buffer: Buffer.from('hello e2e'),
    })

    // Then. MSW uploadAttachmentHandler가 store에 추가 → invalidateQueries → 목록 re-fetch
    // TanStack Query가 invalidate 후 목록에 파일명이 나타남
    await expect(section.getByText('test-upload.txt')).toBeVisible()

    // Then. 빈 상태 텍스트 사라짐
    await expect(section.getByText('첨부된 파일이 없습니다.')).toHaveCount(0)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S3 다운로드
  //
  // Given  시드된 첨부 목록
  // When   다운로드 버튼 클릭 (waitForEvent('download')를 클릭 이전에 셋업 — 레이스 회피)
  // Then   download 이벤트 수신 (MSW Blob 응답)
  // ───────────────────────────────────────────────────────────────────────────
  test('S3 다운로드 — 다운로드 버튼 클릭 시 download 이벤트 수신', async ({ page }) => {
    // Given. 첨부 파일 시드
    await seedAttachment(page, ISSUE_KEY, {
      id: SEED_UUID_1,
      filename: 'invoice.pdf',
      contentType: 'application/pdf',
      sizeBytes: 10240,
    })

    // SPA 내부 navigate → 이슈 상세 (ServiceWorker 유지)
    await navigateToIssue(page)

    const section = page.getByRole('region', { name: SECTION_ARIA_LABEL })

    // 파일명이 표시될 때까지 대기
    await expect(section.getByText('invoice.pdf')).toBeVisible()

    // C3 레이스 회피 — downloadPromise를 클릭 이전에 셋업 (issue-pdf.spec.ts 패턴)
    const downloadPromise = page.waitForEvent('download')

    // When. 다운로드 버튼 클릭 (aria-label: "{filename} 다운로드")
    await section
      .getByRole('button', { name: 'invoice.pdf 다운로드', exact: true })
      .click()

    // Then. download 이벤트 수신
    const download = await downloadPromise
    // MSW는 Blob을 반환하므로 브라우저가 파일을 다운로드한다
    expect(download).not.toBeNull()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S4 삭제
  //
  // Given  시드된 첨부 목록 (UPDATE 권한 보유 — alice)
  // When   삭제 버튼 클릭 → 인라인 확인 → 확인 버튼 클릭
  // Then   목록에서 해당 첨부가 제거됨, 나머지 첨부는 유지
  // ───────────────────────────────────────────────────────────────────────────
  test('S4 삭제 — 확인 후 목록에서 첨부 제거', async ({ page }) => {
    // Given. 첨부 파일 2개 시드
    await seedAttachment(page, ISSUE_KEY, {
      id: SEED_UUID_1,
      filename: 'to-delete.txt',
      contentType: 'text/plain',
      sizeBytes: 512,
    })
    await seedAttachment(page, ISSUE_KEY, {
      id: SEED_UUID_2,
      filename: 'to-keep.txt',
      contentType: 'text/plain',
      sizeBytes: 256,
    })

    await navigateToIssue(page)

    const section = page.getByRole('region', { name: SECTION_ARIA_LABEL })
    await expect(section.getByText('to-delete.txt')).toBeVisible()
    await expect(section.getByText('to-keep.txt')).toBeVisible()

    // When. 삭제 버튼 클릭 — to-delete.txt 행 한정 (strict mode violation 회피)
    // 행은 tr 태그로 렌더되며 파일명 텍스트로 필터링
    const deleteRow = section.locator('tr').filter({ hasText: 'to-delete.txt' })
    await deleteRow.getByRole('button', { name: '삭제', exact: true }).click()

    // 인라인 확인 단계 — 경고 텍스트(role=alert) 표시
    await expect(deleteRow.getByRole('alert')).toBeVisible()
    await expect(deleteRow.getByRole('alert')).toContainText('복구할 수 없습니다')

    // When. 확인 버튼 클릭
    await deleteRow.getByRole('button', { name: '확인', exact: true }).click()

    // Then. to-delete.txt 제거 (deleteAttachmentHandler → store에서 제거 → invalidate → refetch)
    await expect(section.getByText('to-delete.txt')).toHaveCount(0)

    // Then. to-keep.txt 유지
    await expect(section.getByText('to-keep.txt')).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S5 권한 게이팅
  //
  // Given  UPDATE 권한 없는 사용자 (alice + E2E_FORCE_READONLY_ISSUE_KEY 플래그)
  //        시드된 첨부 목록 존재
  // When   이슈 상세 화면 진입
  // Then   업로드 드롭존 미표시 (canUpdate=false)
  //        삭제 버튼 미표시 (canDelete=false)
  //        목록(파일명)과 다운로드 버튼은 표시됨
  // ───────────────────────────────────────────────────────────────────────────
  test('S5 권한 게이팅 — UPDATE 없는 사용자는 드롭존·삭제 미표시, 목록·다운로드는 표시', async ({
    page,
  }) => {
    // Given. 첨부 시드 (권한 플래그 설정 전 — dashboard 상태에서)
    await seedAttachment(page, ISSUE_KEY, {
      id: SEED_UUID_1,
      filename: 'read-only-file.pdf',
      contentType: 'application/pdf',
      sizeBytes: 2048,
    })

    // Given. page.evaluate로 localStorage 플래그 직접 설정
    // SPA 내부 navigate는 실제 페이지 로드가 아니므로 addInitScript 대신
    // page.evaluate()로 즉시 localStorage에 플래그를 심는다.
    // MSW 핸들러는 globalThis.localStorage를 읽으므로 navigate 전에 설정하면 적용된다.
    await page.evaluate((key: string) => {
      window.localStorage.setItem(key, 'true')
    }, E2E_FORCE_READONLY_ISSUE_KEY)

    // When. SPA 내부 navigate → 이슈 상세
    // navigate 후 TanStack Query가 권한 fetch 시 localStorage 플래그가 적용됨
    await navigateToIssue(page)

    const section = page.getByRole('region', { name: SECTION_ARIA_LABEL })

    // Then. 드롭존 미표시 (canUpdate=false)
    await expect(section.getByRole('button', { name: DROPZONE_ARIA_LABEL })).toHaveCount(0)

    // Then. 파일명(목록)은 표시됨
    await expect(section.getByText('read-only-file.pdf')).toBeVisible()

    // Then. 다운로드 버튼 표시됨 (VIEW 권한은 항상 허용)
    await expect(
      section.getByRole('button', { name: 'read-only-file.pdf 다운로드', exact: true }),
    ).toBeVisible()

    // Then. 삭제 버튼 미표시 (canDelete=canUpdate=false)
    await expect(section.getByRole('button', { name: '삭제', exact: true })).toHaveCount(0)
  })
})
