// com.bts.workflow.scheme.web 요청 매핑 핸들러의 권한 가드 봉인 — N4 결함 클래스 재발 방지 (호출 강제 + 분류 강제)

package com.bts.workflow.archunit

import com.bts.shared.permission.WorkflowSchemePermissionResolver
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
 *
 * ### ⚠️ 이 테스트가 **덮지 않는 것** (초록이 곧 정당함이 아니다)
 *
 * 이 정적 봉인만으로는 세 가지가 안 잡힌다. 셋 다 [SchemeHandlerPermissionRuntimeMatrixTest]
 * (MockMvc 로 핸들러를 실제 호출해 캡처값을 [HANDLER_CLASSIFICATION] 과 대조)가 닫는다.
 *
 * **(1) 축 1 은 "호출이 바이트코드에 존재하는가" 만 본다 — 도달하지 않는 분기의 가드도 통과한다.**
 * 실증(2026-07-26 코드리뷰 주입) — 아래 핸들러는 **양 축을 통과**한다.
 * ```
 * @GetMapping("/probe")
 * fun probe(): … {
 *     val schemes = applicationService.listWithCounts()
 *     if (schemes.isEmpty()) { permissionResolver.requirePermission(…) }  // 정상 경로에선 안 탄다
 *     return ResponseEntity.ok(DataEnvelope(schemes))
 * }
 * ```
 * 즉 N4 와 실질이 같은(인증만 되면 전 스킴을 읽는) 핸들러가 이 봉인을 그대로 빠져나간다.
 * 런타임 대조는 요청을 실제로 태워 캡처 1건 + 403 응답을 요구하므로 이 형태를 잡는다.
 *
 * **(2) 판별 범위가 [SCHEME_WEB_PACKAGE] 한정이다.**
 * `WorkflowSchemeApplicationService.list()`·`listWithCounts()` 자체는 권한 호출이 0건이므로,
 * **다른 패키지의 컨트롤러**가 그 서비스를 소비하는 읽기 엔드포인트를 만들면 이 봉인은
 * 그 클래스를 임포트조차 하지 않아 무음 통과한다. 런타임 대조 쪽은 판별 범위를 패키지가 아니라
 * **의존 관계**(그 서비스를 주입받는 `@RestController` 전량)로 잡아 자동 편입시킨다.
 *
 * **(3) 축 2 는 핸들러가 맵에 등록돼 있는지(`containsKey`)만 본다.**
 * **맵에 적힌 `(permission, scopeKind)` 가 코드가 실제로 넘기는 인자와 일치하는지는 검사하지 않는다.**
 *
 * 실증(2026-07-26 뮤테이션 M3) — `ProjectWorkflowSchemeController.listAssignableSchemes` 의 스코프를
 * `Project(projectKey)` → `Global` 로 바꿨을 때 **이 테스트는 green 을 유지**했다.
 * ⇒ 이 맵은 여기서는 **등록부**로만 쓰인다. 맵 값이 참인지는 런타임 대조가 판정한다.
 *
 * 관련 교훈 — `archunit-vacuous-rule-silent-pass` · `seal-blinds-existing-guard`.
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
                    val message = "${item.fullName} $verb $REQUIRE_PERMISSION_METHOD_NAME"
                    events.add(SimpleConditionEvent(item, satisfied, message))
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
