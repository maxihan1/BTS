// 조립 전체에서 실효 광역 @ControllerAdvice 를 전수 열거하고 그 ProblemDetail 핸들러의 instance 설정을 강제하는 봉인 (N3)

package com.bts.app

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.http.ProblemDetail
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestController

/**
 * **실효 광역 `@ControllerAdvice` 봉인 (N3).**
 *
 * ## 위험 모델
 * advice 가 **많은 컨트롤러에 붙는 상태**에서 [ProblemDetail] 을 `instance` 미설정으로 반환하면,
 * Spring MVC 의 `RequestResponseBodyMethodProcessor` 가 `instance` 를 **요청 URI 로 자동으로 채운다**
 * (#310 실측 확정). BTS 에는 경로 세그먼트에 원문 비밀 토큰을 싣는 경로가 있어(공유 대시보드 토큰 ·
 * iCal 피드 토큰 · git 웹훅 토큰 · automation 웹훅 토큰) 그 순간 평문 토큰이 응답 본문에 실린다
 * (`DEVELOPMENT.md §1.1-1·§1.1-2`).
 *
 * ## ★ 왜 조립(:modules:app)에서 스캔하는가
 * `BtsApplication` 은 `com.bts` 와 `com.atlas.bts` 를 **둘 다** 스캔한다. 즉 위험은 **조립 전역**이고,
 * 한 BC 의 광역 advice 가 다른 BC 컨트롤러까지 덮는다. 봉인을 한 BC 안에 두면 위험 모델과 가드 범위가
 * 어긋난다(PR #313 코드리뷰 C2). 조립 모듈만 9 BC 를 전부 `implementation` 으로 물고 있어 여기서만
 * 전수 스캔이 가능하며, 형제 PR #312 도 같은 이유로 조립 레벨에 보안 테스트를 두었다
 * ([AuthenticatedErrorPathTokenLeakTest]).
 *
 * ## ★ "선택자 유무"가 아니라 **실효 범위**로 판정한다 (리뷰 C1 — 뮤테이션으로 실증)
 * 초안은 `basePackages`/`basePackageClasses`/`assignableTypes`/`annotations` 가 **전부 비었는가**로
 * 광역을 판정했다. 그 판정은 Spring 의 `HandlerTypePredicate.hasSelectors()` 와 정확히 일치하지만
 * **막으려는 위험과는 어긋난다** — 다음 둘은 선택자가 "있어서" 통과하면서 실효는 전역이다.
 *
 * - `@RestControllerAdvice(annotations = [RestController::class])` — 레포의 **모든** `@RestController` 에 붙는다
 * - `@RestControllerAdvice(basePackages = ["com.bts"])` — 루트 접두사라 사실상 전체
 *
 * 그래서 [isEffectivelyBroad] 는 (1) 선택자 전무 (2) `annotations` 에 컨트롤러 스테레오타입 포함
 * (3) `basePackages` 에 루트 접두사 포함 — 셋 중 하나면 광역으로 본다.
 *
 * ## 세 축
 * - **축 1** — 실효 광역 advice **전수 열거**, [EXPECTED_BROAD_ADVICES] 미등재면 실패.
 *   개수를 세지 않고 열거한다(`guard-handler-matrix-blindfold`)
 * - **축 2** — 광역 advice 의 `@ExceptionHandler` 중 [ProblemDetail] 반환 메서드는 **반드시**
 *   `instance` 를 설정해야 한다. 축 1 만 있으면 "표본에 등재만 하고 처방은 빼먹는" 통로가 남는다(리뷰 C3)
 * - **축 3** — vacuous 방어. 스캔이 깨지면 축 2 가 조용히 참이 되므로 총 개수 하한을 별도로 단언한다
 *
 * (`archunit-vacuous-rule-silent-pass` — 위반을 실제로 주입해 각 축이 red 가 되는 것을 확인했다.
 * ADR `2026-07-26-global-advice-instance-token-leak.md` §검증 참조.)
 */
class GlobalControllerAdviceSealTest {
    private val adviceClasses: List<JavaClass> =
        ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(*SCANNED_PACKAGES)
            .filter { it.isMetaAnnotatedWith(ControllerAdvice::class.java) }
            .toList()

    private val broadAdvices: List<JavaClass> get() = adviceClasses.filter { it.isEffectivelyBroad() }

    @Test
    fun `실효 광역 advice 는 기대 표본에 등재된 것만 존재한다 (미등재=실패)`() {
        val found = broadAdvices.map { it.name }.sorted()

        assertThat(found)
            .withFailMessage(
                """
                실효 광역 @ControllerAdvice 가 기대 표본과 다르다.
                  발견: %s
                  기대: %s

                새 광역 advice 를 추가했다면 — ProblemDetail 을 반환하는지 확인하고,
                반환한다면 instance 를 **반드시 명시**한 뒤(ProjectArchivedExceptionHandler 선례)
                이 표본에 등재하라. instance 를 비우면 Spring 이 요청 URI 로 자동 채워
                경로에 토큰이 실린 요청에서 평문 토큰이 응답 본문에 실린다.
                광역이 아니어도 되는 advice 라면 basePackages/assignableTypes 로 좁히는 쪽이 낫다.
                """.trimIndent(),
                found,
                EXPECTED_BROAD_ADVICES.sorted(),
            ).containsExactlyInAnyOrderElementsOf(EXPECTED_BROAD_ADVICES)
    }

    @Test
    fun `광역 advice 의 ProblemDetail 핸들러는 instance 를 설정한다`() {
        val offenders =
            broadAdvices
                .flatMap { advice -> advice.methods.map { advice to it } }
                .filter { (_, method) -> method.isProblemDetailExceptionHandler() }
                .filterNot { (advice, method) -> advice.setsInstanceFor(method) }
                .map { (advice, method) -> "${advice.name}#${method.name}" }

        assertThat(offenders)
            .withFailMessage(
                """
                광역 advice 의 ProblemDetail 핸들러가 instance 를 설정하지 않는다: %s

                Spring 은 instance 가 null 이면 **요청 URI 로 자동 채운다**. 광역 advice 는 경로에 원문
                토큰이 실린 컨트롤러에도 붙으므로 그 순간 평문 토큰이 응답 본문에 실린다.
                ProblemDetail.instance 를 고정값으로 명시하라(ProjectArchivedExceptionHandler.INSTANCE_PATH 선례).
                """.trimIndent(),
                offenders,
            ).isEmpty()
    }

    @Test
    fun `advice 스캔이 공허하지 않다 (vacuous 방어)`() {
        // 스캔 0건이면 축 2 의 "위반 없음"이 자동으로 참이 되어 봉인이 무력해진다.
        // (축 1 은 기대 표본이 비어 있지 않아 스캔 0건에서도 red 가 되므로, 이 축의 몫은 조기 진단이다.)
        assertThat(adviceClasses)
            .withFailMessage(
                "%s 에서 @ControllerAdvice 를 %d 개만 찾았다 — 스캔이 깨졌을 가능성이 높다.",
                SCANNED_PACKAGES.joinToString(),
                adviceClasses.size,
            ).hasSizeGreaterThanOrEqualTo(MIN_ADVICE_COUNT)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * `@RestControllerAdvice` 는 `@ControllerAdvice` 의 메타 어노테이션이므로
     * [AnnotatedElementUtils.findMergedAnnotation] 으로 속성을 병합해 읽는다(`value` ↔ `basePackages`
     * alias 도 여기서 흡수된다). 둘을 따로 처리하면 한쪽을 빠뜨린다.
     */
    private fun JavaClass.isEffectivelyBroad(): Boolean {
        val merged =
            AnnotatedElementUtils.findMergedAnnotation(reflect(), ControllerAdvice::class.java)
                ?: return false

        val noSelector =
            merged.basePackages.isEmpty() &&
                merged.basePackageClasses.isEmpty() &&
                merged.assignableTypes.isEmpty() &&
                merged.annotations.isEmpty()
        val matchesAllControllers =
            merged.annotations.any { it == RestController::class || it == Controller::class }
        val rootPackage = merged.basePackages.any { it in ROOT_PACKAGE_PREFIXES }

        return noSelector || matchesAllControllers || rootPackage
    }

    private fun JavaMethod.isProblemDetailExceptionHandler(): Boolean =
        isAnnotatedWith(ExceptionHandler::class.java) &&
            rawReturnType.isAssignableTo(ProblemDetail::class.java)

    /**
     * 핸들러가 직접 `instance` 를 설정했거나, **같은 클래스의 다른 메서드**(공통 `problem()` 헬퍼 패턴)가
     * 설정하면 통과로 본다 — automation 두 웹훅 컨트롤러가 그 형태다.
     */
    private fun JavaClass.setsInstanceFor(handler: JavaMethod): Boolean =
        handler.setsProblemDetailInstance() || methods.any { it.setsProblemDetailInstance() }

    /** Kotlin 의 `pd.instance = …` 는 `ProblemDetail.setInstance(URI)` 호출로 컴파일된다. */
    private fun JavaMethod.setsProblemDetailInstance(): Boolean =
        methodCallsFromSelf.any {
            it.name == SET_INSTANCE && it.targetOwner.isAssignableTo(ProblemDetail::class.java)
        }

    private companion object {
        /** `BtsApplication` 의 컴포넌트 스캔 대상과 같은 두 루트. 한쪽만 보면 BC 절반을 놓친다. */
        val SCANNED_PACKAGES = arrayOf("com.bts", "com.atlas.bts")

        /** 이 접두사를 `basePackages` 로 주면 좁힌 것이 아니다. */
        val ROOT_PACKAGE_PREFIXES = setOf("com", "com.bts", "com.atlas", "com.atlas.bts")

        const val SET_INSTANCE = "setInstance"

        /**
         * 실효 광역이 **허용된** advice 의 FQN 목록(단순명은 패키지 다른 동명 클래스를 구분 못 한다).
         * 추가하려면 축 1 실패 메시지의 지침을 따를 것.
         *
         * - `ProjectArchivedExceptionHandler` — `ProjectArchiveGuard` 가 던지는 cross-cutting 예외라
         *   `assignableTypes` 로 좁힐 대상이 없다(그 클래스 KDoc). `instance` 고정으로 유출을 막는다
         * - `WorkflowExceptionHandler` — 손수 만든 `ErrorResponse` 를 반환해 URI 필드 자체가 없다.
         *   축 2 는 `ProblemDetail` 반환 핸들러만 보므로 이 클래스에는 적용되지 않는다
         */
        val EXPECTED_BROAD_ADVICES =
            listOf(
                "com.bts.issue.project.archive.web.ProjectArchivedExceptionHandler",
                "com.bts.workflow.web.WorkflowExceptionHandler",
            )

        /**
         * 조립 전체 `@ControllerAdvice` 하한. 스캔이 깨졌을 때 조용히 통과하지 않도록 여유를 두고 고정한다
         * (정확한 개수를 박으면 advice 추가마다 무의미하게 깨진다 — 이 축이 지키는 것은 개수가 아니라
         * **스캔의 생존**이다).
         */
        const val MIN_ADVICE_COUNT = 35
    }
}
