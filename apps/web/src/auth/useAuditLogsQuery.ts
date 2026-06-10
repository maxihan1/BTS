// 관리자 인증 감사 로그 목록을 조회하는 TanStack Query 훅
import { useQuery, keepPreviousData } from '@tanstack/react-query'
import { fetchAuditLogs } from '@/api/audit-logs'
import type { AuditLogQueryParams, AuditLogPage } from '@/api/audit-logs'

// ─────────────────────────────────────────────────────────────────────────────
// queryKey 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 감사 로그 TanStack Query 캐시 키 상수 */
export const AUDIT_LOGS_QUERY_KEY = ['audit-logs'] as const

/**
 * 감사 로그 쿼리 키 빌더.
 * params를 포함해 필터 변경 시 독립적인 캐시 엔트리를 생성한다.
 */
export const buildAuditLogsQueryKey = (params: AuditLogQueryParams) =>
  [...AUDIT_LOGS_QUERY_KEY, params] as const

// ─────────────────────────────────────────────────────────────────────────────
// 훅
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 관리자 인증 감사 로그 목록을 조회하는 훅. SYSTEM_ADMIN 전용.
 *
 * - `GET /api/v1/admin/auth-audit-logs` → `AuditLogPage`
 * - params가 queryKey에 포함되어 필터/페이지 변경 시 자동 재조회.
 * - `keepPreviousData`: 페이지 전환 시 이전 데이터를 유지해 깜빡임 방지.
 * - staleTime 30초 — 감사 로그는 짧은 주기 갱신이 불필요.
 * - PAT 인증 또는 비관리자 → 403, isError=true.
 *
 * @param params 필터 + 페이지네이션 파라미터 (전부 선택)
 * @returns TanStack Query 훅 반환 객체. `data`는 `AuditLogPage` 또는 undefined
 */
export function useAuditLogsQuery(params: AuditLogQueryParams) {
  return useQuery<AuditLogPage>({
    queryKey: buildAuditLogsQueryKey(params),
    queryFn: () => fetchAuditLogs(params),
    staleTime: 30_000,
    placeholderData: keepPreviousData,
  })
}
