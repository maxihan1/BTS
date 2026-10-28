// IssueMutationPort 커맨드/결과 값 객체 — 필드·담당자·댓글·수정 예정 버전·전환·생성·연결·관찰자 (FR-AT-02)
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
 * 이슈 수정 예정 버전(Fix Version) 설정 커맨드 VO.
 *
 * automation BC 의 액션 실행기가 [IssueMutationPort.setFixVersions] 호출 시 전달하는 커맨드다.
 * [actorUserId] 의 async 안전·위조 차단 설계 근거는 [SetFieldCommand] KDoc 참조.
 *
 * ### versionIds — 전체교체 시맨틱, expectedVersion 없음
 *
 * [versionIds] 는 이슈의 현재 Fix Version 목록을 **전체 교체**한다(추가/제거가 아니다). 빈 목록을
 * 전달하면 Fix Version 전체 해제를 의미한다 — 별도 "해제" 플래그가 없다. 다른 커맨드([SetFieldCommand],
 * [AssignCommand])와 달리 OCC(낙관적 동시성 제어) `expectedVersion` 을 커맨드에 담지 않는다 — 구현체가
 * 자신의 트랜잭션 안에서 현재 version 을 재조회해 채운다(호출자는 OCC 재시도를 알 필요가 없다).
 *
 * @property actorUserId 실행 주체 UUID. 자동화 룰의 actor(룰 소유자 또는 지정된 실행 주체).
 * @property issueKey Fix Version 을 변경할 이슈 키. 예: `"PROJ-1"`.
 * @property versionIds 교체할 버전 UUID 목록. 빈 목록이면 전체 해제.
 * @property dryRun true 이면 구현체가 권한/검증만 수행하고 실제 커밋·이벤트 발행을 하지 않는다.
 * @see IssueMutationPort.setFixVersions
 * @see MutationResult
 */
data class SetFixVersionsCommand(
    val actorUserId: UUID,
    val issueKey: String,
    val versionIds: List<UUID>,
    val dryRun: Boolean,
)

/**
 * [IssueMutationPort] 전 메서드 공통 결과 VO.
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

/**
 * 이슈 상태 전환 커맨드 VO (R9 단계 C · TRANSITION 액션).
 *
 * automation BC 의 액션 실행기가 [IssueMutationPort.transition] 호출 시 전달하는 커맨드다.
 * [actorUserId] 의 async 안전·위조 차단 설계 근거는 [SetFieldCommand] KDoc 참조. OCC `expectedVersion`
 * 은 담지 않는다 — 구현체가 자신의 트랜잭션 안에서 재조회한다([SetFixVersionsCommand] 와 같음).
 *
 * @property actorUserId 실행 주체 UUID. 자동화 룰의 actor.
 * @property issueKey 전환할 이슈 키. 예: `"PROJ-1"`.
 * @property toStateKey 대상 워크플로 상태 키. 워크플로에 그 전환이 없거나 검증기가 거절하면 구현체가 예외를 던진다.
 * @property dryRun true 이면 구현체가 권한/검증만 수행하고 실제 커밋·이벤트 발행을 하지 않는다.
 * @see IssueMutationPort.transition
 */
data class TransitionIssueCommand(
    val actorUserId: UUID,
    val issueKey: String,
    val toStateKey: String,
    val dryRun: Boolean,
)

/**
 * 이슈(또는 하위 작업) 생성 커맨드 VO (R9 단계 C · CREATE_ISSUE 액션).
 *
 * automation BC 의 액션 실행기가 [IssueMutationPort.createIssue] 호출 시 전달하는 커맨드다.
 * 보고자 = [actorUserId] 다. 대상 프로젝트는 호출자가 룰 프로젝트로 고정해 채운다.
 *
 * @property actorUserId 실행 주체 UUID. 만든 이슈의 보고자로 기록된다.
 * @property projectKey 이슈를 만들 프로젝트 키.
 * @property issueTypeKey 이슈 유형 키(`task`, `subtask` 등 — [IssueTypeKey] 형식).
 * @property summary 요약. 템플릿 치환이 완료된 최종 텍스트다.
 * @property parentKey 부모 이슈 키. non-null 이면 하위 작업으로 만든다(부모 UPDATE 권한 필요).
 * @property dryRun true 이면 구현체가 권한/검증만 수행하고 실제 커밋·이벤트 발행을 하지 않는다.
 * @see IssueMutationPort.createIssue
 */
data class CreateIssueCommand(
    val actorUserId: UUID,
    val projectKey: String,
    val issueTypeKey: String,
    val summary: String,
    val parentKey: String?,
    val dryRun: Boolean,
)

/**
 * 이슈 연결 커맨드 VO (R9 단계 C · LINK_ISSUE 액션).
 *
 * @property actorUserId 실행 주체 UUID. 자동화 룰의 actor.
 * @property issueKey 연결의 출발(source) 이슈 키 — 트리거 이슈.
 * @property targetIssueKey 연결 대상 이슈 키.
 * @property linkTypeCode 연결 유형 코드(예: `blocks`, `relates`). 기존 연결 API 와 같은 값이다.
 * @property dryRun true 이면 구현체가 권한/검증만 수행하고 실제 커밋·이벤트 발행을 하지 않는다.
 * @see IssueMutationPort.linkIssues
 */
data class LinkIssueCommand(
    val actorUserId: UUID,
    val issueKey: String,
    val targetIssueKey: String,
    val linkTypeCode: String,
    val dryRun: Boolean,
)

/**
 * 이슈 관찰자 추가 커맨드 VO (R9 단계 C · ADD_WATCHER 액션).
 *
 * 「보고자 · 담당자를 관찰자로」 같은 필드 기준 해석은 호출자가 이슈 스냅샷으로 끝내고 사용자 id 만 넘긴다.
 *
 * @property actorUserId 실행 주체 UUID. 자동화 룰의 actor.
 * @property issueKey 관찰자를 추가할 이슈 키.
 * @property watcherUserId 관찰자로 추가할 사용자 UUID.
 * @property dryRun true 이면 구현체가 권한/검증만 수행하고 실제 커밋을 하지 않는다.
 * @see IssueMutationPort.addWatcher
 */
data class AddWatcherCommand(
    val actorUserId: UUID,
    val issueKey: String,
    val watcherUserId: UUID,
    val dryRun: Boolean,
)
