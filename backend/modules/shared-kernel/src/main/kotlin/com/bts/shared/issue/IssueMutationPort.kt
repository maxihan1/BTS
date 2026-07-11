// 자동화 액션의 이슈 변경 위임 cross-BC 포트 — automation → issue-tracking (FR-AT-02)
package com.bts.shared.issue

/**
 * 자동화 액션(필드 변경/담당자 배정/댓글 추가) cross-BC 위임 포트 — automation BC 용 (FR-AT-02 Task 2).
 *
 * automation BC 가 자동화 룰([com.bts.automation] 도메인, 이 모듈 밖에 위치)에서 정의된 액션을
 * 실행할 때, 이슈 상태를 실제로 바꾸기 위해 issue-tracking 기존 도메인 로직에 위임하는 포트다.
 * 필드 검증·권한 강제·OCC(낙관적 동시성 제어)·이벤트 발행은 모두 issue-tracking 이 담당한다.
 *
 * ### fail-closed — default 구현 없음
 *
 * 권한을 강제하는 쓰기 경로이므로 adapter 부재 시 부팅 자체가 실패해야 한다.
 * 빈 default 구현을 허용하면 adapter 미결선 상태에서 자동화 액션이 silent-drop 될 위험이
 * 있다 — 규칙 사용자는 "액션이 실행됐다"고 오인하지만 실제로는 아무 일도 일어나지 않는
 * 사고로 이어진다. [com.bts.shared.board.IssueTransitionPort] 의 fail-closed 패턴과 동일하다.
 *
 * ### actor 는 호출자(automation 룰 실행기)가 커맨드로 채워 전달
 *
 * 각 커맨드의 `actorUserId` 는 자동화 룰의 actor(룰 소유자 또는 지정된 실행 주체)다.
 * adapter 는 SecurityContext 를 직접 읽지 않고 이 값을 신뢰한다 — 자동화 액션은 pgmq
 * 워커(별도 스레드/프로세스)에서 비동기 실행되므로 SecurityContext 자체가 존재하지 않을 수
 * 있다(async 안전). 커맨드를 채우는 유일한 곳은 automation 실행기이며, adapter 는 이를
 * 그대로 issue-tracking 권한 검증 경로(예: `IssueApplicationService`)에 전달한다.
 *
 * ### 실패 전달 — 예외
 *
 * 세 메서드 모두 성공 시에만 [MutationResult] 를 반환하고, 권한 거부·이슈 부재·OCC 버전
 * 충돌 등 실패는 예외로 던진다. [com.bts.shared.board.IssueTransitionPort.transition] 과 동일한
 * 계약이다 — consumer(automation `ActionExecutor`)가 catch 후 실행 결과(성공/부분실패)로 매핑한다.
 *
 * 이 중 **권한 거부**는 [IssueMutationPermissionDeniedException] 타입으로 던져, consumer 가 예외
 * 클래스명 문자열 매칭 없이 타입으로 분류할 수 있게 한다(FR-AT-02 C3). 그 외 실패(이슈 부재·OCC
 * 충돌 등)는 일반 예외로 전파되며 consumer 는 이를 일반 실패로 처리한다.
 *
 * ### value 타입 — String(JSON 인코딩), Jackson 비의존
 *
 * [SetFieldCommand.value] 는 `com.fasterxml.jackson.databind.JsonNode` 가 아니라 `String?`
 * (JSON 인코딩 문자열)이다. shared-kernel 은 순수 계약 모듈로 Jackson 같은 특정 라이브러리
 * 타입을 포트 계약에 노출하지 않는다([SetFieldCommand] KDoc 참조, `actorUserId: UUID` 가
 * BC 공통 분모인 것과 동형인 설계 원칙). 소비 어댑터(issue-tracking, Task 7)가 이미 보유한
 * 자신의 ObjectMapper 로 파싱해 내부 DTO 로 매핑할 책임을 진다.
 *
 * ### BC 격리 사유 — shared-kernel 배치
 *
 * automation 과 issue-tracking 이 shared-kernel 만 공유 의존한다. automation 이
 * issue-tracking 내부를 직접 import 하면 BC 경계가 무너지고 순환 의존 위험이 생긴다.
 * 이 포트를 shared-kernel 에 배치함으로써 두 BC 는 서로를 gradle 수준에서 의존하지 않는다
 * (BC 격리 룰, ArchUnit 강제).
 *
 * ### 의존 방향
 * ```
 * automation ──(port)──▶ shared-kernel ◀──(impl)── issue-tracking
 * ```
 *
 * @see SetFieldCommand
 * @see AssignCommand
 * @see AddCommentCommand
 * @see MutationResult
 */
interface IssueMutationPort {
    /**
     * 이슈 필드 하나를 변경한다.
     *
     * [SetFieldCommand.dryRun] 이 true 이면 권한/검증만 수행하고 실제 커밋·이벤트 발행을
     * 하지 않아야 한다(구현체 책임). actor 는 [SetFieldCommand.actorUserId] 로 전달받으며,
     * adapter 는 이를 신뢰한다(호출자가 채운 값 — 위조 차단은 automation 실행기 책임).
     *
     * @param cmd 필드 변경 커맨드. actor·이슈 키·필드명·새 값(JSON 문자열)·dryRun 포함.
     * @return 변경 결과. dryRun 이면 `applied=false`, `version=null`.
     * @throws RuntimeException (issue-tracking BC 내부 예외) 권한 거부·이슈 부재·필드 검증
     *   실패 등. consumer(automation `ActionExecutor`)가 catch 후 실행 결과로 매핑한다.
     */
    fun setField(cmd: SetFieldCommand): MutationResult

    /**
     * 이슈 담당자를 배정하거나 해제한다.
     *
     * [AssignCommand.assigneeId] 가 null 이면 담당자 해제를 의미한다.
     * [AssignCommand.dryRun] 이 true 이면 권한/검증만 수행하고 실제 커밋·이벤트 발행을
     * 하지 않아야 한다(구현체 책임).
     *
     * @param cmd 담당자 배정 커맨드. actor·이슈 키·담당자 UUID(nullable)·dryRun 포함.
     * @return 변경 결과. dryRun 이면 `applied=false`, `version=null`.
     * @throws RuntimeException (issue-tracking BC 내부 예외) 권한 거부·이슈 부재·담당자
     *   대상자 부재 등. consumer 가 catch 후 실행 결과로 매핑한다.
     */
    fun assign(cmd: AssignCommand): MutationResult

    /**
     * 이슈에 댓글을 추가한다.
     *
     * [AddCommentCommand.body] 는 치환(템플릿 렌더링)이 완료된 최종 텍스트다 — 이 포트는
     * 템플릿 문법을 해석하지 않는다(automation `TemplateRenderer` 책임). 작성자는
     * [AddCommentCommand.actorUserId] 다. [AddCommentCommand.dryRun] 이 true 이면 권한/검증만
     * 수행하고 실제 커밋·이벤트 발행을 하지 않아야 한다(구현체 책임).
     *
     * @param cmd 댓글 추가 커맨드. actor·이슈 키·본문·dryRun 포함.
     * @return 변경 결과. 댓글은 OCC 버전 대상이 아니므로 `version` 은 항상 null 이다.
     * @throws RuntimeException (issue-tracking BC 내부 예외) 권한 거부·이슈 부재 등.
     *   consumer 가 catch 후 실행 결과로 매핑한다.
     */
    fun addComment(cmd: AddCommentCommand): MutationResult
}
