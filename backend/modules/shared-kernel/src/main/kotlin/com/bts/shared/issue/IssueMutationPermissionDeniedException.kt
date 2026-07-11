// IssueMutationPort 권한 거부를 타입으로 표현하는 예외 — 소비자가 클래스명 문자열 매칭 없이 분류 (FR-AT-02 C3)
package com.bts.shared.issue

/**
 * [IssueMutationPort] 가 위임한 이슈 변경이 actor 권한 부족으로 거부됐음을 나타내는 타입 있는 예외.
 *
 * ### 배경 — 클래스명 문자열 휴리스틱 제거 (FR-AT-02 코드리뷰 C3)
 *
 * 이전에는 소비자(automation `ActionExecutor`)가 포트가 던진 `RuntimeException` 의 클래스
 * **이름**에 `"AccessDenied"`/`"Forbidden"` 같은 관례 토큰이 들어있는지 문자열로 매칭해 권한
 * 거부를 판별했다. 이는 BC 전반의 명명 관례에 암묵 결합하는 취약한 방식이라(권한과 무관한
 * 예외가 우연히 같은 토큰을 포함하면 오분류), 포트 계약에 타입 있는 신호를 도입해 대체한다.
 * issue-tracking prod 어댑터([IssueMutationPort] 구현체)가 자신의 도메인 권한 예외를 이 타입으로
 * 번역해 던지고, 소비자는 `is IssueMutationPermissionDeniedException` 으로 분류한다(문자열 매칭 없음).
 *
 * 권한 거부가 아닌 그 외 실패(이슈 부재·OCC 버전 충돌 등)는 이 타입이 아닌 일반 예외로 전파되며,
 * 소비자는 이를 일반 실패로 처리한다. 이 예외는 로그/집계용 분류 신호일 뿐, 실제 권한 강제는 위임
 * 대상(issue-tracking)이 이미 수행했다.
 *
 * @param message 원인 설명. 어댑터가 원 도메인 예외 메시지를 전달한다.
 * @param cause 원 도메인 예외(issue-tracking `IssueAccessDeniedException` 등). 진단용.
 * @see IssueMutationPort
 */
class IssueMutationPermissionDeniedException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
