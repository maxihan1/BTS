// 저장된 필터 공유 설정 모달 — AUTHENTICATED/PROJECT 토글 + GROUP 보존(EC4)
import type { JSX } from 'react'
import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import {
  updateFilter,
  savedFiltersKey,
} from '@/api/saved-filters'
import type { SavedFilterResponse, ShareDto, UpdateFilterRequest } from '@/api/saved-filters'
import { savedFilterLabels } from '@/i18n/saved-filter-labels'

// ──────────────────────────────────────────────────────────────────────────────
// 순수 헬퍼 — shareType 판별
// ──────────────────────────────────────────────────────────────────────────────

function isAuthShare(share: ShareDto): boolean {
  return share.shareType === 'AUTHENTICATED'
}

function isOwnProjectShare(share: ShareDto, projectKey: string): boolean {
  return share.shareType === 'PROJECT' && share.targetId === projectKey
}

// ──────────────────────────────────────────────────────────────────────────────
// 공개 순수 함수 — EC4 회귀 가드 대상
// ──────────────────────────────────────────────────────────────────────────────

/** mergeShares 입력 — 토글 ON/OFF 상태 */
export interface MergeSharesToggles {
  /** AUTHENTICATED(모든 로그인 사용자) 공유 여부 */
  authEnabled: boolean
  /** PROJECT(이 프로젝트 멤버) 공유 여부 */
  projectEnabled: boolean
  /** 프로젝트 키 — PROJECT shareType의 targetId */
  projectKey: string
}

/**
 * 토글 상태와 보존 항목을 병합해 최종 PUT 요청용 shares 배열을 반환한다.
 *
 * 설계 원칙 (EC4).
 * - `preserved`에 담긴 GROUP·타 PROJECT 항목은 replace-all 위험 없이 그대로 유지된다.
 * - `toggles`에서 활성화된 공유 유형만 추가한다.
 * - 순수 함수 — 외부 상태 변이 없음.
 *
 * @param toggles - 편집 UI로 제어하는 AUTHENTICATED/PROJECT 토글 상태
 * @param preserved - 편집 대상이 아닌 보존 share 목록 (GROUP, 타 PROJECT 등)
 * @returns PUT 바디에 실릴 최종 shares 배열
 */
// eslint-disable-next-line react-refresh/only-export-components -- EC4 단위 커버용 순수 함수 공개 (컴포넌트 파일 내 유틸, 파일 분리 시 병렬 task 충돌 우려)
export const mergeShares = (
  toggles: MergeSharesToggles,
  preserved: ReadonlyArray<ShareDto>,
): ShareDto[] => {
  const result: ShareDto[] = [...preserved]
  if (toggles.projectEnabled) {
    result.push({ shareType: 'PROJECT', targetId: toggles.projectKey })
  }
  if (toggles.authEnabled) {
    result.push({ shareType: 'AUTHENTICATED', targetId: null })
  }
  return result
}

// ──────────────────────────────────────────────────────────────────────────────
// Props
// ──────────────────────────────────────────────────────────────────────────────

/** ShareFilterDialog Props */
export interface ShareFilterDialogProps {
  /** 모달 열림 여부 */
  open: boolean
  /**
   * 공유 설정을 변경할 저장 필터.
   * 이 prop이 바뀔 경우 부모에서 key={filter.id}로 재마운트해야 상태가 초기화된다.
   */
  filter: SavedFilterResponse
  /** 닫기 또는 취소 시 호출 */
  onClose: () => void
}

// ──────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ──────────────────────────────────────────────────────────────────────────────

/**
 * 저장된 필터의 공유 설정을 변경하는 모달.
 *
 * - AUTHENTICATED(모든 로그인 사용자) / PROJECT(이 프로젝트 멤버) 토글만 편집한다.
 * - GROUP 및 자기 projectKey가 아닌 PROJECT 항목은 읽기·보존 전용이다(EC4).
 * - 저장 시 PUT 바디에 name/aqlQuery/version을 함께 전송한다(B2).
 * - 부모에서 key={filter.id}를 줘야 filter prop 변경 시 상태가 올바르게 초기화된다.
 */
export function ShareFilterDialog({ open, filter, onClose }: ShareFilterDialogProps): JSX.Element {
  // 편집 토글 — filter.shares에서 초기값 파생
  const [authEnabled, setAuthEnabled] = useState<boolean>(() =>
    filter.shares.some(isAuthShare),
  )
  const [projectEnabled, setProjectEnabled] = useState<boolean>(() =>
    filter.shares.some((s) => isOwnProjectShare(s, filter.projectKey)),
  )

  // 편집 대상이 아닌 항목(GROUP, 타 PROJECT) — 제출 시 그대로 보존(EC4)
  const preserved: ShareDto[] = filter.shares.filter(
    (s) => !isAuthShare(s) && !isOwnProjectShare(s, filter.projectKey),
  )

  const queryClient = useQueryClient()

  const mutation = useMutation({
    mutationFn: (req: { id: string; payload: UpdateFilterRequest }) =>
      updateFilter(req.id, req.payload),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: savedFiltersKey.all() })
      onClose()
    },
  })

  function handleSave(): void {
    const shares = mergeShares({ authEnabled, projectEnabled, projectKey: filter.projectKey }, preserved)
    mutation.mutate({
      id: filter.id,
      payload: {
        name: filter.name,
        aqlQuery: filter.aqlQuery,
        version: filter.version,
        shares,
      },
    })
  }

  function handleOpenChange(next: boolean): void {
    if (!next) onClose()
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-sm" aria-describedby={undefined}>
        <DialogHeader>
          <DialogTitle>{savedFilterLabels.shareLabel}</DialogTitle>
        </DialogHeader>

        <div className="space-y-3">
          {/* PROJECT 토글 — 이 프로젝트 멤버 */}
          <label className="flex items-center gap-2 cursor-pointer select-none text-sm">
            <input
              type="checkbox"
              className="h-4 w-4 cursor-pointer"
              checked={projectEnabled}
              onChange={(e) => setProjectEnabled(e.target.checked)}
            />
            {savedFilterLabels.shareWithProjectMembers(filter.projectKey)}
          </label>

          {/* AUTHENTICATED 토글 — 모든 로그인 사용자 */}
          <label className="flex items-center gap-2 cursor-pointer select-none text-sm">
            <input
              type="checkbox"
              className="h-4 w-4 cursor-pointer"
              checked={authEnabled}
              onChange={(e) => setAuthEnabled(e.target.checked)}
            />
            {savedFilterLabels.shareWithAuthenticated}
          </label>
        </div>

        {mutation.isError && (
          <p className="text-sm text-destructive" role="alert">
            {savedFilterLabels.saveError}
          </p>
        )}

        <DialogFooter>
          <Button
            variant="outline"
            size="sm"
            onClick={onClose}
            disabled={mutation.isPending}
          >
            {savedFilterLabels.cancelButton}
          </Button>
          <Button
            size="sm"
            onClick={handleSave}
            disabled={mutation.isPending}
          >
            {savedFilterLabels.saveButton}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
