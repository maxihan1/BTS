// 첨부파일 미리보기 가능 MIME 타입 화이트리스트 헬퍼

/** 미리보기 카테고리 — 렌더러 선택의 기준이 된다. */
export type PreviewCategory = 'image' | 'pdf' | 'video'

/**
 * 미리보기 가능한 MIME 타입과 해당 카테고리의 단일 출처 맵.
 *
 * 이 맵에 없는 MIME 타입은 미리보기를 지원하지 않는다.
 * SVG·HTML 등 XSS 위험이 있는 타입은 의도적으로 제외한다.
 */
const PREVIEWABLE_MIME = {
  'image/png': 'image',
  'image/jpeg': 'image',
  'image/gif': 'image',
  'image/webp': 'image',
  'application/pdf': 'pdf',
  'video/mp4': 'video',
  'video/webm': 'video',
} as const satisfies Record<string, PreviewCategory>

/**
 * MIME 타입을 essence 형태로 정규화한다.
 *
 * 서버는 업로드 시 Content-Type를 검증·정규화 없이 저장하므로
 * `image/png; charset=binary`(파라미터)·`IMAGE/PNG`(대문자) 같은 변종이 올 수 있다.
 * 화이트리스트는 소문자·파라미터 없는 essence(`type/subtype`)로만 비교한다.
 *
 * @param contentType 원본 MIME 타입 문자열
 * @returns 파라미터를 제거하고 소문자로 정규화한 essence MIME
 */
function normalizeMime(contentType: string): string {
  return (contentType.split(';')[0] ?? '').trim().toLowerCase()
}

/**
 * 주어진 MIME 타입이 미리보기 가능한지 판별한다.
 *
 * @param contentType 확인할 MIME 타입 (예: `"image/png"`)
 * @returns 화이트리스트에 포함된 경우 `true`, 그 외 `false`
 */
export function isPreviewable(contentType: string): boolean {
  return Object.prototype.hasOwnProperty.call(PREVIEWABLE_MIME, normalizeMime(contentType))
}

/**
 * 주어진 MIME 타입의 미리보기 카테고리를 반환한다.
 *
 * @param contentType 확인할 MIME 타입 (예: `"video/mp4"`)
 * @returns 해당 카테고리(`'image'` | `'pdf'` | `'video'`), 화이트리스트 외 타입은 `null`
 */
export function previewCategory(contentType: string): PreviewCategory | null {
  const mime = normalizeMime(contentType)
  if (!Object.prototype.hasOwnProperty.call(PREVIEWABLE_MIME, mime)) {
    return null
  }
  return PREVIEWABLE_MIME[mime as keyof typeof PREVIEWABLE_MIME]
}
