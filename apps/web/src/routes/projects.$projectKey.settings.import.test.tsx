// 프로젝트 Import 설정 라우트 페이지 단위 테스트 — RouteAdapter useParams 추출 + Page 헤더/모드 토글/ImportForm·ImportMappingWizard 렌더 (FR-IM-01 D6/D7 Task-4, FR-IM-02 D6/D7 Task-7)
import { useMemo } from 'react'
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  ProjectImportSettingsRouteAdapter,
  ProjectImportSettingsPage,
} from '@/routes/projects.$projectKey.settings.import'

// TanStack Router useParams mock — RouteAdapter 단위 테스트용
vi.mock('@tanstack/react-router', () => ({
  useParams: () => ({ projectKey: 'ATLAS' }),
}))

// ImportForm/ImportMappingWizard 마운트 횟수 카운터 — vi.mock 팩토리보다 먼저 평가되어야 하므로 vi.hoisted 사용
const importFormMountCounter = vi.hoisted(() => ({ count: 0 }))
const importMappingWizardMountCounter = vi.hoisted(() => ({ count: 0 }))

// ImportForm은 별도 단위 테스트(ImportForm.test.tsx)에서 검증하므로 vi.mock으로 격리
// data-instance-id: 마운트마다 useMemo로 1회 계산되는 고유값 — remount 여부를 간접 검증하는 용도
// (key는 React 특수 prop이라 컴포넌트에 일반 prop로 전달되지 않으므로 data-key로 직접 읽을 수 없음)
vi.mock('@/components/import/ImportForm', () => ({
  ImportForm: ({ projectKey }: { projectKey: string }) => {
    const instanceId = useMemo(() => ++importFormMountCounter.count, [])
    return (
      <div
        data-testid="import-form"
        data-project-key={projectKey}
        data-instance-id={instanceId}
      >
        import-form-mock
      </div>
    )
  },
}))

// ImportMappingWizard는 별도 단위 테스트(ImportMappingWizard.test.tsx)에서 검증하므로 vi.mock으로 격리(무겁고 이 테스트의 관심사는 모드 토글/렌더 여부다)
vi.mock('@/components/import/mapping/ImportMappingWizard', () => ({
  ImportMappingWizard: ({ projectKey }: { projectKey: string }) => {
    const instanceId = useMemo(() => ++importMappingWizardMountCounter.count, [])
    return (
      <div
        data-testid="import-mapping-wizard"
        data-project-key={projectKey}
        data-instance-id={instanceId}
      >
        import-mapping-wizard-mock
      </div>
    )
  },
}))

function makeClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
}

function renderPage(projectKey = 'ATLAS') {
  return render(
    <QueryClientProvider client={makeClient()}>
      <ProjectImportSettingsPage projectKey={projectKey} />
    </QueryClientProvider>,
  )
}

function renderAdapter() {
  return render(
    <QueryClientProvider client={makeClient()}>
      <ProjectImportSettingsRouteAdapter />
    </QueryClientProvider>,
  )
}

describe('ProjectImportSettingsPage', () => {
  /**
   * T-IM-1. RouteAdapter가 useParams에서 $projectKey를 추출해 Page에 전달한다.
   * useParams mock이 ATLAS를 반환하므로 ImportForm에 projectKey="ATLAS"가 전달된다.
   */
  it('T-IM-1: RouteAdapter가 useParams $projectKey를 Page에 전달한다', () => {
    renderAdapter()

    const form = screen.getByTestId('import-form')
    expect(form).toBeInTheDocument()
    expect(form).toHaveAttribute('data-project-key', 'ATLAS')
  })

  /**
   * T-IM-2. Page가 "가져오기(Import)" h1 헤더를 렌더한다.
   */
  it('T-IM-2: Page가 페이지 제목 h1을 렌더한다', () => {
    renderPage('ATLAS')

    expect(
      screen.getByRole('heading', { level: 1, name: '가져오기(Import)' }),
    ).toBeInTheDocument()
  })

  /**
   * T-IM-3. Page가 ImportForm을 렌더하고 projectKey props를 올바르게 전달한다.
   */
  it('T-IM-3: Page가 ImportForm에 projectKey를 전달한다', () => {
    renderPage('MYPROJECT')

    const form = screen.getByTestId('import-form')
    expect(form).toBeInTheDocument()
    expect(form).toHaveAttribute('data-project-key', 'MYPROJECT')
  })

  /**
   * T-IM-4. projectKey가 바뀌면 ImportForm이 remount되어야 한다 (state leak 차단).
   *
   * 같은 QueryClient/컨테이너를 유지한 채 rerender로 projectKey만 ALPHA → BETA로 바꾼다.
   * ImportForm에 `key={projectKey}`가 없으면 React는 같은 컴포넌트 인스턴스를 재사용하므로
   * (project-workflow 등 stale useState 회귀와 동일 패턴 — react-usestate-stale-key-prop),
   * mock의 data-instance-id(마운트 시 1회만 계산)가 그대로 유지된다.
   * `key={projectKey}`가 있으면 remount되어 instance-id가 달라진다.
   */
  it('T-IM-4: projectKey 변경 시 ImportForm이 remount된다 (프로젝트 간 상태 leak 차단)', () => {
    const client = makeClient()
    const { rerender } = render(
      <QueryClientProvider client={client}>
        <ProjectImportSettingsPage projectKey="ALPHA" />
      </QueryClientProvider>,
    )

    const formAlpha = screen.getByTestId('import-form')
    expect(formAlpha).toHaveAttribute('data-project-key', 'ALPHA')
    const instanceIdAlpha = formAlpha.getAttribute('data-instance-id')

    rerender(
      <QueryClientProvider client={client}>
        <ProjectImportSettingsPage projectKey="BETA" />
      </QueryClientProvider>,
    )

    const formBeta = screen.getByTestId('import-form')
    expect(formBeta).toHaveAttribute('data-project-key', 'BETA')
    const instanceIdBeta = formBeta.getAttribute('data-instance-id')

    expect(instanceIdBeta).not.toBe(instanceIdAlpha)
  })
})

/**
 * FR-IM-02 D6/D7 Task-7. 페이지 상단 모드 토글("바로 가져오기" / "매핑하며 가져오기").
 * 기본 모드는 "바로 가져오기"(ImportForm, 무회귀), 토글 시 ImportMappingWizard로 전환된다.
 */
describe('ProjectImportSettingsPage — 모드 토글', () => {
  /**
   * T-IM-5. 기본 렌더 시 "바로 가져오기" 모드가 선택되어 ImportForm이 렌더되고,
   * ImportMappingWizard는 렌더되지 않는다. 토글 버튼의 aria-pressed로 활성 모드를 노출한다.
   */
  it('T-IM-5: 기본 모드는 "바로 가져오기"이며 ImportForm만 렌더된다 (무회귀)', () => {
    renderPage('ATLAS')

    expect(screen.getByTestId('import-form')).toBeInTheDocument()
    expect(screen.queryByTestId('import-mapping-wizard')).not.toBeInTheDocument()

    expect(screen.getByRole('button', { name: '바로 가져오기' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
    expect(screen.getByRole('button', { name: '매핑하며 가져오기' })).toHaveAttribute(
      'aria-pressed',
      'false',
    )
  })

  /**
   * T-IM-6. "매핑하며 가져오기" 버튼 클릭 시 ImportMappingWizard가 렌더되고
   * ImportForm은 더 이상 렌더되지 않는다. 버튼 aria-pressed도 반전된다.
   */
  it('T-IM-6: "매핑하며 가져오기" 클릭 시 ImportMappingWizard로 전환된다', async () => {
    const user = userEvent.setup()
    renderPage('ATLAS')

    await user.click(screen.getByRole('button', { name: '매핑하며 가져오기' }))

    expect(screen.getByTestId('import-mapping-wizard')).toBeInTheDocument()
    expect(screen.queryByTestId('import-form')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '매핑하며 가져오기' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
    expect(screen.getByRole('button', { name: '바로 가져오기' })).toHaveAttribute(
      'aria-pressed',
      'false',
    )
  })

  /**
   * T-IM-7. 마법사 모드에서 "바로 가져오기"를 다시 클릭하면 ImportForm으로 되돌아간다 (토글 왕복).
   */
  it('T-IM-7: 마법사 모드에서 "바로 가져오기" 재클릭 시 ImportForm으로 되돌아간다', async () => {
    const user = userEvent.setup()
    renderPage('ATLAS')

    await user.click(screen.getByRole('button', { name: '매핑하며 가져오기' }))
    expect(screen.getByTestId('import-mapping-wizard')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '바로 가져오기' }))

    expect(screen.getByTestId('import-form')).toBeInTheDocument()
    expect(screen.queryByTestId('import-mapping-wizard')).not.toBeInTheDocument()
  })

  /**
   * T-IM-8. 토글 하단에 각 모드가 언제 적합한지 안내하는 도움말 한 줄이 노출된다 (DR-3).
   */
  it('T-IM-8: 토글 하단에 모드 안내 도움말이 렌더된다 (DR-3)', () => {
    renderPage('ATLAS')

    expect(
      screen.getByText(
        '바로 가져오기 = canonical 컬럼·JSON 첨부 zip / 매핑하며 가져오기 = 임의 CSV 컬럼·작성자·값 매핑',
      ),
    ).toBeInTheDocument()
  })

  /**
   * T-IM-9. 매핑 마법사 모드에서도 projectKey 변경 시 ImportMappingWizard가 remount된다
   * (EC8 — ImportForm과 동일하게 key={projectKey} 유지).
   */
  it('T-IM-9: 매핑 모드에서 projectKey 변경 시 ImportMappingWizard가 remount된다', async () => {
    const user = userEvent.setup()
    const client = makeClient()
    const { rerender } = render(
      <QueryClientProvider client={client}>
        <ProjectImportSettingsPage projectKey="ALPHA" />
      </QueryClientProvider>,
    )

    await user.click(screen.getByRole('button', { name: '매핑하며 가져오기' }))

    const wizardAlpha = screen.getByTestId('import-mapping-wizard')
    expect(wizardAlpha).toHaveAttribute('data-project-key', 'ALPHA')
    const instanceIdAlpha = wizardAlpha.getAttribute('data-instance-id')

    rerender(
      <QueryClientProvider client={client}>
        <ProjectImportSettingsPage projectKey="BETA" />
      </QueryClientProvider>,
    )

    const wizardBeta = screen.getByTestId('import-mapping-wizard')
    expect(wizardBeta).toHaveAttribute('data-project-key', 'BETA')
    const instanceIdBeta = wizardBeta.getAttribute('data-instance-id')

    expect(instanceIdBeta).not.toBe(instanceIdAlpha)
  })
})
