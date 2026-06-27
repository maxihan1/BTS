// 저장된 필터 공유 설정 모달 — AUTHENTICATED/PROJECT 토글 + GROUP 보존(EC4)
import type { JSX } from 'react'
import type { SavedFilterResponse } from '@/api/saved-filters'

/** ShareFilterDialog Props */
export interface ShareFilterDialogProps {
  /** 모달 열림 여부 */
  open: boolean
  /** 공유 설정을 변경할 저장 필터 */
  filter: SavedFilterResponse
  /** 닫기 또는 취소 시 호출 */
  onClose: () => void
}

/**
 * 저장된 필터의 공유 설정을 변경하는 모달.
 * AUTHENTICATED / PROJECT 토글만 편집하며, GROUP은 읽기·보존 전용이다.
 * [stub] RED 단계 — 구현 미완.
 */
export function ShareFilterDialog({ open }: ShareFilterDialogProps): JSX.Element {
  // stub — open은 미래 분기를 위해 읽지만 현재 렌더는 비어 있다
  return open ? <></> : <></>
}
