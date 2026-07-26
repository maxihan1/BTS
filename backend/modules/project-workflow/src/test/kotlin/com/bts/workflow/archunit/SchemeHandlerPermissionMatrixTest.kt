// com.bts.workflow.scheme.web 요청 매핑 핸들러의 권한 가드 봉인 — N4 결함 클래스 재발 방지 (호출 강제 + 분류 강제)

package com.bts.workflow.archunit

import com.bts.shared.permission.WorkflowSchemePermission
import com.bts.shared.permission.WorkflowSchemePermissionResolver
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.domain.JavaModifier
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping

/**
 * `com.bts.workflow.scheme.web` 패키지의 모든 요청 매핑 핸들러가 권한 가드를 빠뜨리지 않도록
 * 강제하는 아키텍처 테스트.
 *
 * ### 배경 — N4
 * [com.bts.workflow.scheme.web.WorkflowSchemeController] 의 `list`/`get` 두 읽기 핸들러가
 * **14개월간 권한 가드 없이** 배포돼 있었다(스펙은 모든 endpoint 에 `MANAGE_SCHEME` 검증을
 * 요구했음에도). Task 1·2 가 그 인스턴스를 고쳤지만, 이 테스트는 같은 **결함 클래스**를
 * 다시 만들지 않도록 두 축을 봉인한다.
 *
 * - **축 1 (호출 강제)** — [callsRequirePermissionCondition]. 매핑 메서드가
 *   [WorkflowSchemePermissionResolver.requirePermission] 을 (직접 또는 같은 클래스의 private
 *   헬퍼 1단계를 통해) 호출하지 않으면 실패.
 * - **축 2 (분류 강제)** — `요청 매핑 핸들러는 permission-scope 분류맵에 등록돼 있어야 한다` 테스트.
 *   [HANDLER_CLASSIFICATION] 맵에 없는 매핑 메서드가 하나라도 있으면 실패.
 */
class SchemeHandlerPermissionMatrixTest {
    /** `com.bts.workflow.scheme.web` 패키지 클래스 전체 (테스트 클래스 제외). */
    private val importedClasses: JavaClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages(SCHEME_WEB_PACKAGE)

    /**
     * 축 1 — 요청 매핑 핸들러는 [REQUIRE_PERMISSION_METHOD_NAME] 을 호출해야 한다.
     *
     * 주입 A(권한 호출 블록 삭제) 로 판별력을 실증한다 — 호출이 사라지면 반드시 실패해야 한다.
     */
    @Test
    fun `요청 매핑 핸들러는 requirePermission 을 호출해야 한다`() {
        val rule =
            methods()
                .that(isRequestMappingHandler)
                .should(callsRequirePermissionCondition)
                .because(
                    "$SCHEME_WEB_PACKAGE 의 모든 요청 매핑 핸들러는 진입 직후 " +
                        "$REQUIRE_PERMISSION_METHOD_NAME 을 (직접 또는 같은 클래스 private 헬퍼를 통해) 호출해 " +
                        "권한을 검증해야 합니다 (N4 — list/get 읽기 핸들러가 14개월 무가드였던 결함 재발 방지)",
                )
        rule.check(importedClasses)
    }

    /**
     * 축 2 — 요청 매핑 핸들러는 [HANDLER_CLASSIFICATION] 맵에 등록돼 있어야 한다.
     *
     * 주입 B(맵에서 행 삭제) 로 판별력을 실증한다 — 맵에서 핸들러 행이 빠지면 반드시 실패해야 한다.
     */
    @Test
    fun `요청 매핑 핸들러는 permission-scope 분류맵에 등록돼 있어야 한다`() {
        val handlerMethods = importedClasses.flatMap { it.methods }.filter(isRequestMappingHandler::test)
        assertTrue(handlerMethods.isNotEmpty(), "$SCHEME_WEB_PACKAGE 에서 요청 매핑 핸들러를 찾지 못했습니다 — 임포트 대상 패키지를 확인하세요")

        val unclassified =
            handlerMethods.filterNot { method ->
                HANDLER_CLASSIFICATION.containsKey(HandlerKey(method.owner.simpleName, method.name))
            }

        assertTrue(
            unclassified.isEmpty(),
            "HANDLER_CLASSIFICATION 맵에 없는 핸들러가 있습니다. " +
                "핸들러를 추가했다면 이 맵에도 행을 추가해야 합니다 — 미분류: " +
                unclassified.joinToString { "${it.owner.simpleName}.${it.name}" },
        )
    }

    companion object {
        private const val SCHEME_WEB_PACKAGE = "com.bts.workflow.scheme.web"
        private const val REQUIRE_PERMISSION_METHOD_NAME = "requirePermission"
        private val PERMISSION_RESOLVER_FQN = WorkflowSchemePermissionResolver::class.java.name

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
        private val isRequestMappingHandler: DescribedPredicate<JavaMethod> =
            DescribedPredicate.describe("annotated with a Spring request-mapping annotation") { method ->
                REQUEST_MAPPING_ANNOTATIONS.any { method.isAnnotatedWith(it) }
            }

        /**
         * 스코프 종류 — [com.bts.shared.permission.WorkflowSchemeScope] 의 두 구현체에 대응한다.
         *
         * 구체 인스턴스(예. `Project("ATLAS")`)가 아니라 종류만 분류한다 — 분류맵은 컴파일 시점
         * 상수라 런타임 프로젝트 키를 알 수 없기 때문이다.
         */
        private enum class ScopeKind { GLOBAL, PROJECT }

        /** 분류맵 조회 키 — (컨트롤러 단순 클래스명, 핸들러 메서드명). */
        private data class HandlerKey(val className: String, val methodName: String)

        /** 핸들러 1개에 대응하는 (권한, 스코프 종류) 분류. */
        private data class HandlerClassification(val permission: WorkflowSchemePermission, val scopeKind: ScopeKind)

        /**
         * 핸들러 → (permission, scopeKind) 분류맵.
         *
         * ⚠️ **핸들러를 추가하면 이 맵에 행을 추가해야 한다. 안 하면 축 2 분류 테스트가 실패한다.**
         * N4 — list/get 읽기 핸들러 2개가 14개월간 권한 가드 없이 방치됐던 결함 클래스를 다시
         * 만들지 않기 위해, 새 핸들러는 반드시 이 맵에 명시적으로 등록해야 리뷰 대상이 된다.
         */
        private val HANDLER_CLASSIFICATION: Map<HandlerKey, HandlerClassification> =
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

        /**
         * 축 1 조건 — 핸들러가 [REQUIRE_PERMISSION_METHOD_NAME] 을 호출해야 통과.
         *
         * [com.tngtech.archunit.lang.conditions.ArchConditions.callMethodWhere] 는
         * `ArchCondition<JavaClass>` 를 반환해 `methods().should(...)` 가 요구하는
         * `ArchCondition<JavaMethod>` 와 타입이 맞지 않는다. 그래서 [JavaMethod.getMethodCallsFromSelf]
         * 를 직접 순회하는 수제 조건으로 작성한다.
         */
        private val callsRequirePermissionCondition: ArchCondition<JavaMethod> =
            object : ArchCondition<JavaMethod>(
                "call $REQUIRE_PERMISSION_METHOD_NAME directly or via a private helper in the same class",
            ) {
                override fun check(
                    item: JavaMethod,
                    events: ConditionEvents,
                ) {
                    val satisfied = reachesRequirePermission(item)
                    val verb = if (satisfied) "calls" else "does not call"
                    events.add(SimpleConditionEvent(item, satisfied, "${item.fullName} $verb $REQUIRE_PERMISSION_METHOD_NAME"))
                }
            }

        /**
         * [method] 가 [REQUIRE_PERMISSION_METHOD_NAME] 에 도달하는지 판정한다.
         *
         * "도달"의 정의는 두 가지 — 직접 호출([callsRequirePermissionDirectly]) 또는 같은 클래스의
         * private 헬퍼를 거쳐 1단계만 호출([callsRequirePermissionViaPrivateHelper]).
         * [com.bts.workflow.postaction.web.PostActionController.requireManageScheme] 이 이미
         * private 헬퍼 경유 패턴을 쓰고 있어(BC 관례), 직접 호출만 인정하면 향후
         * `WorkflowSchemeController` 7벌 중복을 헬퍼로 DRY 리팩토링하는 것 자체가 금지된다.
         */
        private fun reachesRequirePermission(method: JavaMethod): Boolean =
            callsRequirePermissionDirectly(method) || callsRequirePermissionViaPrivateHelper(method)

        /** [method] 가 [PERMISSION_RESOLVER_FQN] 타입 인스턴스의 [REQUIRE_PERMISSION_METHOD_NAME] 을 직접 호출하는지. */
        private fun callsRequirePermissionDirectly(method: JavaMethod): Boolean =
            method.methodCallsFromSelf.any { call ->
                call.target.name == REQUIRE_PERMISSION_METHOD_NAME &&
                    call.target.owner.isAssignableTo(PERMISSION_RESOLVER_FQN)
            }

        /**
         * [method] 가 같은 클래스의 private 메서드를 호출하고, 그 private 메서드가
         * [REQUIRE_PERMISSION_METHOD_NAME] 을 직접 호출하는지 (1단계 위임만 인정).
         */
        private fun callsRequirePermissionViaPrivateHelper(method: JavaMethod): Boolean =
            method.methodCallsFromSelf.any { call ->
                val resolved = call.target.resolveMember().orElse(null)
                resolved != null &&
                    resolved.owner == method.owner &&
                    resolved.modifiers.contains(JavaModifier.PRIVATE) &&
                    callsRequirePermissionDirectly(resolved)
            }
    }
}
