// 이슈 본문(description) 표시 및 편집 컴포넌트 — GitHub 스타일 Write/Preview 탭
import type { JSX } from 'react'
import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { issueDetailStrings } from '@/i18n/ko'

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
}

/** Write/Preview 탭 상태 */
type ActiveTab = 'write' | 'preview'

// ─────────────────────────────────────────────────────────────────────────────
// IssueDescription
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 본문(description) 표시 및 편집 컴포넌트.
 *
 * - 읽기 모드: descriptionHtml을 dangerouslySetInnerHTML로 렌더(백엔드 정화 신뢰 HTML).
 *   descriptionHtml=null이면 '본문이 없습니다.' placeholder 표시.
 * - 편집 모드: Write/Preview 탭 + textarea + 저장/취소 버튼.
 *   raw description은 Write 탭 textarea에서만 사용, 표시 경로 렌더 금지.
 * - WCAG AA: min-h-[44px] 터치 타깃, aria-label 필수.
 */
export function IssueDescription({
  descriptionHtml,
  description,
  onSave,
  isSaving,
}: IssueDescriptionProps): JSX.Element {
  const [isEditing, setIsEditing] = useState(false)
  const [activeTab, setActiveTab] = useState<ActiveTab>('write')
  const [draftMarkdown, setDraftMarkdown] = useState('')

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

  if (isEditing) {
    return (
      <EditMode
        draftMarkdown={draftMarkdown}
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
    />
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// ReadMode — 읽기 모드
// ─────────────────────────────────────────────────────────────────────────────

interface ReadModeProps {
  descriptionHtml: string | null
  onEditClick: () => void
}

/**
 * 본문 읽기 모드 — descriptionHtml 렌더 또는 placeholder.
 * raw description은 이 컴포넌트에서 절대 사용하지 않는다 (NFR2).
 */
function ReadMode({ descriptionHtml, onEditClick }: ReadModeProps): JSX.Element {
  return (
    <div className="flex flex-col gap-2">
      {/* 본문 영역 — HTML 렌더 또는 placeholder */}
      <div className="min-h-[44px]">
        {descriptionHtml !== null ? (
          // NFR1: 백엔드 정화 HTML만 dangerouslySetInnerHTML로 렌더
          <div
            data-testid="description-preview-content"
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

      {/* 편집 시작 버튼 */}
      <Button
        variant="outline"
        size="sm"
        className="self-start min-h-[44px]"
        onClick={onEditClick}
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
  onDraftChange,
  activeTab,
  onTabChange,
  descriptionHtml,
  onSave,
  onCancel,
  isSaving,
}: EditModeProps): JSX.Element {
  return (
    <div className="flex flex-col gap-2">
      {/* 탭 헤더 */}
      <div className="flex border-b border-border" role="tablist">
        <button
          type="button"
          role="tab"
          aria-selected={activeTab === 'write'}
          aria-label={issueDetailStrings.descriptionWriteTab}
          className={`px-3 py-2 text-sm font-medium min-h-[44px] border-b-2 transition-colors ${
            activeTab === 'write'
              ? 'border-primary text-primary'
              : 'border-transparent text-muted-foreground hover:text-foreground'
          }`}
          onClick={() => onTabChange('write')}
        >
          {issueDetailStrings.descriptionWriteTab}
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={activeTab === 'preview'}
          aria-label={issueDetailStrings.descriptionPreviewTab}
          className={`px-3 py-2 text-sm font-medium min-h-[44px] border-b-2 transition-colors ${
            activeTab === 'preview'
              ? 'border-primary text-primary'
              : 'border-transparent text-muted-foreground hover:text-foreground'
          }`}
          onClick={() => onTabChange('preview')}
        >
          {issueDetailStrings.descriptionPreviewTab}
        </button>
      </div>

      {/* 탭 콘텐츠 */}
      <div className="min-h-[120px]">
        {activeTab === 'write' ? (
          <textarea
            className="w-full min-h-[120px] resize-y rounded-md border border-input bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
            value={draftMarkdown}
            onChange={(e) => onDraftChange(e.target.value)}
            aria-label={issueDetailStrings.descriptionEditButton}
            disabled={isSaving}
          />
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
          disabled={isSaving}
          aria-label={issueDetailStrings.descriptionSaveButton}
        >
          {issueDetailStrings.descriptionSaveButton}
        </Button>
        <Button
          variant="outline"
          size="sm"
          className="min-h-[44px]"
          onClick={onCancel}
          disabled={isSaving}
          aria-label={issueDetailStrings.descriptionCancelButton}
        >
          {issueDetailStrings.descriptionCancelButton}
        </Button>
      </div>
    </div>
  )
}
