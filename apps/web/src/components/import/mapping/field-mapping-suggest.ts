// 필드 매핑 초기 추천 휴리스틱 — 소스 헤더를 대상 필드 카탈로그와 매칭하는 순수 함수
// (search-export-import BC 프론트 전용 best-effort — 백엔드는 필드 매핑 추천을 제공하지 않음, spec G3)

/** 필드 매핑 대상 카탈로그 항목 (analyze 응답 targetFields와 1:1) */
export interface TargetFieldCatalogEntry {
  /** 대상 필드 키 */
  key: string
  /** 대상 필드 한국어 라벨 */
  label: string
  /** 필수 여부 */
  required: boolean
  /** 다중 값 허용 여부 */
  multi: boolean
}

/** 매칭되는 대상 필드가 없을 때 사용하는 센티널 값 */
export const FIELD_MAPPING_IGNORE = 'IGNORE'

/** 매칭 비교를 위해 문자열을 정규화한다 (trim + lowercase) */
function normalize(value: string): string {
  return value.trim().toLowerCase()
}

/**
 * 소스 헤더 배열을 대상 필드 카탈로그와 매칭해 초기 필드 매핑을 추천한다.
 *
 * 프론트 전용 best-effort 휴리스틱이며 비권위적이다 — 백엔드는 필드 매핑 추천을
 * 제공하지 않는다. 정규화(trim+lowercase)한 소스 헤더가 대상 필드의 key 또는
 * label 정규화형과 일치하면 그 대상 필드의 key를, 아니면 FIELD_MAPPING_IGNORE
 * 센티널을 매핑한다.
 *
 * @param sourceFields 업로드 파일의 소스 헤더 목록
 * @param targetFields 대상 필드 카탈로그 (analyze 응답에서 전달됨)
 * @returns 소스 헤더 → 대상 필드 key(또는 IGNORE) 맵
 */
export function suggestFieldMappings(
  sourceFields: string[],
  targetFields: TargetFieldCatalogEntry[],
): Record<string, string> {
  const result: Record<string, string> = {}

  for (const sourceField of sourceFields) {
    const normalizedSource = normalize(sourceField)
    const matched = targetFields.find(
      (target) => normalize(target.key) === normalizedSource || normalize(target.label) === normalizedSource,
    )
    result[sourceField] = matched?.key ?? FIELD_MAPPING_IGNORE
  }

  return result
}
