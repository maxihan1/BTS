// 선택자 없는(전역) @ControllerAdvice 를 전수 열거해 미등재를 실패시키는 봉인 (N3)

package com.bts.issue.architecture

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.web.bind.annotation.ControllerAdvice

/**
 * **선택자 없는 `@ControllerAdvice` 봉인 (N3).**
 *
 * ## 왜 선택자 없는 advice 가 위험한가
 * `@ControllerAdvice` 에 `basePackages` / `basePackageClasses` / `assignableTypes` / `annotations` 중
 * 어느 것도 주지 않으면 **레포의 모든 컨트롤러**에 붙는다. `BtsApplication` 은 `com.bts` 와
 * `com.atlas.bts` 를 **둘 다** 스캔하므로 issue-tracking 의 전역 advice 가 identity-access 컨트롤러까지 덮는다.
 *
 * 그 상태에서 advice 가 [org.springframework.http.ProblemDetail] 을 `instance` 미설정으로 반환하면,
 * Spring 이 `instance` 를 **요청 URI 로 자동으로 채운다**(#310 실측 확정). BTS 에는 경로 세그먼트에
 * 원문 비밀 토큰을 싣는 경로가 4개 있어(공유 대시보드·iCal 피드·git 웹훅·automation 웹훅) 그 순간
 * 평문 토큰이 응답 본문에 실린다(`DEVELOPMENT.md §1.1-1·§1.1-2`).
 *
 * ## 개수를 세지 않고 **열거한다**
 * `guard-handler-matrix-blindfold` 의 교훈 — 개수 단언은 "행 집합 자체가 눈가리개"가 된다.
 * 이 테스트는 전역 advice 를 **전수 열거**하고 [EXPECTED_GLOBAL_ADVICES] 에 없는 것이 있으면 **실패**한다.
 * 새 전역 advice 를 추가하면 표본에 등재해야만 초록이 되고, 등재하려면 `instance` 처방을 검토하게 된다.
 *
 * ## vacuous 방어
 * 스캔이 0건이어도 "미등재 없음"은 참이라 조용히 통과한다. 그래서 **advice 총 개수의 하한**을 별도로
 * 단언한다([adviceScanIsNotVacuous]) — 임포트가 깨지거나 패키지가 바뀌면 그쪽이 먼저 빨강이 된다.
 * (`archunit-vacuous-rule-silent-pass` — 룰 추가 시 일부러 위반을 넣어 fail 을 확인했다.)
 *
 * ## 스코프
 * BC 격리에 따라 **issue-tracking(`com.bts.issue`)만** 스캔한다. 다른 BC 에도 같은 봉인이 필요하면
 * 그 BC 의 PR 에서 이 파일을 복제한다. 현재 레포 전체의 선택자 없는 advice 는 2개이고
 * (`ProjectArchivedExceptionHandler` · project-workflow 의 `WorkflowExceptionHandler`),
 * 후자는 손수 만든 `ErrorResponse` 를 반환해 URI 필드 자체가 없다 — 유출 통로가 아니다.
 */
class GlobalControllerAdviceSealTest {
    private val adviceClasses: List<JavaClass> =
        ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(SCANNED_PACKAGE)
            .filter { it.isMetaAnnotatedWith(ControllerAdvice::class.java) }
            .toList()

    @Test
    fun `선택자 없는 전역 advice 는 기대 표본에 등재된 것만 존재한다 (미등재=실패)`() {
        val globalAdviceNames = adviceClasses.filter { it.isGlobalScoped() }.map { it.simpleName }.sorted()

        assertThat(globalAdviceNames)
            .withFailMessage(
                """
                선택자 없는(전역) @ControllerAdvice 가 기대 표본과 다르다.
                  발견: %s
                  기대: %s

                새 전역 advice 를 추가했다면 — 그 advice 가 ProblemDetail 을 반환하는지 확인하고,
                반환한다면 instance 를 **반드시 명시**한 뒤(ProjectArchivedExceptionHandler 선례)
                이 표본에 등재하라. instance 를 비우면 Spring 이 요청 URI 로 자동 채워
                경로 토큰 4경로에서 평문 토큰이 응답 본문에 실린다.
                """.trimIndent(),
                globalAdviceNames,
                EXPECTED_GLOBAL_ADVICES,
            ).containsExactlyElementsOf(EXPECTED_GLOBAL_ADVICES)
    }

    @Test
    fun `advice 스캔이 공허하지 않다 (vacuous 방어)`() {
        // 스캔 0건이면 위 테스트의 "미등재 없음"이 자동으로 참이 되어 봉인이 무력해진다.
        // 임포트 경로·패키지명이 바뀌면 이 단언이 먼저 빨강이 되어 원인을 가리킨다.
        assertThat(adviceClasses)
            .withFailMessage(
                "%s 에서 @ControllerAdvice 를 %d 개만 찾았다 — 스캔이 깨졌을 가능성이 높다.",
                SCANNED_PACKAGE,
                adviceClasses.size,
            ).hasSizeGreaterThanOrEqualTo(MIN_ADVICE_COUNT)
    }

    /**
     * `basePackages`(=`value`) · `basePackageClasses` · `assignableTypes` · `annotations` 가 **전부 비면**
     * 전역 스코프다. `@RestControllerAdvice` 는 `@ControllerAdvice` 의 메타 어노테이션이므로
     * [AnnotatedElementUtils.findMergedAnnotation] 으로 속성을 병합해 읽는다 — 둘을 따로 처리하면
     * 한쪽을 빠뜨린다.
     */
    private fun JavaClass.isGlobalScoped(): Boolean {
        val merged =
            AnnotatedElementUtils.findMergedAnnotation(reflect(), ControllerAdvice::class.java)
                ?: return false
        return merged.basePackages.isEmpty() &&
            merged.basePackageClasses.isEmpty() &&
            merged.assignableTypes.isEmpty() &&
            merged.annotations.isEmpty()
    }

    private companion object {
        const val SCANNED_PACKAGE = "com.bts.issue"

        /**
         * 전역 스코프가 **허용된** advice 목록. 추가하려면 위 실패 메시지의 지침을 따를 것.
         *
         * `ProjectArchivedExceptionHandler` 가 전역인 이유는 그 클래스 KDoc 참조 —
         * `ProjectArchiveGuard` 가 던지는 cross-cutting 예외라 `assignableTypes` 로 좁힐 대상이 없다.
         * 대신 `@ExceptionHandler` 를 단일 예외 타입으로 한정하고 `instance` 를 고정해 유출을 막는다.
         */
        val EXPECTED_GLOBAL_ADVICES = listOf("ProjectArchivedExceptionHandler")

        /**
         * issue-tracking 의 `@ControllerAdvice` 하한. 현재 실측 21개이며, 스캔이 깨졌을 때
         * 조용히 통과하지 않도록 여유를 두고 고정한다(정확한 개수를 박으면 advice 추가마다 이 테스트가
         * 무의미하게 깨진다 — 이 축이 지키는 것은 개수가 아니라 **스캔의 생존**이다).
         */
        const val MIN_ADVICE_COUNT = 15
    }
}
