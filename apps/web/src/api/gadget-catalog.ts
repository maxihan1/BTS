// 가젯 카탈로그 조회 API + Zod 스키마 + 클라측 config 검증
//
// 계약 drift 방지.
//   정본 파일: backend/modules/notification/src/main/kotlin/com/bts/notification/dashboard/domain/GadgetType.kt
//   DTO 파일:  backend/modules/notification/src/main/kotlin/com/bts/notification/dashboard/web/dto/GadgetCatalogDtos.kt
//   @JsonInclude(NON_NULL) → 선택 필드 누락 허용 → Zod .optional() 필수.
//   itemSchema 재귀(link_list ARRAY) → z.lazy() 사용 (noUncheckedIndexedAccess 호환).

import { z } from 'zod'
import { apiGet } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// FieldType 상수 — GadgetType.kt enum FieldType 1:1 미러
// ─────────────────────────────────────────────────────────────────────────────

/** 백엔드 FieldType enum 값 목록. URL은 http/https 스킴 검증 포함. */
export const FIELD_TYPES = ['STRING', 'INT', 'UUID', 'ENUM', 'ARRAY', 'URL'] as const

/** FieldType union 타입 */
export type FieldType = (typeof FIELD_TYPES)[number]

// ─────────────────────────────────────────────────────────────────────────────
// ConfigField — 재귀 타입이므로 interface 선언 후 z.ZodType<T>로 스키마 주석
// (Zod z.lazy() 재귀 시 z.infer 직접 사용 불가 — interface + z.ZodType<T> 패턴)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * config 필드 디스크립터 인터페이스.
 *
 * 백엔드 ConfigFieldDto 1:1 미러.
 * itemSchema가 자기 참조(ARRAY 항목 스키마)이므로 재귀 interface로 정의한다.
 * @JsonInclude(NON_NULL) 정책 반영 — 선택 필드는 undefined 가능.
 */
export interface ConfigField {
  /** 필드 키 */
  key: string
  /** 필드 타입 (STRING | INT | UUID | ENUM | ARRAY | URL) */
  type: string
  /** 필수 여부 */
  required: boolean
  /** 최소 길이 (STRING) */
  minLength?: number
  /** 최대 길이 (STRING | URL) */
  maxLength?: number
  /** 최솟값 (INT) */
  min?: number
  /** 최댓값 (INT) */
  max?: number
  /** 허용 값 목록 (ENUM) */
  enumValues?: string[]
  /** 배열 항목 스키마 (ARRAY, 재귀) */
  itemSchema?: ConfigField[]
  /** 최소 항목 수 (ARRAY) */
  minItems?: number
  /** 최대 항목 수 (ARRAY) */
  maxItems?: number
}

/**
 * config 필드 Zod 스키마.
 *
 * z.lazy()로 itemSchema 재귀를 구현한다.
 * 타입 애노테이션 z.ZodType<ConfigField>가 없으면 TypeScript가 재귀 추론에 실패한다.
 */
export const configFieldSchema: z.ZodType<ConfigField> = z.lazy(() =>
  z.object({
    key: z.string(),
    type: z.string(),
    required: z.boolean(),
    minLength: z.number().int().optional(),
    maxLength: z.number().int().optional(),
    min: z.number().int().optional(),
    max: z.number().int().optional(),
    enumValues: z.array(z.string()).optional(),
    itemSchema: z.array(configFieldSchema).optional(),
    minItems: z.number().int().optional(),
    maxItems: z.number().int().optional(),
  }),
)

// ─────────────────────────────────────────────────────────────────────────────
// GadgetCatalogEntry — 카탈로그 단건 엔트리
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 가젯 카탈로그 단건 Zod 스키마.
 *
 * 백엔드 GadgetCatalogEntryDto 1:1 미러.
 * requireAtLeastOne 기본값 [] — 백엔드 emptyList() 대응.
 * category는 백엔드 GadgetCategory.name 문자열.
 */
export const gadgetCatalogEntrySchema = z.object({
  /** 가젯 직렬화 키 (소문자 snake_case) */
  type: z.string(),
  /** 가젯 대분류 (ISSUE | STATIC | CHART | ACTIVITY) */
  category: z.enum(['ISSUE', 'STATIC', 'CHART', 'ACTIVITY']),
  /** 표시용 레이블 */
  label: z.string(),
  /** MVP 활성 여부 — false는 미래 기능 */
  enabled: z.boolean(),
  /** config 필드 디스크립터 목록 */
  configFields: z.array(configFieldSchema),
  /** 교차필드 "적어도 하나 필수" 그룹 목록 */
  requireAtLeastOne: z.array(z.array(z.string())).default([]),
})

/** GadgetCatalogEntry 타입 — z.infer 자동 추론 */
export type GadgetCatalogEntry = z.infer<typeof gadgetCatalogEntrySchema>

/** 카탈로그 전체 응답 래퍼 스키마 — { data: { gadgets: [...] } } */
const gadgetCatalogResponseSchema = z.object({
  data: z.object({
    gadgets: z.array(gadgetCatalogEntrySchema),
  }),
})

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 가젯 카탈로그를 조회한다.
 *
 * `GET /api/v1/dashboards/gadget-catalog`
 * 읽기 전용이므로 CSRF 헤더 불요 — apiGet 사용 (dashboards.ts 관례).
 *
 * @returns 카탈로그 엔트리 배열 (12종, enabled 플래그 포함)
 * @throws ApiError(401) 미인증
 * @throws ZodError 응답 스키마 불일치
 */
export async function fetchGadgetCatalog(): Promise<GadgetCatalogEntry[]> {
  const res = await apiGet('/api/v1/dashboards/gadget-catalog', gadgetCatalogResponseSchema)
  return res.data.gadgets
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * enabled=true 가젯만 필터링해 반환한다.
 *
 * 카탈로그 모달에서 추가 가능한 가젯 목록 결정에 사용한다.
 * enabled 플래그는 백엔드 단일 진실원천 — 하드코딩 금지 (FR-7).
 *
 * @param entries 전체 카탈로그 엔트리 배열
 * @returns enabled=true 항목만 필터링된 배열
 */
export function enabledGadgets(entries: GadgetCatalogEntry[]): GadgetCatalogEntry[] {
  return entries.filter((e) => e.enabled)
}

// ─────────────────────────────────────────────────────────────────────────────
// validateGadgetConfig — 클라측 동적 config 검증
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 가젯 config를 카탈로그 configFields 기반으로 동적 검증한다.
 *
 * 백엔드 GadgetType.validateConfig 로직 1:1 미러 (단일 출처 = 카탈로그 configFields).
 * 정적 Zod 스키마를 가젯별로 중복 작성하지 않는다 — configFields가 유일한 진실원천.
 *
 * 검증 항목.
 * - EC5: required 필드 누락
 * - EC8: STRING/URL maxLength 초과 / STRING minLength 미달
 * - EC7: URL 스킴 (http/https만 허용)
 * - EC3: requireAtLeastOne 위반
 * - INT min/max 범위
 * - ENUM 허용 값 목록
 * - ARRAY minItems/maxItems + itemSchema 재귀 검증
 *
 * @param entry 카탈로그 엔트리 (configFields + requireAtLeastOne 포함)
 * @param config 검증할 config 객체
 * @returns 위반 메시지 배열 (빈 배열 = 유효)
 */
export function validateGadgetConfig(
  entry: GadgetCatalogEntry,
  config: Record<string, unknown>,
): string[] {
  const errors: string[] = []

  // per-field 검증
  for (const field of entry.configFields) {
    const value: unknown = config[field.key]
    if (value === undefined || value === null) {
      if (field.required) {
        errors.push(`필드 '${field.key}'는 필수입니다.`)
      }
      continue
    }
    validateFieldValue(field, value, errors)
  }

  // 교차필드 규칙 검증 (requireAtLeastOne)
  for (const group of entry.requireAtLeastOne) {
    const hasAny = group.some((k) => {
      const v: unknown = config[k]
      return v !== undefined && v !== null
    })
    if (!hasAny) {
      errors.push(`[${group.join(', ')}] 중 적어도 하나는 입력해야 합니다.`)
    }
  }

  return errors
}

/**
 * 단일 필드 값을 필드 타입에 따라 검증한다.
 *
 * @param field 필드 디스크립터
 * @param value 검증할 값 (undefined/null이 아님을 호출자가 보장)
 * @param errors 에러 메시지 누적 배열 (in-place 추가)
 */
function validateFieldValue(field: ConfigField, value: unknown, errors: string[]): void {
  switch (field.type) {
    case 'STRING':
      validateString(field, value, errors)
      break
    case 'INT':
      validateInt(field, value, errors)
      break
    case 'UUID':
      validateUuid(field, value, errors)
      break
    case 'ENUM':
      validateEnum(field, value, errors)
      break
    case 'ARRAY':
      validateArray(field, value, errors)
      break
    case 'URL':
      validateUrl(field, value, errors)
      break
    default:
      // 미지원 타입은 무시 (forward-compat)
      break
  }
}

/**
 * STRING 필드를 검증한다 (minLength / maxLength).
 *
 * @param field 필드 디스크립터
 * @param value 검증할 값
 * @param errors 에러 메시지 누적 배열
 */
function validateString(field: ConfigField, value: unknown, errors: string[]): void {
  if (typeof value !== 'string') {
    errors.push(`필드 '${field.key}'는 문자열이어야 합니다.`)
    return
  }
  if (field.minLength !== undefined && value.length < field.minLength) {
    errors.push(`필드 '${field.key}'는 최소 ${field.minLength}자 이상이어야 합니다.`)
  }
  if (field.maxLength !== undefined && value.length > field.maxLength) {
    errors.push(`필드 '${field.key}'는 최대 ${field.maxLength}자 이하여야 합니다.`)
  }
}

/**
 * INT 필드를 검증한다 (정수 여부 / min / max).
 *
 * @param field 필드 디스크립터
 * @param value 검증할 값
 * @param errors 에러 메시지 누적 배열
 */
function validateInt(field: ConfigField, value: unknown, errors: string[]): void {
  if (typeof value !== 'number' || !Number.isInteger(value)) {
    errors.push(`필드 '${field.key}'는 정수여야 합니다.`)
    return
  }
  if (field.min !== undefined && value < field.min) {
    errors.push(`필드 '${field.key}'는 ${field.min} 이상이어야 합니다.`)
  }
  if (field.max !== undefined && value > field.max) {
    errors.push(`필드 '${field.key}'는 ${field.max} 이하여야 합니다.`)
  }
}

/**
 * UUID 필드를 검증한다 (RFC4122 형식).
 *
 * @param field 필드 디스크립터
 * @param value 검증할 값
 * @param errors 에러 메시지 누적 배열
 */
function validateUuid(field: ConfigField, value: unknown, errors: string[]): void {
  if (typeof value !== 'string') {
    errors.push(`필드 '${field.key}'는 문자열(UUID)이어야 합니다.`)
    return
  }
  const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
  if (!UUID_RE.test(value)) {
    errors.push(`필드 '${field.key}'는 유효한 UUID 형식이어야 합니다.`)
  }
}

/**
 * ENUM 필드를 검증한다 (허용 값 목록).
 *
 * @param field 필드 디스크립터
 * @param value 검증할 값
 * @param errors 에러 메시지 누적 배열
 */
function validateEnum(field: ConfigField, value: unknown, errors: string[]): void {
  if (typeof value !== 'string') {
    errors.push(`필드 '${field.key}'는 문자열이어야 합니다.`)
    return
  }
  if (field.enumValues !== undefined && !field.enumValues.includes(value)) {
    errors.push(
      `필드 '${field.key}'의 값 '${value}'은 허용되지 않습니다. 허용 값: ${field.enumValues.join(', ')}`,
    )
  }
}

/**
 * ARRAY 필드를 검증한다 (minItems / maxItems / itemSchema 재귀).
 *
 * @param field 필드 디스크립터
 * @param value 검증할 값
 * @param errors 에러 메시지 누적 배열
 */
function validateArray(field: ConfigField, value: unknown, errors: string[]): void {
  if (!Array.isArray(value)) {
    errors.push(`필드 '${field.key}'는 배열이어야 합니다.`)
    return
  }
  if (field.minItems !== undefined && value.length < field.minItems) {
    errors.push(`필드 '${field.key}'는 최소 ${field.minItems}개 이상의 항목이 필요합니다.`)
  }
  if (field.maxItems !== undefined && value.length > field.maxItems) {
    errors.push(`필드 '${field.key}'는 최대 ${field.maxItems}개 이하의 항목을 가져야 합니다.`)
  }
  if (field.itemSchema !== undefined) {
    for (const item of value) {
      if (typeof item !== 'object' || item === null || Array.isArray(item)) {
        errors.push(`배열 '${field.key}'의 항목은 객체여야 합니다.`)
        continue
      }
      const itemObj = item as Record<string, unknown>
      for (const itemField of field.itemSchema) {
        const itemValue: unknown = itemObj[itemField.key]
        if (itemValue === undefined || itemValue === null) {
          if (itemField.required) {
            errors.push(`배열 항목의 필수 필드 '${itemField.key}'가 누락되었습니다.`)
          }
        } else {
          validateFieldValue(itemField, itemValue, errors)
        }
      }
    }
  }
}

/**
 * URL 필드를 검증한다 (http/https 스킴 강제 / maxLength).
 *
 * @param field 필드 디스크립터
 * @param value 검증할 값
 * @param errors 에러 메시지 누적 배열
 */
function validateUrl(field: ConfigField, value: unknown, errors: string[]): void {
  if (typeof value !== 'string') {
    errors.push(`필드 '${field.key}'는 문자열(URL)이어야 합니다.`)
    return
  }
  if (field.maxLength !== undefined && value.length > field.maxLength) {
    errors.push(`필드 '${field.key}'는 최대 ${field.maxLength}자 이하여야 합니다.`)
  }
  const lower = value.toLowerCase()
  if (!lower.startsWith('http://') && !lower.startsWith('https://')) {
    errors.push(`URL은 http 또는 https 스킴이어야 합니다. 현재 값: ${value}`)
  }
}
