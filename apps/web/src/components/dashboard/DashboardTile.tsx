// 대시보드 그리드 단일 타일 컴포넌트 — 인라인 편집·삭제·접근성·가젯 통합 (FR-DB-01 Task 8 / FR-DB-02 Task 7)
import type { JSX, KeyboardEvent } from 'react'
import { useState, useRef, useEffect } from 'react'
import { LayoutDashboard, Trash2 } from 'lucide-react'
import { dashboardLabels } from '@/i18n/dashboard-labels'
import type { DashboardTile as DashboardTileData } from '@/lib/dashboard-layout'
import { GadgetRenderer } from '@/components/dashboard/gadgets/GadgetRenderer'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** DashboardTile 컴포넌트 Props */
export interface DashboardTileProps {
  /** 타일 데이터 (id, title, 위치·크기) */
  tile: DashboardTileData
  /** 편집 권한 — true면 인라인 편집·삭제 활성 */
  canEdit: boolean
  /** 타일 삭제 요청 콜백 */
  onDelete: (id: string) => void
  /** 제목 인라인 편집 완료 콜백 */
  onEditTitle: (id: string, title: string) => void
}

// ─────────────────────────────────────────────────────────────────────────────
// DashboardTile 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 대시보드 그리드 단일 타일.
 *
 * - canEdit=true: 제목 클릭 시 인라인 편집 input 활성. 삭제 버튼 노출.
 *   드래그/리사이즈는 react-grid-layout이 담당 (cursor-grab).
 * - canEdit=false: 읽기 전용. 편집 UI 전부 숨김.
 * - 접근성: 삭제 버튼 aria-label, 터치 타깃 44px.
 * - 중립 한국어 문구 — 내부 FR 식별자 노출 절대 금지.
 */
export function DashboardTile({ tile, canEdit, onDelete, onEditTitle }: DashboardTileProps): JSX.Element {
  const [editing, setEditing] = useState(false)
  const [editValue, setEditValue] = useState(tile.title)
  const inputRef = useRef<HTMLInputElement>(null)

  /** 편집 모드 진입 시 input에 포커스 */
  useEffect(() => {
    if (editing && inputRef.current !== null) {
      inputRef.current.focus()
      inputRef.current.select()
    }
  }, [editing])

  /** 제목 클릭 시 편집 모드 시작 */
  function handleTitleClick(): void {
    if (!canEdit) return
    setEditing(true)
  }

  /** 편집 완료 — 빈 값이면 원래 제목으로 복원 */
  function commitEdit(): void {
    const trimmed = editValue.trim()
    const next = trimmed !== '' ? trimmed : tile.title
    setEditing(false)
    setEditValue(next)
    onEditTitle(tile.i, next)
  }

  /** input 키보드 처리 */
  function handleInputKeyDown(e: KeyboardEvent<HTMLInputElement>): void {
    if (e.key === 'Enter') {
      e.preventDefault()
      commitEdit()
    } else if (e.key === 'Escape') {
      setEditing(false)
      setEditValue(tile.title)
    }
  }

  return (
    <div
      className={[
        'h-full rounded-lg border shadow-sm bg-card flex flex-col',
        canEdit ? 'cursor-grab active:cursor-grabbing' : 'cursor-default',
      ].join(' ')}
    >
      {/* 타일 헤더 */}
      <div className="flex items-center justify-between px-3 py-2 border-b">
        <div className="flex items-center gap-2 min-w-0 flex-1">
          <LayoutDashboard
            className="h-4 w-4 shrink-0 text-muted-foreground"
            aria-hidden="true"
          />

          {/*
           * 헤더 레이블 분기.
           * - 가젯 타일(gadgetType 있음): gadgetType 텍스트 표시(편집 불가).
           * - legacy 타일: 기존 인라인 편집 동작 그대로.
           */}
          {tile.gadgetType !== undefined ? (
            <span className="min-w-0 flex-1 text-sm font-medium truncate text-muted-foreground">
              {tile.gadgetType}
            </span>
          ) : editing ? (
            <input
              ref={inputRef}
              className="min-w-0 flex-1 text-sm font-medium bg-transparent border-b border-primary outline-none"
              value={editValue}
              onChange={(e) => setEditValue(e.target.value)}
              onBlur={commitEdit}
              onKeyDown={handleInputKeyDown}
              aria-label="위젯 제목 편집"
            />
          ) : (
            <button
              type="button"
              className={[
                'min-w-0 flex-1 text-left text-sm font-medium truncate',
                canEdit ? 'hover:underline cursor-text' : '',
              ].join(' ')}
              onClick={handleTitleClick}
              disabled={!canEdit}
              aria-label={canEdit ? `${tile.title} — 클릭하여 제목 편집` : tile.title}
            >
              {tile.title}
            </button>
          )}
        </div>

        {/* 삭제 버튼 — canEdit=true일 때만 표시 */}
        {canEdit && (
          <button
            type="button"
            className="ml-2 rounded p-1 text-muted-foreground hover:text-destructive hover:bg-destructive/10 transition-colors min-h-[44px] min-w-[44px] flex items-center justify-center"
            aria-label={`${tile.gadgetType ?? tile.title} 삭제`}
            onClick={() => onDelete(tile.i)}
          >
            <Trash2 className="h-4 w-4" aria-hidden="true" />
          </button>
        )}
      </div>

      {/*
       * 타일 본문 분기.
       * - 가젯 타일(gadgetType 있음): GadgetRenderer가 가젯 컴포넌트를 렌더.
       * - legacy 타일: 기존 placeholder 텍스트.
       */}
      {tile.gadgetType !== undefined ? (
        <div className="flex flex-1 overflow-auto">
          <GadgetRenderer tile={tile} />
        </div>
      ) : (
        <div className="flex flex-1 items-center justify-center p-4 text-sm text-muted-foreground text-center">
          {dashboardLabels.placeholder.description}
        </div>
      )}
    </div>
  )
}
