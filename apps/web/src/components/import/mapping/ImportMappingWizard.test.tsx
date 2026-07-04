// Import 매핑 마법사 컨테이너 단위 테스트 — 상태머신/동적 스킵/검토/폴링/dry-run 재적용 (FR-IM-02 D6 Task-6)
//
// 설계 결정.
//   - '@/api/import-mappings' 전체를 mock해 analyze/collectUsers/collectValues/confirmMapping을 제어한다.
//   - '@/hooks/use-import-job-polling'를 mock해 폴링 결과(tracking→done 전이)를 직접 제어한다.
//   - FieldMappingStep/UserMappingStep/ValueMappingStep은 실제 컴포넌트를 그대로 렌더한다(모킹 없음) —
//     대부분은 Radix Select를 여는 상호작용(jsdom pointer-capture 미지원)을 피하기 위해 JSON 업로드
//     (필드 매핑 스킵) 또는 collectUsers/collectValues 빈 결과(자동 스킵) 경로로 Select 상호작용 없이
//     시나리오를 구성한다("다음" 버튼 클릭만 사용). 단, 재수집 통지(DR-4) 검증처럼 실제로 필드 매핑
//     값을 바꿔야 하는 테스트만 '@/components/ui/select'를 네이티브 <select> mock으로 교체한다
//     (FieldMappingStep.test.tsx 동형 선례 — jsdom pointer-capture 미지원 우회).
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { ApiError } from '@/api/client'
import type {
  ImportAnalysisResponse,
  UserCollectionResponse,
  ValueCollectionResponse,
} from '@/api/import-mappings'
import type { ImportJobStatus } from '@/api/imports'
import { ImportMappingWizard } from './ImportMappingWizard'

// ─────────────────────────────────────────────────────────────────────────────
// 모듈 mock
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@/api/import-mappings', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/import-mappings')>()
  return {
    ...actual,
    analyzeImport: vi.fn(),
    validateFieldMapping: vi.fn(),
    collectUsers: vi.fn(),
    collectValues: vi.fn(),
    confirmMapping: vi.fn(),
  }
})

vi.mock('@/hooks/use-import-job-polling', () => ({
  useImportJobPolling: vi.fn(),
}))

vi.mock('@/lib/download', () => ({
  triggerBlobDownload: vi.fn(),
}))

// shadcn Select → 네이티브 <select> mock (jsdom pointer-capture 미지원 우회,
// FieldMappingStep.test.tsx 동형 선례). aria-label/옵션 구조를 그대로 보존해
// 이 파일의 다른 테스트(Select 상호작용 없음)에도 영향을 주지 않는다.
vi.mock('@/components/ui/select', async () => {
  const { createElement: ce, useRef, Children } = await vi.importActual<typeof import('react')>('react')

  function Select({
    children,
    onValueChange,
    value,
  }: {
    children: ReactNode
    onValueChange?: (v: string) => void
    value?: string
  }) {
    const triggerLabel = useRef<string>('')
    const contentOptions = useRef<ReactNode>(null)

    Children.forEach(children, (child) => {
      if (child !== null && typeof child === 'object' && 'props' in (child as object)) {
        const el = child as { props: { 'aria-label'?: string; children?: ReactNode } }
        if (el.props['aria-label']) triggerLabel.current = el.props['aria-label']
        if (el.props.children) contentOptions.current = el.props.children
      }
    })

    return ce(
      'select',
      {
        'aria-label': triggerLabel.current,
        value: value ?? '',
        onChange: (e: { target: { value: string } }) => {
          if (onValueChange) onValueChange(e.target.value)
        },
      },
      contentOptions.current,
    )
  }

  function SelectTrigger({ children, 'aria-label': ariaLabel }: { children?: ReactNode; 'aria-label'?: string }) {
    return ce('span', { 'aria-label': ariaLabel }, children)
  }
  function SelectValue() {
    return null
  }
  function SelectContent({ children }: { children: ReactNode }) {
    return ce('span', {}, children)
  }
  function SelectItem({ value, children }: { value: string; children: ReactNode }) {
    return ce('option', { value }, children)
  }

  return { Select, SelectTrigger, SelectValue, SelectContent, SelectItem }
})

import {
  analyzeImport,
  validateFieldMapping,
  collectUsers,
  collectValues,
  confirmMapping,
} from '@/api/import-mappings'
import { useImportJobPolling } from '@/hooks/use-import-job-polling'
import { downloadImportErrorLog } from '@/api/imports'

vi.mock('@/api/imports', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/imports')>()
  return {
    ...actual,
    downloadImportErrorLog: vi.fn(),
  }
})

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const JOB_ID_1 = '00000000-0000-4000-a000-000000000001'
const JOB_ID_2 = '00000000-0000-4000-a000-000000000002'

const TARGET_FIELDS_FIXTURE: ImportAnalysisResponse['targetFields'] = [
  { key: 'summary', label: '제목', required: true, multi: false },
  { key: 'status', label: '상태', required: false, multi: false },
]

/** CSV 분석 응답 — 필드 매핑 단계로 진입 */
function makeCsvAnalysis(overrides: Partial<ImportAnalysisResponse> = {}): ImportAnalysisResponse {
  return {
    jobId: JOB_ID_1,
    status: 'AWAITING_MAPPING',
    format: 'CSV',
    sourceFields: [{ name: 'Summary' }],
    sampleRows: [],
    targetFields: TARGET_FIELDS_FIXTURE,
    ...overrides,
  }
}

/** JSON 분석 응답 — 필드 매핑 단계를 건너뛰고 바로 사용자 매핑으로 진입 */
function makeJsonAnalysis(overrides: Partial<ImportAnalysisResponse> = {}): ImportAnalysisResponse {
  return {
    jobId: JOB_ID_1,
    status: 'AWAITING_MAPPING',
    format: 'JSON',
    sourceFields: [{ name: 'summary' }],
    sampleRows: [],
    targetFields: TARGET_FIELDS_FIXTURE,
    ...overrides,
  }
}

const USERS_FIXTURE: UserCollectionResponse['users'] = [
  { sourceIdentifier: 'alice@example.com', suggestedUserId: '11111111-0000-4000-a000-000000000001', suggestedDisplayName: 'Alice' },
]

const VALUES_FIXTURE: ValueCollectionResponse['fields'] = [
  { targetField: 'STATUS', values: [{ sourceValue: 'Open', suggestedTargetValue: '할 일' }] },
]

function makeJobStatus(overrides: Partial<ImportJobStatus> = {}): ImportJobStatus {
  return {
    jobId: JOB_ID_1,
    status: 'PENDING',
    progress: 0,
    succeededRows: 0,
    failedRows: 0,
    errorLogReady: false,
    dryRun: false,
    ...overrides,
  }
}

function makeFile(name: string, type: string): File {
  return new File(['dummy'], name, { type })
}

// ─────────────────────────────────────────────────────────────────────────────
// 렌더 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return createElement(QueryClientProvider, { client: qc }, children)
  }
}

function renderWizard() {
  const user = userEvent.setup()
  const utils = render(createElement(ImportMappingWizard, { projectKey: 'ATLAS' }), {
    wrapper: createWrapper(),
  })
  return { user, ...utils }
}

async function uploadAndAnalyze(user: ReturnType<typeof userEvent.setup>, format: 'CSV' | 'JSON') {
  if (format === 'JSON') {
    await user.click(screen.getByRole('radio', { name: 'JSON' }))
  }
  await user.upload(
    screen.getByLabelText('분석할 파일'),
    makeFile(format === 'JSON' ? 'issues.json' : 'issues.csv', format === 'JSON' ? 'application/json' : 'text/csv'),
  )
  await user.click(screen.getByRole('button', { name: '분석' }))
}

// ─────────────────────────────────────────────────────────────────────────────
// 셋업
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  vi.mocked(analyzeImport).mockReset()
  vi.mocked(validateFieldMapping).mockReset()
  vi.mocked(collectUsers).mockReset()
  vi.mocked(collectValues).mockReset()
  vi.mocked(confirmMapping).mockReset()
  vi.mocked(downloadImportErrorLog).mockReset()
  vi.mocked(useImportJobPolling).mockReset()
  vi.mocked(useImportJobPolling).mockReturnValue({
    data: undefined,
    isError: false,
  } as ReturnType<typeof useImportJobPolling>)
})

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('ImportMappingWizard', () => {
  // (a) upload → analyze(CSV) → fields 진입
  it('CSV 분석 성공 시 필드 매핑 단계로 진입한다', async () => {
    vi.mocked(analyzeImport).mockResolvedValue(makeCsvAnalysis())
    const { user } = renderWizard()

    await uploadAndAnalyze(user, 'CSV')

    await waitFor(() => {
      expect(screen.getByTestId('field-mapping-row-Summary')).toBeInTheDocument()
    })
  })

  // (b) JSON → 필드 매핑 스킵 → 사용자 매핑 진입
  it('JSON 분석 성공 시 필드 매핑을 건너뛰고 사용자 매핑 단계로 진입한다', async () => {
    vi.mocked(analyzeImport).mockResolvedValue(makeJsonAnalysis())
    vi.mocked(collectUsers).mockResolvedValue({ users: USERS_FIXTURE })
    const { user } = renderWizard()

    await uploadAndAnalyze(user, 'JSON')

    await waitFor(() => {
      expect(vi.mocked(collectUsers)).toHaveBeenCalledWith(JOB_ID_1, [])
    })
    expect(screen.getByText('alice@example.com')).toBeInTheDocument()
    expect(screen.queryByTestId('field-mapping-row-Summary')).not.toBeInTheDocument()
  })

  // (c) collectUsers 빈 목록 → 값 매핑 단계로 자동 스킵
  it('collectUsers 결과가 빈 배열이면 사용자 매핑을 건너뛰고 값 매핑 단계로 진입한다', async () => {
    vi.mocked(analyzeImport).mockResolvedValue(makeJsonAnalysis())
    vi.mocked(collectUsers).mockResolvedValue({ users: [] })
    vi.mocked(collectValues).mockResolvedValue({ fields: VALUES_FIXTURE })
    const { user } = renderWizard()

    await uploadAndAnalyze(user, 'JSON')

    await waitFor(() => {
      expect(vi.mocked(collectValues)).toHaveBeenCalledWith(JOB_ID_1, [])
    })
    expect(screen.getByLabelText('상태 Open 대상 값')).toBeInTheDocument()
  })

  // (d) collectValues 빈 목록 → 검토 단계로 자동 스킵
  it('collectValues 결과가 빈 배열이면 값 매핑을 건너뛰고 검토 단계로 진입한다', async () => {
    vi.mocked(analyzeImport).mockResolvedValue(makeJsonAnalysis())
    vi.mocked(collectUsers).mockResolvedValue({ users: [] })
    vi.mocked(collectValues).mockResolvedValue({ fields: [] })
    const { user } = renderWizard()

    await uploadAndAnalyze(user, 'JSON')

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '가져오기 실행' })).toBeInTheDocument()
    })
  })

  // (e) 검토 → [가져오기 실행] → confirm(dryRun=false) → tracking → done
  it('검토 단계에서 [가져오기 실행] 클릭 시 confirmMapping(dryRun=false)이 호출되고 완료까지 진행된다', async () => {
    vi.mocked(analyzeImport).mockResolvedValue(makeJsonAnalysis())
    vi.mocked(collectUsers).mockResolvedValue({ users: [] })
    vi.mocked(collectValues).mockResolvedValue({ fields: [] })
    vi.mocked(confirmMapping).mockResolvedValue(makeJobStatus({ status: 'PENDING', dryRun: false }))
    const { user, rerender } = renderWizard()

    await uploadAndAnalyze(user, 'JSON')
    await waitFor(() => screen.getByRole('button', { name: '가져오기 실행' }))

    await user.click(screen.getByRole('button', { name: '가져오기 실행' }))

    await waitFor(() => {
      expect(vi.mocked(confirmMapping)).toHaveBeenCalledWith(
        JOB_ID_1,
        expect.objectContaining({ dryRun: false, fieldMappings: [], userMappings: [], valueMappings: [] }),
      )
    })

    // tracking → COMPLETED 폴링 결과로 done 전환 — 훅 mock은 반응형이 아니므로 rerender로 재조회를 유도한다
    vi.mocked(useImportJobPolling).mockReturnValue({
      data: makeJobStatus({ status: 'COMPLETED', progress: 100, succeededRows: 3, failedRows: 0, dryRun: false }),
      isError: false,
    } as ReturnType<typeof useImportJobPolling>)
    rerender(createElement(ImportMappingWizard, { projectKey: 'ATLAS' }))

    await waitFor(() => {
      expect(screen.getByText('Import 완료')).toBeInTheDocument()
    })
  })

  // (e-1) 버그 수정 회귀 테스트 — 폴링은 tracking 단계에서만 활성화된다
  // (실측 결함: enabled가 상수 true라 analyze 직후[confirm 이전] 존재하지 않는 jobId로 즉시
  // GET가 나가 404 → retry:false라 쿼리가 error 상태로 굳고 refetchInterval이 영구 false를 반환해
  // confirm 이후에도 tracking 화면이 "상태를 조회하지 못했습니다"에 고착되는 회귀를 고정한다.)
  it('confirm 이전(검토 단계까지)에는 폴링이 비활성화되고, confirm 성공 후 tracking 진입 시에만 활성화된다', async () => {
    vi.mocked(analyzeImport).mockResolvedValue(makeJsonAnalysis())
    vi.mocked(collectUsers).mockResolvedValue({ users: [] })
    vi.mocked(collectValues).mockResolvedValue({ fields: [] })
    vi.mocked(confirmMapping).mockResolvedValue(makeJobStatus({ status: 'PENDING', dryRun: false }))
    const { user } = renderWizard()

    await uploadAndAnalyze(user, 'JSON')
    await waitFor(() => screen.getByRole('button', { name: '가져오기 실행' }))

    // jobId는 이미 세팅됐지만(analyze 응답) 아직 confirm 전 — 폴링은 비활성이어야 한다
    expect(vi.mocked(useImportJobPolling)).toHaveBeenLastCalledWith(JOB_ID_1, false)

    await user.click(screen.getByRole('button', { name: '가져오기 실행' }))

    await waitFor(() => {
      expect(vi.mocked(useImportJobPolling)).toHaveBeenLastCalledWith(JOB_ID_1, true)
    })
  })

  // (f) dry-run 완료 후 [이 매핑으로 실제 가져오기] → 재-analyze + confirm 재호출
  it('dry-run 완료 후 [이 매핑으로 실제 가져오기] 클릭 시 재-analyze 후 dryRun=false로 confirmMapping이 재호출된다', async () => {
    vi.mocked(analyzeImport).mockResolvedValueOnce(makeJsonAnalysis())
    vi.mocked(collectUsers).mockResolvedValue({ users: [] })
    vi.mocked(collectValues).mockResolvedValue({ fields: [] })
    vi.mocked(confirmMapping).mockResolvedValueOnce(makeJobStatus({ status: 'PENDING', dryRun: true }))
    vi.mocked(useImportJobPolling).mockReturnValue({
      data: makeJobStatus({ status: 'COMPLETED', succeededRows: 2, failedRows: 1, dryRun: true }),
      isError: false,
    } as ReturnType<typeof useImportJobPolling>)

    const { user } = renderWizard()
    await uploadAndAnalyze(user, 'JSON')
    await waitFor(() => screen.getByRole('button', { name: '검증만 실행' }))
    await user.click(screen.getByRole('button', { name: '검증만 실행' }))

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '이 매핑으로 실제 가져오기' })).toBeInTheDocument()
    })

    vi.mocked(analyzeImport).mockResolvedValueOnce(makeJsonAnalysis({ jobId: JOB_ID_2 }))
    vi.mocked(confirmMapping).mockResolvedValueOnce(makeJobStatus({ jobId: JOB_ID_2, status: 'PENDING', dryRun: false }))

    await user.click(screen.getByRole('button', { name: '이 매핑으로 실제 가져오기' }))

    await waitFor(() => {
      expect(vi.mocked(analyzeImport)).toHaveBeenCalledTimes(2)
    })
    await waitFor(() => {
      expect(vi.mocked(confirmMapping)).toHaveBeenLastCalledWith(
        JOB_ID_2,
        expect.objectContaining({ dryRun: false }),
      )
    })

    // dry-run 재적용(G1) — 폴링이 이전 jobId가 아니라 새로 발급된 jobId를 tracking 활성 상태로 추적해야
    // 한다. mock이 COMPLETED를 상수로 반환하므로 tracking 진입 직후 즉시 done으로 전이해 최종 호출은
    // (JOB_ID_2, false)로 안정화되지만(done 단계는 폴링 비활성이 올바른 동작), 그 전이 과정에서
    // (JOB_ID_2, true) 호출이 반드시 존재해야 한다 — toHaveBeenCalledWith는 호출 이력 전체를 검사한다.
    await waitFor(() => {
      expect(vi.mocked(useImportJobPolling)).toHaveBeenCalledWith(JOB_ID_2, true)
    })
  })

  // (h) 게이트2 리뷰 BLOCKER — 재수집 시 override 보존(생존 키 유지·사라진 키 폐기·신규 키만 추천 시드)
  it('필드 매핑을 바꿔 사용자를 재수집해도, 사용자가 지정한 override는 유지되고 사라진 키는 폐기되며 신규 키만 추천값으로 시드된다', async () => {
    const ALICE_ID = '11111111-0000-4000-a000-000000000001'
    const CAROL_ID = '11111111-0000-4000-a000-000000000003'
    vi.mocked(analyzeImport).mockResolvedValue(makeCsvAnalysis())
    vi.mocked(validateFieldMapping).mockResolvedValue({ valid: true, errors: [], warnings: [] })
    vi.mocked(collectUsers)
      .mockResolvedValueOnce({
        users: [
          { sourceIdentifier: 'alice@example.com', suggestedUserId: ALICE_ID, suggestedDisplayName: 'Alice' },
          { sourceIdentifier: 'bob@example.com' },
        ],
      })
      .mockResolvedValueOnce({
        users: [
          { sourceIdentifier: 'alice@example.com', suggestedUserId: ALICE_ID, suggestedDisplayName: 'Alice' },
          { sourceIdentifier: 'carol@example.com', suggestedUserId: CAROL_ID, suggestedDisplayName: 'Carol' },
        ],
      })
    const { user } = renderWizard()

    await uploadAndAnalyze(user, 'CSV')
    await waitFor(() => screen.getByTestId('field-mapping-row-Summary'))
    await user.click(screen.getByRole('button', { name: '다음' }))

    // 1차 수집 — alice에 추천이 잡혀 있다. 사용자가 명시적으로 "미매핑"으로 override한다.
    await waitFor(() => screen.getByText('alice@example.com'))
    const aliceRow = screen.getByTestId('user-mapping-row-alice@example.com')
    await user.click(within(aliceRow).getByRole('button', { name: /미매핑/ }))
    expect(within(aliceRow).getByRole('button', { name: /미매핑/ })).toHaveAttribute('aria-pressed', 'true')

    // 필드 매핑으로 돌아갔다가 다시 [다음] — 재수집을 트리거한다(매핑 자체는 바뀌지 않아도 재수집은 발생)
    await user.click(screen.getByRole('button', { name: '이전' }))
    await waitFor(() => screen.getByTestId('field-mapping-row-Summary'))
    await user.click(screen.getByRole('button', { name: '다음' }))

    // 2차 수집 — bob은 사라지고(폐기 확인) carol이 새로 등장한다(추천 시드 확인). alice는 생존 키.
    await waitFor(() => {
      expect(vi.mocked(collectUsers)).toHaveBeenCalledTimes(2)
    })
    await waitFor(() => screen.getByText('carol@example.com'))

    expect(screen.queryByText('bob@example.com')).not.toBeInTheDocument()

    const aliceRowAfter = screen.getByTestId('user-mapping-row-alice@example.com')
    expect(within(aliceRowAfter).getByRole('button', { name: /미매핑/ })).toHaveAttribute(
      'aria-pressed',
      'true',
    )

    const carolRow = screen.getByTestId('user-mapping-row-carol@example.com')
    expect(within(carolRow).getByRole('button', { name: /추천/ })).toHaveAttribute('aria-pressed', 'true')
  })

  // (i) 게이트2 리뷰 높은 CONCERN — review 422 dead-end 해소: errors[] 렌더 + [이전] 복구
  it('confirm이 422(errors[] 포함)로 실패하면 각 사유가 role=alert로 표시되고, [이전]으로 값 매핑 단계로 복귀할 수 있다', async () => {
    vi.mocked(analyzeImport).mockResolvedValue(makeJsonAnalysis())
    vi.mocked(collectUsers).mockResolvedValue({ users: [] })
    vi.mocked(collectValues).mockResolvedValue({ fields: VALUES_FIXTURE })
    vi.mocked(confirmMapping).mockRejectedValue(
      new ApiError(422, {
        errorCode: 'IMPORT_VALUE_MAPPING_INVALID',
        errors: [
          { code: 'TARGET_VALUE_NOT_FOUND', message: '대상 값을 찾을 수 없습니다.', field: 'Bug' },
        ],
      }),
    )
    const { user } = renderWizard()

    await uploadAndAnalyze(user, 'JSON')
    await waitFor(() => screen.getByLabelText('상태 Open 대상 값'))
    await user.click(screen.getByRole('button', { name: '다음' }))

    await waitFor(() => screen.getByRole('button', { name: '가져오기 실행' }))
    await user.click(screen.getByRole('button', { name: '가져오기 실행' }))

    await waitFor(() => {
      const alerts = screen.getAllByRole('alert')
      expect(alerts.some((el) => el.textContent?.includes('Bug') && el.textContent?.includes('대상 값을 찾을 수 없습니다.'))).toBe(true)
    })

    // [이전] — collectedValues가 존재(non-empty)하므로 값 매핑 단계로 복귀해야 한다
    await user.click(screen.getByRole('button', { name: '이전' }))
    await waitFor(() => {
      expect(screen.getByLabelText('상태 Open 대상 값')).toBeInTheDocument()
    })
  })

  // (j) 게이트2 리뷰 경미 — 검토 요약 카운트 기준 통일(사용자 매핑 건수는 실제 매핑=non-null 기준)
  it('검토 요약의 사용자 매핑 건수는 실제로 대상 사용자에 매핑된(non-null) 건수만 센다', async () => {
    const ALICE_ID = '11111111-0000-4000-a000-000000000001'
    const BOB_ID = '11111111-0000-4000-a000-000000000002'
    vi.mocked(analyzeImport).mockResolvedValue(makeJsonAnalysis())
    vi.mocked(collectUsers).mockResolvedValue({
      users: [
        { sourceIdentifier: 'alice@example.com', suggestedUserId: ALICE_ID, suggestedDisplayName: 'Alice' },
        { sourceIdentifier: 'bob@example.com', suggestedUserId: BOB_ID, suggestedDisplayName: 'Bob' },
      ],
    })
    vi.mocked(collectValues).mockResolvedValue({ fields: [] })
    const { user } = renderWizard()

    await uploadAndAnalyze(user, 'JSON')
    await waitFor(() => screen.getByText('alice@example.com'))

    // bob은 명시적으로 "미매핑"으로 override — 실제 매핑 건수는 alice 1건뿐이어야 한다(collected 총 2건과 구분)
    const bobRow = screen.getByTestId('user-mapping-row-bob@example.com')
    await user.click(within(bobRow).getByRole('button', { name: /미매핑/ }))

    await user.click(screen.getByRole('button', { name: '다음' }))

    await waitFor(() => {
      expect(screen.getByText(/사용자 매핑 1건/)).toBeInTheDocument()
    })
    expect(screen.queryByText(/사용자 매핑 2건/)).not.toBeInTheDocument()
  })

  // (k) 게이트2 리뷰 경미 — DR-4 재수집 통지를 값 매핑 단계에도 노출
  it('필드 매핑 변경으로 재수집되면 값 매핑 단계에도 재수집 통지가 표시된다', async () => {
    vi.mocked(analyzeImport).mockResolvedValue(
      makeCsvAnalysis({ sourceFields: [{ name: 'Summary' }, { name: 'Status' }] }),
    )
    vi.mocked(validateFieldMapping).mockResolvedValue({ valid: true, errors: [], warnings: [] })
    vi.mocked(collectUsers)
      .mockResolvedValueOnce({ users: USERS_FIXTURE })
      .mockResolvedValueOnce({ users: USERS_FIXTURE })
    vi.mocked(collectValues).mockResolvedValue({ fields: VALUES_FIXTURE })
    const { user } = renderWizard()

    await uploadAndAnalyze(user, 'CSV')
    await waitFor(() => screen.getByTestId('field-mapping-row-Summary'))
    await user.click(screen.getByRole('button', { name: '다음' }))

    await waitFor(() => screen.getByText('alice@example.com'))
    expect(screen.queryByText('필드 매핑이 바뀌어 작성자를 다시 수집했습니다.')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '이전' }))
    await waitFor(() => screen.getByTestId('field-mapping-row-Status'))
    await user.selectOptions(screen.getByLabelText('Status 매핑 대상'), 'IGNORE')
    await user.click(screen.getByRole('button', { name: '다음' }))

    await waitFor(() => {
      expect(screen.getByText('필드 매핑이 바뀌어 작성자를 다시 수집했습니다.')).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: '다음' }))

    await waitFor(() => {
      expect(screen.getByText('필드 매핑이 바뀌어 값을 다시 수집했습니다.')).toBeInTheDocument()
    })
  })

  // (g) DR-6 — 빈 sourceFields면 에러 + upload 단계 유지
  it('analyze 결과 sourceFields가 빈 배열이면 에러가 표시되고 업로드 단계에 머무른다', async () => {
    vi.mocked(analyzeImport).mockResolvedValue(makeCsvAnalysis({ sourceFields: [] }))
    const { user } = renderWizard()

    await uploadAndAnalyze(user, 'CSV')

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
    expect(screen.getByRole('button', { name: '분석' })).toBeInTheDocument()
  })
})
