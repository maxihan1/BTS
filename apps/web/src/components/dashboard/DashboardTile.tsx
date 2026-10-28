// 대시보드 그리드 단일 타일 컴포넌트 — 인라인 편집·삭제·접근성·가젯 통합 (FR-DB-01 Task 8 / FR-DB-02 Task 7)
import type { JSX, KeyboardEvent } from 'react'
import { useState, useRef, useEffect } from 'react'
import { Copy, LayoutDashboard, MoreHorizontal, Palette, Settings2, Trash2 } from 'lucide-react'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { dashboardModeLabels } from '@/i18n/dashboard-labels'
import { Button } from '@/components/ui/button'
import { dashboardLabels, gadgetLabels } from '@/i18n/dashboard-labels'
import type { DashboardTile as DashboardTileData } from '@/lib/dashboard-layout'
import { GadgetRenderer } from '@/components/dashboard/gadgets/GadgetRenderer'
import { PublicGadgetRenderer } from '@/components/dashboard/gadgets/PublicGadgetRenderer'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** DashboardTile 컴포넌트 Props */
export interface DashboardTileProps {
  /** 타일 데이터 (id, title, 위치·크기) */
  tile: DashboardTileData
  /**
   * 편집 **모드** — 권한과 다른 축이다 (Jira 패리티 JD-1).
   *
   * `canEdit` 은 「고칠 수 있는 사람인가」이고 이것은 「지금 고치는 중인가」다.
   * 권한이 있어도 기본은 보기 모드라, 보는 동안 실수로 타일을 끌어 배치가 망가지지 않는다.
   */
  isEditing: boolean
  /** 타일 복제 요청 — 설정을 그대로 복사한다 (JD-3) */
  onDuplicate: (id: string) => void
  /** 가젯 설정 폼 다시 열기 요청 (JD-19). 없으면 「구성」 항목을 숨긴다. legacy 타일엔 늘 없다 */
  onConfigure?: (id: string) => void
  /** 강조 색 변경 (JD-19). `undefined` = 흰색(띠 없음). 없으면 「강조 색상」 항목을 숨긴다 */
  onAccentChange?: (id: string, accent: string | undefined) => void
  /** 편집 권한 — true면 인라인 편집·삭제 활성 */
  canEdit: boolean
  /** 타일 삭제 요청 콜백 */
  onDelete: (id: string) => void
  /** 제목 인라인 편집 완료 콜백 */
  onEditTitle: (id: string, title: string) => void
  /**
   * 익명(비로그인) 공유 뷰 모드 — true면 canEdit 값과 무관하게 강제 읽기전용이며
   * 가젯 본문을 PublicGadgetRenderer(정적 화이트리스트, fail-closed)로 렌더한다.
   * 기본 false — 기존 인증 모드 동작 불변 (FR-DB-01 default 보호 패턴, FR-DB-03 D6/D7 Task-8).
   */
  publicMode?: boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// 강조 색 (JD-19)
// ─────────────────────────────────────────────────────────────────────────────

/** 흰색 — 띠 없음. 저장할 때는 `accent` 키를 지운다 */
const ACCENT_NONE = 'none'

/**
 * 강조 색 키 → 타일 위 띠 클래스. 키 순서가 메뉴 순서다(Jira 8색 radio 와 같다).
 * 테마 토큰이 있는 색은 토큰을 쓴다(다크 자동). 노랑 · 청록은 토큰이 없어 팔레트 값이다.
 */
const ACCENT_CLASS: Record<string, string> = {
  blue: 'border-t-info',
  red: 'border-t-danger',
  yellow: 'border-t-yellow-400',
  green: 'border-t-success',
  teal: 'border-t-teal-500',
  purple: 'border-t-discovery',
  gray: 'border-t-neutral-bold',
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
 * - publicMode=true: canEdit 값과 무관하게 강제 읽기전용 + 본문을 PublicGadgetRenderer로 렌더
 *   (익명 공유 뷰, FR-DB-03 D6/D7 Task-8).
 * - 접근성: 삭제 버튼 aria-label, 터치 타깃 44px.
 * - 중립 한국어 문구 — 내부 FR 식별자 노출 절대 금지.
 */
export function DashboardTile({
  tile,
  canEdit,
  isEditing,
  onDuplicate,
  onConfigure,
  onAccentChange,
  onDelete,
  onEditTitle,
  publicMode = false,
}: DashboardTileProps): JSX.Element {
  /** publicMode면 canEdit 값과 무관하게 강제 읽기전용 (FR-DB-03 D6/D7 Task-8) */
  // ★세 조건의 곱이다. 권한이 있고(canEdit) · 익명 뷰가 아니고(!publicMode) ·
  //   지금 편집 모드여야(isEditing) 편집 UI 가 산다. 하나라도 빠지면 보기 중에 배치가 바뀐다.
  const effectiveCanEdit = canEdit && !publicMode && isEditing

  const [editing, setEditing] = useState(false)
  // C3: title?: string — undefined 방어를 위해 초기값에 ?? '' 적용
  const [editValue, setEditValue] = useState(tile.title ?? '')
  const inputRef = useRef<HTMLInputElement>(null)

  /**
   * C6: 가젯 타일 헤더 표시 라벨.
   * gadgetLabels 매핑 적용 후 미지 타입은 raw gadgetType을 fallback으로 사용.
   */
  const gadgetHeaderLabel =
    tile.gadgetType !== undefined
      ? (gadgetLabels[tile.gadgetType] ?? tile.gadgetType)
      : undefined

  /** 편집 모드 진입 시 input에 포커스 */
  useEffect(() => {
    if (editing && inputRef.current !== null) {
      inputRef.current.focus()
      inputRef.current.select()
    }
  }, [editing])

  /** 제목 클릭 시 편집 모드 시작 */
  function handleTitleClick(): void {
    if (!effectiveCanEdit) return
    setEditing(true)
  }

  /** 편집 완료 — 빈 값이면 원래 제목으로 복원 */
  function commitEdit(): void {
    const trimmed = editValue.trim()
    // C3: tile.title?: string — undefined 방어
    const next = trimmed !== '' ? trimmed : (tile.title ?? '')
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
      // C3: tile.title?: string — undefined 방어
      setEditValue(tile.title ?? '')
    }
  }

  /** 삭제 버튼 aria-label — 가젯은 한국어 라벨, legacy는 title 사용 */
  const deleteAriaLabel = `${gadgetHeaderLabel ?? tile.title ?? ''} 삭제`

  /** 모르는 키는 띠 없음으로 떨어진다 — layout 은 사용자 JSON 이라 아무 값이나 올 수 있다 */
  const accentClass = tile.accent !== undefined ? ACCENT_CLASS[tile.accent] : undefined

  return (
    <div
      data-accent={accentClass !== undefined ? tile.accent : undefined}
      className={[
        'h-full rounded-lg border shadow-sm bg-card flex flex-col',
        accentClass !== undefined ? `border-t-4 ${accentClass}` : '',
        effectiveCanEdit ? 'cursor-grab active:cursor-grabbing' : 'cursor-default',
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
           * - 가젯 타일(gadgetType 있음): gadgetLabels 한국어 라벨 표시(편집 불가).
           *   미지 타입은 raw gadgetType fallback (C6).
           * - legacy 타일: 기존 인라인 편집 동작 그대로.
           */}
          {gadgetHeaderLabel !== undefined ? (
            <span className="min-w-0 flex-1 text-sm font-medium truncate text-muted-foreground">
              {gadgetHeaderLabel}
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
            /* PR22 OUT — P6 전체 클릭 영역: 타일 제목 인라인 편집 트리거로 flex-1 text-left truncate 가 필요하고, Button의 justify-center와 충돌한다 */
            <button
              type="button"
              className={[
                'min-w-0 flex-1 text-left text-sm font-medium truncate',
                effectiveCanEdit ? 'hover:underline cursor-text' : '',
              ].join(' ')}
              onClick={handleTitleClick}
              disabled={!effectiveCanEdit}
              // C3: tile.title?: string — undefined 방어
              aria-label={
                effectiveCanEdit ? `${tile.title ?? ''} — 클릭하여 제목 편집` : (tile.title ?? '')
              }
            >
              {tile.title}
            </button>
          )}
        </div>

        {/*
         * 타일 `⋯` 메뉴 — 편집 모드에서만 뜬다 (JD-3).
         * Jira 는 타일마다 More actions 드롭다운을 두고 Duplicate 을 담는다.
         * ⚠️ 트리거 이름이 타일마다 같으므로 e2e 는 컨테이너로 스코프를 좁혀야 한다
         *    (BoardActionsMenu 가 세운 선례와 같은 함정).
         */}
        {effectiveCanEdit && (
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button
                type="button"
                variant="ghost"
                size="icon-sm"
                className="ml-2 min-h-[44px] min-w-[44px] rounded text-muted-foreground"
                aria-label={dashboardModeLabels.tileMenuAriaLabel}
              >
                <MoreHorizontal className="h-4 w-4" aria-hidden="true" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              {/* Jira 순서 — 강조 색상 · 구성 · 복제 · 삭제 (JD-19) */}
              {onAccentChange !== undefined && (
                <DropdownMenuSub>
                  <DropdownMenuSubTrigger>
                    <Palette className="h-4 w-4" aria-hidden="true" />
                    {dashboardModeLabels.accent}
                  </DropdownMenuSubTrigger>
                  <DropdownMenuSubContent>
                    <DropdownMenuRadioGroup
                      value={accentClass !== undefined ? tile.accent : ACCENT_NONE}
                      onValueChange={(next) => {
                        onAccentChange(tile.i, next === ACCENT_NONE ? undefined : next)
                      }}
                    >
                      {Object.entries(dashboardModeLabels.accentColors).map(([key, label]) => (
                        <DropdownMenuRadioItem key={key} value={key}>
                          {label}
                        </DropdownMenuRadioItem>
                      ))}
                    </DropdownMenuRadioGroup>
                  </DropdownMenuSubContent>
                </DropdownMenuSub>
              )}
              {tile.gadgetType !== undefined && onConfigure !== undefined && (
                <DropdownMenuItem
                  onSelect={() => {
                    onConfigure(tile.i)
                  }}
                >
                  <Settings2 className="h-4 w-4" aria-hidden="true" />
                  {dashboardModeLabels.configure}
                </DropdownMenuItem>
              )}
              <DropdownMenuItem
                onSelect={() => {
                  onDuplicate(tile.i)
                }}
              >
                <Copy className="h-4 w-4" aria-hidden="true" />
                {dashboardModeLabels.duplicate}
              </DropdownMenuItem>
              <DropdownMenuItem
                variant="destructive"
                aria-label={deleteAriaLabel}
                onSelect={() => {
                  onDelete(tile.i)
                }}
              >
                <Trash2 className="h-4 w-4" aria-hidden="true" />
                {dashboardModeLabels.delete}
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        )}
      </div>

      {/*
       * 타일 본문 분기.
       * - publicMode(익명 공유 뷰): gadgetType 유무와 무관하게 PublicGadgetRenderer가
       *   정적 화이트리스트 렌더(fail-closed) — legacy 타일도 로그인 필요 플레이스홀더로 처리(Task 7).
       * - 가젯 타일(gadgetType 있음): GadgetRenderer가 가젯 컴포넌트를 렌더.
       * - legacy 타일: 기존 placeholder 텍스트.
       */}
      {publicMode ? (
        <div className="flex flex-1 overflow-auto">
          <PublicGadgetRenderer tile={tile} />
        </div>
      ) : tile.gadgetType !== undefined ? (
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
