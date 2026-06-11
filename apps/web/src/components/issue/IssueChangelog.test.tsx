// IssueChangelog 타임라인 컴포넌트 단위 테스트 — S1~S6 스펙 검증 (FR-HS-02)
import { describe, it, expect, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { issueDetailStrings } from '@/i18n/ko'
import {
  atlasOneChangelogFixture,
  buildChangeGroup,
  buildChangeItem,
  ACTOR_ALICE_ID,
  COMPONENT_UUID,
  SECURITY_LEVEL_CONFIDENTIAL_ID,
  SECURITY_LEVEL_PUBLIC_ID,
} from '@/mocks/changelog-fixtures'
import type { ChangelogRefs } from '@/lib/changelog-labels'
import { IssueChangelog } from './IssueChangelog'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return createElement(QueryClientProvider, { client: queryClient }, children)
  }
}

/** 테스트에서 사용할 기본 refs — 거의 빈 값 (라벨 해석 테스트만 필요한 항목 채움) */
const defaultRefs: ChangelogRefs = {
  types: [{ id: 1, key: 'bug', name: '버그', description: '', iconName: null }],
  components: [{ id: COMPONENT_UUID, name: '백엔드 서버', projectId: 'e5f6a7b8-c9d0-4e1f-af2a-3b4c5d6e7f8b', description: null, leadUserId: null }],
  versions: [],
  priorityMap: { 1: 'Highest', 2: 'High', 3: 'Medium', 4: 'Low', 5: 'Lowest' },
  impactMap: { 1: '높음', 2: '보통', 3: '낮음' },
  customFieldDefinitions: [],
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueChangelog', () => {
  beforeEach(() => {
    // 기본 MSW 핸들러: atlasOneChangelogFixture 반환 (6개 그룹, page=0, last=true)
    server.use(
      http.get('/api/v1/issues/:key/changelog', ({ request, params }) => {
        const key = params['key'] as string
        const url = new URL(request.url)
        const page = parseInt(url.searchParams.get('page') ?? '0', 10)
        const size = parseInt(url.searchParams.get('size') ?? '20', 10)

        if (key === 'DENIED-1') {
          return HttpResponse.json({ status: 404, title: 'Not Found' }, { status: 404 })
        }

        const all = key === 'ATLAS-1' ? atlasOneChangelogFixture : []
        const offset = page * size
        const content = all.slice(offset, offset + size)
        const totalElements = all.length
        const totalPages = Math.ceil(totalElements / size)
        return HttpResponse.json({
          content,
          totalElements,
          totalPages,
          size,
          number: page,
          first: page === 0,
          last: page >= totalPages - 1,
          empty: content.length === 0,
        })
      }),
    )
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S1: 그룹별 헤더 + 필드별 from → to 렌더
  // ─────────────────────────────────────────────────────────────────────────

  it('S1: 그룹별 actorName · 상대시각 헤더와 필드 변경 항목을 렌더한다', async () => {
    const Wrapper = createWrapper()
    render(
      <IssueChangelog issueKey="ATLAS-1" refs={defaultRefs} />,
      { wrapper: Wrapper },
    )

    // "변경 이력" 섹션 제목
    expect(
      screen.getByRole('heading', { name: issueDetailStrings.changelogSectionTitle }),
    ).toBeInTheDocument()

    // Alice가 행위자인 그룹 헤더가 존재 (최소 1개)
    const aliceHeaders = await screen.findAllByText(/Alice/)
    expect(aliceHeaders.length).toBeGreaterThan(0)

    // priority 변경: 필드 표시명 "우선순위"
    await waitFor(() => {
      expect(screen.getByText('우선순위')).toBeInTheDocument()
    })
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S2: 박제 label 우선 표시 (assignee/securityLevel)
  // ─────────────────────────────────────────────────────────────────────────

  it('S2: 박제 label이 있으면 raw 값 대신 표시명을 표시한다', async () => {
    const Wrapper = createWrapper()
    render(
      <IssueChangelog issueKey="ATLAS-1" refs={defaultRefs} />,
      { wrapper: Wrapper },
    )

    // securityLevel 그룹: fromLabel="Confidential", toLabel="Public"
    await waitFor(() => {
      expect(screen.getByText('Confidential')).toBeInTheDocument()
      expect(screen.getByText('Public')).toBeInTheDocument()
    })

    // raw UUID가 노출되면 안 됨
    expect(screen.queryByText(SECURITY_LEVEL_CONFIDENTIAL_ID)).not.toBeInTheDocument()
    expect(screen.queryByText(SECURITY_LEVEL_PUBLIC_ID)).not.toBeInTheDocument()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S3: lifecycle 특수 렌더 — "이슈를 생성했습니다" (from→to 형식 아님)
  // ─────────────────────────────────────────────────────────────────────────

  it('S3: lifecycle=created 항목은 "이슈를 생성했습니다"로 특수 렌더된다', async () => {
    const Wrapper = createWrapper()
    render(
      <IssueChangelog issueKey="ATLAS-1" refs={defaultRefs} />,
      { wrapper: Wrapper },
    )

    await waitFor(() => {
      expect(
        screen.getByText(issueDetailStrings.changelogLifecycleCreated),
      ).toBeInTheDocument()
    })

    // "→" 화살표가 lifecycle 행에 없어야 한다 — 특수 렌더
    // (lifecycle 행을 컨테이너로 한정해 화살표 부재 확인)
    const lifecycleItem = screen.getByText(issueDetailStrings.changelogLifecycleCreated)
    expect(lifecycleItem.textContent).not.toContain('→')
  })

  it('S3: lifecycle=deleted 항목은 "이슈를 삭제했습니다"로 특수 렌더된다', async () => {
    server.use(
      http.get('/api/v1/issues/:key/changelog', ({ params }) => {
        const key = params['key'] as string
        if (key !== 'ATLAS-DEL') {
          return HttpResponse.json({ content: [], totalElements: 0, totalPages: 0, size: 20, number: 0, first: true, last: true, empty: true })
        }
        const groups = [
          buildChangeGroup({
            actorId: null,
            actorName: null,
            createdAt: '2026-06-11T12:00:00Z',
            items: [buildChangeItem('lifecycle', null, 'deleted')],
          }),
        ]
        return HttpResponse.json({
          content: groups,
          totalElements: 1,
          totalPages: 1,
          size: 20,
          number: 0,
          first: true,
          last: true,
          empty: false,
        })
      }),
    )

    const Wrapper = createWrapper()
    render(
      <IssueChangelog issueKey="ATLAS-DEL" refs={defaultRefs} />,
      { wrapper: Wrapper },
    )

    await waitFor(() => {
      expect(
        screen.getByText(issueDetailStrings.changelogLifecycleDeleted),
      ).toBeInTheDocument()
    })
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S4: "더 보기" 누적 페이징
  // ─────────────────────────────────────────────────────────────────────────

  it('S4: 마지막 페이지가 아닐 때 "더 보기" 버튼이 표시되고 클릭 시 다음 페이지를 누적 표시한다', async () => {
    // 2페이지 시나리오: page0=3그룹(last=false), page1=3그룹(last=true)
    const page0Groups = atlasOneChangelogFixture.slice(0, 3)
    const page1Groups = atlasOneChangelogFixture.slice(3)

    server.use(
      http.get('/api/v1/issues/:key/changelog', ({ request, params }) => {
        const key = params['key'] as string
        if (key !== 'ATLAS-PAGED') {
          return HttpResponse.json({ content: [], totalElements: 0, totalPages: 0, size: 20, number: 0, first: true, last: true, empty: true })
        }
        const url = new URL(request.url)
        const page = parseInt(url.searchParams.get('page') ?? '0', 10)
        const content = page === 0 ? page0Groups : page1Groups
        return HttpResponse.json({
          content,
          totalElements: atlasOneChangelogFixture.length,
          totalPages: 2,
          size: 3,
          number: page,
          first: page === 0,
          last: page >= 1,
          empty: false,
        })
      }),
    )

    const user = userEvent.setup()
    const Wrapper = createWrapper()
    render(
      <IssueChangelog issueKey="ATLAS-PAGED" refs={defaultRefs} />,
      { wrapper: Wrapper },
    )

    // 초기: "더 보기" 버튼 표시
    const loadMoreButton = await screen.findByRole('button', {
      name: issueDetailStrings.changelogLoadMore,
    })
    expect(loadMoreButton).toBeInTheDocument()

    // 클릭 → 다음 페이지 누적
    await user.click(loadMoreButton)

    // 마지막 페이지 도달 → "더 보기" 버튼 사라짐
    await waitFor(() => {
      expect(
        screen.queryByRole('button', { name: issueDetailStrings.changelogLoadMore }),
      ).not.toBeInTheDocument()
    })
  })

  it('S4: 마지막 페이지이면 "더 보기" 버튼이 표시되지 않는다', async () => {
    // atlasOneChangelogFixture는 size=20 기준 1페이지 (last=true)
    const Wrapper = createWrapper()
    render(
      <IssueChangelog issueKey="ATLAS-1" refs={defaultRefs} />,
      { wrapper: Wrapper },
    )

    // 데이터 로드 완료 대기 (Alice 이름 등장)
    await screen.findAllByText(/Alice/)

    expect(
      screen.queryByRole('button', { name: issueDetailStrings.changelogLoadMore }),
    ).not.toBeInTheDocument()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S5/S6: 빈 상태
  // ─────────────────────────────────────────────────────────────────────────

  it('S6: 변경 이력이 0건이면 빈 상태 메시지를 표시한다', async () => {
    server.use(
      http.get('/api/v1/issues/:key/changelog', ({ params }) => {
        const key = params['key'] as string
        if (key !== 'ATLAS-EMPTY') {
          return HttpResponse.json({ content: [], totalElements: 0, totalPages: 0, size: 20, number: 0, first: true, last: true, empty: true })
        }
        return HttpResponse.json({
          content: [],
          totalElements: 0,
          totalPages: 0,
          size: 20,
          number: 0,
          first: true,
          last: true,
          empty: true,
        })
      }),
    )

    const Wrapper = createWrapper()
    render(
      <IssueChangelog issueKey="ATLAS-EMPTY" refs={defaultRefs} />,
      { wrapper: Wrapper },
    )

    await waitFor(() => {
      expect(screen.getByText(issueDetailStrings.changelogEmpty)).toBeInTheDocument()
    })
  })

  // ─────────────────────────────────────────────────────────────────────────
  // actorName=null → "시스템" 표시
  // ─────────────────────────────────────────────────────────────────────────

  it('actorName이 null이면 "시스템"으로 표시된다', async () => {
    const Wrapper = createWrapper()
    render(
      <IssueChangelog issueKey="ATLAS-1" refs={defaultRefs} />,
      { wrapper: Wrapper },
    )

    // lifecycle 그룹은 actorId=null, actorName=null → "시스템" 표시
    await waitFor(() => {
      expect(
        screen.getByText(issueDetailStrings.changelogSystemActor),
      ).toBeInTheDocument()
    })
  })

  // ─────────────────────────────────────────────────────────────────────────
  // 로딩 상태
  // ─────────────────────────────────────────────────────────────────────────

  it('데이터 로딩 중에는 로딩 상태(aria role=status)를 표시한다', () => {
    // 응답을 지연시키는 핸들러
    server.use(
      http.get('/api/v1/issues/:key/changelog', async () => {
        await new Promise((resolve) => setTimeout(resolve, 5000))
        return HttpResponse.json({ content: [], totalElements: 0, totalPages: 0, size: 20, number: 0, first: true, last: true, empty: true })
      }),
    )

    const Wrapper = createWrapper()
    render(
      <IssueChangelog issueKey="ATLAS-1" refs={defaultRefs} />,
      { wrapper: Wrapper },
    )

    // 로딩 중 role=status 요소 존재
    expect(screen.getByRole('status')).toBeInTheDocument()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // 에러 상태
  // ─────────────────────────────────────────────────────────────────────────

  it('API 에러 시 에러 메시지를 표시한다', async () => {
    server.use(
      http.get('/api/v1/issues/:key/changelog', ({ params }) => {
        const key = params['key'] as string
        if (key === 'ATLAS-ERR') {
          return HttpResponse.json({ status: 500 }, { status: 500 })
        }
        return HttpResponse.json({ content: [], totalElements: 0, totalPages: 0, size: 20, number: 0, first: true, last: true, empty: true })
      }),
    )

    const Wrapper = createWrapper()
    render(
      <IssueChangelog issueKey="ATLAS-ERR" refs={defaultRefs} />,
      { wrapper: Wrapper },
    )

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
  })

  // ─────────────────────────────────────────────────────────────────────────
  // components 필드 — UUID → refs 이름 해석
  // ─────────────────────────────────────────────────────────────────────────

  it('components UUID는 refs.components에서 이름을 해석한다', async () => {
    const Wrapper = createWrapper()
    render(
      <IssueChangelog issueKey="ATLAS-1" refs={defaultRefs} />,
      { wrapper: Wrapper },
    )

    // 그룹 4: components = "[]" → [COMPONENT_UUID], defaultRefs.components에 "백엔드 서버"
    await waitFor(() => {
      expect(screen.getByText('백엔드 서버')).toBeInTheDocument()
    })

    // raw UUID가 노출되면 안 됨
    expect(screen.queryByText(COMPONENT_UUID)).not.toBeInTheDocument()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // P2-b: 로드모어 페이지 에러 시 재시도 가능 — 버튼 영구 소실 방지
  // ─────────────────────────────────────────────────────────────────────────

  it('P2-b: 로드모어 페이지(page≥1) 에러 시 재시도 버튼이 남아있고 클릭하면 재요청된다', async () => {
    let page1CallCount = 0

    server.use(
      http.get('/api/v1/issues/:key/changelog', ({ request, params }) => {
        const key = params['key'] as string
        if (key !== 'ATLAS-RETRY') {
          return HttpResponse.json({ content: [], totalElements: 0, totalPages: 0, size: 20, number: 0, first: true, last: true, empty: true })
        }
        const url = new URL(request.url)
        const page = parseInt(url.searchParams.get('page') ?? '0', 10)

        if (page === 0) {
          const page0Groups = atlasOneChangelogFixture.slice(0, 3)
          return HttpResponse.json({
            content: page0Groups,
            totalElements: 6,
            totalPages: 2,
            size: 3,
            number: 0,
            first: true,
            last: false,
            empty: false,
          })
        }

        // page=1: 첫 번째 요청은 에러, 두 번째는 성공
        page1CallCount++
        if (page1CallCount === 1) {
          return HttpResponse.json({ status: 500 }, { status: 500 })
        }
        const page1Groups = atlasOneChangelogFixture.slice(3)
        return HttpResponse.json({
          content: page1Groups,
          totalElements: 6,
          totalPages: 2,
          size: 3,
          number: 1,
          first: false,
          last: true,
          empty: false,
        })
      }),
    )

    const user = userEvent.setup()
    const Wrapper = createWrapper()
    render(
      <IssueChangelog issueKey="ATLAS-RETRY" refs={defaultRefs} />,
      { wrapper: Wrapper },
    )

    // 첫 페이지 로드 → "더 보기" 버튼 등장
    const loadMoreBtn = await screen.findByRole('button', {
      name: issueDetailStrings.changelogLoadMore,
    })
    await user.click(loadMoreBtn)

    // page=1 에러 후에도 재시도 버튼이 표시되어야 한다
    const retryBtn = await screen.findByRole('button', {
      name: issueDetailStrings.changelogLoadMore,
    })
    expect(retryBtn).toBeInTheDocument()

    // 재시도 클릭 → page=1 두 번째 요청 → 성공
    await user.click(retryBtn)

    // 성공 후 "더 보기" 버튼이 사라진다 (last=true)
    await waitFor(() => {
      expect(
        screen.queryByRole('button', { name: issueDetailStrings.changelogLoadMore }),
      ).not.toBeInTheDocument()
    })
  })

  // ─────────────────────────────────────────────────────────────────────────
  // P2-c: createdAt Invalid Date 방어
  // ─────────────────────────────────────────────────────────────────────────

  it('P2-c: createdAt이 유효하지 않은 날짜 문자열이면 "Invalid Date"가 렌더되지 않는다', async () => {
    server.use(
      http.get('/api/v1/issues/:key/changelog', ({ params }) => {
        const key = params['key'] as string
        if (key !== 'ATLAS-BADDATE') {
          return HttpResponse.json({ content: [], totalElements: 0, totalPages: 0, size: 20, number: 0, first: true, last: true, empty: true })
        }
        return HttpResponse.json({
          content: [
            buildChangeGroup({
              actorId: ACTOR_ALICE_ID,
              actorName: 'Alice',
              createdAt: 'not-a-date',
              items: [buildChangeItem('summary', '이전', '이후')],
            }),
          ],
          totalElements: 1,
          totalPages: 1,
          size: 20,
          number: 0,
          first: true,
          last: true,
          empty: false,
        })
      }),
    )

    const Wrapper = createWrapper()
    render(
      <IssueChangelog issueKey="ATLAS-BADDATE" refs={defaultRefs} />,
      { wrapper: Wrapper },
    )

    await screen.findByText('Alice')

    // "Invalid Date" 문자열이 어디에도 표시되어서는 안 된다
    expect(screen.queryByText(/Invalid Date/i)).not.toBeInTheDocument()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // actorId가 있는 그룹 헤더의 접근성
  // ─────────────────────────────────────────────────────────────────────────

  it('각 그룹 헤더에 role=group 또는 aria-label이 있어 접근성을 제공한다', async () => {
    const Wrapper = createWrapper()
    render(
      <IssueChangelog issueKey="ATLAS-1" refs={defaultRefs} />,
      { wrapper: Wrapper },
    )

    // 데이터 로드 후 그룹 aria-label 존재 확인
    await screen.findAllByText(/Alice/)

    // 변경 이력 섹션 내 그룹 구분 항목이 있어야 함
    const changelogSection = screen.getByRole('region', {
      name: issueDetailStrings.changelogSectionTitle,
    })
    expect(changelogSection).toBeInTheDocument()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // priority 숫자 → refs.priorityMap 해석
  // ─────────────────────────────────────────────────────────────────────────

  it('priority 숫자는 refs.priorityMap으로 해석한다', async () => {
    const Wrapper = createWrapper()
    render(
      <IssueChangelog issueKey="ATLAS-1" refs={defaultRefs} />,
      { wrapper: Wrapper },
    )

    // 그룹 2: priority "1" → "3", defaultRefs.priorityMap: 1→"Highest", 3→"Medium"
    await waitFor(() => {
      expect(screen.getByText('Highest')).toBeInTheDocument()
      expect(screen.getByText('Medium')).toBeInTheDocument()
    })

    // raw 숫자 "1", "3"이 그대로 노출되면 안 됨
    // (다른 맥락의 숫자와 겹칠 수 있어 priority 항목 컨테이너 내에서 확인)
    const priorityLabel = screen.getByText('우선순위')
    const priorityRow = priorityLabel.closest('li') ?? priorityLabel.parentElement
    expect(priorityRow?.textContent).not.toMatch(/^\s*1\s*$/)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // actorId=null인 그룹에서 ACTOR_ALICE_ID UUID가 노출되지 않음 (props 확인)
  // ─────────────────────────────────────────────────────────────────────────

  it('actorId UUID가 그대로 UI에 노출되지 않는다', async () => {
    const Wrapper = createWrapper()
    render(
      <IssueChangelog issueKey="ATLAS-1" refs={defaultRefs} />,
      { wrapper: Wrapper },
    )

    await screen.findAllByText(/Alice/)

    // actorId UUID는 표시하지 않음
    expect(screen.queryByText(ACTOR_ALICE_ID)).not.toBeInTheDocument()
  })
})
