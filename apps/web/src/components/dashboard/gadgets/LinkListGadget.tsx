// 링크 목록 가젯 — http/https 스킴 필터링 + rel noopener noreferrer (FR-DB-02 D6/D7 Task-5)
import type { JSX } from 'react'

// ─────────────────────────────────────────────────────────────────────────────
// 타입
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 링크 목록 가젯의 단일 링크 항목.
 * config.links 배열 요소와 동일한 구조.
 */
export interface LinkItem {
  /** 링크 표시 레이블 */
  label: string
  /** 링크 URL (http/https 스킴만 허용) */
  url: string
}

/** LinkListGadget props */
export interface LinkListGadgetProps {
  /** 렌더할 링크 목록 */
  links: LinkItem[]
}

// ─────────────────────────────────────────────────────────────────────────────
// 유틸
// ─────────────────────────────────────────────────────────────────────────────

/**
 * URL이 허용된 스킴(http / https)인지 확인한다.
 * javascript: / file: / data: 등 다른 스킴은 허용하지 않아 XSS를 방지한다.
 *
 * 비교는 대소문자 무관(toLowerCase)으로 수행한다.
 * 클라측 validateGadgetConfig(validateUrl) · 백엔드 validateUrlField가 모두
 * toLowerCase 후 비교하므로 3계층 일관성을 유지한다.
 *
 * @param url 확인할 URL 문자열
 * @returns http: 또는 https:로 시작하면(대소문자 무관) true
 */
function isSafeUrl(url: string): boolean {
  const lower = url.toLowerCase()
  return lower.startsWith('http://') || lower.startsWith('https://')
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 링크 목록 가젯.
 *
 * config.links를 목록으로 렌더링한다.
 * - http / https 스킴만 허용 — javascript:, file:, data: 등 무시.
 * - 외부 링크: rel="noopener noreferrer" + target="_blank" (탭내킹 방지).
 */
export function LinkListGadget({ links }: LinkListGadgetProps): JSX.Element {
  const safeLinks = links.filter(({ url }) => isSafeUrl(url))

  return (
    <ul className="h-full overflow-y-auto divide-y divide-border">
      {safeLinks.map(({ label, url }) => (
        <li key={`${url}-${label}`} className="px-3 py-2 hover:bg-accent/40 transition-colors">
          <a
            href={url}
            target="_blank"
            rel="noopener noreferrer"
            className="text-sm text-primary hover:underline break-all"
          >
            {label}
          </a>
        </li>
      ))}
    </ul>
  )
}
