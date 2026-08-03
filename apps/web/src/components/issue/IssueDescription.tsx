// 이슈 본문(description) 표시 및 편집 컴포넌트 — GitHub 스타일 Write/Preview 탭
import type {
  JSX,
  KeyboardEvent as ReactKeyboardEvent,
  MouseEvent as ReactMouseEvent,
} from 'react'
import { useState, useRef, useEffect } from 'react'
import { Button } from '@/components/ui/button'
import { issueDetailStrings } from '@/i18n/ko'
import { useMentionAutocomplete } from './mention/use-mention-autocomplete'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** IssueDescription 컴포넌트 props */
export interface IssueDescriptionProps {
  /**
   * 백엔드가 OWASP 기준으로 정화(sanitize)한 HTML 문자열.
   * null이면 본문 없음 — dangerouslySetInnerHTML 호출 금지 (NFR1).
   */
  descriptionHtml: string | null
  /**
   * 원본 마크다운 텍스트 — Write 탭 textarea 편집에만 사용.
   * 절대 표시 경로(Preview)에 렌더하지 않는다 (NFR2 보안).
   */
  description: string | null
  /** 저장 버튼 클릭 시 호출 — 편집된 마크다운 문자열 전달 */
  onSave: (markdown: string) => void
  /** 저장 진행 중 여부 — true 시 저장/취소 버튼 disabled */
  isSaving: boolean
  /**
   * 수정 권한 여부 — false(기본값 true)이면 편집 버튼 disabled (FR-PM-02).
   * fail-closed: 권한 미확정 시 false 전달 권장.
   */
  canEdit?: boolean
  /**
   * 열람 불가 필드 키 목록 — "description"이 포함되면 본문 대신
   * 열람 불가 placeholder를 표시하고 편집 버튼을 숨긴다 (FR-PM-07 §3.1).
   * 기본값 빈 배열 — 미전달 시 제한 없음.
   */
  restrictedFields?: string[]
  /**
   * 편집 불가 필드 키 목록 — "description"이 포함되면 canEdit과 AND로
   * 편집 버튼을 disabled 처리한다 (FR-PM-07 §3.2).
   * 기본값 빈 배열 — 미전달 시 제한 없음.
   */
  noneditableFields?: string[]
}

/** Write/Preview 탭 상태 */
type ActiveTab = 'write' | 'preview'

/** 탭 버튼 활성/비활성 공통 className */
const TAB_BASE_CLS =
  'px-3 py-2 text-sm font-medium min-h-[44px] border-b-2 transition-colors'
const TAB_ACTIVE_CLS = 'border-primary text-primary'
const TAB_INACTIVE_CLS =
  'border-transparent text-muted-foreground hover:text-foreground'

// ─────────────────────────────────────────────────────────────────────────────
// IssueDescription
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 본문(description) 표시 및 편집 컴포넌트.
 *
 * **열람 제어 (FR-PM-07 §3.1)** — `restrictedFields`에 "description"이 포함되면
 * 본문/편집 버튼 대신 열람 불가 placeholder(`data-testid="description-restricted"`)만
 * 표시한다. 백엔드가 description=null로 마스킹한 상태에서도 UI가 어색하게 보이지 않도록
 * 명확한 안내 문구를 제공한다.
 *
 * **편집 제어 (FR-PM-07 §3.2)** — `canEdit`(UPDATE 권한)과 `noneditableFields`에
 * "description" 포함 여부를 AND 연산한다. 둘 중 하나라도 false이면 편집 버튼 disabled.
 *
 * - 읽기 모드: descriptionHtml을 dangerouslySetInnerHTML로 렌더(백엔드 정화 신뢰 HTML).
 *   descriptionHtml=null이면 '본문이 없습니다.' placeholder 표시.
 * - 편집 모드: Write/Preview 탭 + textarea + 저장/취소 버튼.
 *   raw description은 Write 탭 textarea에서만 사용, 표시 경로 렌더 금지 (NFR2).
 * - WCAG AA: min-h-[44px] 터치 타깃, aria-label 필수.
 */
export function IssueDescription({
  descriptionHtml,
  description,
  onSave,
  isSaving,
  canEdit = true,
  restrictedFields = [],
  noneditableFields = [],
}: IssueDescriptionProps): JSX.Element {
  const [isEditing, setIsEditing] = useState(false)
  const [activeTab, setActiveTab] = useState<ActiveTab>('write')
  const [draftMarkdown, setDraftMarkdown] = useState('')

  // FR-PM-07 §3.1: "description"이 restrictedFields에 포함되면 열람 자체를 차단
  const isRestricted = restrictedFields.includes('description')
  // FR-PM-07 §3.2: canEdit AND noneditableFields 둘 중 하나라도 false이면 편집 불가
  const isEditDisabled = !canEdit || noneditableFields.includes('description')

  function handleEditClick() {
    setDraftMarkdown(description ?? '')
    setActiveTab('write')
    setIsEditing(true)
  }

  function handleCancel() {
    setIsEditing(false)
    setDraftMarkdown('')
  }

  function handleSave() {
    onSave(draftMarkdown)
  }

  // 열람 불가 — 본문/편집 버튼 대신 placeholder만 표시
  if (isRestricted) {
    return (
      <p
        data-testid="description-restricted"
        className="text-sm text-muted-foreground italic min-h-[44px] flex items-center"
      >
        {issueDetailStrings.descriptionRestricted}
      </p>
    )
  }

  if (isEditing) {
    return (
      <EditMode
        draftMarkdown={draftMarkdown}
        // 편집 진입 시 draftMarkdown 을 seed 한 값과 같은 식이어야 변경분 판정이 정확하다
        initialMarkdown={description ?? ''}
        onDraftChange={setDraftMarkdown}
        activeTab={activeTab}
        onTabChange={setActiveTab}
        descriptionHtml={descriptionHtml}
        onSave={handleSave}
        onCancel={handleCancel}
        isSaving={isSaving}
      />
    )
  }

  return (
    <ReadMode
      descriptionHtml={descriptionHtml}
      onEditClick={handleEditClick}
      canEdit={!isEditDisabled}
    />
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// ReadMode — 읽기 모드
// ─────────────────────────────────────────────────────────────────────────────

interface ReadModeProps {
  descriptionHtml: string | null
  onEditClick: () => void
  canEdit: boolean
}

/**
 * 클릭 진입이 가능할 때 본문 읽기 영역에 붙는 hover 어포던스 (FR-UX-11 F8 NFR5).
 * hover 배경은 DESIGN.md §7 상태 토큰 — 라이트 `#F1F2F4` / 다크 `#A1BDD914` 가 이미 정의돼
 * 두 테마에 자동 대응한다(임의 알파 금지). 좌우 음수 마진 + 같은 크기 패딩으로
 * 본문 텍스트의 정렬은 그대로 두고 강조 영역만 넓힌다.
 */
const DESCRIPTION_CLICKABLE_CLS =
  '-mx-2 rounded px-2 cursor-text hover:bg-(--bg-neutral-hover)'

/**
 * 본문 읽기 모드 — descriptionHtml 렌더 또는 placeholder.
 * raw description은 이 컴포넌트에서 절대 사용하지 않는다 (NFR2).
 *
 * 편집 진입 경로는 2개다 — 본문 클릭(FR4)과 `본문 편집` 버튼(FR7).
 * 버튼은 문자열·동작 모두 불변으로 유지하며, 이것이 키보드 사용자의 진입 경로가 되어
 * FR9(WCAG 2.1.1 — 동등한 키보드 경로)를 충족한다.
 */
function ReadMode({ descriptionHtml, onEditClick, canEdit }: ReadModeProps): JSX.Element {
  /**
   * 본문 클릭 → 편집 진입 (FR4). 세 경우를 의도적으로 배제한다.
   *
   * - 권한 없음(E7). `canEdit=false`면 아무 일도 일어나지 않는다(에러 토스트도 없음).
   * - 텍스트 선택 중(E2 / 편차 D-2). 드래그로 본문을 복사하려는 참이면 열지 않는다.
   *   Jira Cloud 는 이 경우에도 편집이 열려 버리는 미해결 결함이 있다(JRA-64389 · JRA-29063).
   *   결함까지 복제하지 않도록 `Selection.isCollapsed` 로 판정한다.
   * - 링크·멘션 클릭(E3). 클릭 지점이 `<a>` 안이면 그 요소가 먼저 동작해야 한다.
   */
  function handleContentClick(e: ReactMouseEvent<HTMLElement>): void {
    if (!canEdit) return
    if (window.getSelection()?.isCollapsed === false) return
    if ((e.target as HTMLElement).closest('a') !== null) return
    onEditClick()
  }

  // 편집 권한이 없으면 hover 어포던스를 붙이지 않는다 — 열리지 않는 진입면을 광고하지 않기 위함
  const clickableCls = canEdit ? ` ${DESCRIPTION_CLICKABLE_CLS}` : ''

  return (
    <div className="flex flex-col gap-2">
      {/* 본문 영역 — HTML 렌더 또는 placeholder. 둘 다 클릭으로 편집 진입(빈 본문도 눌러 쓸 수 있어야 한다) */}
      <div className="min-h-[44px]">
        {descriptionHtml !== null ? (
          // NFR1: 백엔드 정화 HTML만 dangerouslySetInnerHTML로 렌더
          <div
            data-testid="description-preview-content"
            onClick={handleContentClick}
            className={`prose prose-sm max-w-none text-sm text-foreground${clickableCls}`}
            // biome-ignore lint/security/noDangerouslySetInnerHtml: 백엔드 OWASP 정화 HTML만 허용
            dangerouslySetInnerHTML={{ __html: descriptionHtml }}
          />
        ) : (
          <p
            onClick={handleContentClick}
            className={`text-sm text-muted-foreground italic${clickableCls}`}
          >
            {issueDetailStrings.descriptionEmpty}
          </p>
        )}
      </div>

      {/* 편집 시작 버튼 — canEdit=false이면 disabled (FR-PM-02) */}
      <Button
        variant="outline"
        size="sm"
        className="self-start min-h-[44px]"
        onClick={onEditClick}
        disabled={!canEdit}
        title={!canEdit ? issueDetailStrings.descriptionEditButtonNoPermission : undefined}
        aria-label={issueDetailStrings.descriptionEditButton}
      >
        {issueDetailStrings.descriptionEditButton}
      </Button>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// EditMode — Write/Preview 탭 편집 모드
// ─────────────────────────────────────────────────────────────────────────────

interface EditModeProps {
  draftMarkdown: string
  /** 편집 진입 시점의 원본 마크다운 — 변경분 유무 판정 기준 (편차 D-1) */
  initialMarkdown: string
  onDraftChange: (value: string) => void
  activeTab: ActiveTab
  onTabChange: (tab: ActiveTab) => void
  /** Preview 탭에서 표시할 정화 HTML — null이면 placeholder */
  descriptionHtml: string | null
  onSave: () => void
  onCancel: () => void
  isSaving: boolean
}

/**
 * 본문 편집 모드 — Write/Preview 탭 + 저장/취소 버튼.
 * Preview 탭은 descriptionHtml만 렌더(draftMarkdown 노출 금지, NFR2).
 */
function EditMode({
  draftMarkdown,
  initialMarkdown,
  onDraftChange,
  activeTab,
  onTabChange,
  descriptionHtml,
  onSave,
  onCancel,
  isSaving,
}: EditModeProps): JSX.Element {
  /**
   * 작성분 폐기 확인 패널 **요청** 플래그 (편차 D-1).
   *
   * 편집 진입 시점의 초기화는 언마운트가 해 준다 — `isEditing` 이 꺼지면 EditMode 가 통째로
   * 사라지므로 다음 진입은 항상 false 로 시작한다.
   * **다만 `isEditing` 이 꺼지지 않는 경로**(저장 · 타이핑 · 탭 전환)에서는 언마운트가 없어
   * 이 플래그만으로는 패널이 남는다. 그래서 실제 노출은 아래 `showDiscardConfirm` 파생값이
   * 결정하고, 타이핑·탭 전환은 별도로 플래그를 내린다(리뷰 R-1).
   */
  const [confirmDiscard, setConfirmDiscard] = useState(false)

  // FR-MN-02: 멘션 자동완성 배선 — textarea ref + 훅 연결
  const textareaRef = useRef<HTMLTextAreaElement | null>(null)

  /**
   * 초안 변경 — 타이핑은 "계속 편집하겠다"는 의사표시다.
   * 폐기 확인이 떠 있는 채로 빨간 경고 아래에서 계속 쓰게 두지 않는다 (R-1).
   */
  function handleDraftChange(next: string): void {
    setConfirmDiscard(false)
    onDraftChange(next)
  }

  const mention = useMentionAutocomplete({
    value: draftMarkdown,
    onChange: handleDraftChange,
    textareaRef,
  })

  /**
   * 폐기 확인 패널 **실제 노출** 여부 — 상태가 아니라 파생값이다 (리뷰 R-1).
   *
   * `confirmDiscard` 만으로 그리면 `isEditing` 이 꺼지지 않는 경로에서 패널이 남는다.
   * 특히 **저장 후**에는 버릴 것이 없는데도 "작성 중인 내용이 사라집니다" 가 계속 떠 있고,
   * 그 상태의 `편집 그만두기` 는 문구와 달리 아무것도 버리지 않아 **문구가 거짓말**이 된다.
   * 초안이 원본과 같아지는 순간(저장 후 refetch · 사용자가 직접 되돌림) 자동 해소된다.
   *
   * 미리보기 탭에서는 그리지 않는다 — 이 패널은 쓰기 탭 편집 맥락의 확인이고,
   * 탭 콘텐츠의 형제로 놓여 있어 조건이 없으면 미리보기 화면 위에 그대로 남는다.
   */
  const showDiscardConfirm =
    confirmDiscard && draftMarkdown !== initialMarkdown && activeTab === 'write'

  /**
   * 상단 `저장`·`취소` 잠금 — 사유 2가지를 분리해 읽히게 둔다.
   *
   * - `isSaving`. 저장 진행 중 중복 제출 방지 (기존 조건, E5).
   * - `showDiscardConfirm`. **확인을 띄워 놓고 확인 없이 버리는 모순 차단** (리뷰 C-1 3행).
   *   확인 중에는 뒤의 결정 버튼을 잠그는 것이 다이얼로그의 자연스러운 관례다.
   *
   * 잠금은 `disabled` 로 하고 DOM 에서 제거하지 않는다 — 버튼이 사라지면 사용자가 위치를
   * 잃고 패널을 닫을 때 레이아웃이 튀며, `getByRole('button', { name })` 로 찾는 기존 E2E 가
   * 요소 부재로 깨질 수 있다. `disabled` 는 요소가 남아 strict mode 계산에 영향이 없다.
   *
   * 잠긴 이유는 바로 아래 확인 패널이 화면으로 설명한다 — `title` 은 붙이지 않는다.
   * Button 프리미티브가 `disabled:pointer-events-none` 이라 툴팁이 뜨지도 않는다.
   */
  const actionsLocked = isSaving || showDiscardConfirm

  // 마운트 효과에서 쓰는 훅 함수 — useCallback([]) 이라 참조가 안정적이다(mentionReset 과 같은 패턴)
  const suppressNextSelect = mention.suppressNextSelect

  /**
   * 편집 진입 시 입력 영역으로 포커스를 옮긴다 (스펙 S4 — "포커스가 입력 영역에 놓인다").
   *
   * 진입 경로 3개(본문 텍스트 클릭 · 빈 본문 placeholder 클릭 · `본문 편집` 버튼)는 모두
   * `handleEditClick` 을 거쳐 이 컴포넌트를 **마운트**시키므로, 마운트 1회 효과로 셋이 갈라지지 않는다.
   * `handleEditClick` 이 `activeTab` 을 항상 'write' 로 되돌리므로 진입 시점에 textarea 는 반드시 존재한다.
   *
   * - `preventScroll` — 긴 이슈에서 포커스가 화면을 튀게 하지 않는다.
   * - 커서는 **끝**에 둔다 — 기존 본문에 이어 쓰는 것이 자연스럽고, 제목 편집(FR1)과도 같아진다.
   * - **`suppressNextSelect()` 를 커서 이동 직전에 부른다.** `setSelectionRange` 는 사용자
   *   조작과 구별되지 않는 `select` 이벤트를 낳고, 그것이 멘션 감지를 깨워 본문이 `@이름` 으로
   *   끝나는 이슈에서 **열지도 않은 자동완성이 진입 직후 떠 버린다**. 그 상태에서는 첫 `Enter`
   *   가 줄바꿈이 아니라 후보 선택, 첫 `Esc` 가 취소가 아니라 팝업 닫기가 되어 FR5·FR6 이
   *   첫 타건에 무력화된다(실측 확인). 억제 범위는 **다음 타건 전까지 최대 1회**다 —
   *   `select` 가 영영 오지 않으면(빈 본문의 `setSelectionRange(0,0)` 은 범위가 안 바뀌어
   *   명세상 미발화) 플래그가 남지만, 다음 `change` 나 `select` 가 소비하므로 유계·자가치유다.
   *   사용자의 클릭·방향키 이동은 종전대로 감지된다.
   */
  useEffect(() => {
    const el = textareaRef.current
    if (el === null) return
    el.focus({ preventScroll: true })
    // 이 아래 setSelectionRange 가 낳을 select 1회만 멘션 감지에서 제외한다
    suppressNextSelect()
    const caret = el.value.length
    el.setSelectionRange(caret, caret)
  }, [suppressNextSelect])

  // 탭 전환 시 멘션 상태 즉시 초기화 — blur 타이머 타이밍에 의존하지 않음
  const mentionReset = mention.reset
  useEffect(() => {
    if (activeTab !== 'write') {
      mentionReset()
      // 탭을 떠나는 것은 편집을 계속하겠다는 뜻이다 — 대기 중인 폐기 확인 요청도 함께 내린다.
      // (파생값이 미리보기에서 숨기긴 하지만, 플래그를 남겨 두면 쓰기 탭 복귀 시 되살아난다) — R-1
      setConfirmDiscard(false)
    }
  }, [activeTab, mentionReset])

  /**
   * 본문 편집 키 처리 (FR5) — `Ctrl`/`Cmd` + `Enter` 로 저장하고 맨 `Enter` 는 줄바꿈으로 남긴다.
   *
   * 본문은 여러 줄 마크다운 편집기라 맨 `Enter` 를 저장에 쓰면 개행 자체가 불가능해진다.
   * 그래서 저장은 수식키를 요구한다(Jira 동일).
   *
   * **호출 순서가 계약이다 (E10).** 멘션 팝업이 **먼저** 볼 기회를 갖는다 —
   * `use-mention-autocomplete.ts:222` 가 드롭다운이 열렸을 때만 Arrow/Enter/Tab/Escape 를
   * 가로채 `preventDefault()` 하므로, `defaultPrevented` 로 "팝업이 소비했다"를 판정할 수 있다.
   * 팝업이 열린 상태의 `Enter` 는 후보 선택이지 저장이 아니다.
   *
   * @param e textarea keydown 이벤트
   */
  function handleEditorKeyDown(e: ReactKeyboardEvent<HTMLTextAreaElement>): void {
    // 멘션 훅 호출은 **교체이지 삭제가 아니다** — 빠뜨리면 자동완성이 통째로 죽는다
    mention.onKeyDown(e)
    if (e.defaultPrevented) return

    // 한글 IME 조합 확정 Enter 를 저장으로 오인하지 않는다 (E4)
    if (e.nativeEvent.isComposing || e.keyCode === 229) return

    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
      e.preventDefault()
      // E5 중복 제출 방지의 **1차 방어는 아래 textarea 의 `disabled={isSaving}`** 이다.
      // 저장 중에는 keydown 자체가 발화하지 않아 이 줄은 현재 도달 불가다(실측 — 지워도 전량 초록).
      // 그럼에도 남기는 것은 `disabled` 가 제거될 때를 대비한 **이중 방어**이기 때문이다.
      // PATCH 중복 발행은 되돌리기 어려운 쓰기 사고라 2줄 보험이 값싸다.
      // (대조 — 제목 편집의 Input 에는 disabled 가 없어 같은 코드가 그쪽에선 유일한 가드다.)
      if (isSaving) return
      // 부모의 handleSave 가 draftMarkdown 을 클로저로 읽으므로 인자를 넘기지 않는다
      onSave()
    } else if (e.key === 'Escape') {
      // 상위 pane 닫기와 이중 발화 차단 — usePaneEscapeClose 가 defaultPrevented 를 존중한다 (E1)
      e.preventDefault()
      requestCancel()
    }
  }

  /**
   * 폐기 확인을 내리고 입력 영역으로 포커스를 되돌린다 — `계속 편집` 의 동작.
   * 포커스를 되돌리지 않으면 사용자가 버튼 위에 남아 바로 이어 쓰지 못한다.
   * `focus()` 만 부르고 커서는 건드리지 않는다(멘션 감지를 깨우지 않는 경로 — 실측).
   */
  function dismissDiscardConfirm(): void {
    setConfirmDiscard(false)
    textareaRef.current?.focus({ preventScroll: true })
  }

  /**
   * 편집 취소 요청 (FR6) — 초안이 원본과 다르면 확인을 먼저 거친다.
   *
   * **편차 D-1.** Jira 는 `Esc` 에 확인 없이 작성분을 버리고, Atlassian 이 개선하지 않기로
   * 공표했다(JRACLOUD-36670 · JRACLOUD-41814). 그 결함을 복제하지 않는다.
   *
   * **확인이 이미 떠 있으면 `Escape` 는 `계속 편집` 이다 (리뷰 R-2 경로 B).**
   * 예전에는 이미 true 인 플래그를 다시 true 로 세워 **화면이 전혀 변하지 않는데
   * `preventDefault()` 는 발화**했다. 그 결과 키보드 사용자는 확인을 진행할 수도, pane 을
   * 닫을 수도 없이 갇혔다. 두 번째 `Escape` 를 패널 닫기로 매핑해 그 죽은 키를 없앤다.
   * (폐기로 매핑하지 않는다 — `Esc` 두 번에 초안이 날아가면 우리가 피하려던 Jira 결함이 된다.)
   */
  function requestCancel(): void {
    if (showDiscardConfirm) {
      dismissDiscardConfirm()
      return
    }
    if (draftMarkdown !== initialMarkdown) {
      setConfirmDiscard(true)
      return
    }
    onCancel()
  }

  /**
   * 확인 패널 안에서의 `Escape` — `계속 편집` 으로 매핑한다 (다이얼로그 관례, 리뷰 R-2 경로 A).
   *
   * 패널이 뜨면 포커스가 `계속 편집` 으로 이동하므로 textarea 의 키 핸들러는 더 이상 발화하지
   * 않는다. 여기서 `preventDefault()` 를 하지 않으면 `usePaneEscapeClose` 의 **document 전역
   * 리스너**가 `e.defaultPrevented` 만 보고 pane 을 닫아 **작성분이 그대로 사라진다** —
   * 확인 패널이 지키기로 한 바로 그것을 못 지키게 된다.
   */
  function handleConfirmKeyDown(e: ReactKeyboardEvent<HTMLDivElement>): void {
    if (e.key !== 'Escape') return
    e.preventDefault()
    dismissDiscardConfirm()
  }

  return (
    <div className="flex flex-col gap-2">
      {/* 탭 헤더 */}
      <div className="flex border-b border-border" role="tablist">
        {/* PR22 OUT — P4 role="tab": 탭 시맨틱을 직접 지정하므로 Button 프리미티브의 role 처리와 충돌할 위험이 있다 */}
        <button
          type="button"
          role="tab"
          aria-selected={activeTab === 'write'}
          aria-label={issueDetailStrings.descriptionWriteTab}
          className={`${TAB_BASE_CLS} ${activeTab === 'write' ? TAB_ACTIVE_CLS : TAB_INACTIVE_CLS}`}
          onClick={() => onTabChange('write')}
        >
          {issueDetailStrings.descriptionWriteTab}
        </button>
        {/* PR22 OUT — P4 role="tab": 탭 시맨틱을 직접 지정하므로 Button 프리미티브의 role 처리와 충돌할 위험이 있다 */}
        <button
          type="button"
          role="tab"
          aria-selected={activeTab === 'preview'}
          aria-label={issueDetailStrings.descriptionPreviewTab}
          className={`${TAB_BASE_CLS} ${activeTab === 'preview' ? TAB_ACTIVE_CLS : TAB_INACTIVE_CLS}`}
          onClick={() => onTabChange('preview')}
        >
          {issueDetailStrings.descriptionPreviewTab}
        </button>
      </div>

      {/* 탭 콘텐츠 */}
      <div className="min-h-[120px]">
        {activeTab === 'write' ? (
          /* relative 컨테이너 — 드롭다운 absolute 앵커 역할(FR11/주의4) */
          <div className="relative">
            <textarea
              ref={textareaRef}
              className="w-full min-h-[120px] resize-y rounded-md border border-input bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
              value={draftMarkdown}
              onChange={mention.onChange}
              onKeyDown={handleEditorKeyDown}
              onCompositionStart={mention.onCompositionStart}
              onCompositionEnd={mention.onCompositionEnd}
              onSelect={mention.onSelect}
              onBlur={mention.onBlur}
              aria-label={issueDetailStrings.descriptionEditButton}
              disabled={isSaving}
            />
            {mention.mentionDropdown}
          </div>
        ) : (
          /* Preview 탭: descriptionHtml만 표시, draftMarkdown 노출 금지(NFR2) */
          <div className="min-h-[120px] rounded-md border border-input bg-background px-3 py-2">
            {descriptionHtml !== null ? (
              <div
                className="prose prose-sm max-w-none text-sm text-foreground"
                // biome-ignore lint/security/noDangerouslySetInnerHtml: 백엔드 OWASP 정화 HTML만 허용
                dangerouslySetInnerHTML={{ __html: descriptionHtml }}
              />
            ) : (
              <p className="text-sm text-muted-foreground italic">
                {issueDetailStrings.descriptionEmpty}
              </p>
            )}
          </div>
        )}
      </div>

      {/* 저장/취소 버튼 */}
      <div className="flex items-center gap-2">
        <Button
          size="sm"
          className="min-h-[44px]"
          onClick={onSave}
          disabled={actionsLocked}
          aria-label={issueDetailStrings.descriptionSaveButton}
        >
          {issueDetailStrings.descriptionSaveButton}
        </Button>
        <Button
          variant="outline"
          size="sm"
          className="min-h-[44px]"
          onClick={onCancel}
          disabled={actionsLocked}
          aria-label={issueDetailStrings.descriptionCancelButton}
        >
          {issueDetailStrings.descriptionCancelButton}
        </Button>
      </div>

      {/*
        작성분 폐기 확인 패널 (편차 D-1) — 기존 삭제 확인 관례(issues.$key.tsx:815-838)를 따르고
        새 다이얼로그 프리미티브를 도입하지 않는다.
        ★ 버튼 문자열은 위 저장/취소와 **의도적으로 다르다**(리뷰 F-2) — 같은 화면에 '취소' 가
        둘이면 E2E strict mode violation 이 나고 사용자도 「취소의 취소」를 이해하지 못한다.
      */}
      {showDiscardConfirm ? (
        <div
          onKeyDown={handleConfirmKeyDown}
          className="border border-destructive/40 rounded-md p-3 text-sm text-destructive space-y-2"
        >
          {/*
            role="alert" — 스크린리더에 패널 등장을 알린다 (리뷰 R-3).
            이 패널은 `Esc` 로만 도달하는 **키보드 전용 결정 지점**이라, 무음이면
            "편집을 빠져나갈 수 없다"고 느끼게 된다.
            role="alertdialog" 는 쓰지 않는다 — 포커스 트랩 등 모달 계약을 구현하지 않으므로
            틀린 role 을 붙이는 것이 아예 없는 것보다 나쁘다.
          */}
          <p role="alert">{issueDetailStrings.descriptionDiscardConfirm}</p>
          <div className="flex gap-2">
            <Button
              variant="destructive"
              size="sm"
              className="min-h-[44px]"
              onClick={() => {
                setConfirmDiscard(false)
                onCancel()
              }}
              aria-label={issueDetailStrings.descriptionDiscardConfirmButton}
            >
              {issueDetailStrings.descriptionDiscardConfirmButton}
            </Button>
            <Button
              variant="outline"
              size="sm"
              className="min-h-[44px]"
              // 패널이 뜨면 포커스를 여기로 옮긴다 — 안전한 선택지가 기본이고,
              // 포커스가 패널 안에 있어야 위 handleConfirmKeyDown 이 Escape 를 받는다 (R-2·R-3)
              autoFocus
              onClick={dismissDiscardConfirm}
              aria-label={issueDetailStrings.descriptionDiscardCancelButton}
            >
              {issueDetailStrings.descriptionDiscardCancelButton}
            </Button>
          </div>
        </div>
      ) : null}
    </div>
  )
}
