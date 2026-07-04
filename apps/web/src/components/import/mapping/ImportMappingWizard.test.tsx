// Import 매핑 마법사 컨테이너 단위 테스트 — 상태머신/동적 스킵/검토/폴링/dry-run 재적용 (FR-IM-02 D6 Task-6)
//
// 설계 결정.
//   - '@/api/import-mappings' 전체를 mock해 analyze/collectUsers/collectValues/confirmMapping을 제어한다.
//   - '@/hooks/use-import-job-polling'를 mock해 폴링 결과(tracking→done 전이)를 직접 제어한다.
//   - FieldMappingStep/UserMappingStep/ValueMappingStep은 실제 컴포넌트를 그대로 렌더한다(모킹 없음) —
//     단, Radix Select를 여는 상호작용(jsdom pointer-capture 미지원)을 피하기 위해 이 테스트들은
//     JSON 업로드(필드 매핑 스킵) 또는 collectUsers/collectValues 빈 결과(자동 스킵) 경로로
//     FieldMappingStep의 Select 상호작용 없이 시나리오를 구성한다("다음" 버튼 클릭만 사용).
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'
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
