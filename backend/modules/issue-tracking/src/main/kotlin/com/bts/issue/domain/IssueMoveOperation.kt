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
     * @throws IssueHasSubtasksException EC15 — 이동 이슈에 서브태스크 존재 (단건 경로 전용)
     * @throws InvalidTargetStateException EC7 — 유효한 대상 상태를 결정할 수 없음
     * @throws InvalidTargetMappingException EC8 — 컴포넌트 또는 버전 매핑 대상 미존재
     * @throws RequiredFieldMissingException EC9 — 필수 커스텀필드 누락
     */
    fun validate(ctx: IssueMoveContext) {
        validateNode(ctx, includeSubtaskCheck = true)
    }

    /**
     * 서브태스크 동반 이동(노드별 매핑) 검증.
     *
     * 단건 [validate] 와 달리 EC15([IssueHasSubtasksException]) 는 적용하지 않는다.
     * 자식 존재는 정상(동반 이동 의도). 대신 다단계(EC16) 와 불완전 매핑(EC15 재정의) 을 검증한다.
     *
     * 검증 순서.
     * 1. EC16 — 자식의 자식 존재(다단계) 거부
     * 2. 불완전 매핑 거부 — actualChildKeys ≠ providedChildKeys
     * 3. 루트 노드 검증 (EC1/EC7/EC8/EC9, EC15 제외)
     * 4. 각 자식 노드 검증 (EC7/EC8/EC9)
     *
     * @param rootCtx 루트(부모) 이슈 이동 컨텍스트
     * @param childCtxs 각 직접 자식의 이동 컨텍스트 목록
     * @param actualChildKeys DB 에서 조회한 실제 직접 자식 키 집합
     * @param providedChildKeys 요청에서 제공된 자식 키 집합
     * @param childKeysWithOwnChildren 다단계 위반 자식 키 집합
     * @throws SubtaskHasOwnSubtasksException EC16 — 자식이 또 자식을 가짐
     * @throws IncompleteSubtaskMappingException EC15(재정의) — 자식 매핑 불완전
     * @throws MoveSameProjectException EC1 — 같은 프로젝트 이동
     * @throws InvalidTargetStateException EC7 — 유효한 대상 상태 결정 불가
     * @throws InvalidTargetMappingException EC8 — 매핑 대상 미존재
     * @throws RequiredFieldMissingException EC9 — 필수 커스텀필드 누락
     */
    fun validateWithSubtasks(
        rootCtx: IssueMoveContext,
        childCtxs: List<IssueMoveContext>,
        actualChildKeys: Set<String>,
        providedChildKeys: Set<String>,
        childKeysWithOwnChildren: Set<String>,
    ) {
        // 1. EC16 — 다단계 거부
        if (childKeysWithOwnChildren.isNotEmpty()) {
            throw SubtaskHasOwnSubtasksException(childKeysWithOwnChildren)
        }

        // 2. 불완전 매핑 거부
        if (actualChildKeys != providedChildKeys) {
            throw IncompleteSubtaskMappingException(
                expected = actualChildKeys,
                provided = providedChildKeys,
            )
        }

        // 3. 루트 노드 검증 (EC15 체크 제외)
        validateNode(rootCtx, includeSubtaskCheck = false)

        // 4. 각 자식 노드 검증
        for (childCtx in childCtxs) {
            validateNode(childCtx, includeSubtaskCheck = false)
        }
    }

    /**
     * 단일 노드(루트 또는 자식)에 대한 공통 검증을 실행한다.
     *
     * @param ctx 검증 대상 이동 컨텍스트
     * @param includeSubtaskCheck true 이면 EC15([checkSubtasks]) 포함, false 이면 제외(동반 경로)
     */
    private fun validateNode(
        ctx: IssueMoveContext,
        includeSubtaskCheck: Boolean,
    ) {
        checkSameProject(ctx)
        if (includeSubtaskCheck) checkSubtasks(ctx)
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

/**
 * EC16 — 직접 자식이 또 자식을 가지는 다단계 트리를 이동 시도할 때.
 *
 * BTS 서브태스크는 1레벨 모델이므로 자식의 자식은 거부한다.
 * 이동 시점에 1레벨 불변식을 명시 검증한다.
 *
 * @param childKeys 다단계 위반 자식 이슈 키 집합
 */
class SubtaskHasOwnSubtasksException(val childKeys: Set<String>) :
    IssueDomainException("Subtask cannot have its own subtasks: $childKeys")

/**
 * EC15(재정의) — 동반 이동 요청에서 모든 직접 자식 매핑이 제공되지 않았을 때.
 *
 * 단건 이동의 [IssueHasSubtasksException](무조건 거부) 을 대체하는 동반 이동 전용 예외.
 * 일부 자식 누락 또는 요청에 없는(비존재) 자식 키 포함 시 발생한다.
 *
 * @param expected DB 에서 조회한 실제 직접 자식 키 집합
 * @param provided 요청에서 제공된 자식 키 집합
 */
class IncompleteSubtaskMappingException(
    val expected: Set<String>,
    val provided: Set<String>,
) : IssueDomainException("Subtask mapping incomplete: expected=$expected provided=$provided")
