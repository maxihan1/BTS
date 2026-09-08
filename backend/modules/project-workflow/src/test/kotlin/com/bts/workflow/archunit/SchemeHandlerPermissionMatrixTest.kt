// 스킴·전환 규칙 컨트롤러 요청 매핑 핸들러의 권한 가드 봉인 — N4 결함 클래스 재발 방지 (호출 강제 + 분류 강제)

package com.bts.workflow.archunit

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

/**
 * 스킴 컨트롤러와 전환 규칙 컨트롤러([GUARDED_HANDLER_PACKAGES])의 모든 요청 매핑 핸들러가
 * 권한 가드를 빠뜨리지 않도록 강제하는 아키텍처 테스트.
 *
 * ### 배경 — N4
 * [com.bts.workflow.scheme.web.WorkflowSchemeController] 의 `list`/`get` 두 읽기 핸들러가
 * **14개월간 권한 가드 없이** 배포돼 있었다(스펙은 모든 endpoint 에 `MANAGE_SCHEME` 검증을
 * 요구했음에도). Task 1·2 가 그 인스턴스를 고쳤지만, 이 테스트는 같은 **결함 클래스**를
 * 다시 만들지 않도록 두 축을 봉인한다.
 *
 * - **축 1 (호출 강제)** — [callsRequirePermissionCondition]. [GUARDED_HANDLER_PACKAGES] 의 매핑
 *   메서드가 [WorkflowSchemePermissionResolver.requirePermission] 을 (직접 · 같은 클래스의 private
 *   헬퍼 1단계 · 모듈 공용 최상위 가드 중 하나로) 호출하지 않으면 실패.
 * - **축 2 (분류 강제)** — `요청 매핑 핸들러는 permission-scope 분류맵에 등록돼 있어야 한다` 테스트.
 *   [HANDLER_CLASSIFICATION] 맵에 없는 매핑 메서드가 하나라도 있으면 실패. 이쪽은 스코프가
 *   갈리는 스킴 컨트롤러 전용이라 [SCHEME_WEB_PACKAGE] 한정이다 (아래 (2) 참조).
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
 * **(2) 판별 범위가 손으로 유지하는 패키지 목록이다.**
 * 축 1 은 2026-08-26 부터 전환 규칙 컨트롤러까지 덮지만([GUARDED_HANDLER_PACKAGES]), 범위는
 * 여전히 **열거된 패키지**다 — **네 번째 패키지**에 컨트롤러가 생기면 이 봉인은 그 클래스를
 * 임포트조차 하지 않아 무음 통과한다. 런타임 대조 쪽은 판별 범위를 패키지가 아니라
 * **의존 관계**(`WorkflowSchemeApplicationService` 를 주입받는 `@RestController` 전량)로 잡아
 * 자동 편입시킨다.
 *
 * 축 2 는 [SCHEME_WEB_PACKAGE] 한정으로 남는다. [HANDLER_CLASSIFICATION] 은 스킴 컨트롤러의
 * `(permission, scopeKind)` 가 핸들러마다 갈리기 때문에 존재하는 등록부이고, 런타임 대조가
 * 그 맵을 **의존 관계로 뽑은 집합과 정확히 같은지**(`isEqualTo`) 대조한다. 규칙 컨트롤러 8개를
 * 그 맵에 밀어 넣으면 런타임 대조 쪽이 깨진다. 규칙 컨트롤러는 스코프가 MANAGE_SCHEME+Global
 * 한 종류뿐이라 등록부가 아니라 축 1 이 지킨다.
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
    /** 축 2 판별 대상 — `com.bts.workflow.scheme.web` 패키지 클래스 전체 (테스트 클래스 제외). */
    private val schemeWebClasses: JavaClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages(SCHEME_WEB_PACKAGE)

    /**
     * 축 1 판별 대상 — [GUARDED_HANDLER_PACKAGES] + 공용 가드 패키지.
     *
     * [MANAGE_SCHEME_GUARD_PACKAGE] 를 함께 임포트하는 이유는 **판별 대상이라서가 아니다.**
     * [callsRequirePermissionViaSharedGuard] 가 가드의 **본문**을 봐야 하는데
     * `resolveMember()` 는 같은 임포트 안에 있는 클래스만 풀어내기 때문이다. 이게 빠지면 그 갈래가
     * 늘 `false` 로 떨어져 규칙 컨트롤러 8개가 전부 위반이 된다. 판별 **대상**은
     * [isGuardedHandler] 가 패키지로 좁히므로, 이 패키지의 다른 컨트롤러(`WorkflowController` ·
     * `WorkflowStatusCompositionController`)는 룰에 걸리지 않는다.
     */
    private val guardedScopeClasses: JavaClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages(*GUARDED_HANDLER_PACKAGES, MANAGE_SCHEME_GUARD_PACKAGE)

    /**
     * 축 1 — 요청 매핑 핸들러는 [REQUIRE_PERMISSION_METHOD_NAME] 을 호출해야 한다.
     *
     * 주입 A(권한 호출 블록 삭제) 로 판별력을 실증한다 — 호출이 사라지면 반드시 실패해야 한다.
     */
    @Test
    fun `요청 매핑 핸들러는 requirePermission 을 호출해야 한다`() {
        assertEveryGuardedPackageHasHandlers()

        val rule =
            methods()
                .that(isGuardedHandler)
                .should(callsRequirePermissionCondition)
                .because(
                    "${GUARDED_HANDLER_PACKAGES.joinToString()} 의 모든 요청 매핑 핸들러는 진입 직후 " +
                        "$REQUIRE_PERMISSION_METHOD_NAME 을 (직접 · 같은 클래스 private 헬퍼 · 모듈 공용 " +
                        "최상위 가드 중 하나로) 호출해 권한을 검증해야 합니다 " +
                        "(N4 — list/get 읽기 핸들러가 14개월 무가드였던 결함 재발 방지)",
                )
        rule.check(guardedScopeClasses)
    }

    /**
     * 판별 범위가 조용히 비지 않았는지 확인한다.
     *
     * 패키지가 이름을 바꾸거나 컨트롤러가 옮겨 가면 [isGuardedHandler] 는 그 패키지에서 아무것도
     * 못 고르고, 룰은 **줄어든 범위 그대로 초록**이 된다. 범위 축소는 실패가 아니라 부재로
     * 나타나므로 여기서 패키지마다 한 건 이상을 명시적으로 요구한다.
     */
    private fun assertEveryGuardedPackageHasHandlers() {
        val handlers = guardedScopeClasses.flatMap { it.methods }.filter(isGuardedHandler::test)
        GUARDED_HANDLER_PACKAGES.forEach { pkg ->
            assertTrue(
                handlers.any { it.owner.packageName == pkg },
                "$pkg 에서 요청 매핑 핸들러를 찾지 못했습니다 — 봉인 범위가 조용히 줄었는지 확인하세요",
            )
        }
    }

    /**
     * 축 2 — 요청 매핑 핸들러는 [HANDLER_CLASSIFICATION] 맵에 등록돼 있어야 한다.
     *
     * 주입 B(맵에서 행 삭제) 로 판별력을 실증한다 — 맵에서 핸들러 행이 빠지면 반드시 실패해야 한다.
     */
    @Test
    fun `요청 매핑 핸들러는 permission-scope 분류맵에 등록돼 있어야 한다`() {
        val handlerMethods = schemeWebClasses.flatMap { it.methods }.filter(isRequestMappingHandler::test)
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
        private const val VALIDATOR_WEB_PACKAGE = "com.bts.workflow.validator.web"
        private const val POST_ACTION_WEB_PACKAGE = "com.bts.workflow.postaction.web"

        /** 축 1 판별 대상 패키지 — 스킴 컨트롤러 + 전환 규칙 컨트롤러 2종. */
        private val GUARDED_HANDLER_PACKAGES =
            arrayOf(SCHEME_WEB_PACKAGE, VALIDATOR_WEB_PACKAGE, POST_ACTION_WEB_PACKAGE)

        /** 모듈 공용 가드가 사는 패키지 — 본문 해석용으로만 임포트한다 (판별 대상 아님). */
        private const val MANAGE_SCHEME_GUARD_PACKAGE = "com.bts.workflow.web"

        private const val REQUIRE_PERMISSION_METHOD_NAME = "requirePermission"
        private val PERMISSION_RESOLVER_FQN = WorkflowSchemePermissionResolver::class.java.name

        /**
         * 모듈 공용 가드의 FQN.
         *
         * ✔ **2026-09-08(FR-WF-08) — 최상위 확장 함수에서 `@Component` 클래스로 바뀌었다.**
         * 가드가 스코프를 결정하려면 협력자(`WorkflowOwnershipScopeResolver`)를 들어야 하는데
         * 최상위 함수는 주입을 받을 자리가 없다. 그때 이 봉인이 **red 로 먼저 알려 줬다** — 파일
         * 파사드 `ManageSchemeGuardKt` 를 찾다 실패해 규칙 컨트롤러 8개가 전부 위반으로 잡혔다.
         * 이름만 대조했다면 조용히 통과했을 자리다.
         */
        private const val MANAGE_SCHEME_GUARD_FQN = "com.bts.workflow.web.ManageSchemeGuard"

        /** 모듈 공용 가드의 메서드명. */
        private const val REQUIRE_MANAGE_SCHEME_METHOD_NAME = "requireForWorkflow"

        /**
         * 축 1 판별 대상 — [GUARDED_HANDLER_PACKAGES] 안의 요청 매핑 핸들러.
         *
         * [guardedScopeClasses] 는 가드 본문 해석을 위해 [MANAGE_SCHEME_GUARD_PACKAGE] 도 담고
         * 있으므로, 대상 좁히기를 임포트에 맡기지 않고 여기서 패키지로 명시한다.
         */
        private val isGuardedHandler: DescribedPredicate<JavaMethod> =
            DescribedPredicate.describe(
                "request-mapping handler residing in ${GUARDED_HANDLER_PACKAGES.joinToString()}",
            ) { method ->
                isRequestMappingHandler.test(method) && method.owner.packageName in GUARDED_HANDLER_PACKAGES
            }

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
                "call $REQUIRE_PERMISSION_METHOD_NAME directly, via a private helper in the same class, " +
                    "or via the shared $MANAGE_SCHEME_GUARD_FQN.$REQUIRE_MANAGE_SCHEME_METHOD_NAME guard",
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
         * "도달"의 정의는 세 가지다.
         * 1. 직접 호출 — [callsRequirePermissionDirectly].
         * 2. 같은 클래스의 private 헬퍼를 거쳐 1단계만 호출 — [callsRequirePermissionViaPrivateHelper].
         *    직접 호출만 인정하면 `WorkflowSchemeController` 7벌 중복을 헬퍼로 DRY 리팩토링하는 것
         *    자체가 금지되므로 1단계 경유를 함께 인정한다.
         * 3. 모듈 공용 가드 클래스 경유 — [callsRequirePermissionViaSharedGuard].
         *
         * ✔ **2026-08-26 — 범위를 규칙 컨트롤러까지 넓히면서 3번 갈래를 함께 넣었다.**
         * task-15b 가 예고한 상황이다. `validator.web` · `postaction.web` 은 2026-08-25 부터 모듈
         * 공용 가드 `com.bts.workflow.web.ManageSchemeGuard` 를 부르므로, 「직접 호출 또는
         * 같은 클래스 private 1단계」만 인정하던 그때의 판정으로 범위만 넓혔다면 8개 핸들러가 전부
         * 위반으로 잡혔을 것이다. 판정 확장([callsRequirePermissionViaSharedGuard])과 범위 확장
         * ([GUARDED_HANDLER_PACKAGES])은 **한 쌍**이다 — 한쪽만 하면 red 다.
         *
         * 3번 갈래는 호출 대상 **이름만 보지 않고 가드 본문까지** 판정한다. 이유는 그쪽 KDoc 참조.
         */
        private fun reachesRequirePermission(method: JavaMethod): Boolean =
            callsRequirePermissionDirectly(method) ||
                callsRequirePermissionViaPrivateHelper(method) ||
                callsRequirePermissionViaSharedGuard(method)

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

        /**
         * [method] 가 모듈 공용 가드 [MANAGE_SCHEME_GUARD_FQN].[REQUIRE_MANAGE_SCHEME_METHOD_NAME]
         * 을 호출하고, **그 가드의 본문이 [REQUIRE_PERMISSION_METHOD_NAME] 을 직접 호출하는지**까지
         * 확인한다.
         *
         * ★ **이름만 보면 이 봉인은 장식이다.** 호출 대상 이름만 대조하면, 가드 본문이 권한 호출을
         * 잃는 날에도 호출부의 이름은 그대로 남아 규칙 컨트롤러 핸들러 전부가 무가드인 채 초록이
         * 된다 — 사본을 한 벌로 모은 대가로 폭발 반경이 8개 핸들러로 커진 자리다. 그래서 이름 대조
         * 뒤에 [callsRequirePermissionDirectly] 로 **본문**을 한 번 더 판정한다.
         *
         * 해석 실패([java.util.Optional.empty], 가드 클래스가 임포트 범위 밖일 때)는 `false` 로
         * 수렴시킨다 — 불명은 통과가 아니라 거부다.
         */
        private fun callsRequirePermissionViaSharedGuard(method: JavaMethod): Boolean =
            method.methodCallsFromSelf.any { call ->
                call.target.owner.fullName == MANAGE_SCHEME_GUARD_FQN &&
                    call.target.name == REQUIRE_MANAGE_SCHEME_METHOD_NAME &&
                    call.target
                        .resolveMember()
                        .map { guard -> callsRequirePermissionDirectly(guard) }
                        .orElse(false)
            }
    }
}
