// IssueTransitionPort 전환 실패를 타입으로 구분하는 shared-kernel 예외 — 권한 거부·OCC 충돌 (FR-SL-05 PR1 Task 2)

package com.bts.shared.board

/**
 * [IssueTransitionPort.transition] 이 위임한 이슈 전환이 actor 권한 부족(TRANSITION 미충족)으로
 * 거부됐음을 나타내는 타입 있는 예외.
 *
 * ### 배경 — 클래스명 문자열 매칭 금지 (BC 격리)
 *
 * `IssueTransitionPort` 는 `transition` 실패를 generic `RuntimeException` 으로만 계약했다. issue-tracking
 * prod 어댑터([com.bts.issue.adapter.outbound.board.IssueTransitionAdapter])는 내부 도메인 권한 예외
 * (`IssueAccessDeniedException`)를 verbatim 전파했는데, 소비 BC(slack-integration 등)는 BC 격리로 그
 * 도메인 예외를 import 할 수 없어 무권한과 그 외 실패(OCC 충돌 등)를 구분하지 못했다. 어댑터가 이 타입으로
 * 번역해 던지면, 소비자는 issue-tracking 내부 클래스명을 문자열로 매칭하지 않고 `is
 * IssueTransitionPermissionDeniedException` 타입 검사만으로 권한 거부를 분류할 수 있다.
 * [com.bts.shared.issue.IssueMutationPermissionDeniedException] 과 동형 패턴(FR-AT-02 C3 선례).
 *
 * 실제 권한 강제는 위임 대상(issue-tracking)이 이미 수행했다 — 이 예외는 소비자를 위한 분류 신호일 뿐이다.
 *
 * @param message 원인 설명. 어댑터가 원 도메인 예외 메시지를 전달한다.
 * @param cause 원 도메인 예외(issue-tracking `IssueAccessDeniedException`). 진단용.
 * @see IssueTransitionPort
 */
class IssueTransitionPermissionDeniedException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * [IssueTransitionPort.transition] 이 위임한 이슈 전환이 낙관적 잠금(OCC, 여러 요청이 동시에 같은
 * 데이터를 수정하려 할 때 먼저 읽은 버전 번호로 충돌을 감지하는 방식) 버전 충돌로 실패했음을 나타내는
 * 타입 있는 예외.
 *
 * issue-tracking prod 어댑터가 내부 도메인 예외(`IssueVersionConflictException`)를 이 타입으로 번역해
 * 던진다. [IssueTransitionPermissionDeniedException] 과 마찬가지로 소비자는 클래스명 문자열 매칭 없이
 * 이 타입으로 OCC 충돌을 분류한다 — 권한 거부와 구분해 재조회 후 재시도 같은 별도 처리를 할 수 있다.
 *
 * @param message 원인 설명. 어댑터가 원 도메인 예외 메시지를 전달한다.
 * @param cause 원 도메인 예외(issue-tracking `IssueVersionConflictException`). 진단용.
 * @see IssueTransitionPort
 */
class IssueOptimisticLockException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
