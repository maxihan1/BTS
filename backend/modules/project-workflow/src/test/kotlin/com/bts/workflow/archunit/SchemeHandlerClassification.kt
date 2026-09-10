// 스킴 컨트롤러 핸들러 → (권한, 스코프 종류) 분류맵 정본 — 정적 봉인과 런타임 대조가 함께 읽는다

package com.bts.workflow.archunit

import com.bts.shared.permission.WorkflowSchemePermission
import com.bts.shared.permission.WorkflowScope
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaMethod
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping

/**
 * 스코프 종류 — [WorkflowScope] 의 두 구현체에 대응한다.
 *
 * 구체 인스턴스(예. `Project("ATLAS")`)가 아니라 종류만 분류한다 — 분류맵은 컴파일 시점 상수라
 * 런타임 프로젝트 키를 알 수 없기 때문이다. 런타임에 캡처한 스코프는 [toScopeKind] 로 축약해 비교한다.
 */
internal enum class ScopeKind { GLOBAL, PROJECT }

/** 런타임에 캡처한 [WorkflowScope] 를 분류맵과 비교 가능한 [ScopeKind] 로 축약한다. */
internal fun WorkflowScope.toScopeKind(): ScopeKind =
    when (this) {
        is WorkflowScope.Global -> ScopeKind.GLOBAL
        is WorkflowScope.Project -> ScopeKind.PROJECT
    }

/** 분류맵 조회 키 — (컨트롤러 단순 클래스명, 핸들러 메서드명). */
internal data class HandlerKey(val className: String, val methodName: String)

/** 핸들러 1개에 대응하는 (권한, 스코프 종류) 분류. */
internal data class HandlerClassification(val permission: WorkflowSchemePermission, val scopeKind: ScopeKind)

/** 요청 매핑 핸들러로 인정하는 Spring 어노테이션 전체. */
private val REQUEST_MAPPING_ANNOTATIONS: List<Class<out Annotation>> =
    listOf(
        GetMapping::class.java,
        PostMapping::class.java,
        PutMapping::class.java,
        DeleteMapping::class.java,
        RequestMapping::class.java,
    )

/** 메서드가 [REQUEST_MAPPING_ANNOTATIONS] 중 하나라도 보유하면 요청 매핑 핸들러로 판정한다. */
internal val isRequestMappingHandler: DescribedPredicate<JavaMethod> =
    DescribedPredicate.describe("annotated with a Spring request-mapping annotation") { method ->
        REQUEST_MAPPING_ANNOTATIONS.any { method.isAnnotatedWith(it) }
    }

/**
 * 핸들러 → (permission, scopeKind) 분류맵.
 *
 * ⚠️ **핸들러를 추가하면 이 맵에 행을 추가해야 한다.** 안 하면 두 봉인이 함께 실패한다.
 * - [SchemeHandlerPermissionMatrixTest] 축 2 — 미등록 핸들러 탐지(정적).
 * - [SchemeHandlerPermissionRuntimeMatrixTest] — 실제 호출로 캡처한 `(permission, scope)` 대조(런타임).
 *
 * N4 — `list`/`get` 읽기 핸들러 2개가 14개월간 권한 가드 없이 방치됐던 결함 클래스를 다시 만들지
 * 않기 위해, 새 핸들러는 반드시 이 맵에 명시적으로 등록해야 리뷰 대상이 된다.
 */
internal val HANDLER_CLASSIFICATION: Map<HandlerKey, HandlerClassification> =
    mapOf(
        HandlerKey("WorkflowSchemeController", "create") to
            HandlerClassification(WorkflowSchemePermission.MANAGE_SCHEME, ScopeKind.GLOBAL),
        HandlerKey("WorkflowSchemeController", "list") to
            HandlerClassification(WorkflowSchemePermission.MANAGE_SCHEME, ScopeKind.GLOBAL),
        HandlerKey("WorkflowSchemeController", "get") to
            HandlerClassification(WorkflowSchemePermission.MANAGE_SCHEME, ScopeKind.GLOBAL),
        HandlerKey("WorkflowSchemeController", "update") to
            HandlerClassification(WorkflowSchemePermission.MANAGE_SCHEME, ScopeKind.GLOBAL),
        HandlerKey("WorkflowSchemeController", "delete") to
            HandlerClassification(WorkflowSchemePermission.MANAGE_SCHEME, ScopeKind.GLOBAL),
        HandlerKey("WorkflowSchemeController", "addMapping") to
            HandlerClassification(WorkflowSchemePermission.MANAGE_SCHEME, ScopeKind.GLOBAL),
        HandlerKey("WorkflowSchemeController", "deleteMapping") to
            HandlerClassification(WorkflowSchemePermission.MANAGE_SCHEME, ScopeKind.GLOBAL),
        HandlerKey("ProjectWorkflowSchemeController", "assignScheme") to
            HandlerClassification(WorkflowSchemePermission.ASSIGN_SCHEME, ScopeKind.PROJECT),
        HandlerKey("ProjectWorkflowSchemeController", "getAssignedScheme") to
            HandlerClassification(WorkflowSchemePermission.ASSIGN_SCHEME, ScopeKind.PROJECT),
        HandlerKey("ProjectWorkflowSchemeController", "listAssignableSchemes") to
            HandlerClassification(WorkflowSchemePermission.ASSIGN_SCHEME, ScopeKind.PROJECT),
    )
