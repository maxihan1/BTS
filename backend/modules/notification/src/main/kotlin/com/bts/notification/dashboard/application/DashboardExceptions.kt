// 대시보드 BC 애플리케이션 레이어 전용 예외 3종 — 동명 예외 오import 방지를 위해 Dashboard 접두사 사용

package com.bts.notification.dashboard.application

import java.util.UUID

/**
 * 요청한 대시보드 ID 가 존재하지 않거나 접근 권한이 없을 때 발생하는 예외.
 *
 * HTTP 404 로 매핑된다. 존재 숨김(Existence Probe 방지) 정책에 따라
 * 접근 불가 리소스도 404 를 반환한다 (memory: auth-extraction-before-resource-lookup 교훈).
 *
 * @param id 조회하려 했던 대시보드 식별자
 */
class DashboardNotFoundException(val id: UUID) : RuntimeException("대시보드를 찾을 수 없습니다. id=$id")

/**
 * 소유자가 아닌 사용자가 대시보드를 수정·삭제 시도할 때 발생하는 예외.
 *
 * HTTP 403 으로 매핑된다. message 에 actorId 나 ownerId 를 포함하지 않는다
 * (memory: fr-pm-04-guard-exception-message-http-leak 교훈).
 */
class DashboardForbiddenException : RuntimeException("해당 대시보드를 수정·삭제할 권한이 없습니다.")

/**
 * OCC(낙관적 잠금) version 불일치로 동시 수정 충돌이 발생했을 때 던지는 예외.
 *
 * HTTP 409 로 매핑된다. repository.update rowcount = 0 이면 Service 가 이 예외를 발생시킨다.
 *
 * @param id 충돌이 발생한 대시보드 식별자
 */
class DashboardConflictException(val id: UUID) :
    RuntimeException("대시보드가 다른 사용자에 의해 이미 수정되었습니다. 최신 버전으로 다시 시도하세요. id=$id")

/**
 * 대시보드당 공유 토큰 개수 상한(DashboardShareToken.MAX_SHARE_TOKENS)을 초과해
 * 신규 발급이 거부됐을 때 던지는 예외.
 *
 * HTTP 400 으로 매핑된다. message 에 현재 개수·상한 등 내부 상태를 포함하지 않는다
 * (memory: fr-pm-04-guard-exception-message-http-leak 교훈).
 */
class ShareTokenLimitExceededException :
    RuntimeException("발급 가능한 공유 링크 개수 상한을 초과했습니다.")

/**
 * 취소하려는 공유 토큰이 존재하지 않거나 해당 대시보드 소유 토큰이 아닐 때 던지는 예외.
 *
 * HTTP 404 로 매핑된다. deleteById rowcount = 0(미존재 또는 dashboardId 스코프 불일치)이면
 * Service 가 이 예외를 발생시킨다. message 에 shareId 등 내부 식별자를 포함하지 않는다.
 */
class ShareTokenNotFoundException :
    RuntimeException("공유 링크를 찾을 수 없습니다.")

/**
 * 익명(비로그인) 공개 조회에서 공유 토큰이 유효하지 않을 때 던지는 예외.
 *
 * HTTP 404 로 매핑된다. 미존재·만료·부모 대시보드 삭제를 구분하지 않고 모두 404 로 수렴시켜
 * 토큰 존재 여부·만료 여부가 응답으로 새어나가지 않도록 한다(존재 숨김 정책).
 * message 에 토큰 원문·해시·내부 상태를 절대 포함하지 않는다.
 */
class PublicDashboardNotFoundException :
    RuntimeException("공유된 대시보드를 찾을 수 없습니다.")
