<!-- FR-SR-02 AQL 파서 구현 방식 + BC 경계 + cross-BC 검색 포트 결정 ADR -->

# ADR — FR-SR-02 AQL 텍스트 쿼리: 파서 방식 + BC 경계 + 검색 포트

- 날짜: 2026-06-25
- 상태: 채택 (Accepted)
- 관련 FR: FR-SR-02
- 관련 PR: #189
- 선행 ADR: [2026-06-23-fr-sr-01-issue-filter-bc.md](2026-06-23-fr-sr-01-issue-filter-bc.md)

## 맥락 (Context)

FR-SR-02는 JQL 호환 텍스트 쿼리 언어 AQL(Atlas Query Language)이다. SDD 10.1은 두 가지를 명세한다.

1. **"JQL과 99% 호환되는 쿼리 언어. ANTLR 4로 구현."** (10.1)
2. **AST → jOOQ Condition 변환** 예시를 검색 모듈 내부에서 수행하는 것으로 그림 (10.1.4)

그러나 BTS 코드베이스 조사 결과 두 명세 모두 현 아키텍처와 충돌한다.

- **ANTLR 4 미도입.** 빌드에 ANTLR 의존성/플러그인이 없다. ANTLR은 새 빌드 의존성(생성코드 빌드 통합 + 학습비용)이며, BTS는 일관되게 무거운 도구 도입을 회피해 왔다(Kafka→pgmq, OpenSearch→PostgreSQL FTS, SDD 03/16장). 직전 FR-SR-01의 필터 파서(`IssueFilterQueryParser`)도 외부 라이브러리 없이 손수 작성됐다.
- **BC 격리 + jOOQ 코드 모듈 격리.** jOOQ 생성 코드(`com.bts.issue.jooq.Tables.ISSUES` 등)는 issue-tracking 모듈 전용이다. ArchUnit이 BC 간 직접 import와 타 모듈 jOOQ 접근을 빌드 시점에 차단한다(`IssueBcArchTest`, `AgilePlanningBcArchTest`). 따라서 신설 search-export-import 모듈이 직접 jOOQ로 이슈 테이블을 쿼리하는 것은 구조적으로 불가능하다. cross-BC 데이터 접근은 shared-kernel 포트를 통해야 한다(선례: `BoardIssueLookupPort`).

또한 FR-SR-01 ADR(D1/D3)이 이미 다음을 예고했다.

- "`search-export-import` 모듈은 FR-SR-02(AQL 파서) 시점에 본격 신설한다."
- "임의 AND/OR 표현식 트리(괄호 중첩)는 FR-SR-02(AQL 파서)의 영역으로 명확히 분리한다."

## 결정 (Decision)

### D1. 파서 — 손수 작성 재귀하강 파서 (ANTLR 미도입)

AQL 파서는 ANTLR 없이 손수 작성한 렉서(lexer) + 재귀하강(recursive descent) 파서로 구현한다.
새 빌드 의존성을 추가하지 않는다(zero-dep).

근거: MVP 문법(아래 D3)이 단순해 재귀하강으로 충분하며, FR-SR-01 파서 + BTS 미니멀 인프라 철학과 일관된다.
SDD 10.1의 "ANTLR 4로 구현"에서 deviation한다 — 본 ADR이 그 deviation의 정본 근거다.

### D2. 모듈 경계 — search-export-import 신설, 단 jOOQ 변환은 issue-tracking

- **search-export-import 모듈 신설(7번째 BC).** AQL 렉서/파서 + AST 구성(텍스트 → AST) + 검색 API 엔드포인트(`POST /api/v1/search/aql`)를 소유한다. AQL "언어" 책임.
- **issue-tracking이 AST → jOOQ 변환 + visibility 보안 + 실행을 담당.** jOOQ 생성 코드가 issue-tracking 전용이므로 BC 격리상 변환은 여기 둘 수밖에 없다. AQL "데이터 접근" 책임.
- **shared-kernel에 신규 `IssueSearchPort` 계약.** search 모듈이 AST(+viewer+projectKey)를 넘기면 issue-tracking 어댑터가 실행해 결과를 돌려준다. 선례 `BoardIssueLookupPort`와 동형.

즉 SDD 10.1.4의 "검색 모듈 내부 AST→jOOQ"는 BC 격리에 의해 "search=AST 생성 / issue-tracking=AST→jOOQ 실행(포트)"로 분할된다.

### D3. MVP 범위 — 핵심 키워드만, 함수/상대날짜/형태소/pg_trgm은 후속

이번 PR(백엔드 코어 MVP)이 지원하는 AQL 문법.

- 비교: `=`, `!=`, `IN`, `not in`, `~`(부분 일치)
- 논리: `AND`, `OR`, `NOT` + 괄호 중첩(임의 불리언 트리)
- 정렬: `ORDER BY <field> [ASC|DESC]`
- 필드: status(current_state_key), assignee, label, component, summary, project (issue-tracking 기존 검색 가능 필드)

**후속 PR로 명시 분리.** `currentUser()`/`now()`/`startOfDay()` 등 함수, 상대 날짜(`-7d`/`+1w`), `is EMPTY`/`is NOT EMPTY`, pg_trgm 유사도, 한글 형태소(FR-SR-04), 프론트 입력창+syntax highlight(D6/D7).

### D4. SR-01(필터)과의 관계 — 무변경, 책임 분리

기존 `GET /api/v1/issues?filter=`(FR-SR-01)는 **그대로 둔다.** 이 엔드포인트는 "이슈 목록 정본 조회 API"(필터는 부가)로서 issue-tracking의 기본 책임이며, 보드/목록 페이지가 데이터 공급원으로 사용한다. AQL은 별도의 "검색 도구"로 search 모듈이 소유한다.

검색 jOOQ 로직의 통합은 엔드포인트 이동 없이 달성된다 — AQL의 AST→Condition 변환이 issue-tracking `IssueRepository`의 기존 visibility 보안 술어(`buildSecurityCondition`, `IssueSecurityDirectory.accessibleLevels`)를 그대로 재사용한다. 필터와 AQL이 같은 보안 술어·같은 모듈에서 수렴한다.

진입 계약은 용도별 분리. `BoardIssueLookupPort`(보드/목록) + 신규 `IssueSearchPort`(AQL).

### D5. 권한/가시성 — visibility 보안 술어 자동 AND 결합

SDD 10.1.4 "권한 필터는 항상 자동 추가" 원칙을 유지한다. AQL이 어떤 조건을 표현하든 visibility 보안 술어(actor가 접근 가능한 보안 등급)가 항상 AND로 결합되어 우회 불가하다(FR-SR-01 패턴 동일). 사용자 입력 AST는 보안 술어 "위"에만 얹힌다.

## 결과 (Consequences)

- search-export-import 모듈이 처음 부트스트랩된다(Gradle 설정 + ArchUnit BC 격리 룰 + 통합 테스트 부팅). 향후 Export/Import/REST API FR의 본거지.
- shared-kernel에 `IssueSearchPort`(+ AST/계약 타입) 추가. shared-kernel 경계 가드(`SharedKernelBoundaryArchTest`) 통과 필요(BC 역참조 금지).
- AQL 문법 확장(함수/상대날짜 등)은 손수 작성 파서에 직접 반영한다(후속 PR). ANTLR 부재로 문법은 코드로 관리된다.
- fr-index의 FR-SR-02 BC 매핑(search-export-import) 무변경. SR-01 물리 위치(issue-tracking) 무변경. 카운트 영향 0.
- SDD 10.1 "ANTLR 4" 표기는 본 ADR로 superseded — SDD 갱신 시 deviation 주석 반영(전수 동기화).

## 대안 (Rejected)

- **ANTLR 4 채택(SDD 원안).** 기각 — 새 빌드 의존성 + 생성코드 빌드 통합 비용. MVP 문법은 재귀하강으로 충분. BTS 미니멀 인프라 철학과 충돌.
- **검색 모듈이 직접 jOOQ로 이슈 테이블 쿼리(SDD 10.1.4 그림 직역).** 기각 — BC 격리 + jOOQ 모듈 격리 위반(ArchUnit 빌드 실패). 불가능.
- **기존 BoardIssueLookupPort + BoardCardFilter 재사용.** 기각 — BoardCardFilter는 "필드 내 OR + 필드 간 AND"의 납작한 필터라 AQL의 임의 중첩 불리언 트리를 표현할 수 없다. 신규 `IssueSearchPort` 필요.
- **검색 정본 엔드포인트를 search로 이전(GET /issues 필터 떼기).** 기각(이번 범위) — 프론트 호출처 변경 동반 + 과도기 중복 엔드포인트 부채. "이슈 목록 조회"는 issue-tracking 기본 책임이라 잔류가 책임 정합상 옳다.
