// 이슈 상세 고정 헤더 — 경로·액션·제목. 본문/메타가 스크롤해도 이 영역은 제자리에 남는다 (J25~J27)
import type { JSX, RefObject, KeyboardEvent as ReactKeyboardEvent } from 'react'
import { FileDown, X } from 'lucide-react'
import type { IssueResponse } from '@/api/issues'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { IssueDetailPresentationMenu } from '@/components/issue/IssueDetailPresentationMenu'
import { SUMMARY_MAX_LENGTH } from '@/components/issue/create/issue-create-schema'
import { issueDetailStrings } from '@/i18n/ko'

interface IssueDetailHeaderProps {
  /** 표시할 이슈. `projectKey`·`key`·`summary` 만 쓴다. */
  issue: IssueResponse
  /** 렌더 모드. `'pane'` 이면 제목이 `<h2>` 로 강등되고 닫기·표시방식 버튼이 붙는다. */
  variant: 'page' | 'pane'
  /** 이슈 키 — 표시 방식 메뉴가 스토어를 갱신할 때 쓴다. */
  issueKey: string
  /** UPDATE 권한. 없으면 제목이 순수 텍스트로 남고 편집 진입면이 비활성이다. */
  canEdit: boolean
  /** 제목 편집 모드 여부 */
  isEditingTitle: boolean
  /** 편집 중인 제목 값 */
  editSummary: string
  /** 저장 진행 중 — 저장 버튼 비활성 */
  isSavingTitle: boolean
  /** PDF 다운로드 진행 중 — 버튼 비활성 */
  isPdfDownloading: boolean
  onEditSummaryChange: (value: string) => void
  onTitleKeyDown: (e: ReactKeyboardEvent<HTMLInputElement>) => void
  onTitleClick: () => void
  onEditStart: () => void
  onEditSave: () => void
  onEditCancel: () => void
  onPdfDownload: () => void
  onMoveClick: () => void
  /** pane 전용 닫기. `variant === 'pane'` 일 때만 버튼을 그린다. */
  onClose?: () => void
  /** 편집 진입 후 포커스를 받을 입력창 */
  titleInputRef: RefObject<HTMLInputElement | null>
  /** 편집 종료 후 포커스가 돌아갈 제목 버튼 */
  titleButtonRef: RefObject<HTMLButtonElement | null>
  /** pane 마운트 시 포커스를 받는 제목 heading */
  paneTitleRef: RefObject<HTMLHeadingElement | null>
}

/**
 * 이슈 상세의 **스크롤하지 않는 머리 영역**.
 *
 * ## 왜 별도 컴포넌트인가
 *
 * 본문·메타가 각자 스크롤하게 되면서(J26) 이 영역은 두 스크롤 컨테이너의 **바깥 형제**가
 * 됐다 — 구조상 다른 층이므로 파일도 나눈다. 덤으로 `IssueDetailPage` 의 200줄 래칫을
 * 갚는다(`IssueReporterRow`·`IssueMetaActions` 선례와 같은 처방).
 *
 * ## `position: sticky` 를 쓰지 않는다
 *
 * 부모가 `flex-col` 이고 이 헤더가 `shrink-0` 형제라 스크롤 영역과 **면적이 겹치지 않는다**.
 * `sticky` 로 만들면 헤더가 스크롤 콘텐츠 위에 떠서, 댓글 딥링크가 `scrollIntoView` 로
 * 데려간 행이 헤더 밑에 깔린다(FR-UX-03 #459 가 맞춘 위치가 어긋난다). 겹치지 않는 구조가
 * `scroll-margin-top` 같은 보정을 아예 필요 없게 만든다.
 *
 * ## 제목을 여기 두는 이유
 *
 * Maxi 지적이 「본문 스크롤 시 위에 서머리와 UI 는 고정」이다. 제목이 본문 컬럼 안에 있으면
 * 본문과 함께 밀려 올라가 「지금 어느 이슈를 보는가」가 사라진다.
 */
export function IssueDetailHeader({
  issue, variant, issueKey, canEdit, isEditingTitle, editSummary, isSavingTitle,
  isPdfDownloading, onEditSummaryChange, onTitleKeyDown, onTitleClick, onEditStart,
  onEditSave, onEditCancel, onPdfDownload, onMoveClick, onClose,
  titleInputRef, titleButtonRef, paneTitleRef,
}: IssueDetailHeaderProps): JSX.Element {
  // ── 제목 본문 ─────────────────────────────────────────────────────────────
  // 수정 권한이 있으면 제목 텍스트 자체를 편집 진입면(button)으로 감싼다.
  // heading 요소는 그대로 두고 "안쪽만" 감싸므로 heading 의 접근성 이름은
  // 내부 텍스트에서 계산돼 보존된다 (jira-parity-contract §2 즉사 계약).
  // <button> 이라 키보드 Tab·Enter 로도 도달·발동된다.
  const titleContent = canEdit ? (
    // PR22 OUT — P6 전체 클릭 영역: 제목 인라인 편집 트리거로 w-full text-left 가 필요하고,
    // Button 프리미티브의 inline-flex justify-center · h-8 px-2.5 text-sm 과 충돌한다
    // (DashboardTile 타일 제목 인라인 편집과 동형 — 같은 P6 판정).
    //
    // 🛑 이 버튼에 `aria-label` 을 붙이지 마라. 감싸는 h1/h2 의 접근성 이름은 자손 텍스트로
    //    계산되는데, aria-label 이 붙으면 **heading 의 이름까지 그 문자열로 대체돼**
    //    jira-parity-contract §2 즉사 계약(h1 verbatim)과 E2E `getByRole('heading',{name})`
    //    가 동시에 깨진다 (F8-T1-2 가 현재 보존의 증인).
    //    용도 설명이 필요하면 `aria-describedby` + 시각적 숨김 텍스트를 쓸 것.
    //
    // 🛑 `select-text` 를 지우지 마라 — 장식이 아니다. <button> 에서 `user-select: auto` 는
    //    CSS UI 규격상 **none 으로 해석**된다(Chromium 실측. 이 클래스 없이 드래그하면 선택
    //    길이 0). 제목을 버튼으로 감싼 순간 사용자가 **제목을 복사할 수 없게 되는** 회귀가
    //    생기고(감싸기 전 <h1> 순수 텍스트에서는 됐다), 선택 자체가 안 생기니
    //    handleTitleClick 의 isCollapsed 가드도 영원히 발동하지 못한다.
    <button
      ref={titleButtonRef}
      type="button"
      onClick={onTitleClick}
      className="text-left w-full rounded-sm select-text hover:bg-(--bg-neutral-hover) focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--border-focus)"
    >
      {issue.summary}
    </button>
  ) : (
    issue.summary
  )

  return (
    // `shrink-0` 이 없으면 flex 가 이 영역부터 눌러 제목이 잘린다 — 줄어들 곳은 스크롤 영역이다.
    //
    // 🛑 `<header>` 를 쓰지 마라 — **모달에서 banner 랜드마크를 가로챈다.** HTML-AAM 상
    //    `<header>` 는 `article/aside/main/nav/section` 자손일 때만 banner 를 잃는데, Radix 는
    //    DialogContent 를 body 직속으로 포털하고 `role="dialog"` 인 div 는 그 목록에 없다.
    //    실측 — 모달을 연 상태에서 `role=banner` 조회가 `TopBar` 가 아니라 이 요소를 잡았다.
    //    `ShellLayout` KDoc 이 「banner = TopBar 의 <header>」로 랜드마크 소유를 못박고 있다.
    //    시각·판정에 필요한 것은 testid 와 `border-b` 뿐이라 `<header>` 로 얻는 것이 없다.
    <div data-testid="issue-detail-header" className="shrink-0 border-b border-border pb-4">
      {/* breadcrumb 행 — 좌측 경로 / 우측 액션 버튼 */}
      <div className="flex items-center justify-between mb-4">
        <nav aria-label="이동 경로" className="text-sm text-muted-foreground">
          <span>{issue.projectKey}</span>
          <span className="mx-1.5">/</span>
          <span className="font-medium text-foreground">{issue.key}</span>
        </nav>
        <div className="flex gap-2">
          {/* 이슈 이동 버튼 — UPDATE 권한 게이팅 (FR-MV-01 D6) */}
          {canEdit && (
            <Button variant="outline" size="sm" aria-label="이슈 이동" onClick={onMoveClick}>
              이슈 이동
            </Button>
          )}
          <Button
            variant="secondary"
            size="sm"
            disabled={isPdfDownloading}
            aria-label={issueDetailStrings.pdfDownloadAriaLabel}
            onClick={onPdfDownload}
          >
            <FileDown className="size-4 mr-1.5" aria-hidden="true" />
            {issueDetailStrings.pdfDownloadButton}
          </Button>
          {variant === 'pane' && <IssueDetailPresentationMenu issueKey={issueKey} />}
          {/* pane 전용 닫기 버튼 — split view 우측 페인 (FR-UX-06 PR20 Task 1) */}
          {variant === 'pane' && (
            <Button
              type="button"
              variant="ghost"
              size="icon-sm"
              aria-label="닫기"
              onClick={() => onClose?.()}
            >
              <X className="size-4" aria-hidden="true" />
            </Button>
          )}
        </div>
      </div>

      {isEditingTitle ? (
        <div className="flex flex-col gap-2">
          <Input
            ref={titleInputRef}
            aria-label={issueDetailStrings.titleEditLabel}
            value={editSummary}
            onChange={(e) => onEditSummaryChange(e.target.value)}
            onKeyDown={onTitleKeyDown}
            // ★상한이 **아예 없었다** — 생성 폼에만 zod 검증이 있어 저장 시점에야 400 이었다.
            maxLength={SUMMARY_MAX_LENGTH}
            className="text-xl font-semibold"
          />
          <div className="flex gap-2">
            <Button
              size="sm"
              onClick={onEditSave}
              disabled={isSavingTitle || !canEdit}
              aria-label={issueDetailStrings.saveButton}
              data-testid="issue-title-save"
            >
              {issueDetailStrings.saveButton}
            </Button>
            <Button
              size="sm"
              variant="outline"
              onClick={onEditCancel}
              aria-label={issueDetailStrings.cancelButton}
            >
              {issueDetailStrings.cancelButton}
            </Button>
          </div>
        </div>
      ) : (
        <>
          {/* pane이면 h2로 강등 — 문서 h1 단일 계약 (FR-UX-06 PR20 Task 1) */}
          {variant === 'pane' ? (
            <h2 ref={paneTitleRef} tabIndex={-1} className="text-2xl font-semibold leading-snug mb-1">
              {titleContent}
            </h2>
          ) : (
            <h1 className="text-2xl font-semibold leading-snug mb-1">{titleContent}</h1>
          )}
          <Button
            type="button"
            variant="ghost"
            size="xs"
            onClick={onEditStart}
            disabled={!canEdit}
            className="text-muted-foreground hover:text-foreground disabled:opacity-40 disabled:cursor-not-allowed"
            aria-label={issueDetailStrings.editTitleButton}
          >
            {issueDetailStrings.editTitleButton}
          </Button>
        </>
      )}
    </div>
  )
}
