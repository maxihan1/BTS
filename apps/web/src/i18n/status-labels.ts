// 상태 설정 모달 한국어 라벨 상수 — 프리셋/필드/버튼 (FR-PR-02 Task 8)
import type { ExpiryPreset } from '@/lib/status-expiry'

/** 상태 설정 모달의 고정 한국어 라벨 (BC 내 상수, profile-labels 관례). */
export const statusLabels = {
  title: '상태 설정',
  emojiLabel: '이모지',
  emojiPlaceholder: '🌴',
  textLabel: '상태 메시지',
  textPlaceholder: '무엇을 하고 있나요?',
  expiryLabel: '유지 기간',
  saveButton: '저장',
  savingButton: '저장 중...',
  clearButton: '상태 지우기',
  errorMessage: '상태를 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.',
  presets: {
    none: '안 지움',
    '30m': '30분',
    '1h': '1시간',
    '4h': '4시간',
    today: '오늘',
    week: '이번 주',
  } satisfies Record<ExpiryPreset, string>,
} as const
