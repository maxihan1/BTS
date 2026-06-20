// agile-planning BC 루트 패키지 문서 — 칸반 보드/컬럼 관리 Bounded Context 개요

/**
 * agile-planning 바운디드 컨텍스트(BC).
 *
 * 이 모듈은 칸반 보드 · 보드 컬럼 · 카드 배치를 담당하는 agile-planning BC의 루트 패키지다.
 *
 * ## 패키지 구조
 * - `domain` — Board/BoardColumn 엔티티 · 순수 도메인 로직
 * - `application` — BoardApplicationService (@Service, @Transactional)
 * - `repository` — jOOQ 기반 DB 접근 (@Component, jOOQ 생성 코드 접촉 유일 허용 레이어)
 * - `web` — BoardController (@RestController) · DTO
 * - `config` — Spring 설정 클래스
 *
 * ## BC 격리 정책
 * 이 BC는 다른 BC(issue-tracking · project-workflow · identity-access)의 내부 패키지를
 * 직접 import하지 않는다. cross-BC 통신은 shared-kernel 포트(`com.bts.shared.*`)를 통해서만 허용된다.
 * [com.bts.agileplanning.architecture.AgilePlanningBcArchTest]가 이 규칙을 자동 검증한다.
 *
 * ## Flyway 마이그레이션 범위
 * agile-planning 전용 V번호 범위는 V500~V599다. 다른 BC와 번호 충돌을 피하기 위해
 * `classpath:db/migration/agile-planning` 경로를 사용한다.
 */
package com.bts.agileplanning
