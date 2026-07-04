// FieldMappingStep 단위 테스트 — 소스별 select 렌더/초기값, 샘플 표, validate 호출, errors/warnings 렌더, onNext
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { ApiError } from '@/api/client'
import type { FieldMappingEntry, MappingValidationResponse } from '@/api/import-mappings'
import type { TargetFieldCatalogEntry } from './field-mapping-suggest'

// ─────────────────────────────────────────────────────────────────────────────
// shadcn Select → 네이티브 <select> mock (jsdom pointer-capture 미지원 우회,
// ResolutionPickerModal.test.tsx 동형 선례)
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@/components/ui/select', async () => {
  const { createElement: ce, useRef, Children } = await vi.importActual<typeof import('react')>('react')

  function Select({
    children,
    onValueChange,
    value,
  }: {
    children: React.ReactNode
    onValueChange?: (v: string) => void
    value?: string
  }) {
    const triggerLabel = useRef<string>('')
    const contentOptions = useRef<React.ReactNode>(null)

    Children.forEach(children, (child) => {
      if (child !== null && typeof child === 'object' && 'props' in (child as object)) {
        const el = child as React.ReactElement<{ 'aria-label'?: string; children?: React.ReactNode }>
        if (el.props['aria-label']) {
          triggerLabel.current = el.props['aria-label']
        }
        if (el.props.children) {
          contentOptions.current = el.props.children
        }
      }
    })

    return ce(
      'select',
      {
        'aria-label': triggerLabel.current,
        value: value ?? '',
        onChange: (e: React.ChangeEvent<HTMLSelectElement>) => {
          if (onValueChange) onValueChange(e.target.value)
        },
      },
      contentOptions.current,
    )
  }

  function SelectTrigger({
    children,
    'aria-label': ariaLabel,
  }: {
    children?: ReactNode
    'aria-label'?: string
    className?: string
  }) {
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

// ─────────────────────────────────────────────────────────────────────────────
// validateFieldMapping mock
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@/api/import-mappings', () => ({
  validateFieldMapping: vi.fn(),
}))

import { validateFieldMapping } from '@/api/import-mappings'
import { FieldMappingStep } from './FieldMappingStep'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const JOB_ID = '00000000-0000-4000-a000-000000000099'

const TARGET_FIELDS: TargetFieldCatalogEntry[] = [
  { key: 'summary', label: '제목', required: true, multi: false },
  { key: 'status', label: '상태', required: false, multi: false },
  { key: 'priority', label: '우선순위', required: false, multi: false },
]

const SOURCE_FIELDS = ['Summary', 'Status']
const SAMPLE_ROWS = [
  ['버그 수정', 'Open'],
  ['기능 추가', 'Done'],
]

/** suggestFieldMappings(SOURCE_FIELDS, TARGET_FIELDS) 결과와 동치인 초기값 (key 정규화 매칭) */
const INITIAL_VALUE: Record<string, string> = { Summary: 'summary', Status: 'status' }

function makeValidation(overrides: Partial<MappingValidationResponse> = {}): MappingValidationResponse {
  return { valid: true, errors: [], warnings: [], ...overrides }
}

function createDeferred<T>(): { promise: Promise<T>; resolve: (v: T) => void } {
  let resolve: (v: T) => void = () => undefined
  const promise = new Promise<T>((res) => {
    resolve = res
  })
  return { promise, resolve }
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

interface RenderStepOptions {
  sourceFields?: string[]
  sampleRows?: string[][]
  targetFields?: TargetFieldCatalogEntry[]
  value?: Record<string, string>
  onChange?: (next: Record<string, string>) => void
  onNext?: (entries: FieldMappingEntry[]) => void
  onBack?: () => void
}

function renderStep(options: RenderStepOptions = {}) {
  const user = userEvent.setup()
  const props = {
    jobId: JOB_ID,
    sourceFields: options.sourceFields ?? SOURCE_FIELDS,
    sampleRows: options.sampleRows ?? SAMPLE_ROWS,
    targetFields: options.targetFields ?? TARGET_FIELDS,
    value: options.value ?? INITIAL_VALUE,
    onChange: options.onChange ?? vi.fn(),
    onNext: options.onNext ?? vi.fn(),
    ...(options.onBack !== undefined ? { onBack: options.onBack } : {}),
  }
  const utils = render(createElement(FieldMappingStep, props), { wrapper: createWrapper() })
  return { user, props, ...utils }
}

beforeEach(() => {
  vi.mocked(validateFieldMapping).mockReset()
})

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('FieldMappingStep', () => {
  describe('렌더 — select/초기값', () => {
    it('소스 헤더별 매핑 대상 select가 렌더되고 카탈로그+매핑 안 함 옵션을 갖는다', () => {
      renderStep()
      const summarySelect = screen.getByLabelText('Summary 매핑 대상')
      expect(summarySelect).toBeInTheDocument()
      expect(within(summarySelect).getByRole('option', { name: '매핑 안 함' })).toBeInTheDocument()
      expect(within(summarySelect).getByRole('option', { name: /제목/ })).toBeInTheDocument()
      expect(within(summarySelect).getByRole('option', { name: /상태/ })).toBeInTheDocument()
      expect(within(summarySelect).getByRole('option', { name: /우선순위/ })).toBeInTheDocument()
    })

    it('부모 value가 초기 선택값으로 반영된다', () => {
      renderStep()
      expect(screen.getByLabelText('Summary 매핑 대상')).toHaveValue('summary')
      expect(screen.getByLabelText('Status 매핑 대상')).toHaveValue('status')
    })

    it('대상을 변경하면 onChange가 병합된 매핑을 전달한다', async () => {
      const onChange = vi.fn()
      const { user } = renderStep({ onChange })
      await user.selectOptions(screen.getByLabelText('Status 매핑 대상'), 'priority')
      expect(onChange).toHaveBeenCalledWith({ Summary: 'summary', Status: 'priority' })
    })
  })

  describe('렌더 — 샘플 미리보기 표', () => {
    it('sampleRows가 있으면 sourceFields 순서로 표를 렌더한다', () => {
      renderStep()
      const table = screen.getByRole('table')
      expect(within(table).getByRole('columnheader', { name: 'Summary' })).toBeInTheDocument()
      expect(within(table).getByRole('columnheader', { name: 'Status' })).toBeInTheDocument()
      expect(within(table).getByText('버그 수정')).toBeInTheDocument()
      expect(within(table).getByText('Open')).toBeInTheDocument()
    })

    it('sampleRows가 빈 배열이면 표를 생략한다', () => {
      renderStep({ sampleRows: [] })
      expect(screen.queryByRole('table')).not.toBeInTheDocument()
    })
  })

  describe('검증 — [다음]', () => {
    it('[다음] 클릭 시 validateFieldMapping(jobId, entries)가 호출된다', async () => {
      vi.mocked(validateFieldMapping).mockResolvedValue(makeValidation())
      const { user } = renderStep()

      await user.click(screen.getByRole('button', { name: '다음' }))

      await waitFor(() => {
        expect(vi.mocked(validateFieldMapping)).toHaveBeenCalledWith(JOB_ID, [
          { sourceField: 'Summary', targetField: 'summary' },
          { sourceField: 'Status', targetField: 'status' },
        ])
      })
    })

    it('검증 중에는 버튼이 비활성화되고 "검증 중..." 라벨을 보여준다(DR-1)', async () => {
      const deferred = createDeferred<MappingValidationResponse>()
      vi.mocked(validateFieldMapping).mockReturnValue(deferred.promise)
      const { user } = renderStep()

      await user.click(screen.getByRole('button', { name: '다음' }))

      const pendingButton = screen.getByRole('button', { name: '검증 중...' })
      expect(pendingButton).toBeDisabled()

      deferred.resolve(makeValidation())
      await waitFor(() => {
        expect(screen.getByRole('button', { name: '다음' })).toBeInTheDocument()
      })
    })

    it('valid=true이면 onNext(entries)가 호출된다', async () => {
      vi.mocked(validateFieldMapping).mockResolvedValue(makeValidation())
      const onNext = vi.fn()
      const { user } = renderStep({ onNext })

      await user.click(screen.getByRole('button', { name: '다음' }))

      await waitFor(() => {
        expect(onNext).toHaveBeenCalledWith([
          { sourceField: 'Summary', targetField: 'summary' },
          { sourceField: 'Status', targetField: 'status' },
        ])
      })
    })

    it('전역 error(field=null, 예: SUMMARY_NOT_MAPPED)가 있으면 role=alert로 표시되고 onNext는 호출되지 않는다', async () => {
      vi.mocked(validateFieldMapping).mockResolvedValue(
        makeValidation({
          valid: false,
          errors: [
            {
              code: 'SUMMARY_NOT_MAPPED',
              message: 'summary(제목) 대상에 매핑된 소스 필드가 없습니다.',
              field: null,
            },
          ],
        }),
      )
      const onNext = vi.fn()
      const { user } = renderStep({ onNext, value: { Summary: 'IGNORE', Status: 'status' } })

      await user.click(screen.getByRole('button', { name: '다음' }))

      await waitFor(() => {
        expect(screen.getByRole('alert')).toHaveTextContent(
          'summary(제목) 대상에 매핑된 소스 필드가 없습니다.',
        )
      })
      expect(onNext).not.toHaveBeenCalled()
    })

    it('필드별 error는 해당 소스 행에 role=alert로 귀속된다', async () => {
      vi.mocked(validateFieldMapping).mockResolvedValue(
        makeValidation({
          valid: false,
          errors: [
            { code: 'UNKNOWN_TARGET', message: '알 수 없는 대상 필드 키입니다: foo', field: 'Status' },
          ],
        }),
      )
      const { user } = renderStep()

      await user.click(screen.getByRole('button', { name: '다음' }))

      await waitFor(() => {
        const row = screen.getByTestId('field-mapping-row-Status')
        expect(within(row).getByRole('alert')).toHaveTextContent('알 수 없는 대상 필드 키입니다: foo')
      })
      expect(screen.getByTestId('field-mapping-row-Summary')).not.toHaveTextContent(
        '알 수 없는 대상 필드 키입니다',
      )
    })

    it('warnings는 role=alert가 아니라 비차단으로 표시된다', async () => {
      vi.mocked(validateFieldMapping).mockResolvedValue(
        makeValidation({
          warnings: [
            {
              code: 'SOURCE_FIELD_IGNORED',
              message: '이 소스 필드는 매핑되지 않아 import 시 무시됩니다: Status',
              field: 'Status',
            },
          ],
        }),
      )
      const { user } = renderStep()

      await user.click(screen.getByRole('button', { name: '다음' }))

      await waitFor(() => {
        expect(
          screen.getByText('이 소스 필드는 매핑되지 않아 import 시 무시됩니다: Status'),
        ).toBeInTheDocument()
      })
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    })

    it('validateFieldMapping이 ApiError로 실패하면 detail이 role=alert로 표시된다', async () => {
      vi.mocked(validateFieldMapping).mockRejectedValue(
        new ApiError(409, { detail: '이미 처리 중이거나 완료된 작업입니다.' }),
      )
      const { user } = renderStep()

      await user.click(screen.getByRole('button', { name: '다음' }))

      await waitFor(() => {
        expect(screen.getByRole('alert')).toHaveTextContent('이미 처리 중이거나 완료된 작업입니다.')
      })
    })
  })

  describe('onBack', () => {
    it('onBack이 주어지면 [이전] 버튼이 렌더되고 클릭 시 호출된다', async () => {
      const onBack = vi.fn()
      const { user } = renderStep({ onBack })
      await user.click(screen.getByRole('button', { name: '이전' }))
      expect(onBack).toHaveBeenCalledOnce()
    })

    it('onBack이 없으면 [이전] 버튼이 렌더되지 않는다', () => {
      renderStep()
      expect(screen.queryByRole('button', { name: '이전' })).not.toBeInTheDocument()
    })
  })
})
