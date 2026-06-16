// 이슈 프로젝트 간 이동 검증 규칙 — 순수 도메인, DB/Spring 의존 없음

package com.bts.issue.domain

import java.util.UUID

/**
 * 이슈 이동 검증에 필요한 입력 컨텍스트.
 *
 * 호출 서비스가 DB 조회를 통해 이 객체를 준비한 뒤 [IssueMoveOperation.validate] 에 전달한다.
 * 이 클래스 자체는 외부 의존이 없는 불변 데이터 홀더다.
 *
 * @param sourceProjectKey 이동 원본 프로젝트 키
 * @param targetProjectKey 이동 대상 프로젝트 키
 * @param hasSubtasks 이동 대상 이슈에 서브태스크(자식 이슈)가 하나 이상 존재하는지 여부
 * @param sourceStatusKey 현재 이슈 상태 키
 * @param targetWorkflowStatuses 대상 프로젝트 워크플로우에서 허용하는 상태 키 집합
 * @param targetStateKey 호출자가 명시한 대상 상태 키. null 이면 소스 상태 키를 그대로 사용
 * @param componentMappingTargetIds 컴포넌트 매핑에서 대상으로 지정한 컴포넌트 UUID 집합
 * @param targetProjectComponentIds 대상 프로젝트에 실제로 존재하는 컴포넌트 UUID 집합
 * @param versionMappingTargetIds 버전 매핑에서 대상으로 지정한 버전 UUID 집합
 * @param targetProjectVersionIds 대상 프로젝트에 실제로 존재하는 버전 UUID 집합
 * @param requiredFieldKeys 대상 프로젝트에서 필수로 요구하는 커스텀 필드 키 집합
 * @param providedFieldKeys 이동 요청에 포함된 커스텀 필드 키 집합
 */
data class IssueMoveContext(
    val sourceProjectKey: String,
    val targetProjectKey: String,
    val hasSubtasks: Boolean,
    val sourceStatusKey: String,
    val targetWorkflowStatuses: Set<String>,
    val targetStateKey: String?,
    val componentMappingTargetIds: Set<UUID>,
    val targetProjectComponentIds: Set<UUID>,
    val versionMappingTargetIds: Set<UUID>,
    val targetProjectVersionIds: Set<UUID>,
    val requiredFieldKeys: Set<String>,
    val providedFieldKeys: Set<String>,
)

/**
 * 이슈 프로젝트 간 이동의 순수 도메인 검증기.
 *
 * 모든 검증 규칙을 한 곳에 모아 preview 서비스와 move 서비스가 동일한 규칙을 공유하도록 한다.
 * DB·Spring·외부 I/O 의존이 없으므로 단위 테스트만으로 완전 검증 가능하다.
 */
object IssueMoveOperation {

    /**
     * 이슈 이동 전 검증 규칙을 순서대로 실행한다.
     *
     * 첫 번째로 위반되는 규칙에서 즉시 예외를 던진다. 모든 위반을 수집하지 않는다.
     *
     * 검증 순서.
     * 1. EC1 — 같은 프로젝트 이동 거부
     * 2. EC15 — 서브태스크 존재 시 이동 거부
     * 3. EC7 — 워크플로우 상태 비호환 + targetStateKey 미지정 또는 무효
     * 4. EC8 — 컴포넌트/버전 매핑 대상 id 미존재
     * 5. EC9 — 대상 프로젝트 필수 커스텀필드 미제공
     *
     * @param ctx 이동 검증에 필요한 모든 입력 컨텍스트
     * @throws MoveSameProjectException EC1 — 대상이 원본과 동일한 프로젝트
     * @throws IssueHasSubtasksException EC15 — 이동 이슈에 서브태스크 존재
     * @throws InvalidTargetStateException EC7 — 유효한 대상 상태를 결정할 수 없음
     * @throws InvalidTargetMappingException EC8 — 컴포넌트 또는 버전 매핑 대상 미존재
     * @throws RequiredFieldMissingException EC9 — 필수 커스텀필드 누락
     */
    fun validate(ctx: IssueMoveContext) {
        checkSameProject(ctx)
        checkSubtasks(ctx)
        checkTargetState(ctx)
        checkMappings(ctx)
        checkRequiredFields(ctx)
    }

    /**
     * EC1 — 원본과 대상 프로젝트가 동일한지 확인한다.
     */
    private fun checkSameProject(ctx: IssueMoveContext) {
        if (ctx.sourceProjectKey == ctx.targetProjectKey) {
            throw MoveSameProjectException(ctx.sourceProjectKey)
        }
    }

    /**
     * EC15 — 이동 이슈에 서브태스크(자식 이슈)가 있는지 확인한다.
     */
    private fun checkSubtasks(ctx: IssueMoveContext) {
        if (ctx.hasSubtasks) {
            throw IssueHasSubtasksException()
        }
    }

    /**
     * EC7 — 대상 프로젝트 워크플로우에서 사용할 상태 키를 결정한다.
     *
     * 결정 순서.
     * 1. 소스 상태 키가 대상 워크플로우에 존재 → 그대로 사용
     * 2. [IssueMoveContext.targetStateKey] 가 대상 워크플로우에 존재 → 해당 상태 사용
     * 3. 둘 다 아니면 → [InvalidTargetStateException] 발생
     */
    private fun checkTargetState(ctx: IssueMoveContext) {
        val sourceCompatible = ctx.sourceStatusKey in ctx.targetWorkflowStatuses
        if (sourceCompatible) return

        val explicitKey = ctx.targetStateKey
        if (explicitKey != null && explicitKey in ctx.targetWorkflowStatuses) return

        throw InvalidTargetStateException(
            sourceStatusKey = ctx.sourceStatusKey,
            targetStateKey = ctx.targetStateKey,
        )
    }

    /**
     * EC8 — 컴포넌트·버전 매핑에서 지정한 대상 id 가 실제 대상 프로젝트에 존재하는지 확인한다.
     *
     * 컴포넌트를 먼저 검사하고 위반 시 즉시 예외를 던진다. 버전 검사는 그 이후에 실행된다.
     */
    private fun checkMappings(ctx: IssueMoveContext) {
        checkComponentMapping(ctx)
        checkVersionMapping(ctx)
    }

    /**
     * 컴포넌트 매핑 대상 id 가 대상 프로젝트에 모두 존재하는지 확인한다.
     */
    private fun checkComponentMapping(ctx: IssueMoveContext) {
        val unknownComponents = ctx.componentMappingTargetIds - ctx.targetProjectComponentIds
        if (unknownComponents.isNotEmpty()) {
            throw InvalidTargetMappingException(kind = MappingKind.COMPONENT, unknownIds = unknownComponents)
        }
    }

    /**
     * 버전 매핑 대상 id 가 대상 프로젝트에 모두 존재하는지 확인한다.
     */
    private fun checkVersionMapping(ctx: IssueMoveContext) {
        val unknownVersions = ctx.versionMappingTargetIds - ctx.targetProjectVersionIds
        if (unknownVersions.isNotEmpty()) {
            throw InvalidTargetMappingException(kind = MappingKind.VERSION, unknownIds = unknownVersions)
        }
    }

    /**
     * EC9 — 대상 프로젝트 필수 커스텀필드가 모두 제공되었는지 확인한다.
     */
    private fun checkRequiredFields(ctx: IssueMoveContext) {
        val missing = ctx.requiredFieldKeys - ctx.providedFieldKeys
        if (missing.isNotEmpty()) {
            throw RequiredFieldMissingException(missingKeys = missing)
        }
    }
}

// ── 이동 전용 도메인 예외 ──────────────────────────────────────────────────────

/**
 * EC1 — 대상 프로젝트가 원본 프로젝트와 동일할 때.
 *
 * 같은 프로젝트 내 이동은 의미가 없으므로 즉시 거부한다.
 *
 * @param projectKey 원본·대상이 동일한 프로젝트 키
 */
class MoveSameProjectException(projectKey: String) :
    IssueDomainException("Cannot move issue to the same project: $projectKey")

/**
 * EC15 — 이동 대상 이슈에 서브태스크(자식 이슈)가 하나 이상 존재할 때.
 *
 * 서브태스크 동반 이동은 후속 기능으로 예정되어 있으며 현재는 지원하지 않는다.
 */
class IssueHasSubtasksException :
    IssueDomainException("Cannot move issue that has subtasks")

/**
 * EC7 — 대상 프로젝트 워크플로우에서 유효한 상태를 결정할 수 없을 때.
 *
 * 소스 상태 키가 대상 워크플로우에 없고 [targetStateKey] 도 지정되지 않았거나
 * 지정되었더라도 대상 워크플로우에 존재하지 않는 경우에 발생한다.
 *
 * @param sourceStatusKey 원본 이슈의 현재 상태 키
 * @param targetStateKey 호출자가 명시한 대상 상태 키. null 이면 미지정
 */
class InvalidTargetStateException(
    val sourceStatusKey: String,
    val targetStateKey: String?,
) : IssueDomainException(
        "No valid target state: sourceStatus=$sourceStatusKey, targetStateKey=${targetStateKey ?: "<not specified>"}",
    )

/** 컴포넌트/버전 매핑에서 대상 id 가 소속된 리소스 종류. */
enum class MappingKind { COMPONENT, VERSION }

/**
 * EC8 — 컴포넌트 또는 버전 매핑에서 대상으로 지정한 id 가 대상 프로젝트에 존재하지 않을 때.
 *
 * @param kind 문제가 발생한 매핑 종류 ([MappingKind.COMPONENT] 또는 [MappingKind.VERSION])
 * @param unknownIds 대상 프로젝트에 존재하지 않는 UUID 집합
 */
class InvalidTargetMappingException(
    val kind: MappingKind,
    val unknownIds: Set<UUID>,
) : IssueDomainException("Invalid ${kind.name.lowercase()} mapping: ids not found in target project")

/**
 * EC9 — 대상 프로젝트의 필수 커스텀필드 값이 이동 요청에 포함되지 않았을 때.
 *
 * @param missingKeys 제공되지 않은 필수 커스텀필드 키 집합
 */
class RequiredFieldMissingException(val missingKeys: Set<String>) :
    IssueDomainException("Required custom fields missing for target project: $missingKeys")
