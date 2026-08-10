// 프로젝트 Import 설정 라우트 페이지 단위 테스트 — RouteAdapter useParams 추출 + Page 헤더/모드 토글/ImportForm·ImportMappingWizard 렌더 (FR-IM-01 D6/D7 Task-4, FR-IM-02 D6/D7 Task-7)
import { useMemo } from 'react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import {
  adminProjectPermissions,
  nonMemberProjectPermissions,
} from '@/mocks/project-permission-fixtures'
import { projectHandlers } from '@/mocks/project-handlers'
import { PROJECT_PERMISSION_KEYS } from '@/hooks/use-project-permissions'
import { PROJECT_KEYS } from '@/hooks/use-project'
import { importLabels } from '@/i18n/import-labels'
import { workflowSchemeLabels } from '@/i18n/workflow-scheme-labels'
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

/**
 * 프로젝트 단건 조회 핸들러를 파일 전체에 등록한다.
 *
 * 페이지가 `useProject` 로 프로젝트 존재를 확인하는데, 이 저장소의 전역 MSW 서버는
 * `src/test/handlers.ts` 의 `/auth/refresh` **하나**만 들고 있다(`src/mocks/handlers.ts` 가 아니다).
 * 등록하지 않으면 그 요청이 `onUnhandledRequest: 'error'` 에 걸려 조용히 에러로 안착하고,
 * 「404 라서 카드가 안 뜬다」와 「요청이 아예 안 잡혀서 안 뜬다」가 구분되지 않는다.
 *
 * `beforeEach` 여야 한다 — 전역 `setup.ts` 의 `afterEach(server.resetHandlers())` 가
 * 매 테스트 뒤 등록을 지운다.
 */
beforeEach(() => {
  server.use(...projectHandlers)
})

function makeClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
}

/**
 * Page 렌더 헬퍼.
 *
 * QueryClient를 함께 돌려준다 — CREATE 게이트 테스트가 권한 쿼리의 **정착 여부**를
 * `getQueryState`로 직접 확인하기 위해서다. 정착을 기다리지 않고 단언하면
 * "아직 로딩이라 폼이 보인다"와 "권한이 있어서 폼이 보인다"가 구분되지 않아 공허해진다.
 *
 * ★projectKey 는 반드시 **MSW 시드에 있는 키**를 쓸 것(`project-handlers.ts` SEED_PROJECTS —
 * ATLAS·MIDDLE·ZETA·NOVA). 페이지가 `useProject` 로 프로젝트 존재를 확인하므로, 시드에 없는
 * 임의 키는 404 → 「프로젝트를 찾을 수 없습니다」 카드가 되어 폼이 아예 렌더되지 않는다.
 */
function renderPage(projectKey = 'ATLAS') {
  const client = makeClient()
  render(
    <QueryClientProvider client={client}>
      <ProjectImportSettingsPage projectKey={projectKey} />
    </QueryClientProvider>,
  )
  return { client }
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
    renderPage('MIDDLE')

    const form = screen.getByTestId('import-form')
    expect(form).toBeInTheDocument()
    expect(form).toHaveAttribute('data-project-key', 'MIDDLE')
  })

  /**
   * T-IM-4. projectKey가 바뀌면 ImportForm이 remount되어야 한다 (state leak 차단).
   *
   * 같은 QueryClient/컨테이너를 유지한 채 rerender로 projectKey만 ATLAS → MIDDLE로 바꾼다.
   * ImportForm에 `key={projectKey}`가 없으면 React는 같은 컴포넌트 인스턴스를 재사용하므로
   * (project-workflow 등 stale useState 회귀와 동일 패턴 — react-usestate-stale-key-prop),
   * mock의 data-instance-id(마운트 시 1회만 계산)가 그대로 유지된다.
   * `key={projectKey}`가 있으면 remount되어 instance-id가 달라진다.
   */
  it('T-IM-4: projectKey 변경 시 ImportForm이 remount된다 (프로젝트 간 상태 leak 차단)', () => {
    const client = makeClient()
    const { rerender } = render(
      <QueryClientProvider client={client}>
        <ProjectImportSettingsPage projectKey="ATLAS" />
      </QueryClientProvider>,
    )

    const formAlpha = screen.getByTestId('import-form')
    expect(formAlpha).toHaveAttribute('data-project-key', 'ATLAS')
    const instanceIdAlpha = formAlpha.getAttribute('data-instance-id')

    rerender(
      <QueryClientProvider client={client}>
        <ProjectImportSettingsPage projectKey="MIDDLE" />
      </QueryClientProvider>,
    )

    const formBeta = screen.getByTestId('import-form')
    expect(formBeta).toHaveAttribute('data-project-key', 'MIDDLE')
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
        <ProjectImportSettingsPage projectKey="ATLAS" />
      </QueryClientProvider>,
    )

    await user.click(screen.getByRole('button', { name: '매핑하며 가져오기' }))

    const wizardAlpha = screen.getByTestId('import-mapping-wizard')
    expect(wizardAlpha).toHaveAttribute('data-project-key', 'ATLAS')
    const instanceIdAlpha = wizardAlpha.getAttribute('data-instance-id')

    rerender(
      <QueryClientProvider client={client}>
        <ProjectImportSettingsPage projectKey="MIDDLE" />
      </QueryClientProvider>,
    )

    const wizardBeta = screen.getByTestId('import-mapping-wizard')
    expect(wizardBeta).toHaveAttribute('data-project-key', 'MIDDLE')
    const instanceIdBeta = wizardBeta.getAttribute('data-instance-id')

    expect(instanceIdBeta).not.toBe(instanceIdAlpha)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// CREATE 게이트 — 임포트는 「대상 프로젝트에 이슈를 만드는」 액션이다
//
// 서버는 이미 `ImportJobService.kt:36-38,381-392` 에서 CREATE 를 fail-fast 로 검사해 403 을
// 준다. 그런데 UI 는 그 사실을 **파일을 고르고 업로드를 누른 뒤에야** 알려준다.
// 게이트는 그 403 의 **사전 신호**이지 유일 방어가 아니다.
//
// ★게이트 지점은 **페이지 1곳**이다. 두 모드(ImportForm · ImportMappingWizard)가 같은
//   페이지에서 분기하므로 여기서 막으면 둘을 한 번에 덮는다. 폼 컴포넌트 안에 각각 넣으면
//   같은 판정이 2벌이 되고, 이 문서(TODOS)가 이미 required 판정에서 겪은 사본 drift 양식이다.
//
// ★판정식은 `permissions.CREATE === false`(명시 거부만) — 클론(PR #357)·생성 폼과 같다.
//   `!isLoading && === true`(미지=거부)는 로딩·조회실패 구간에서 CREATE 를 실제로 가진
//   사용자를 영구 차단한다(`use-project-permissions.ts` 에 retry 도 에러 폴백도 없다).
// ─────────────────────────────────────────────────────────────────────────────

describe('ProjectImportSettingsPage — CREATE 게이트', () => {
  it('CREATE:false(명시 거부)면 두 모드 진입 자체가 막히고 사유를 말한다', async () => {
    server.use(
      http.get('/api/v1/users/me/project-permissions', () =>
        HttpResponse.json({ projectKey: 'ATLAS', permissions: nonMemberProjectPermissions }),
      ),
    )
    renderPage('ATLAS')

    expect(await screen.findByTestId('import-create-denied')).toBeInTheDocument()
    // 제목은 i18n(`importLabels.createDeniedTitle`), 본문은 서버 403 이 줄 메시지와 같은 문장이다
    // (`api/imports.ts` IMPORT_ACCESS_DENIED). 둘 다 라우트 파일에 리터럴로 두지 않는다.
    expect(screen.getByText(importLabels.createDeniedTitle)).toBeInTheDocument()
    expect(screen.getByText('이 프로젝트에 이슈를 생성할 권한이 없습니다.')).toBeInTheDocument()

    // 두 모드 모두 진입 불가 — 폼도, 모드 토글도 없다.
    expect(screen.queryByTestId('import-form')).not.toBeInTheDocument()
    expect(screen.queryByTestId('import-mapping-wizard')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '바로 가져오기' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '매핑하며 가져오기' })).not.toBeInTheDocument()

    // 페이지 정체성(h1)은 남긴다 — 「왜 빈 화면이지」가 되면 안 된다.
    expect(
      screen.getByRole('heading', { level: 1, name: '가져오기(Import)' }),
    ).toBeInTheDocument()
  })

  it('CREATE:true 면 권한 조회가 끝난 뒤에도 폼이 그대로 열린다 (비-공허 짝)', async () => {
    server.use(
      http.get('/api/v1/users/me/project-permissions', () =>
        HttpResponse.json({ projectKey: 'ATLAS', permissions: adminProjectPermissions }),
      ),
    )
    const { client } = renderPage('ATLAS')

    // 쿼리가 success 로 정착한 뒤에 단언한다 — 로딩 중 통과를 「권한 있음」으로 오독하지 않는다.
    await waitFor(() => {
      expect(client.getQueryState(PROJECT_PERMISSION_KEYS.detail('ATLAS'))?.status).toBe('success')
    })

    expect(screen.getByTestId('import-form')).toBeInTheDocument()
    expect(screen.queryByTestId('import-create-denied')).not.toBeInTheDocument()
  })

  it('권한 조회가 실패하면 막지 않는다 (미지 ≠ 거부 — 비-공허 짝)', async () => {
    server.use(
      http.get('/api/v1/users/me/project-permissions', () =>
        HttpResponse.json({ error: 'server_error' }, { status: 500 }),
      ),
    )
    const { client } = renderPage('ATLAS')

    // 쿼리가 error 로 정착한 뒤에 단언해야 `!isLoading && CREATE === true` 판정식을 잡는다.
    await waitFor(() => {
      expect(client.getQueryState(PROJECT_PERMISSION_KEYS.detail('ATLAS'))?.status).toBe('error')
    })

    expect(screen.getByTestId('import-form')).toBeInTheDocument()
    expect(screen.queryByTestId('import-create-denied')).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 프로젝트 존재 — 「없는 프로젝트」를 「권한 없음」이라 말하지 않는다
//
// 권한 API 는 미존재 projectKey 에도 **200 + CREATE:false** 를 준다
// (`MyProjectPermissionController.kt` — resolver 가 미존재를 거부로 판정).
// 그래서 CREATE 게이트만 있으면 `/projects/BOGUS/settings/import` 로 들어온 사용자가
// 「이 프로젝트에 이슈를 생성할 권한이 없습니다」를 본다 — 프로젝트가 아예 없는데.
//
// 형제 페이지가 이미 같은 결함을 고쳤다(`projects.$projectKey.settings.workflow-scheme.tsx`
// KDoc — 예전엔 404 를 「미할당」으로 읽어 틀린 안내 카드가 떴다). 여기도 같은 분리를 한다.
//
// ★존재 신호로 `GET /api/v1/projects/{key}` 의 404 를 쓰는 근거.
//   `ProjectQueryService.getOne` 은 **존재 → 권한** 순서라(미존재 404, BROWSE 없음 403)
//   404 가 「프로젝트 없음」 하나를 뜻한다. 이동 preview 의 404 와 달리 prod 도달 가능하다.
// ─────────────────────────────────────────────────────────────────────────────

describe('ProjectImportSettingsPage — 프로젝트 존재 확인', () => {
  it('프로젝트가 없으면(404) 권한 카드가 아니라 「프로젝트 없음」 카드를 보여 준다', async () => {
    // 권한 API 는 미존재 키에도 200 + CREATE:false 를 준다 — 이 상황을 그대로 재현한다.
    server.use(
      http.get('/api/v1/users/me/project-permissions', () =>
        HttpResponse.json({ projectKey: 'BOGUS', permissions: nonMemberProjectPermissions }),
      ),
    )
    renderPage('BOGUS') // 시드 밖 키 → projectHandlers 가 404

    expect(
      await screen.findByText(workflowSchemeLabels.assignment.projectNotFoundTitle),
    ).toBeInTheDocument()
    expect(screen.queryByTestId('import-create-denied')).not.toBeInTheDocument()
    expect(screen.queryByTestId('import-form')).not.toBeInTheDocument()

    // 페이지 정체성(h1)은 여기서도 남긴다.
    expect(
      screen.getByRole('heading', { level: 1, name: '가져오기(Import)' }),
    ).toBeInTheDocument()
  })

  it('프로젝트 조회가 404 아닌 사유로 실패하면 막지 않는다 (미지 ≠ 부재 — 비-공허 짝)', async () => {
    server.use(
      http.get('/api/v1/projects/:idOrKey', () =>
        HttpResponse.json({ errorCode: 'INTERNAL_ERROR' }, { status: 500 }),
      ),
    )
    const { client } = renderPage('ATLAS')

    await waitFor(() => {
      expect(client.getQueryState(PROJECT_KEYS.detail('ATLAS'))?.status).toBe('error')
    })

    expect(screen.getByTestId('import-form')).toBeInTheDocument()
    expect(
      screen.queryByText(workflowSchemeLabels.assignment.projectNotFoundTitle),
    ).not.toBeInTheDocument()
  })

  it('프로젝트가 있고 CREATE 도 거부면 권한 카드가 이긴다 (404 분기가 권한 카드를 삼키지 않는다)', async () => {
    server.use(
      http.get('/api/v1/users/me/project-permissions', () =>
        HttpResponse.json({ projectKey: 'ATLAS', permissions: nonMemberProjectPermissions }),
      ),
    )
    renderPage('ATLAS') // 시드에 있는 키 → 200

    expect(await screen.findByTestId('import-create-denied')).toBeInTheDocument()
    expect(
      screen.queryByText(workflowSchemeLabels.assignment.projectNotFoundTitle),
    ).not.toBeInTheDocument()
  })
})
