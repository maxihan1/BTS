// 사용자 프로필 설정 화면(/settings/profile) i18n 라벨 + 에러코드 → 한글 메시지 매핑
import type { ApiError } from '@/api/client'

/**
 * `/settings/profile` 화면이 노출하는 한국어 라벨/문구.
 *
 * 그룹 — page / status / form / avatar
 *
 * 주의: 모든 값은 콜론으로 끝나지 않는다 (글로벌 §5).
 */
export const profileLabels = {
  /** 페이지 헤더 영역 */
  page: {
    /** 페이지 h1 heading */
    heading: '프로필',
    /** 페이지 설명 문구 */
    description: '표시 이름·시간대·부서를 편집하고 아바타를 관리하세요.',
  },

  /** 조회/저장 상태 메시지 */
  status: {
    /** 프로필 조회 로딩 텍스트 */
    loading: '프로필을 불러오는 중입니다',
    /** 프로필 조회 실패 텍스트(role=alert) */
    loadError: '프로필을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.',
    /** 저장 성공 인라인 메시지(role=status) */
    saveSuccess: '프로필이 저장되었습니다',
  },

  /** 편집 폼 필드 라벨/버튼 */
  form: {
    /** 아이디(읽기 전용) 라벨 */
    usernameLabel: '아이디',
    /** 이메일(읽기 전용) 라벨 */
    emailLabel: '이메일',
    /** 표시 이름(편집) 라벨 */
    displayNameLabel: '표시 이름',
    /** 시간대(편집, native select) 라벨 */
    timezoneLabel: '시간대',
    /** 부서(편집) 라벨 */
    departmentLabel: '부서',
    /** 저장 버튼 텍스트(평상시) */
    saveButton: '저장',
    /** 저장 버튼 텍스트(제출 중, isPending) */
    savingButton: '저장 중...',
  },

  /** 아바타 섹션 라벨/버튼 */
  avatar: {
    /** 파일 input 접근 가능한 라벨 */
    fileInputLabel: '아바타 이미지 선택',
    /** 아바타 삭제 버튼 텍스트(평상시) */
    deleteButton: '아바타 삭제',
    /** 아바타 삭제 버튼 텍스트(제출 중, isPending) */
    deletingButton: '삭제 중...',
  },
} as const

/** profileLabels const 추론 타입 */
export type ProfileLabels = typeof profileLabels

// ─────────────────────────────────────────────────────────────────────────────
// 에러 매핑 — 백엔드 `{code, message}` 봉투(ChangePasswordForm과 동일 BC 관례, message 무시)
// ─────────────────────────────────────────────────────────────────────────────

/** 알 수 없는/미분류 에러 폴백 메시지 */
const PROFILE_FALLBACK_ERROR = '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'

/** 401(미인증/세션 만료) 공통 메시지 */
const UNAUTHENTICATED_ERROR = '로그인이 만료되었습니다. 다시 로그인해 주세요.'

/**
 * `ApiError.body`에서 대문자 스네이크 에러 코드를 추출한다.
 *
 * 프로필/아바타 에러 봉투는 `{code, message}` 형태(`UserProfileController.errorResponse`) —
 * 계정 연결의 `{error}` 형태와는 다른 관례이므로 공유 `extractErrorCode`(lib) 대신
 * ChangePasswordForm과 동일한 로컬 파싱을 사용한다(frontend-api-convention-per-bc).
 * `mapProfileError`/`mapAvatarError`가 공통으로 사용한다.
 */
function extractProfileErrorCode(body: unknown): string | undefined {
  if (body === null || typeof body !== 'object') return undefined
  const code = (body as { code?: unknown }).code
  return typeof code === 'string' ? code : undefined
}

/**
 * 프로필 조회/PATCH 에러를 한글 메시지로 변환한다.
 *
 * 백엔드 `message` 필드는 무시하고 `code`(대문자 스네이크)만 신뢰한다
 * (ChangePasswordForm 선례 — 소문자 비교 금지, PR #41 BLOCKER 재발 방지).
 * 401은 code와 무관하게 세션 만료로 취급한다.
 *
 * @param error PATCH `/api/v1/users/me/profile` 등에서 던져진 {@link ApiError}
 * @returns 표시할 한글 에러 메시지
 */
export function mapProfileError(error: ApiError): string {
  if (error.status === 401) return UNAUTHENTICATED_ERROR

  switch (extractProfileErrorCode(error.body)) {
    case 'PROFILE_VALIDATION_FAILED':
      return '표시 이름을 입력해 주세요.'
    case 'PROFILE_NOT_FOUND':
      return '프로필을 찾을 수 없습니다.'
    default:
      return PROFILE_FALLBACK_ERROR
  }
}

/**
 * 아바타 업로드/삭제 에러를 한글 메시지로 변환한다.
 *
 * 백엔드는 MIME 위반과 5MB 초과 모두 동일 코드(`AVATAR_VALIDATION_FAILED`)로 응답하므로
 * (message만 다르고 code 기준 매핑 원칙상 message는 무시) 두 위반 계열을 함께 안내하는
 * 메시지 한 종류로 대응한다(스펙 S7).
 *
 * @param error 아바타 업로드/삭제 API에서 던져진 {@link ApiError}
 * @returns 표시할 한글 에러 메시지
 */
export function mapAvatarError(error: ApiError): string {
  if (error.status === 401) return UNAUTHENTICATED_ERROR

  switch (extractProfileErrorCode(error.body)) {
    case 'AVATAR_VALIDATION_FAILED':
      return '아바타 파일이 너무 크거나 지원하지 않는 형식입니다. 5MB 이하의 JPEG·PNG·GIF·WebP 이미지를 선택해 주세요.'
    case 'AVATAR_NOT_FOUND':
      return '아바타를 찾을 수 없습니다.'
    default:
      return PROFILE_FALLBACK_ERROR
  }
}
