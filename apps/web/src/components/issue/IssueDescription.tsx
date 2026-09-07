// 이슈 본문(description) 표시 및 편집 — TipTap WYSIWYG (Jira 패리티 J8)
import type { JSX, KeyboardEvent as ReactKeyboardEvent, MouseEvent as ReactMouseEvent } from 'react'
import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { issueDetailStrings } from '@/i18n/ko'
import { RichTextEditor } from '@/components/editor/RichTextEditor'
import { RICH_TEXT_CLASS } from '@/components/editor/rich-text-class'
import { TextLengthCounter } from '@/components/editor/TextLengthCounter'
import { DESCRIPTION_MAX_LENGTH } from '@/lib/issue-text-constraints'
import { AttachmentHtml } from './AttachmentHtml'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** IssueDescription 컴포넌트 props */
export interface IssueDescriptionProps {
  /**
   * 백엔드가 정화한 HTML 문자열. null 이면 본문 없음.
   *
   * 읽기·편집 **양쪽**이 이 값을 쓴다. 마크다운 왕복이 사라져(V039) 편집기에 넣는 것과
   * 화면에 그리는 것이 같은 문자열이다 — 「미리보기와 실제가 다르다」가 원천적으로 없다.
   */
  descriptionHtml: string | null
  /**
   * 이슈 식별 키 — 본문 이미지의 첨부 참조(`attachment:<uuid>`)를 내려받고,
   * 편집 중 붙여넣은 이미지를 이 이슈의 첨부로 올린다(J7).
   */
  issueKey: string
  /** 저장 버튼 클릭 시 호출 — 편집된 **HTML** 문자열 전달 */
  onSave: (html: string) => void
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
   */
  restrictedFields?: string[]
  /**
   * 편집 불가 필드 키 목록 — "description"이 포함되면 canEdit과 AND로
   * 편집 버튼을 disabled 처리한다 (FR-PM-07 §3.2).
   */
  noneditableFields?: string[]
}

/** 빈 본문일 때 편집기에 넣는 값 — TipTap 은 빈 문자열을 빈 문단으로 정규화한다. */
const EMPTY_DOC = ''

// ─────────────────────────────────────────────────────────────────────────────
// IssueDescription
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 본문 표시·편집.
 *
 * ## Write/Preview 탭이 사라진 이유
 *
 * 마크다운 textarea 시절에는 원문과 결과가 달라서 미리보기가 필요했다. WYSIWYG 은 **쓰는
 * 화면이 곧 결과**라 탭이 없다 — Jira 도 그렇다. `descriptionWriteTab`/`descriptionPreviewTab`
 * 문자열과 `activeTab` 상태가 함께 사라졌다.
 *
 * ## 남긴 것
 *
 * 작성분 폐기 확인 패널(편차 D-1)은 그대로다. WYSIWYG 이어도 「Esc 한 번에 작성분이 날아간다」는
 * 여전히 나쁘고, 그 결함은 Jira 에도 있다. 판정식만 마크다운 비교에서 HTML 비교로 바뀐다.
 */
export function IssueDescription({
  descriptionHtml,
  issueKey,
  onSave,
  isSaving,
  canEdit = true,
  restrictedFields = [],
  noneditableFields = [],
}: IssueDescriptionProps): JSX.Element {
  const [isEditing, setIsEditing] = useState(false)
  const [draftHtml, setDraftHtml] = useState('')

  // FR-PM-07 §3.1: "description"이 restrictedFields에 포함되면 열람 자체를 차단
  const isRestricted = restrictedFields.includes('description')
  // FR-PM-07 §3.2: canEdit AND noneditableFields 둘 중 하나라도 false이면 편집 불가
  const isEditDisabled = !canEdit || noneditableFields.includes('description')

  function handleEditClick(): void {
    setDraftHtml(descriptionHtml ?? EMPTY_DOC)
    setIsEditing(true)
  }

  function handleCancel(): void {
    setIsEditing(false)
    setDraftHtml('')
  }

  function handleSave(): void {
    onSave(draftHtml)
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
        issueKey={issueKey}
        draftHtml={draftHtml}
        // 편집 진입 시 draftHtml 을 seed 한 값과 같은 식이어야 변경분 판정이 정확하다
        initialHtml={descriptionHtml ?? EMPTY_DOC}
        onDraftChange={setDraftHtml}
        onSave={handleSave}
        onCancel={handleCancel}
        isSaving={isSaving}
      />
    )
  }

  return (
    <ReadMode
      descriptionHtml={descriptionHtml}
      issueKey={issueKey}
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
  issueKey: string
  onEditClick: () => void
  canEdit: boolean
}

/**
 * 클릭 진입이 가능할 때 본문 읽기 영역에 붙는 hover 어포던스 (FR-UX-11 F8 NFR5).
 * hover 배경은 DESIGN.md §7 상태 토큰 — 라이트 `#F1F2F4` / 다크 `#A1BDD914` 가 이미 정의돼
 * 두 테마에 자동 대응한다(임의 알파 금지).
 */
const DESCRIPTION_CLICKABLE_CLS = '-mx-2 rounded px-2 cursor-text hover:bg-(--bg-neutral-hover)'

/**
 * 본문 읽기 모드 — descriptionHtml 렌더 또는 placeholder.
 *
 * `dangerouslySetInnerHTML` 을 쓰는 근거는 그대로다 — 이 HTML 은 **서버가 정화한 것**이고
 * (`MarkdownRenderer.sanitizeHtml`), 정화 지점이 하나라 검증 대상이 갈라지지 않는다(NFR1).
 */
function ReadMode({ descriptionHtml, issueKey, onEditClick, canEdit }: ReadModeProps): JSX.Element {
  /** 본문 클릭 진입 — 텍스트를 드래그로 선택하는 중이면 진입하지 않는다 (FR-UX-11 F8). */
  function handleBodyClick(e: ReactMouseEvent<HTMLDivElement>): void {
    if (!canEdit) return
    if (window.getSelection()?.toString() !== '') return
    // 링크를 눌렀으면 이동이 우선이다 — 편집으로 가로채면 본문 안 링크가 죽는다.
    if ((e.target as HTMLElement).closest('a') !== null) return
    onEditClick()
  }

  const clickableCls = canEdit ? ` ${DESCRIPTION_CLICKABLE_CLS}` : ''

  return (
    <div className="flex flex-col gap-2">
      {descriptionHtml !== null && descriptionHtml !== '' ? (
        // 본문 클릭 진입은 편집 버튼과 **같은 동작의 보조 경로**다(FR-UX-11 F8). 키보드
        // 사용자는 바로 아래 「본문 편집」 버튼으로 도달하므로 여기에 키 핸들러를 더하지 않는다 —
        // 본문 안 텍스트 선택·링크 이동을 방해하지 않는 것이 우선이다.
        <div onClick={handleBodyClick}>
          <AttachmentHtml
            html={descriptionHtml}
            issueKey={issueKey}
            testId="description-body"
            className={`${RICH_TEXT_CLASS} text-sm text-foreground${clickableCls}`}
          />
        </div>
      ) : (
        <p
          data-testid="description-empty"
          onClick={handleBodyClick}
          className={`text-sm text-muted-foreground italic${clickableCls}`}
        >
          {issueDetailStrings.descriptionEmpty}
        </p>
      )}
      <div>
        <Button
          type="button"
          variant="ghost"
          size="xs"
          onClick={onEditClick}
          disabled={!canEdit}
          className="text-muted-foreground hover:text-foreground disabled:opacity-40 disabled:cursor-not-allowed"
          aria-label={issueDetailStrings.descriptionEditButton}
        >
          {issueDetailStrings.descriptionEditButton}
        </Button>
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// EditMode — 편집 모드
// ─────────────────────────────────────────────────────────────────────────────

interface EditModeProps {
  /** 붙여넣은 이미지를 매달 이슈 키 (J7) */
  issueKey: string
  draftHtml: string
  initialHtml: string
  onDraftChange: (next: string) => void
  onSave: () => void
  onCancel: () => void
  isSaving: boolean
}

/**
 * 본문 편집 모드 — WYSIWYG 에디터 + 저장/취소 + 작성분 폐기 확인.
 *
 * 폐기 확인(편차 D-1)의 계약을 그대로 승계한다. 판정식만 마크다운 비교 → HTML 비교다.
 */
function EditMode({
  issueKey,
  draftHtml,
  initialHtml,
  onDraftChange,
  onSave,
  onCancel,
  isSaving,
}: EditModeProps): JSX.Element {
  const [confirmDiscard, setConfirmDiscard] = useState(false)

  /**
   * 초안 변경 — 타이핑은 "계속 편집하겠다"는 의사표시다.
   * 폐기 확인이 떠 있는 채로 빨간 경고 아래에서 계속 쓰게 두지 않는다 (R-1).
   */
  function handleDraftChange(next: string): void {
    setConfirmDiscard(false)
    onDraftChange(next)
  }

  /**
   * 폐기 확인 패널 **실제 노출** 여부 — 상태가 아니라 파생값이다 (리뷰 R-1).
   *
   * `confirmDiscard` 만으로 그리면 저장 후에도 "작성 중인 내용이 사라집니다" 가 남아
   * **문구가 거짓말**이 된다. 초안이 원본과 같아지는 순간 자동 해소된다.
   */
  const showDiscardConfirm = confirmDiscard && draftHtml !== initialHtml

  /**
   * 상단 `저장`·`취소` 잠금 — 사유 2가지.
   *
   * - `isSaving`. 저장 진행 중 중복 제출 방지 (E5).
   * - `showDiscardConfirm`. 확인을 띄워 놓고 확인 없이 버리는 모순 차단 (리뷰 C-1).
   */
  const actionsLocked = isSaving || showDiscardConfirm

  /**
   * 상한 초과 — 저장을 막는다.
   *
   * 재는 것은 **HTML** 이다. 서버 `UpdateIssueRequest` 가 `description` 과 `descriptionHtml`
   * 양쪽에 `@Size(max = DESCRIPTION_MAX)` 를 걸고, 프론트가 보내는 것은 HTML 이다.
   * 막지 않으면 사용자는 32,767자를 다 쓴 뒤 저장 버튼을 눌러서야 400 으로 알게 된다.
   */
  const isOverLimit = draftHtml.length > DESCRIPTION_MAX_LENGTH

  function dismissDiscardConfirm(): void {
    setConfirmDiscard(false)
  }

  /**
   * 취소 요청 — 초안이 원본과 다르면 먼저 확인을 띄운다.
   *
   * 확인이 이미 떠 있으면 두 번째 `Escape` 는 **패널 닫기**다. 폐기로 매핑하지 않는다 —
   * `Esc` 두 번에 초안이 날아가면 우리가 피하려던 Jira 결함이 된다.
   */
  function requestCancel(): void {
    if (showDiscardConfirm) {
      dismissDiscardConfirm()
      return
    }
    if (draftHtml !== initialHtml) {
      setConfirmDiscard(true)
      return
    }
    onCancel()
  }

  /**
   * 확인 패널 안에서의 `Escape` — `계속 편집` 으로 매핑한다 (다이얼로그 관례, R-2 경로 A).
   *
   * `preventDefault()` 가 필수다. 없으면 `usePaneEscapeClose` 의 **document 전역 리스너**가
   * pane 을 닫아 **작성분이 그대로 사라진다** — 확인 패널이 지키기로 한 바로 그것을 못 지킨다.
   */
  function handleConfirmKeyDown(e: ReactKeyboardEvent<HTMLDivElement>): void {
    if (e.key !== 'Escape') return
    e.preventDefault()
    dismissDiscardConfirm()
  }

  return (
    <div className="flex flex-col gap-2">
      <RichTextEditor
        initialHtml={initialHtml}
        onChange={handleDraftChange}
        onSubmit={() => { if (!isOverLimit) onSave() }}
        onCancel={requestCancel}
        editable={!isSaving}
        autoFocus
        placeholder={issueDetailStrings.descriptionEmpty}
        ariaLabel={issueDetailStrings.descriptionEditLabel}
        imageIssueKey={issueKey}
      />

      {/*
        길이 카운터 — 서버가 재는 것은 **정화된 HTML** 이다(`@Size` on `descriptionHtml`).
        보이는 글자 수를 세면 서식이 많은 본문에서 카운터가 거짓말을 한다.
      */}
      <TextLengthCounter
        length={draftHtml.length}
        max={DESCRIPTION_MAX_LENGTH}
        testId="description-length-counter"
      />

      {/* 저장/취소 버튼 */}
      <div className="flex items-center gap-2">
        <Button
          size="sm"
          className="min-h-[44px]"
          onClick={onSave}
          disabled={actionsLocked || isOverLimit}
          aria-label={issueDetailStrings.descriptionSaveButton}
        >
          {issueDetailStrings.descriptionSaveButton}
        </Button>
        <Button
          variant="outline"
          size="sm"
          className="min-h-[44px]"
          onClick={requestCancel}
          disabled={actionsLocked}
          aria-label={issueDetailStrings.descriptionCancelButton}
        >
          {issueDetailStrings.descriptionCancelButton}
        </Button>
      </div>

      {/*
        작성분 폐기 확인 패널 (편차 D-1) — 기존 삭제 확인 관례를 따르고 새 다이얼로그
        프리미티브를 도입하지 않는다.
        ★버튼 문자열은 위 저장/취소와 **의도적으로 다르다**(리뷰 F-2) — 같은 화면에 '취소' 가
        둘이면 E2E strict mode violation 이 나고 사용자도 「취소의 취소」를 이해하지 못한다.
      */}
      {showDiscardConfirm ? (
        <div
          onKeyDown={handleConfirmKeyDown}
          className="border border-destructive/40 rounded-md p-3 text-sm text-destructive space-y-2"
        >
          {/*
            role="alert" — 스크린리더에 패널 등장을 알린다 (리뷰 R-3).
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
