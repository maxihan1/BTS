// Import 매핑 마법사 컨테이너 — RED 단계 임시 스텁 (FR-IM-02 D6 Task-6)
import type { JSX } from 'react'

/** ImportMappingWizard Props */
export interface ImportMappingWizardProps {
  /** Import 대상 프로젝트 키 */
  readonly projectKey: string
}

/** RED 단계 임시 스텁 — GREEN 커밋에서 전체 상태머신으로 교체 예정 */
export const ImportMappingWizard = (_props: ImportMappingWizardProps): JSX.Element => {
  return <div />
}
