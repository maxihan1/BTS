// 라벨 자동완성 MSW 핸들러 — 고정 라벨셋 prefix 필터 + 빈도순 정렬 (FR-IS-09)
import { http, HttpResponse } from 'msw'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 자동완성 결과 최대 반환 개수 */
const MAX_RESULTS = 10

/**
 * 고정 라벨셋 — 빈도(freq) 내림차순으로 정렬된 상태.
 * 실제 백엔드는 이슈에 사용된 빈도순으로 반환하므로 동일 정렬 보장.
 */
interface LabelEntry {
  label: string
  freq: number
}

const LABEL_SEED: LabelEntry[] = [
  { label: 'bug', freq: 120 },
  { label: 'feature', freq: 95 },
  { label: 'frontend', freq: 80 },
  { label: 'backend', freq: 75 },
  { label: 'billing', freq: 60 },
  { label: 'performance', freq: 50 },
  { label: 'security', freq: 45 },
  { label: 'documentation', freq: 40 },
  { label: 'testing', freq: 35 },
  { label: 'refactor', freq: 30 },
  { label: 'hotfix', freq: 25 },
  { label: 'breaking-change', freq: 20 },
]

// ─────────────────────────────────────────────────────────────────────────────
// 핸들러
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 라벨 자동완성 MSW 핸들러.
 *
 * GET /api/v1/labels?q=<prefix>
 * - q prefix로 대소문자 무시 필터링
 * - 빈도 내림차순 정렬 (LABEL_SEED 순서 유지)
 * - 최대 MAX_RESULTS개 반환
 * - 응답 형태: { data: string[] } (백엔드 DataResponse 계약과 동일)
 */
export const labelHandlers = [
  http.get('/api/v1/labels', ({ request }) => {
    const url = new URL(request.url)
    const q = url.searchParams.get('q') ?? ''
    const prefix = q.toLowerCase()

    const filtered = LABEL_SEED
      .filter(({ label }) => label.startsWith(prefix))
      .slice(0, MAX_RESULTS)
      .map(({ label }) => label)

    return HttpResponse.json({ data: filtered })
  }),
]
