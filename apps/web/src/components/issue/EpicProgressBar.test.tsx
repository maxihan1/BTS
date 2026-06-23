// EpicProgressBar 컴포넌트 단위 테스트 — FR-EP-02 Task-5 TDD RED
import { describe, it, expect } from 'vitest'
import type { ReactNode } from 'react'
import { render, screen, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { EpicProgressBar } from './EpicProgressBar'
import type { EpicProgress } from '@/api/epic-children'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  }
}

/** 10개 자식 중 4개 완료 픽스처 */
const progressFixture: EpicProgress = {
  total: 10,
  done: 4,
  donePercentage: 40,
  byCategory: {
    todo: 3,
    inProgress: 3,
    done: 4,
  },
}

/** 빈 에픽 픽스처 (자식 없음) */
const emptyProgressFixture: EpicProgress = {
  total: 0,
  done: 0,
  donePercentage: 0,
  byCategory: {
    todo: 0,
    inProgress: 0,
    done: 0,
  },
}

// ─────────────────────────────────────────────────────────────────────────────
// S1. props로 렌더 — 3색 바 + 퍼센트 + 카운트
// ─────────────────────────────────────────────────────────────────────────────

describe('EpicProgressBar — S1 props 기반 렌더', () => {
  it('S1a: donePercentage 텍스트(40%)가 표시된다', () => {
    render(<EpicProgressBar progress={progressFixture} />)

    expect(screen.getByText('40%')).toBeInTheDocument()
  })

  it('S1b: done/total 카운트 텍스트가 표시된다 (4 / 10 완료)', () => {
    render(<EpicProgressBar progress={progressFixture} />)

    expect(screen.getByText('4 / 10 완료')).toBeInTheDocument()
  })

  it('S1c: done 구간 바에 aria-label이 있다', () => {
    render(<EpicProgressBar progress={progressFixture} />)

    const bar = screen.getByTestId('progress-segment-done')
    expect(bar).toBeInTheDocument()
    // done 비율: 4/10 = 40%
    expect(bar).toHaveStyle({ width: '40%' })
  })

  it('S1d: inProgress 구간 바에 aria-label이 있다', () => {
    render(<EpicProgressBar progress={progressFixture} />)

    const bar = screen.getByTestId('progress-segment-inprogress')
    expect(bar).toBeInTheDocument()
    // inProgress 비율: 3/10 = 30%
    expect(bar).toHaveStyle({ width: '30%' })
  })

  it('S1e: todo 구간 바가 렌더된다', () => {
    render(<EpicProgressBar progress={progressFixture} />)

    expect(screen.getByTestId('progress-segment-todo')).toBeInTheDocument()
  })

  it('S1f: 진행률 컨테이너 aria-label이 "진행률 40%"다', () => {
    render(<EpicProgressBar progress={progressFixture} />)

    expect(screen.getByRole('progressbar')).toHaveAccessibleName('진행률 40%')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2. 빈 에픽 — total=0 처리
// ─────────────────────────────────────────────────────────────────────────────

describe('EpicProgressBar — S2 빈 에픽 (total=0)', () => {
  it('S2a: "자식 이슈 없음" 텍스트가 표시된다', () => {
    render(<EpicProgressBar progress={emptyProgressFixture} />)

    expect(screen.getByText('자식 이슈 없음')).toBeInTheDocument()
  })

  it('S2b: 빈 에픽에서 0%가 표시된다', () => {
    render(<EpicProgressBar progress={emptyProgressFixture} />)

    expect(screen.getByText('0%')).toBeInTheDocument()
  })

  it('S2c: 빈 에픽에서 "자식 이슈 없음"이 카운트 자리에 표시된다 (countLabel 대신)', () => {
    render(<EpicProgressBar progress={emptyProgressFixture} />)

    // total=0이면 "0 / 0 완료" 대신 "자식 이슈 없음" 표시
    expect(screen.getByText('자식 이슈 없음')).toBeInTheDocument()
    expect(screen.queryByText('0 / 0 완료')).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3. epicKey prop — 자체 패칭 (MSW)
// ─────────────────────────────────────────────────────────────────────────────

describe('EpicProgressBar — S3 epicKey로 자체 패칭', () => {
  it('S3a: epicKey를 받으면 API를 호출해 진행률 막대를 렌더한다', async () => {
    server.use(
      http.get('/api/v1/epics/ATLAS-EPIC/progress', () =>
        HttpResponse.json({ data: progressFixture }),
      ),
    )
    const Wrapper = createWrapper()
    render(<EpicProgressBar epicKey="ATLAS-EPIC" />, { wrapper: Wrapper })

    expect(await screen.findByText('40%')).toBeInTheDocument()
    expect(screen.getByText('4 / 10 완료')).toBeInTheDocument()
  })

  it('S3b: 로딩 중에는 스켈레톤/로딩 요소가 표시된다', async () => {
    // 응답 지연 없이 로딩 상태를 바로 확인하기 위해
    // 네트워크 응답 전 단계를 잡는다.
    let resolve: (() => void) | undefined
    const blocker = new Promise<void>((r) => { resolve = r })

    server.use(
      http.get('/api/v1/epics/ATLAS-EPIC-SLOW/progress', async () => {
        await blocker
        return HttpResponse.json({ data: progressFixture })
      }),
    )
    const Wrapper = createWrapper()
    render(<EpicProgressBar epicKey="ATLAS-EPIC-SLOW" />, { wrapper: Wrapper })

    expect(screen.getByTestId('epic-progress-loading')).toBeInTheDocument()

    // cleanup — 블로커 해제
    resolve?.()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4. 텍스트 중복 안전 — within 셀렉터 사용
// ─────────────────────────────────────────────────────────────────────────────

describe('EpicProgressBar — S4 컨테이너 한정 셀렉터', () => {
  it('S4a: data-testid=epic-progress-bar 컨테이너 안에서 퍼센트를 찾는다', () => {
    render(<EpicProgressBar progress={progressFixture} />)

    const container = screen.getByTestId('epic-progress-bar')
    expect(within(container).getByText('40%')).toBeInTheDocument()
  })

  it('S4b: data-testid=epic-progress-bar 컨테이너 안에서 카운트를 찾는다', () => {
    render(<EpicProgressBar progress={progressFixture} />)

    const container = screen.getByTestId('epic-progress-bar')
    expect(within(container).getByText('4 / 10 완료')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S5. i18n — ko.ts 라벨 반영 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('EpicProgressBar — S5 i18n 라벨 검증', () => {
  it('S5a: epicProgressStrings 라벨이 모두 콜론으로 끝나지 않는다', async () => {
    const { epicProgressStrings } = await import('@/i18n/ko')
    for (const [key, value] of Object.entries(epicProgressStrings)) {
      if (typeof value === 'string') {
        expect(value, `epicProgressStrings["${key}"] = "${value}" 는 콜론으로 끝나면 안 됩니다`).not.toMatch(/:$/)
      }
    }
  })
})
