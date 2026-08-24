// FR-IS-01 이슈 상태 전환 E2E — S1 happy path + S2 가용전환 필터 + S5 에러 노출 + S7 409 후보 선택
import { test, expect } from '@playwright/test'
import { loginAsAlice, i18nLabels } from './fixtures/issue-fixtures'
import {
  MOCK_AMBIGUOUS_ISSUE_KEY,
  MOCK_LEGACY_AMBIGUOUS_ISSUE_KEY,
  MOCK_AMBIGUOUS_TRANSITION_NAME,
  MOCK_AMBIGUOUS_TRANSITION_ID,
} from '../src/mocks/issue-handlers'

/**
 * 모호 전환 후보 선택 다이얼로그의 접근성 이름.
 *
 * 정본은 `src/components/issue/meta/IssueStateTransition.tsx` 의
 * `ambiguousTransitionStrings.dialogTitle` 이다. 그 모듈은 radix-ui·React 를 끌어오므로
 * Playwright 런타임에서 값으로 import 하지 않고 리터럴로 둔다 —
 * `issue-list-inline-edit.spec.ts` 의 `RESOLUTION_DIALOG_TITLE` 과 같은 처방.
 */
const AMBIGUOUS_DIALOG_TITLE = '이동할 전환 선택'

// ATLAS-1(open), ATLAS-4(closed) 는 issue-fixtures.ts 의 정적 fixture.
// 전환 후 상태는 MSW transitionOverrides 가 stateful 보관.

test.describe('FR-IS-01 이슈 상태 전환 (IssueMetaPanel)', () => {
  test.beforeEach(async ({ page }) => {
    // Given. alice 로그인 공통 사전조건
    await loginAsAlice(page)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S1 Happy Path — open → in_progress 전환
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Given   ATLAS-1(open 상태) 이슈 상세 페이지 진입
   * When    전환 셀렉터에서 "Start Work" 선택
   * Then    상태 배지가 "in_progress" 로 갱신됨
   */
  test('S1 happy — Start Work 선택 시 상태 배지가 in_progress 로 갱신', async ({ page }) => {
    // Given. open 상태 이슈 상세 진입
    await page.goto('/issues/ATLAS-1')
    const badge = page.getByTestId('issue-state-badge')
    await expect(badge).toBeVisible()
    await expect(badge).toContainText('open')

    // When. 전환 셀렉터에서 "Start Work" 선택
    const transitionSelect = page.getByRole('combobox', {
      name: i18nLabels.issueDetail.transitionSelectLabel,
    })
    await expect(transitionSelect).toBeVisible()
    await transitionSelect.selectOption({ label: 'Start Work' })

    // Then. 상태 배지가 in_progress 로 갱신 (MSW stateful 응답 + 쿼리 무효화 재조회)
    await expect(badge).toContainText('in_progress')
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S2 가용전환 필터 — open 상태에서 Start Work, Cancel 만 노출
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Given   ATLAS-1(open 상태) 이슈 상세 페이지 진입
   * When    전환 셀렉터 옵션 목록 확인
   * Then    "Start Work", "Cancel" 만 노출; "Submit for Review", "Approve" 없음
   */
  test('S2 가용전환 필터 — open 상태에서 Start Work·Cancel 만 노출', async ({ page }) => {
    // Given. open 상태 이슈 상세 진입
    await page.goto('/issues/ATLAS-1')

    const transitionSelect = page.getByRole('combobox', {
      name: i18nLabels.issueDetail.transitionSelectLabel,
    })
    await expect(transitionSelect).toBeVisible()

    // Then. open 출발 전환 2건만 옵션으로 존재
    //   ★옵션 **이름**으로 센다. 값은 전환의 1급 식별자(`transitionId`)라 도착 상태가 아니고,
    //   값으로 세면 같은 상태쌍의 전환 둘을 한 건으로 합산해 이 필터가 조용히 눈이 먼다.
    await expect(transitionSelect.getByRole('option', { name: 'Start Work' })).toHaveCount(1)
    await expect(transitionSelect.getByRole('option', { name: 'Cancel' })).toHaveCount(1)

    // Then. 타 상태 출발 전환은 존재하지 않음
    await expect(transitionSelect.getByRole('option', { name: 'Submit for Review' })).toHaveCount(0)
    await expect(transitionSelect.getByRole('option', { name: 'Approve' })).toHaveCount(0)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S5 워크플로우 미설정 — ATLAS-NOWF 는 GET /transitions 422 반환
  //   → useIssueTransitions 가 워크플로우 미설정 에러로 처리 → 전환 셀렉터 미노출
  //   + "이 이슈에 워크플로우가 설정되지 않아 상태를 변경할 수 없습니다."
  //     (transitionWorkflowNotConfiguredError) 안내 노출.
  //   이슈 상세 자체(title/badge)는 정상 렌더됨.
  //   S6(종료 상태 빈 배열) 와 구별되는 별도 문구 — 두 문구 동시 노출 없음.
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Given   ATLAS-NOWF(open 상태, GET /transitions → 422) 이슈 상세 페이지 진입
   * When    메타패널 상태 영역 확인
   * Then    이슈 상세 정상 렌더 + 전환 셀렉터 없음 + 워크플로우 미설정 안내 문구 노출
   *         + "더 진행할 전환 없음" 문구는 노출되지 않음 (S6 와 구별)
   */
  test('S5 워크플로우 미설정 — 전환 셀렉터 없고 미설정 안내 문구 노출', async ({ page }) => {
    // Given. GET /transitions 422 반환 이슈 상세 진입
    await page.goto('/issues/ATLAS-NOWF')

    // Then. 이슈 상세 정상 렌더 (상태 배지 + 이슈 키 breadcrumb)
    const badge = page.getByTestId('issue-state-badge')
    await expect(badge).toBeVisible()
    await expect(badge).toContainText('open')

    // Then. 전환 셀렉터 없음 (GET /transitions 422 → 워크플로우 미설정 처리)
    await expect(
      page.getByRole('combobox', { name: i18nLabels.issueDetail.transitionSelectLabel }),
    ).toHaveCount(0)

    // Then. 워크플로우 미설정 전용 안내 문구 노출
    await expect(
      page.getByText(i18nLabels.issueDetail.transitionWorkflowNotConfiguredError, { exact: true }),
    ).toBeVisible()

    // Then. S6 종료 상태 문구는 노출되지 않음 (두 문구 동시 노출 없음)
    await expect(
      page.getByText(i18nLabels.issueDetail.noTransitionsAvailable, { exact: true }),
    ).toHaveCount(0)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S6 종료 상태 — closed 이슈는 전환 셀렉터 미노출
  //   issueAtlas4Fixture.reporterId UUID 버그 수정 완료 (variant byte 'cd5c'→'8d5c').
  //   closed 상태 출발 전환은 softwareDefaultFixture 에 없음 → transitions=[]
  //   → IssueStateTransition 이 noTransitionsAvailable 안내 노출, 셀렉터 미노출.
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Given   ATLAS-4(closed 상태) 이슈 상세 페이지 진입
   * When    메타패널 상태 영역 확인
   * Then    전환 셀렉터 없음 + "더 진행할 전환 없음" 안내 문구 노출
   */
  test('S6 종료 상태 — closed 이슈는 전환 셀렉터 없고 안내 문구 노출', async ({ page }) => {
    // Given. closed 상태 이슈 상세 진입
    await page.goto('/issues/ATLAS-4')
    const badge = page.getByTestId('issue-state-badge')
    await expect(badge).toBeVisible()
    await expect(badge).toContainText('closed')

    // Then. 전환 셀렉터 없음 (closed 출발 전환 0건)
    await expect(
      page.getByRole('combobox', { name: i18nLabels.issueDetail.transitionSelectLabel }),
    ).toHaveCount(0)

    // Then. 종료 안내 문구 노출
    await expect(
      page.getByText(i18nLabels.issueDetail.noTransitionsAvailable),
    ).toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S7 모호 전환 (409 AMBIGUOUS_TRANSITION) — 같은 도착 상태로 가는 전환이 2건
  //   ADR 2026-08-18 §D3: transitionId 없이 toStatusKey 만 오면 후보가 정확히 1개일 때만
  //   실행하고, 2개 이상이면 409 + 후보 목록을 돌려준다. 클라이언트는 사용자가 고른 후보의
  //   transitionId 를 되실어 재요청한다.
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Given   ATLAS-AMBIG-LEGACY(open 상태, open→in_progress 전환 2건, **전환 ID 없음**) 진입
   * When    전환 셀렉터에서 in_progress 도착 전환을 고름 → 409 후보 목록
   *         → 다이얼로그에서 후보 하나를 고름
   * Then    상태 배지가 in_progress 로 갱신되고 다이얼로그가 닫힘
   *
   * ★`ATLAS-AMBIG` 이 아니라 `-LEGACY` 다. 화면이 고른 전환의 `transitionId` 를 실어 보내는
   *   지금, 409 는 그 값이 **응답에 없을 때만** 난다(`issueTransitionSchema.transitionId` 가
   *   `nullable().optional()` 인 그 자리). 여기서 검사하는 것은 그 폴백 경로다.
   */
  test('S7 모호 전환 폴백 — 전환 ID 가 없으면 409 후보 목록에서 고른 전환이 실행된다', async ({ page }) => {
    // Given. 같은 도착 상태 전환이 2건이고 전환 ID 가 없는 이슈 상세 진입
    await page.goto(`/issues/${MOCK_LEGACY_AMBIGUOUS_ISSUE_KEY}`)
    const badge = page.getByTestId('issue-state-badge')
    await expect(badge).toBeVisible()
    await expect(badge).toContainText('open')

    // When. toStatusKey 만으로는 못 가르는 전환을 고른다
    const transitionSelect = page.getByRole('combobox', {
      name: i18nLabels.issueDetail.transitionSelectLabel,
    })
    await expect(transitionSelect).toBeVisible()
    await transitionSelect.selectOption({ label: 'Start Work' })

    // Then. 409 후보 목록이 다이얼로그로 노출된다 (조용히 아무거나 고르지 않는다)
    const dialog = page.getByRole('dialog', { name: AMBIGUOUS_DIALOG_TITLE })
    await expect(dialog).toBeVisible()
    const chosen = dialog.getByRole('button', { name: MOCK_AMBIGUOUS_TRANSITION_NAME })
    await expect(chosen).toBeVisible()

    // When. 후보 하나를 고른다 → 그 transitionId 로 재요청
    await chosen.click()

    // Then. 다이얼로그가 닫히고 상태가 갱신된다
    await expect(dialog).toHaveCount(0)
    await expect(badge).toContainText('in_progress')
  })

  /**
   * Given   ATLAS-AMBIG 이슈 상세에서 409 후보 다이얼로그가 열린 상태
   * When    취소를 누름
   * Then    다이얼로그가 닫히고 상태는 open 그대로 (전환 미실행)
   */
  test('S7-2 모호 전환 취소 — 상태가 바뀌지 않는다', async ({ page }) => {
    await page.goto(`/issues/${MOCK_LEGACY_AMBIGUOUS_ISSUE_KEY}`)
    const badge = page.getByTestId('issue-state-badge')
    await expect(badge).toContainText('open')

    await page
      .getByRole('combobox', { name: i18nLabels.issueDetail.transitionSelectLabel })
      .selectOption({ label: 'Start Work' })

    const dialog = page.getByRole('dialog', { name: AMBIGUOUS_DIALOG_TITLE })
    await expect(dialog).toBeVisible()
    await dialog.getByRole('button', { name: '취소', exact: true }).click()

    await expect(dialog).toHaveCount(0)
    await expect(badge).toContainText('open')
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S8 (FR-WF-05 D7) 같은 쌍 두 전환이 각각 보이고 **각각 실행된다**
  //   화면이 고른 전환의 `transitionId` 를 그대로 실어 보내므로 409 왕복이 없다.
  //   종전에는 도착 상태(`toStateKey`)만 보내 어느 쪽을 골라도 같은 값이 나갔고,
  //   서버가 409 로 되물어 **방금 이름으로 고른 것을 다시 고르게** 했다.
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Given   ATLAS-AMBIG(open→in_progress 전환이 2건, 둘 다 전환 ID 보유) 상세 진입
   * When    「긴급 착수」를 고름
   * Then    그 전환의 ID 가 요청에 실리고, 후보 다이얼로그 없이 바로 실행된다
   */
  test('S8 두 전환이 각각 보이고, 고른 쪽의 전환 ID 로 다이얼로그 없이 실행된다', async ({ page }) => {
    await page.goto(`/issues/${MOCK_AMBIGUOUS_ISSUE_KEY}`)
    const badge = page.getByTestId('issue-state-badge')
    await expect(badge).toContainText('open')

    const transitionSelect = page.getByRole('combobox', {
      name: i18nLabels.issueDetail.transitionSelectLabel,
    })
    await expect(transitionSelect).toBeVisible()

    // Then. 도착 상태가 같아도 두 전환이 각각 보인다
    await expect(transitionSelect.getByRole('option', { name: 'Start Work' })).toHaveCount(1)
    await expect(
      transitionSelect.getByRole('option', { name: MOCK_AMBIGUOUS_TRANSITION_NAME }),
    ).toHaveCount(1)

    // When. 한쪽을 고른다 — 요청을 가로채 무엇을 보냈는지 본다
    const posted = page.waitForRequest(
      (r) => r.method() === 'POST' && r.url().includes('/transition'),
    )
    await transitionSelect.selectOption({ label: MOCK_AMBIGUOUS_TRANSITION_NAME })

    // Then. 고른 전환의 1급 식별자가 그대로 실린다
    const body = (await posted).postDataJSON() as { transitionId?: string }
    expect(body.transitionId).toBe(MOCK_AMBIGUOUS_TRANSITION_ID)

    // Then. 후보 다이얼로그는 뜨지 않는다 — 같은 선택을 두 번 시키지 않는다
    await expect(page.getByRole('dialog', { name: AMBIGUOUS_DIALOG_TITLE })).toHaveCount(0)
    await expect(badge).toContainText('in_progress')
  })

  /**
   * Given   같은 이슈
   * When    다른 쪽(「Start Work」)을 고름
   * Then    **다른** 전환 ID 가 실린다 — 두 옵션이 서로 다른 전환을 실행한다
   */
  test('S8-2 다른 쪽을 고르면 다른 전환 ID 가 실린다', async ({ page }) => {
    await page.goto(`/issues/${MOCK_AMBIGUOUS_ISSUE_KEY}`)
    const badge = page.getByTestId('issue-state-badge')
    await expect(badge).toContainText('open')

    const posted = page.waitForRequest(
      (r) => r.method() === 'POST' && r.url().includes('/transition'),
    )
    await page
      .getByRole('combobox', { name: i18nLabels.issueDetail.transitionSelectLabel })
      .selectOption({ label: 'Start Work' })

    // Then. 값이 있고, 옆 전환의 것이 아니다.
    //   ★기대값을 리터럴로 적지 않는다 — 그 id 는 `softwareDefaultFixture` 소유이고
    //   여기 베껴 두면 픽스처가 바뀔 때 둘이 갈라진다. 계약은 「서로 다르다」 하나다.
    const body = (await posted).postDataJSON() as { transitionId?: string }
    expect(body.transitionId).toBeTruthy()
    expect(body.transitionId).not.toBe(MOCK_AMBIGUOUS_TRANSITION_ID)

    await expect(page.getByRole('dialog', { name: AMBIGUOUS_DIALOG_TITLE })).toHaveCount(0)
    await expect(badge).toContainText('in_progress')
  })
})
