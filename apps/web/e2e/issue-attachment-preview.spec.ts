// FR-AC-02 D7 E2E — 첨부 파일 미리보기 모달 (image/pdf/video/화이트리스트 밖/에러/닫기)
//
// 설계 결정.
//   - issue-attachments.spec.ts 의 시드/내비게이션 패턴을 동일하게 재사용한다.
//     (loginAsAlice → dashboard 착지 → page.evaluate fetch 시드 → pushState 내비게이션)
//   - MSW attachmentStore 시드를 SPA 내부 이동 전에 심어야 이슈 상세 진입 시
//     목록 fetch에 즉시 반영된다 (msw-derived-behavior-shared-store-e2e 교훈).
//   - beforeEach 에서 attachmentStore 를 초기화해 테스트 간 상태 격리를 보장한다.
//   - 셀렉터는 섹션 컨테이너(region role) 한정으로 strict mode violation 회피
//     (playwright-getbyrole-exact-strict-mode 교훈).
//   - video/webm 은 Chromium 코덱 불확실이므로 video/mp4 로만 테스트.
//
// S6 처리.
//   다운로드 핸들러가 존재하지 않는 ID에 대해 404를 반환하도록 MSW가 이미
//   구성되어 있다(att === undefined → 404). 단, 시드된 첨부 ID 가 아닌 다른
//   ID로 모달을 직접 열 방법이 E2E에서 없다(컴포넌트 state 직접 주입 불가).
//   → 별도 MSW 핸들러 override 방식(localStorage 플래그 + addInitScript)으로
//     구현 가능하지만, 페이지 리로드를 수반하므로 SPA 내부 이동 패턴과 충돌한다.
//   → SKIP 처리 후 사유를 보고.
//
// 교훈 반영.
//   - msw-derived-behavior-shared-store-e2e
//   - playwright-getbyrole-exact-strict-mode
//   - e2e-msw-serviceworker-block (serviceWorkers:'block' 절대 금지)
//   - msw-mutation-stateful-refetch
//   - e2e-fixture-whoami-userid-alignment
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/session-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

const ISSUE_KEY = 'ATLAS-1'
const ISSUE_URL = `/issues/${ISSUE_KEY}`

/** 첨부 섹션 aria-label */
const SECTION_ARIA_LABEL = '첨부 파일'

/** 미리보기 시드용 RFC4122 v4 UUID (Zod v4 UUID 검증 통과 형식) */
const SEED_PNG_ID = 'c1d2e3f4-a5b6-4cde-8f01-2a3b4c5d6e7f'
const SEED_PDF_ID = 'd2e3f4a5-b6c7-4def-9012-3b4c5d6e7f8a'
const SEED_MP4_ID = 'e3f4a5b6-c7d8-4ef0-a123-4c5d6e7f8a9b'
const SEED_ZIP_ID = 'f4a5b6c7-d8e9-4f01-b234-5d6e7f8a9b0c'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — MSW attachmentStore 초기화
// ─────────────────────────────────────────────────────────────────────────────

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
// 헬퍼 — MSW attachmentStore 에 첨부 시드
// ─────────────────────────────────────────────────────────────────────────────

interface SeedAttachmentOptions {
  id: string
  filename: string
  contentType: string
  sizeBytes?: number
}

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
      contentType: opts.contentType,
      sizeBytes: opts.sizeBytes ?? 1024,
    },
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — SPA 내부 내비게이션 (ServiceWorker 재시작 없음)
// ─────────────────────────────────────────────────────────────────────────────

async function navigateToIssue(page: import('@playwright/test').Page): Promise<void> {
  await page.evaluate((url: string) => {
    window.history.pushState({}, '', url)
    window.dispatchEvent(new PopStateEvent('popstate'))
  }, ISSUE_URL)

  await expect(page.getByRole('region', { name: SECTION_ARIA_LABEL })).toBeVisible()
}

// ─────────────────────────────────────────────────────────────────────────────
// Suite: FR-AC-02 첨부 파일 미리보기
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-AC-02 첨부 파일 미리보기', () => {
  test.beforeEach(async ({ page }) => {
    await loginAsAlice(page)
    await resetAttachmentStore(page, ISSUE_KEY)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S1 이미지 미리보기
  //
  // Given  image/png 첨부가 시드된 이슈
  // When   "미리보기" 버튼 클릭
  // Then   dialog role 요소 열림 + 내부에 <img alt={filename}> 존재
  //        img.src 가 blob: 으로 시작함
  // ───────────────────────────────────────────────────────────────────────────
  test('S1 이미지 — image/png 미리보기 버튼 클릭 시 모달 내 img[blob:]이 표시됨', async ({
    page,
  }) => {
    // Given. image/png 첨부 시드
    await seedAttachment(page, ISSUE_KEY, {
      id: SEED_PNG_ID,
      filename: 'screenshot.png',
      contentType: 'image/png',
      sizeBytes: 512,
    })

    // SPA 내부 navigate (ServiceWorker 유지)
    await navigateToIssue(page)

    const section = page.getByRole('region', { name: SECTION_ARIA_LABEL })
    await expect(section.getByText('screenshot.png')).toBeVisible()

    // When. 미리보기 버튼 클릭 (행 컨테이너 한정 — strict mode 회피)
    const row = section.locator('tr').filter({ hasText: 'screenshot.png' })
    await row.getByRole('button', { name: 'screenshot.png 미리보기', exact: true }).click()

    // Then. 모달 열림
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()

    // Then. 모달 내 img[alt=screenshot.png] 존재 + src가 blob: 으로 시작
    const img = dialog.getByRole('img', { name: 'screenshot.png' })
    await expect(img).toBeVisible()
    await expect(img).toHaveAttribute('src', /^blob:/)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S2 PDF 미리보기
  //
  // Given  application/pdf 첨부가 시드된 이슈
  // When   "미리보기" 버튼 클릭
  // Then   dialog role 요소 열림 + 내부에 iframe[title={filename}] 존재
  //        iframe.src 가 blob: 으로 시작함
  // ───────────────────────────────────────────────────────────────────────────
  test('S2 PDF — application/pdf 미리보기 버튼 클릭 시 모달 내 iframe[blob:]이 표시됨', async ({
    page,
  }) => {
    // Given. application/pdf 첨부 시드
    await seedAttachment(page, ISSUE_KEY, {
      id: SEED_PDF_ID,
      filename: 'report.pdf',
      contentType: 'application/pdf',
      sizeBytes: 2048,
    })

    await navigateToIssue(page)

    const section = page.getByRole('region', { name: SECTION_ARIA_LABEL })
    await expect(section.getByText('report.pdf')).toBeVisible()

    // When. 미리보기 버튼 클릭
    const row = section.locator('tr').filter({ hasText: 'report.pdf' })
    await row.getByRole('button', { name: 'report.pdf 미리보기', exact: true }).click()

    // Then. 모달 열림
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()

    // Then. iframe[title=report.pdf] 존재 + src가 blob: 으로 시작
    // (PDF 픽셀 렌더 단언은 불가 — iframe sandbox="" 이라 콘텐츠 접근 제한)
    const iframe = dialog.locator('iframe[title="report.pdf"]')
    await expect(iframe).toBeAttached()
    await expect(iframe).toHaveAttribute('src', /^blob:/)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S3 동영상 미리보기
  //
  // Given  video/mp4 첨부가 시드된 이슈
  // When   "미리보기" 버튼 클릭
  // Then   dialog role 요소 열림 + 내부에 [data-testid="preview-video"] 존재
  //        video.src 가 blob: 으로 시작함
  // ───────────────────────────────────────────────────────────────────────────
  test('S3 동영상 — video/mp4 미리보기 버튼 클릭 시 모달 내 video[blob:]이 표시됨', async ({
    page,
  }) => {
    // Given. video/mp4 첨부 시드
    await seedAttachment(page, ISSUE_KEY, {
      id: SEED_MP4_ID,
      filename: 'demo.mp4',
      contentType: 'video/mp4',
      sizeBytes: 8192,
    })

    await navigateToIssue(page)

    const section = page.getByRole('region', { name: SECTION_ARIA_LABEL })
    await expect(section.getByText('demo.mp4')).toBeVisible()

    // When. 미리보기 버튼 클릭
    const row = section.locator('tr').filter({ hasText: 'demo.mp4' })
    await row.getByRole('button', { name: 'demo.mp4 미리보기', exact: true }).click()

    // Then. 모달 열림
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()

    // Then. data-testid="preview-video" 존재 + src가 blob: 으로 시작
    const video = dialog.locator('[data-testid="preview-video"]')
    await expect(video).toBeAttached()
    await expect(video).toHaveAttribute('src', /^blob:/)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S4 화이트리스트 밖 — 미리보기 버튼 미노출
  //
  // Given  application/zip 첨부가 시드된 이슈
  // When   이슈 상세 화면 진입
  // Then   행에 "미리보기" 버튼이 없음 (다운로드 버튼만 존재)
  // ───────────────────────────────────────────────────────────────────────────
  test('S4 화이트리스트 밖 — application/zip 첨부에는 미리보기 버튼이 표시되지 않음', async ({
    page,
  }) => {
    // Given. application/zip 첨부 시드
    await seedAttachment(page, ISSUE_KEY, {
      id: SEED_ZIP_ID,
      filename: 'archive.zip',
      contentType: 'application/zip',
      sizeBytes: 4096,
    })

    await navigateToIssue(page)

    const section = page.getByRole('region', { name: SECTION_ARIA_LABEL })
    await expect(section.getByText('archive.zip')).toBeVisible()

    // Then. 미리보기 버튼 미존재 (컨테이너 한정)
    const row = section.locator('tr').filter({ hasText: 'archive.zip' })
    await expect(row.getByRole('button', { name: /미리보기/ })).toHaveCount(0)

    // Then. 다운로드 버튼은 존재
    await expect(
      row.getByRole('button', { name: 'archive.zip 다운로드', exact: true }),
    ).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S7 닫기 — Esc 또는 닫기 버튼으로 모달 닫힘
  //
  // Given  모달이 열린 상태
  // When   '닫기' 버튼 클릭 (또는 Esc)
  // Then   dialog role 요소가 사라짐
  // ───────────────────────────────────────────────────────────────────────────
  test('S7 닫기 — 닫기 버튼 클릭 시 모달이 닫힘', async ({ page }) => {
    // Given. image/png 첨부 시드 + 모달 열기
    await seedAttachment(page, ISSUE_KEY, {
      id: SEED_PNG_ID,
      filename: 'close-test.png',
      contentType: 'image/png',
      sizeBytes: 256,
    })

    await navigateToIssue(page)

    const section = page.getByRole('region', { name: SECTION_ARIA_LABEL })
    await expect(section.getByText('close-test.png')).toBeVisible()

    const row = section.locator('tr').filter({ hasText: 'close-test.png' })
    await row.getByRole('button', { name: 'close-test.png 미리보기', exact: true }).click()

    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()

    // When. 닫기 버튼 클릭 (모달 내 버튼 한정)
    await dialog.getByRole('button', { name: '닫기', exact: true }).click()

    // Then. 모달 사라짐
    await expect(dialog).toHaveCount(0)
  })

  test('S7 닫기 — Esc 키 입력 시 모달이 닫힘', async ({ page }) => {
    // Given. image/png 첨부 시드 + 모달 열기
    await seedAttachment(page, ISSUE_KEY, {
      id: SEED_PNG_ID,
      filename: 'esc-test.png',
      contentType: 'image/png',
      sizeBytes: 256,
    })

    await navigateToIssue(page)

    const section = page.getByRole('region', { name: SECTION_ARIA_LABEL })
    await expect(section.getByText('esc-test.png')).toBeVisible()

    const row = section.locator('tr').filter({ hasText: 'esc-test.png' })
    await row.getByRole('button', { name: 'esc-test.png 미리보기', exact: true }).click()

    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()

    // When. Esc 키 입력 (Radix Dialog 기본 동작)
    await page.keyboard.press('Escape')

    // Then. 모달 사라짐
    await expect(dialog).toHaveCount(0)
  })
})
