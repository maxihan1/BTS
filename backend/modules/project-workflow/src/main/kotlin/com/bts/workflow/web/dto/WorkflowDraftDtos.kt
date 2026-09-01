// 초안·발행 API 의 요청/응답 DTO

package com.bts.workflow.web.dto

import com.bts.shared.issue.StatusMigrationMapping
import com.bts.workflow.domain.WorkflowDraftDefinition
import com.fasterxml.jackson.annotation.JsonProperty
import java.util.UUID

/**
 * 초안 조회 응답.
 *
 * @property definition 초안 정의. 저장된 초안이 없으면 지금 발행된 정의가 담긴다.
 * @property baseVersion 발행 요청에 **그대로 되돌려 보내야 하는** 버전. 화면이 이 값을 들고 있다가
 *   발행 시 실어 보내고, 서버는 그 사이 남이 발행했는지를 이것으로 판정한다.
 * @property exists 저장된 초안이 실제로 있었는지. false 면 화면은 「편집 시작 전」으로 표시한다.
 */
data class DraftResponse(
    val definition: WorkflowDraftDefinition,
    val baseVersion: Long,
    val exists: Boolean,
)

/**
 * 초안 저장 요청.
 *
 * ### 왜 정의만 받지 않고 `baseVersion` 을 함께 받나
 * 낙관적 락의 기준은 「**편집기가 무엇을 보고 있었는가**」인데, 서버는 그것을 재구성할 수 없다.
 * 저장 시점에 `workflows.version` 을 다시 읽어 앵커로 삼으면, 남이 방금 발행해(발행은 초안 행을
 * 지운다) 초안이 사라진 직후의 자동 저장 한 번으로 앵커가 새 버전으로 올라가고 락이 풀린다.
 *
 * 이 값은 신뢰 대상이 아니라 **화면의 주장**이다. 서버는 미래 버전만 기계로 거절하고(400),
 * 과거 버전은 그대로 받아 둔다 — 그 초안은 발행에서 409 로 막히는 것이 옳은 결말이다.
 *
 * @property definition 초안 정의 전체.
 * @property baseVersion `GET /draft` 가 돌려준 값. 초안 행이 새로 생길 때만 앵커로 기록된다.
 */
data class SaveDraftRequest(
    @JsonProperty(required = true) val definition: WorkflowDraftDefinition,
    @JsonProperty(required = true) val baseVersion: Long,
)

/**
 * 기본값 복원 요청.
 *
 * 복원도 편집의 일종이라 [SaveDraftRequest] 와 앵커 규칙이 같다. 본문 없이 두면 서버가 앵커를
 * 재조회하게 되어 같은 구멍이 다시 열린다.
 *
 * @property baseVersion `GET /draft` 가 돌려준 값.
 */
data class ResetToDefaultRequest(
    @JsonProperty(required = true) val baseVersion: Long,
)

/**
 * 발행 요청.
 *
 * ### `statusMappings` 가 없는 이유
 * Jira Cloud 는 이 요청에 `statusMappings` 를 함께 받아 이슈를 옮긴다. BTS 는 그 UPDATE 가
 * issue-tracking BC 소유라 아직 실행할 수 없어(다중 BC 트랜잭션 금지) **필드를 두지 않는다** —
 * 받아 놓고 무시하면 화면은 이관을 지시했다고 믿는데 아무 일도 일어나지 않는다.
 * 이관이 필요하면 발행이 409 로 막히고 응답이 상태별 잔여 건수를 알려준다. 매핑 수용은 로드맵 PR 7.
 *
 * ### `required = true` 인 이유
 * Kotlin 의 non-null `Long` 은 JVM primitive 라, 본문에서 **빠지면 Jackson 이 조용히 0 으로 채운다**
 * (Kotlin 모듈이 있어도 그렇다 — 값이 Kotlin 생성자에 닿기 전에 primitive 기본값이 들어간다).
 * 0 은 「새 워크플로우의 첫 발행」에서 실제로 나오는 그럴듯한 앵커라, 필드를 빠뜨린 요청이 400 이
 * 아니라 **잘못된 버전으로 발행을 시도**하게 된다. `required = true` 가 그 자리를 400 으로 되돌린다.
 *
 * @property baseVersion 초안 조회 때 받은 값. **저장된 초안의 앵커**와 다르면 409 다 — 지금 DB
 *   버전과 대조하지 않는다. 그렇게 하면 화면이 미리보기의 `currentVersion` 을 되실어 보내는 것만으로
 *   락이 풀린다.
 */
data class PublishRequest(
    @JsonProperty(required = true) val baseVersion: Long,
)

/**
 * 발행 성공 응답.
 *
 * @property versionNo 이번 발행의 회차. 워크플로우 안에서 1 부터 증가한다.
 */
data class PublishResponse(
    val versionNo: Int,
)

/**
 * 상태 이관 큐잉 요청.
 *
 * ### ★`projectKeys` 필드를 두지 않는다
 * `IssueStatusMigrationPort` KDoc 이 「[com.bts.shared.issue.StatusMigrationCommand.projectKeys] 는
 * 발행 대상 워크플로우에 연결된 프로젝트여야 한다 — 사용자 입력을 그대로 실으면 안 되고, 호출자가
 * 자기 발행 트랜잭션 안에서 직접 조회해 채운다」고 계약한다. 상태 키는 전역이라 범위를 요청이
 * 정하게 두면, 워크플로우 하나에 발행 권한을 가진 사람이 **남의 프로젝트 이슈를 통째로 옮길** 수
 * 있다 — 과다 집계(읽기)와 달리 과다 이동은 데이터 손상이다. 필드가 없는 것이 그 유일한 방어다.
 *
 * ### `required = true` 인 이유
 * [PublishRequest] 와 같다 — non-null `Long` 은 JVM primitive 라 본문에서 빠지면 Jackson 이 조용히
 * 0 으로 채우고, 0 은 「첫 발행」에서 실제로 나오는 그럴듯한 앵커다.
 *
 * @property baseVersion 초안 조회 때 받은 값. **저장된 초안의 앵커**와 다르면 409 다.
 * @property mappings 빠지는 상태마다 옮길 곳. 한 번의 발행에서 여러 상태가 동시에 빠질 수 있고
 *   그 각각의 대상이 다르므로 단일 쌍이 아니라 목록이다.
 */
data class MigrateRequest(
    @JsonProperty(required = true) val baseVersion: Long,
    @JsonProperty(required = true) val mappings: List<StatusMappingDto>,
)

/**
 * 상태 1개의 이관 대상 매핑.
 *
 * shared-kernel 의 [StatusMigrationMapping] 과 필드가 같지만 웹 DTO 를 따로 둔다 — 포트 계약 타입을
 * 그대로 본문 스키마로 쓰면 계약을 고칠 때마다 외부 API 가 함께 흔들린다.
 *
 * @property fromStatusKey 이번 발행에서 사라지는 상태 키. 이 상태에 남은 이슈가 이관 대상이다.
 * @property toStatusKey 옮겨 갈 상태 키.
 */
data class StatusMappingDto(
    @JsonProperty(required = true) val fromStatusKey: String,
    @JsonProperty(required = true) val toStatusKey: String,
) {
    /** 포트 계약 타입으로 옮긴다. 변환을 한 곳에만 두어 컨트롤러와 테스트가 같은 경로를 지난다. */
    fun toMapping(): StatusMigrationMapping = StatusMigrationMapping(fromStatusKey, toStatusKey)
}

/**
 * 이관 큐잉 접수 응답.
 *
 * 진행률은 담지 않는다 — 기존 `GET /api/v1/bulk-operations/{id}` 가 이미 그 일을 한다. 두 번째
 * 조회 경로를 만들면 둘이 서로를 검사하지 않는다.
 *
 * @property bulkOperationId 큐잉된 일괄작업 id. 화면은 이 id 로 진행률을 폴링한다.
 */
data class MigrateResponse(
    val bulkOperationId: UUID,
)

/**
 * 발행 미리보기 응답. Jira 의 `validateOnly` 에 해당한다.
 *
 * @property baseVersion 초안이 들고 있는 버전.
 * @property currentVersion 지금 DB 의 버전. [baseVersion] 과 다르면 이미 남이 발행한 것이다.
 * @property removedStatusKeys 발행하면 이 워크플로우에서 빠지는 상태 키.
 * @property pendingIssueCounts 그중 이슈가 남아 있는 상태와 그 건수. 비어 있지 않으면 발행이 막힌다.
 */
data class PublishPreviewResponse(
    val baseVersion: Long,
    val currentVersion: Long,
    val removedStatusKeys: List<String>,
    val pendingIssueCounts: Map<String, Long>,
)
