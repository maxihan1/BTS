// IssueMutationPort 커맨드/결과 값 객체 — 필드 변경·담당자 배정·댓글 추가 (FR-AT-02)
package com.bts.shared.issue

import java.util.UUID

/**
 * 이슈 필드 변경 커맨드 VO.
 *
 * automation BC 의 액션 실행기가 [IssueMutationPort.setField] 호출 시 전달하는 커맨드다.
 *
 * ### value — JSON 인코딩 문자열, Jackson 비의존
 *
 * `com.fasterxml.jackson.databind.JsonNode` 대신 `String?` 을 채택했다. shared-kernel 은
 * 순수 계약 모듈로 특정 JSON 라이브러리 타입을 포트 계약에 노출하지 않는다 — [actorUserId] 가
 * `java.util.UUID`(BC 공통 분모)인 것과 동형인 설계 원칙이다. Jackson 의존을 shared-kernel 에
 * 추가하면 이 모듈을 참조하는 모든 BC 가 원치 않는 라이브러리 결합을 갖게 된다.
 * 소비 어댑터(issue-tracking, Task 7)는 이미 Jackson 을 보유하므로 자신의 ObjectMapper 로
 * [value] 를 파싱해 내부 `UpdateIssueRequest` 등으로 매핑할 책임을 진다.
 *
 * ### actorUserId — async 안전 + 위조 차단
 *
 * 자동화 액션은 pgmq 워커(별도 스레드/프로세스)에서 비동기 실행되므로 SecurityContext 가
 * 존재하지 않을 수 있다. actor 는 호출자(automation 룰 실행기)가 룰 정의에서 읽어 이
 * 커맨드로 명시 전달하며, adapter 는 SecurityContext 를 직접 읽지 않고 이 값을 신뢰한다.
 *
 * @property actorUserId 실행 주체 UUID. 자동화 룰의 actor(룰 소유자 또는 지정된 실행 주체).
 * @property issueKey 변경할 이슈 키. 예: `"PROJ-1"`.
 * @property field 변경할 이슈 필드명. 예: `"priority"`, `"summary"`.
 * @property value 새 값의 JSON 인코딩 문자열. null 이면 필드 해제(구현체 책임으로 해석).
 * @property dryRun true 이면 구현체가 권한/검증만 수행하고 실제 커밋·이벤트 발행을 하지 않는다.
 * @see IssueMutationPort.setField
 * @see MutationResult
 */
data class SetFieldCommand(
    val actorUserId: UUID,
    val issueKey: String,
    val field: String,
    val value: String?,
    val dryRun: Boolean,
)

/**
 * 이슈 담당자 배정/해제 커맨드 VO.
 *
 * automation BC 의 액션 실행기가 [IssueMutationPort.assign] 호출 시 전달하는 커맨드다.
 * [actorUserId] 의 async 안전·위조 차단 설계 근거는 [SetFieldCommand] KDoc 참조.
 *
 * @property actorUserId 실행 주체 UUID. 자동화 룰의 actor(룰 소유자 또는 지정된 실행 주체).
 * @property issueKey 담당자를 변경할 이슈 키. 예: `"PROJ-1"`.
 * @property assigneeId 배정할 담당자 UUID. null 이면 담당자 해제를 의미한다.
 * @property dryRun true 이면 구현체가 권한/검증만 수행하고 실제 커밋·이벤트 발행을 하지 않는다.
 * @see IssueMutationPort.assign
 * @see MutationResult
 */
data class AssignCommand(
    val actorUserId: UUID,
    val issueKey: String,
    val assigneeId: UUID?,
    val dryRun: Boolean,
)

/**
 * 이슈 댓글 추가 커맨드 VO.
 *
 * automation BC 의 액션 실행기가 [IssueMutationPort.addComment] 호출 시 전달하는 커맨드다.
 * [actorUserId] 의 async 안전·위조 차단 설계 근거는 [SetFieldCommand] KDoc 참조.
 *
 * @property actorUserId 실행 주체 UUID. 댓글 작성자로 기록된다(자동화 룰의 actor).
 * @property issueKey 댓글을 추가할 이슈 키. 예: `"PROJ-1"`.
 * @property body 댓글 본문. 템플릿 치환(`{{ var }}` 등)이 완료된 최종 텍스트다 — 이 커맨드
 *   자체는 템플릿 문법을 해석하지 않는다(automation `TemplateRenderer` 책임).
 * @property dryRun true 이면 구현체가 권한/검증만 수행하고 실제 커밋·이벤트 발행을 하지 않는다.
 * @see IssueMutationPort.addComment
 * @see MutationResult
 */
data class AddCommentCommand(
    val actorUserId: UUID,
    val issueKey: String,
    val body: String,
    val dryRun: Boolean,
)

/**
 * [IssueMutationPort] 세 메서드([IssueMutationPort.setField]/[IssueMutationPort.assign]/
 * [IssueMutationPort.addComment]) 공통 결과 VO.
 *
 * 실패(권한 거부·이슈 부재·OCC 버전 충돌 등)는 이 VO 로 표현하지 않고 예외로 던진다
 * ([IssueMutationPort] KDoc "실패 전달 — 예외" 참조). 이 VO 는 성공(또는 dryRun 미리보기
 * 성공) 시에만 반환된다.
 *
 * @property issueKey 변경(또는 검증)이 적용된 이슈 키.
 * @property applied true 면 실제로 커밋됨. false 면 dryRun 미리보기(커밋·이벤트 미발행).
 * @property version 커밋 후 OCC(낙관적 동시성 제어) 버전. 댓글 추가처럼 OCC 버전 대상이
 *   아니거나, dryRun 이라 실제 커밋이 없었던 경우 null 이다.
 * @see IssueMutationPort
 */
data class MutationResult(
    val issueKey: String,
    val applied: Boolean,
    val version: Long?,
)
