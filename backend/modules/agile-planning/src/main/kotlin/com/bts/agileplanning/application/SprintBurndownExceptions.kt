// 스프린트 번다운 애플리케이션 계층 예외 — agile-planning BC (FR-RP-01)
// MatchingDeclarationName: 파일명은 복수형(Exceptions)으로 "번다운 예외 모음" 의미를 표현하고,
// 현재는 SprintDatesRequiredException 1개만 정의한다. SprintExceptions.kt(FR-BL-02) 선례와 동일 이유로
// 향후 번다운 관련 예외 추가를 수용하기 위해 파일명을 유지하고 ktlint/detekt 규칙을 억제한다.
@file:Suppress("ktlint:standard:filename", "MatchingDeclarationName")

package com.bts.agileplanning.application

/**
 * 스프린트의 start_date 또는 end_date 가 설정되지 않아 번다운을 계산할 수 없을 때 던지는 예외.
 *
 * 시간축 정박점(start~end)이 없으면 Ideal/Actual 라인을 산출할 수 없다(스펙 S3).
 * 422 응답 매핑은 `SprintExceptionHandler`(Task 5)에서 수행한다 — 이 파일은 예외 타입 정의만 담당한다.
 *
 * @param message 오류 설명 메시지.
 */
class SprintDatesRequiredException(message: String) : RuntimeException(message)
