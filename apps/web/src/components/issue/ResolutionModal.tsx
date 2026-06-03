// 종료 전이 시 결의안 선택 모달 (FR-IS-07 Task B9)
import type { JSX } from 'react'
import { useState, useEffect } from 'react'
import { Dialog as DialogPrimitive } from 'radix-ui'
import { Button } from '@/components/ui/button'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { useResolutions } from '@/hooks/use-resolutions'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 모달 설명 요소 ID — aria-describedby 연결용 */
const MODAL_DESCRIPTION_ID = 'resolution-modal-description'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/**
 * pre-fill에 필요한 최소 resolution 정보.
 * 단건 이슈 응답(IssueResponse.resolution)과 전체 Resolution 타입 모두에 호환된다.
 */
export interface PrefilledResolution {
  /** 결의안 UUID */
  readonly id: string
  /** URL-safe 슬러그 */
  readonly key: string
  /** 표시 이름 */
  readonly name: string
}

/** ResolutionModal 컴포넌트 props */
export interface ResolutionModalProps {
  /** 모달 열림 여부 */
  readonly open: boolean
  /**
   * 이미 resolution이 설정된 이슈의 DONE→DONE 전이 시 기존 값으로 pre-fill.
   * null이면 선택 없음 상태로 시작.
   */
  readonly prefilledResolution: PrefilledResolution | null
  /**
   * 확인 버튼 클릭 시 선택된 결의안 UUID를 전달하는 콜백.
   * @param resolutionId 선택된 결의안의 UUID
   */
  readonly onConfirm: (resolutionId: string) => void
  /** 취소 버튼 클릭 시 호출되는 콜백 */
  readonly onCancel: () => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 종료 전이 시 결의안 선택 모달.
 *
 * - DONE 카테고리 전이 선택 시 부모가 open=true로 마운트한다.
 * - useResolutions(B8)로 결의안 목록을 로드해 드롭다운에 표시한다.
 * - prefilledResolution이 있으면 초기 선택값으로 pre-fill한다 (DONE→DONE 재전이 시).
 * - resolution 미선택 상태에서 확인 버튼은 비활성이다.
 * - 확인 시 onConfirm(resolutionId)를 호출한다.
 *
 * @param open 모달 열림 여부
 * @param prefilledResolution 기존 결의안 (DONE→DONE 전이 시 pre-fill)
 * @param onConfirm 확인 콜백 — 선택된 resolutionId 전달
 * @param onCancel 취소 콜백
 */
export function ResolutionModal({
  open,
  prefilledResolution,
  onConfirm,
  onCancel,
}: ResolutionModalProps): JSX.Element {
  const { data: resolutions = [], isLoading } = useResolutions()

  /** 현재 선택된 결의안 ID. pre-fill이 있으면 그 값으로 초기화. */
  const [selectedId, setSelectedId] = useState<string>(
    prefilledResolution?.id ?? '',
  )

  /**
   * 모달이 열릴 때(open=true)마다 선택 상태를 pre-fill 값으로 리셋한다.
   * 동일 컴포넌트 인스턴스가 재사용될 때 이전 선택값이 남지 않도록 방어한다.
   */
  useEffect(() => {
    if (open) {
      setSelectedId(prefilledResolution?.id ?? '')
    }
  }, [open, prefilledResolution])

  /** 확인 버튼 활성 조건: 결의안이 선택되어 있어야 한다 */
  const canConfirm = selectedId !== ''

  function handleConfirm(): void {
    if (!canConfirm) return
    onConfirm(selectedId)
  }

  function handleOpenChange(next: boolean): void {
    if (!next) onCancel()
  }

  return (
    <DialogPrimitive.Root open={open} onOpenChange={handleOpenChange}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="fixed inset-0 z-50 bg-black/40 data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0" />

        <DialogPrimitive.Content
          role="dialog"
          aria-describedby={MODAL_DESCRIPTION_ID}
          aria-label="종료 결의안 선택"
          className="fixed left-1/2 top-1/2 z-50 w-full max-w-md -translate-x-1/2 -translate-y-1/2 rounded-xl bg-background p-6 shadow-xl data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95"
        >
          <DialogPrimitive.Title className="text-lg font-semibold mb-2">
            종료 결의안 선택
          </DialogPrimitive.Title>

          <p id={MODAL_DESCRIPTION_ID} className="text-sm text-muted-foreground mb-4">
            이슈를 종료하려면 결의안을 선택해 주세요.
          </p>

          {/* 결의안 드롭다운 */}
          <div>
            <label htmlFor="resolution-select" className="text-sm font-medium mb-1 block">
              결의안
            </label>
            {isLoading ? (
              <p className="text-sm text-muted-foreground py-2" aria-live="polite">
                결의안 목록을 불러오는 중...
              </p>
            ) : (
              <Select value={selectedId} onValueChange={setSelectedId}>
                <SelectTrigger
                  id="resolution-select"
                  className="w-full"
                  aria-label="결의안 선택"
                >
                  <SelectValue placeholder="결의안을 선택하세요" />
                </SelectTrigger>
                <SelectContent>
                  {resolutions.map((resolution) => (
                    <SelectItem key={resolution.id} value={resolution.id}>
                      {resolution.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            )}
          </div>

          {/* 액션 버튼 */}
          <div className="flex justify-end gap-2 mt-6">
            <Button
              variant="outline"
              size="sm"
              onClick={onCancel}
              aria-label="취소"
            >
              취소
            </Button>
            <Button
              size="sm"
              disabled={!canConfirm}
              onClick={handleConfirm}
              aria-label="확인"
            >
              확인
            </Button>
          </div>
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}
