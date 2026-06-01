// 프로젝트 멤버 API 에러코드를 한국어 메시지로 변환하는 유틸리티
import { toast } from 'sonner'
import { ProjectMemberApiError } from '@/api/project-members'

// ─────────────────────────────────────────────────────────────────────────────
// 에러코드 → 한국어 메시지 매핑
// ─────────────────────────────────────────────────────────────────────────────

/** 에러코드별 한국어 메시지 상수 맵 */
const ERROR_MESSAGES: Readonly<Record<string, string>> = {
  not_project_admin: '프로젝트 관리자만 멤버를 변경할 수 있습니다',
  last_admin_protected: '마지막 관리자는 제거하거나 강등할 수 없습니다',
  membership_already_exists: '이미 멤버입니다',
  user_not_found: '사용자를 찾을 수 없습니다',
  member_not_found: '이미 제거된 멤버입니다',
  project_not_found: '프로젝트를 찾을 수 없습니다',
  invalid_role: '역할 값이 올바르지 않습니다',
  unauthorized: '로그인이 필요합니다',
}

/** 폴백 메시지 — 매핑에 없는 에러코드에 사용한다 */
const FALLBACK_MESSAGE = '멤버 작업 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.'

/**
 * 에러코드를 한국어 메시지로 변환한다.
 *
 * @param errorCode backend에서 내려온 snake_case 에러코드
 * @returns 사용자에게 노출할 한국어 메시지. 매핑 없으면 폴백 반환.
 */
export function getProjectMemberErrorMessage(errorCode: string): string {
  return ERROR_MESSAGES[errorCode] ?? FALLBACK_MESSAGE
}

/**
 * 알 수 없는 에러에 대한 폴백 메시지를 반환한다.
 *
 * @returns 일반 오류 메시지 문자열
 */
export function getFallbackErrorMessage(): string {
  return FALLBACK_MESSAGE
}

/**
 * 에러를 분석해 toast.error로 한국어 메시지를 표시한다.
 *
 * ProjectMemberApiError이면 errorCode 기반 메시지를,
 * 그 외 알 수 없는 에러이면 폴백 메시지를 표시한다.
 *
 * @param error mutation onError로 전달된 에러 값
 */
export function notifyMemberError(error: unknown): void {
  if (error instanceof ProjectMemberApiError) {
    toast.error(getProjectMemberErrorMessage(error.errorCode))
  } else {
    toast.error(FALLBACK_MESSAGE)
  }
}
