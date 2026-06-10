// 감사 로그 관리자 조회 결과 테이블 컴포넌트
import type { JSX } from 'react'
import type { AuditLogEntry } from '@/api/audit-logs'
import { auditLogLabels, authEventTypeLabels } from '@/i18n/audit-log-labels'
import { formatDateTime } from '@/lib/datetime'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 유틸
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 행위 주체 표시 이름을 결정한다.
 * displayName > username > "(알 수 없음)" 순으로 우선한다.
 */
function resolveSubject(entry: AuditLogEntry): string {
  if (entry.displayName != null && entry.displayName !== '') return entry.displayName
  if (entry.username != null && entry.username !== '') return entry.username
  return auditLogLabels.fallback.unknownSubject
}

/**
 * eventType 한국어 라벨을 반환한다.
 * 미지 이벤트(백엔드 enum 추가 대비)는 원문 그대로 반환한다 (전방호환).
 */
function resolveEventLabel(eventType: string): string {
  return (authEventTypeLabels as Record<string, string>)[eventType] ?? eventType
}

/**
 * metadata Record를 한 줄 요약 텍스트로 변환한다.
 * key=value 형태로 쉼표 연결. 빈 맵이면 빈 문자열.
 */
function summarizeMetadata(metadata: Record<string, string>): string {
  const entries = Object.entries(metadata)
  if (entries.length === 0) return ''
  return entries.map(([k, v]) => `${k}=${v}`).join(', ')
}

// ─────────────────────────────────────────────────────────────────────────────
// 행 서브컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

interface AuditLogRowProps {
  readonly entry: AuditLogEntry
}

/**
 * 감사 로그 단건 행 컴포넌트.
 */
function AuditLogRow({ entry }: AuditLogRowProps): JSX.Element {
  const subject = resolveSubject(entry)
  const eventLabel = resolveEventLabel(entry.eventType)
  const ip = entry.ipAddress ?? '—'
  const metaSummary = summarizeMetadata(entry.metadata)

  return (
    <tr className="border-b text-sm hover:bg-muted/50">
      <td className="px-4 py-2 whitespace-nowrap text-muted-foreground">
        {formatDateTime(entry.createdAt)}
      </td>
      <td className="px-4 py-2 whitespace-nowrap font-medium">
        {eventLabel}
      </td>
      <td className="px-4 py-2 whitespace-nowrap">
        {subject}
      </td>
      <td className="px-4 py-2 whitespace-nowrap text-muted-foreground">
        {entry.providerId || auditLogLabels.fallback.unknownProvider}
      </td>
      <td className="px-4 py-2 whitespace-nowrap tabular-nums text-muted-foreground">
        {ip}
      </td>
      <td className="px-4 py-2 max-w-xs truncate text-xs text-muted-foreground" title={metaSummary}>
        {metaSummary}
      </td>
    </tr>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 테이블 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

interface AuditLogTableProps {
  /** 렌더할 감사 로그 목록 */
  readonly entries: AuditLogEntry[]
  /** 로딩 중 여부 — true이면 스켈레톤 표시 */
  readonly isLoading: boolean
}

/**
 * 관리자 인증 감사 로그 결과 테이블.
 *
 * - isLoading=true: 로딩 상태 표시 (role="status").
 * - entries 빈 배열: 빈 상태 메시지 표시.
 * - eventType 미지 값: 원문 표시 (전방호환).
 * - displayName > username > "(알 수 없음)" 폴백.
 * - ipAddress=null → "—".
 * - metadata: key=value 요약.
 */
export function AuditLogTable({ entries, isLoading }: AuditLogTableProps): JSX.Element {
  if (isLoading) {
    return (
      <div role="status" aria-label="로딩 중" className="py-8 text-center text-sm text-muted-foreground">
        로딩 중...
      </div>
    )
  }

  if (entries.length === 0) {
    return (
      <div className="py-8 text-center text-sm text-muted-foreground">
        {auditLogLabels.empty.noResults}
      </div>
    )
  }

  return (
    <div className="overflow-x-auto rounded-md border">
      <table className="w-full border-collapse text-sm">
        <thead>
          <tr className="border-b bg-muted/50 text-left text-xs font-medium text-muted-foreground">
            <th className="px-4 py-2">{auditLogLabels.table.createdAt}</th>
            <th className="px-4 py-2">{auditLogLabels.table.eventType}</th>
            <th className="px-4 py-2">{auditLogLabels.table.subject}</th>
            <th className="px-4 py-2">{auditLogLabels.table.provider}</th>
            <th className="px-4 py-2">{auditLogLabels.table.ipAddress}</th>
            <th className="px-4 py-2">{auditLogLabels.table.metadata}</th>
          </tr>
        </thead>
        <tbody>
          {entries.map((entry) => (
            <AuditLogRow key={entry.id} entry={entry} />
          ))}
        </tbody>
      </table>
    </div>
  )
}
