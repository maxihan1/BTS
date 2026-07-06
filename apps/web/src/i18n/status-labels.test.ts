// status-labels i18n 상수 검증 — 프리셋 6종 라벨 + 버튼/필드 라벨 존재 (FR-PR-02 Task 8)
import { describe, it, expect } from 'vitest'
import { statusLabels } from '@/i18n/status-labels'
import { EXPIRY_PRESETS } from '@/lib/status-expiry'

describe('statusLabels', () => {
  it('제목/저장/해제 버튼 라벨을 가진다', () => {
    expect(statusLabels.title).toBeTruthy()
    expect(statusLabels.saveButton).toBeTruthy()
    expect(statusLabels.clearButton).toBeTruthy()
  })

  it('EXPIRY_PRESETS 전 프리셋에 대응하는 라벨이 있다', () => {
    for (const preset of EXPIRY_PRESETS) {
      expect(statusLabels.presets[preset]).toBeTruthy()
    }
  })
})
