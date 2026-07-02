// 번다운/번업 차트 페이지 i18n 라벨 (FR-RP-01 D6)

export const burndownLabels = {
  page: {
    title: '번다운 / 번업 차트',
  },

  toggle: {
    burndown: '번다운',
    burnup: '번업',
  },

  series: {
    remaining: '잔여',
    ideal: '이상선',
    scope: '범위',
    completed: '완료',
  },

  status: {
    loading: '불러오는 중…',
    forbidden: '접근 권한이 없습니다.',
    sprintNotFound: '스프린트를 찾을 수 없습니다.',
    datesRequired: '스프린트 시작일과 종료일을 설정해야 번다운을 볼 수 있습니다.',
    empty: '아직 표시할 데이터가 없습니다.',
  },

  chart: {
    ariaLabel: '번다운 차트, 스프린트의 잔여 작업량과 이상적인 소진 추이, 범위 변화를 선으로 보여줍니다',
  },
} as const

export type BurndownLabels = typeof burndownLabels
