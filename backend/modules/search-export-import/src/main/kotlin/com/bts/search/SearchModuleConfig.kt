// search-export-import BC 스프링 설정 — AQL 텍스트 쿼리(JQL 호환) 모듈 진입점

package com.bts.search

import org.springframework.context.annotation.Configuration

/**
 * search-export-import BC 스프링 설정 클래스.
 *
 * 이 클래스는 search-export-import BC의 Spring 컨텍스트 진입점이다.
 * AQL(Atlas Query Language) 파서, 검색 API 컨트롤러 등의 Bean이
 * 이 모듈 컨텍스트 안에서 등록된다.
 *
 * ### 모듈 책임
 * - AQL 렉서/파서 (텍스트 → AST)
 * - 검색 API 엔드포인트 (`POST /api/v1/search/aql`)
 * - shared-kernel [com.bts.shared.search.IssueSearchPort]를 통해 이슈 검색 위임
 *
 * ### BC 격리
 * 이 모듈은 issue-tracking·project-workflow·identity-access·agile-planning·notification
 * BC 내부 패키지를 직접 import하지 않는다. jOOQ도 사용하지 않는다.
 * AST → jOOQ Condition 변환은 issue-tracking IssueSearchAdapter가 전담한다.
 * (ADR 2026-06-25-fr-sr-02-aql-parser-and-bc §D2)
 */
@Configuration(proxyBeanMethods = false)
class SearchModuleConfig
