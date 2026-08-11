// 대시보드 생성/편집 공용 폼 컴포넌트 — name 검증·visibility 연동·3-state 편집 페이로드 (FR-DB-01 Task 6)
import type { JSX } from 'react'
import { useState } from 'react'
import { dashboardLabels } from '@/i18n/dashboard-labels'
import { useUserSearch } from '@/hooks/use-user-directory'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

const VISIBILITY_OPTIONS = ['PRIVATE', 'TEAM', 'ORG'] as const

/** 대시보드 공개 범위 타입 */
type Visibility = (typeof VISIBILITY_OPTIONS)[number]

/** name 최대 길이 */
const NAME_MAX_LENGTH = 200

// ─────────────────────────────────────────────────────────────────────────────
// 초기값 타입
// ─────────────────────────────────────────────────────────────────────────────

/** 편집 모드 초기값 */
export interface DashboardFormInitialValues {
  /** 기존 이름 */
  name: string
  /** 기존 설명 (미설정 시 undefined) */
  description?: string
  /** 기존 공개 범위 */
  visibility: string
  /** 기존 공유 사용자 ID 목록 */
  sharedUserIds: string[]
  /** OCC 버전 (편집 모드에서 부모가 주입) */
  version?: number
}

// ─────────────────────────────────────────────────────────────────────────────
// 제출 페이로드 타입
// ─────────────────────────────────────────────────────────────────────────────

/**
 * DashboardForm 제출 페이로드.
 *
 * - 생성 모드: name·description·visibility·sharedUserIds 전부 포함.
 * - 편집 모드: 변경된 필드만 포함 (3-state diff). version은 부모 주입.
 */
export type DashboardFormPayload = Record<string, unknown>

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** DashboardForm Props */
export interface DashboardFormProps {
  /** 'create' = 생성 모드, 'edit' = 편집 모드 */
  mode: 'create' | 'edit'
  /** 편집 모드 초기값 (create 시 undefined 가능) */
  initialValues?: DashboardFormInitialValues
  /** 제출 콜백 — 페이로드를 부모에 전달 */
  onSubmit: (payload: DashboardFormPayload) => void
  /** 제출 중 여부 — true이면 버튼 disabled + "저장 중" 표시 */
  isPending: boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// 순수 헬퍼 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * name 필드 검증 — EC8.
 * 통과 시 undefined, 실패 시 오류 메시지 반환.
 */
function validateName(value: string): string | undefined {
  if (value.trim() === '') return '이름을 입력해 주세요.'
  if (value.length > NAME_MAX_LENGTH) return `이름은 ${NAME_MAX_LENGTH}자 이하여야 합니다.`
  return undefined
}

/**
 * 편집 모드 3-state diff 페이로드 계산.
 * 변경된 필드만 포함한 Record를 반환한다.
 */
function buildEditPayload(
  current: { name: string; description: string; visibility: Visibility; sharedUserIds: string[] },
  initial: DashboardFormInitialValues,
): DashboardFormPayload {
  const payload: DashboardFormPayload = {}

  if (current.name.trim() !== initial.name) {
    payload['name'] = current.name.trim()
  }

  const trimmedDesc = current.description.trim()
  const initialDesc = initial.description ?? ''
  if (trimmedDesc !== initialDesc) {
    payload['description'] = trimmedDesc !== '' ? trimmedDesc : undefined
  }

  const initialVisibility = (initial.visibility as Visibility | undefined) ?? 'PRIVATE'
  if (current.visibility !== initialVisibility) {
    payload['visibility'] = current.visibility
  }

  const currentShared = current.visibility === 'TEAM' ? current.sharedUserIds : []
  const initialShared = initial.sharedUserIds
  const sharedChanged =
    currentShared.length !== initialShared.length ||
    currentShared.some((id, i) => id !== initialShared[i])
  if (sharedChanged) {
    payload['sharedUserIds'] = currentShared
  }

  return payload
}

// ─────────────────────────────────────────────────────────────────────────────
// 공유 사용자 선택 서브컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

interface SharedUserPickerProps {
  /** 현재 선택된 사용자 ID 목록 */
  selectedIds: string[]
  /** 선택 변경 콜백 */
  onChange: (ids: string[]) => void
}

/**
 * TEAM visibility 선택 시 표시되는 공유 사용자 입력 UI.
 *
 * - 검색 input(aria-label="공유")으로 useUserSearch 호출.
 * - 결과 드롭다운에서 선택 → selectedIds에 추가.
 * - 선택된 사용자 태그 표시 + 제거 가능.
 */
function SharedUserPicker({ selectedIds, onChange }: SharedUserPickerProps): JSX.Element {
  const [query, setQuery] = useState('')
  const { data: users } = useUserSearch(query)

  function handleSelect(id: string): void {
    if (selectedIds.includes(id)) return
    onChange([...selectedIds, id])
    setQuery('')
  }

  function handleRemove(id: string): void {
    onChange(selectedIds.filter((sid) => sid !== id))
  }

  const suggestions = (users ?? []).filter((u) => !selectedIds.includes(u.id))

  return (
    <div className="space-y-2">
      <Label htmlFor="share-search">{dashboardLabels.form.share}</Label>
      <Input
        id="share-search"
        aria-label={dashboardLabels.form.share}
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        placeholder={dashboardLabels.ownerSearchPlaceholder}
        autoComplete="off"
      />

      {query.length >= 2 && suggestions.length > 0 && (
        <ul
          role="listbox"
          aria-label="사용자 검색 결과"
          className="border rounded-md bg-popover shadow-sm"
        >
          {suggestions.map((u) => (
            <li key={u.id} role="option" aria-selected={false}>
              {/* PR22 OUT — P5 옵션 행: 콤보박스 후보라 w-full text-left 가 필요하고, Button의 inline-flex/justify-center와 충돌한다 */}
              <button
                type="button"
                className="w-full text-left px-3 py-2 text-sm hover:bg-accent"
                onClick={() => handleSelect(u.id)}
              >
                {u.displayName ?? u.username}
              </button>
            </li>
          ))}
        </ul>
      )}

      {selectedIds.length > 0 && (
        <ul className="flex flex-wrap gap-2" aria-label="선택된 공유 사용자">
          {selectedIds.map((id) => {
            const found = (users ?? []).find((u) => u.id === id)
            const label = found?.displayName ?? found?.username ?? id
            return (
              <li key={id}>
                <span className="inline-flex items-center gap-1 rounded-full bg-secondary px-2.5 py-0.5 text-xs font-medium">
                  {label}
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon-xs"
                    aria-label={`${label} 제거`}
                    className="ml-1 rounded-full hover:bg-muted"
                    onClick={() => handleRemove(id)}
                  >
                    ×
                  </Button>
                </span>
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// DashboardForm 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 대시보드 생성/편집 공용 폼.
 *
 * - name: 1~200자 필수 (EC8).
 * - description: 선택.
 * - visibility: PRIVATE(기본) / TEAM / ORG.
 * - TEAM 선택 시에만 공유 사용자 입력 활성 (EC7). 0명 허용.
 * - 생성 모드: 전체 필드 페이로드.
 * - 편집 모드: 변경 필드만 페이로드 (3-state diff).
 * - isPending=true이면 버튼 disabled + "저장 중" 표시.
 *
 * @param mode 'create' | 'edit'
 * @param initialValues 편집 시 초기값
 * @param onSubmit 제출 콜백
 * @param isPending 제출 중 여부
 */
export function DashboardForm({
  mode,
  initialValues,
  onSubmit,
  isPending,
}: DashboardFormProps): JSX.Element {
  const [name, setName] = useState(initialValues?.name ?? '')
  const [description, setDescription] = useState(initialValues?.description ?? '')
  const [visibility, setVisibility] = useState<Visibility>(
    (initialValues?.visibility as Visibility | undefined) ?? 'PRIVATE',
  )
  const [sharedUserIds, setSharedUserIds] = useState<string[]>(
    initialValues?.sharedUserIds ?? [],
  )
  const [nameError, setNameError] = useState<string | undefined>()

  function handleSubmit(e: React.FormEvent<HTMLFormElement>): void {
    e.preventDefault()

    const nameErr = validateName(name)
    if (nameErr !== undefined) {
      setNameError(nameErr)
      return
    }
    setNameError(undefined)

    if (mode === 'create') {
      onSubmit({
        name: name.trim(),
        description: description.trim() !== '' ? description.trim() : undefined,
        visibility,
        sharedUserIds: visibility === 'TEAM' ? sharedUserIds : [],
      })
      return
    }

    // 편집 모드: 3-state diff — buildEditPayload로 위임
    const initial = initialValues ?? { name: '', description: '', visibility: 'PRIVATE', sharedUserIds: [] }
    onSubmit(buildEditPayload({ name, description, visibility, sharedUserIds }, initial))
  }

  const visibilityLabel: Record<Visibility, string> = {
    PRIVATE: dashboardLabels.card.visibility.PRIVATE,
    TEAM: dashboardLabels.card.visibility.TEAM,
    ORG: dashboardLabels.card.visibility.ORG,
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-4 w-full max-w-md" noValidate>
      {/* name */}
      <div className="space-y-1">
        <Label htmlFor="dashboard-name">{dashboardLabels.form.name}</Label>
        <Input
          id="dashboard-name"
          value={name}
          onChange={(e) => {
            setName(e.target.value)
            if (nameError !== undefined) setNameError(undefined)
          }}
          placeholder={dashboardLabels.namePlaceholder}
          disabled={isPending}
          aria-describedby={nameError !== undefined ? 'dashboard-name-error' : undefined}
          aria-invalid={nameError !== undefined}
        />
        {nameError !== undefined && (
          <p
            id="dashboard-name-error"
            role="alert"
            className="text-sm text-destructive"
          >
            {nameError}
          </p>
        )}
      </div>

      {/* description */}
      <div className="space-y-1">
        <Label htmlFor="dashboard-description">{dashboardLabels.form.description}</Label>
        <Input
          id="dashboard-description"
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          placeholder={dashboardLabels.descriptionPlaceholder}
          disabled={isPending}
        />
      </div>

      {/* visibility */}
      <div className="space-y-1">
        <Label htmlFor="dashboard-visibility">{dashboardLabels.form.visibility}</Label>
        <select
          id="dashboard-visibility"
          aria-label={dashboardLabels.form.visibility}
          value={visibility}
          onChange={(e) => setVisibility(e.target.value as Visibility)}
          disabled={isPending}
          className="flex h-9 w-full rounded-md border border-input bg-background px-3 py-1 text-sm shadow-sm transition-colors focus:outline-none focus:ring-1 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
        >
          {VISIBILITY_OPTIONS.map((v) => (
            <option key={v} value={v}>
              {visibilityLabel[v]}
            </option>
          ))}
        </select>
      </div>

      {/* 공유 사용자 — TEAM 선택 시에만 표시 (EC7) */}
      {visibility === 'TEAM' && (
        <SharedUserPicker
          selectedIds={sharedUserIds}
          onChange={setSharedUserIds}
        />
      )}

      {/* 제출 버튼 */}
      <Button type="submit" disabled={isPending} className="w-full">
        {isPending ? dashboardLabels.detail.saving : dashboardLabels.detail.save}
      </Button>
    </form>
  )
}
