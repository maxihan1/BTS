// FR-MN-02 D7 E2E — 이슈 본문 편집 멘션 자동완성 실 브라우저 caret/splice 검증
import { test, expect } from '@playwright/test'
import { loginAsAlice, i18nLabels } from './fixtures/issue-fixtures'

/**
 * 이슈 본문 편집 멘션 자동완성 E2E (FR-MN-02 Task 4)
 *
 * 핵심 근거: jsdom에서 textarea.selectionStart가 포커스 없이 null을 반환해
 * `?? 0` fallback으로 단위 테스트는 통과하지만 실 caret 동작은 검증 불가.
 * 이 E2E는 Chromium 실 브라우저로 caret 위치, @username 삽입, 드롭다운 제어를 확인한다.
 * (memory: jsdom-browser-textarea-selectionstart)
 *
 * MSW 환경:
 *   - GET /api/v1/users?query=al → alice(김앨리스) 포함 결과 반환 (user-handlers.ts 기존 핸들러)
 *   - serviceWorkers:'block' 사용 금지 — MSW 핸들러를 그대로 사용 (memory: e2e-msw-serviceworker-block)
 *
 * strict-mode 주의:
 *   - 저장/취소 버튼은 페이지 내 여러 곳에 있으므로 descriptionEditor 컨테이너로 한정
 *   - 드롭다운 option 셀렉터는 data-testid="mention-option-<username>" 사용
 *   (memory: playwright-getbyrole-exact-strict-mode)
 *
 * 데이터 격리: 각 시나리오가 loginAsAlice + goto('/issues/ATLAS-1') 로 독립 진입.
 * ATLAS-1 description MSW stateful override는 시나리오 간 누적될 수 있으나,
 * 멘션 자동완성 검증은 textarea 값(draft)에만 의존하므로 저장된 description 상태 무관.
 */

// ─────────────────────────────────────────────────────────────────────────────
// 공통 헬퍼 — 편집 모드 진입
// ─────────────────────────────────────────────────────────────────────────────

/**
 * TipTap contenteditable 을 비운다.
 *
 * `fill('')` 은 `value` 가 있는 폼 요소용이라 contenteditable 에서는 아무 일도 하지 않는다.
 * 전체 선택 후 삭제해야 ProseMirror 문서가 실제로 빈다.
 */
async function clearEditor(
  page: import('@playwright/test').Page,
  editor: import('@playwright/test').Locator,
) {
  await editor.click()
  await page.keyboard.press('ControlOrMeta+a')
  await page.keyboard.press('Delete')
}

/**
 * ATLAS-1 이슈 상세에서 본문 편집 모드로 진입하고 에디터 로케이터를 반환한다.
 *
 * ★2026-09-04 TipTap 전환(J8). 바뀐 것이 셋이다.
 *   1. **Write/Preview 탭이 사라졌다** — 마크다운 원문을 보여줄 이유가 없어졌다.
 *      옛 헬퍼는 `descriptionWriteTab` 을 클릭했는데 그 i18n 키 자체가 `ko.ts` 에서 삭제됐다.
 *   2. 입력 대상이 `<textarea>` 가 아니라 **contenteditable** 이다. `role="textbox"` 는
 *      `editorProps.attributes` 가 직접 붙이고, 접근성 이름은 `descriptionEditLabel`
 *      ('본문 편집기')이다 — 옛 헬퍼가 쓰던 `descriptionEditButton`('본문 편집')이 아니다.
 *   3. 컨테이너를 `div:has(> [role="tablist"])` 로 잡던 것도 tablist 와 함께 사라졌다.
 *      저장/취소 버튼은 에디터의 **형제**이므로 그 둘을 함께 감싸는 div 로 잡는다.
 */
async function enterDescriptionEditMode(page: import('@playwright/test').Page) {
  await page.goto('/issues/ATLAS-1')

  const editButton = page.getByRole('button', {
    name: i18nLabels.issueDetail.descriptionEditButton,
  })
  await expect(editButton).toBeVisible()
  await editButton.click()

  // descriptionEditor: 에디터와 저장 버튼을 함께 가진 편집 영역
  // ★컨테이너를 `div:has(> [role="textbox"])` 로 잡으면 안 된다. TipTap 의 `EditorContent` 가
  //   wrapper div 를 하나 더 만들어서 그 직계 자식 div 에는 **저장 버튼이 없다**.
  //   에디터와 저장 버튼을 **함께** 가진 div 중 가장 안쪽(`.last()`)을 잡는다 — 조상들이
  //   DOM 순서상 먼저 나오므로 마지막이 가장 가까운 공통 조상이다.
  const descriptionEditor = page
    .locator('div')
    .filter({ has: page.getByRole('textbox', { name: i18nLabels.issueDetail.descriptionEditLabel }) })
    .filter({ has: page.getByRole('button', { name: i18nLabels.issueDetail.descriptionSaveButton }) })
    .last()

  // textarea(이름 유지): 실체는 TipTap contenteditable 이다.
  // aria-label='본문 편집기' — 환경/라벨 등 타 textbox 와 strict-mode 구별
  const textarea = page.getByRole('textbox', {
    name: i18nLabels.issueDetail.descriptionEditLabel,
  })
  await expect(textarea).toBeVisible()

  return { descriptionEditor, textarea }
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 스위트
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-MN-02 이슈 본문 멘션 자동완성 (S1~S4)', () => {
  test.beforeEach(async ({ page }) => {
    // Given. alice 로그인 (storageState 대신 loginAsAlice 헬퍼 — 기존 패턴 일관)
    await loginAsAlice(page)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S1 후보 표시 + 클릭 선택
  // ───────────────────────────────────────────────────────────────────────────

  /**
   * Given   ATLAS-1 본문 편집 모드(Write 탭) 진입
   * When    textarea에 "@al" 타이핑 → debounce 대기 → 드롭다운 노출
   *         "김앨리스 @alice" 후보 클릭
   * Then    textarea 값에 "@alice " 포함 (spliceMention 결과 확인)
   *         드롭다운 닫힘 확인
   */
  test('S1 @al 타이핑 → 드롭다운 노출 → 클릭 선택 → @alice 삽입', async ({ page }) => {
    // Given.
    const { textarea } = await enterDescriptionEditMode(page)

    // Given. 에디터를 비워 결정론화 — 기존 description 이 caret 위치에 끼어들지 않도록.
    // ★contenteditable 이라 `fill('')` 이 값을 지우지 않는다(value 가 없다). 전체 선택 후
    //   삭제해야 ProseMirror 문서가 실제로 비고, caret 이 빈 문단 끝에 정착한다.
    await clearEditor(page, textarea)

    // When. "@al" 실 키 입력 — pressSequentially로 caret이 끝에 오도록
    // (fill은 change 이벤트만 발생하므로 실 keydown 순서가 없음 → pressSequentially 사용)
    await textarea.pressSequentially('@al')

    // When. debounce(250ms) + MSW 응답 대기 — 드롭다운 노출 확인
    const dropdown = page.getByRole('listbox', { name: '멘션 사용자 자동완성' })
    await expect(dropdown).toBeVisible()

    // When. "김앨리스(@alice)" 후보 클릭 — data-testid로 정확하게 한정 (strict-mode 방지)
    const aliceOption = page.getByTestId('mention-option-alice')
    await expect(aliceOption).toBeVisible()
    await aliceOption.click()

    // Then. textarea 값이 정확히 "@alice " (뒤 공백 1개 포함) — exact 단언으로 오염 방지
    // "@alice ice" 같은 잘못된 splice 오염도 이 단언으로 잡힌다 (부분일치 regex 제거)
    await expect(textarea).toHaveText('@alice')

    // Then. 드롭다운 닫힘 (선택 후 open=false)
    await expect(dropdown).not.toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S2 키보드 선택 (ArrowDown + Enter)
  // ───────────────────────────────────────────────────────────────────────────

  /**
   * Given   ATLAS-1 본문 편집 모드 진입
   * When    textarea에 "@al" 타이핑 → 드롭다운 노출
   *         ArrowDown → Enter (첫 번째 후보 선택)
   * Then    textarea 값에 "@alice " 포함
   *         줄바꿈 미발생 확인 (Enter가 onKeyDown에서 preventDefault됨)
   *         드롭다운 닫힘
   */
  test('S2 @al 타이핑 → ArrowDown → Enter → @alice 삽입 (줄바꿈 없음)', async ({ page }) => {
    // Given.
    const { textarea } = await enterDescriptionEditMode(page)

    // Given. textarea를 비워 결정론화
    await clearEditor(page, textarea)

    // When. "@al" 실 키 입력
    await textarea.pressSequentially('@al')

    // When. 드롭다운 대기
    const dropdown = page.getByRole('listbox', { name: '멘션 사용자 자동완성' })
    await expect(dropdown).toBeVisible()

    // When. ArrowDown → Enter
    await textarea.press('ArrowDown')
    await textarea.press('Enter')

    // Then. 에디터 텍스트가 정확히 "@alice" (exact 단언으로 오염 방지)
    // ★`inputValue()` 는 `<input>`/`<textarea>`/`<select>` 전용이라 contenteditable 에서
    //   "Node is not an <input>..." 로 죽는다. `textContent()` 로 읽는다.
    const value = (await textarea.textContent()) ?? ''
    // 뒤 공백 1개는 TipTap Mention 이 삽입 후 붙인다 — 옛 textarea 구현의 `spliceMention` 과 같다.
    expect(value).toBe('@alice ')
    // 줄바꿈이 삽입됐다면 '\n'이 포함됨 — 없음 확인 (Enter가 onKeyDown에서 preventDefault됨)
    expect(value).not.toContain('\n')

    // Then. 드롭다운 닫힘
    await expect(dropdown).not.toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S3 Escape 닫힘
  // ───────────────────────────────────────────────────────────────────────────

  /**
   * Given   ATLAS-1 본문 편집 모드 진입
   * When    textarea에 "@al" 타이핑 → 드롭다운 노출
   *         Escape 키 입력
   * Then    드롭다운 닫힘
   *         textarea 값은 "@al" 유지 (삽입되지 않음)
   *         textarea 포커스 유지 (Escape가 포커스를 빼앗지 않음)
   */
  test('S3 @al 타이핑 → Escape → 드롭다운 닫힘 + 값 유지', async ({ page }) => {
    // Given.
    const { textarea } = await enterDescriptionEditMode(page)

    // Given. textarea를 비워 결정론화 — Escape 후 "@al" 만 남아 있는지 exact 단언 가능하도록
    await clearEditor(page, textarea)

    // When. "@al" 실 키 입력
    await textarea.pressSequentially('@al')

    // When. 드롭다운 대기
    const dropdown = page.getByRole('listbox', { name: '멘션 사용자 자동완성' })
    await expect(dropdown).toBeVisible()

    // When. Escape 키
    await textarea.press('Escape')

    // Then. 드롭다운 닫힘
    await expect(dropdown).not.toBeVisible()

    // Then. textarea 값에 "@al" 포함 (삽입 없이 원본 유지)
    await expect(textarea).toHaveText('@al')

    // Then. textarea 포커스 유지 — 키 입력 가능 상태
    await expect(textarea).toBeFocused()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S4 저장 연동 — @alice 삽입 후 저장 경로 동작 확인
  // ───────────────────────────────────────────────────────────────────────────

  /**
   * Given   ATLAS-1 본문 편집 모드 진입
   * When    "@al" 타이핑 → 드롭다운 → alice 클릭 → "@alice " 삽입
   *         descriptionEditor 저장 버튼 클릭
   * Then    저장 버튼이 다시 활성화됨 (PATCH 완료, isSaving=false)
   *         (백엔드 MSW PATCH /api/v1/issues/ATLAS-1 → 200 OK 처리됨)
   */
  test('S4 @alice 삽입 후 저장 버튼 → 저장 경로 동작', async ({ page }) => {
    // Given.
    const { descriptionEditor, textarea } = await enterDescriptionEditMode(page)

    // Given. textarea를 비워 결정론화
    await clearEditor(page, textarea)

    // When. "@al" 입력 → 드롭다운 → alice 선택
    await textarea.pressSequentially('@al')
    const dropdown = page.getByRole('listbox', { name: '멘션 사용자 자동완성' })
    await expect(dropdown).toBeVisible()

    const aliceOption = page.getByTestId('mention-option-alice')
    await expect(aliceOption).toBeVisible()
    await aliceOption.click()

    // 삽입 확인 — exact 단언으로 오염 방지
    await expect(textarea).toHaveText('@alice')

    // When. 저장 버튼 클릭 — descriptionEditor 컨테이너로 strict-mode 방지
    const saveBtn = descriptionEditor.getByRole('button', {
      name: i18nLabels.issueDetail.descriptionSaveButton,
    })
    await saveBtn.click()

    // Then. 저장 버튼이 다시 활성화됨 (PATCH mutation 완료 → isSaving=false)
    await expect(saveBtn).toBeEnabled()
  })
})
