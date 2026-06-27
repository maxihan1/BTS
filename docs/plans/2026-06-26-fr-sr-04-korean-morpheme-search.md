# FR-SR-04 — 한글 형태소 기반 전문 검색

> slug: fr-sr-04-korean-morpheme-search
> type: migration (실제 복합: db + backend)
> agent: db-engineer (+ backend-engineer, task별 plan에서 지정)
> 생성: 2026-06-26

## Brief

FR-SR-04 한글 형태소 기반 전문 검색 (PostgreSQL FTS tsvector + GIN 인덱스).

**핵심 긴장점 (spec에서 결정).**
- SDD 10.2 원안: `to_tsvector('simple')` (형태소 분석 없는 단순 토큰화) + pg_trgm 보강.
- product §2.4 D2: "Mecab-ko vs Lucene-Kr 비교 후 선택" (외부 형태소 분석기 도입).
- BTS 기조: 의존성 0 (AQL 파서 손수, Gantt 자체 SVG, ClamAV raw). 외부 형태소 분석기 = PostgreSQL extension + 사전 + Docker 이미지 커스텀 = 큰 결정.

**선행 완료 현황.**
- FR-SR-02 (AQL, `~` 텍스트 검색) 완료 — `issues.summary` 표현식 trigram GIN 인덱스(`gin(lower(summary) gin_trgm_ops)`) 이미 존재.
- 검색 물리 구현은 issue-tracking 모듈 (BC 격리: search 모듈은 jOOQ 직접 접근 불가, IssueSearchPort 계약).

## 도메인 정리

- **BC**: 논리 search-export-import / 물리 issue-tracking (FR-SR-01/02 패턴 계승, 새 BC 신설 0)
- **영향 엔티티**: Issue (issues 테이블 — search_vector tsvector 컬럼 신규)
- **새 용어**: `search_vector`(이슈 제목+본문 결합 tsvector 색인). 기존 glossary의 FTS/AQL 하위 개념 — 머지 시 동기화
- **Maxi 결정 (2026-06-26)**:
  - 형태소 방식 = **simple tsvector + pg_trgm** (외부 형태소 분석기 미도입, zero-dep, SDD 10.2 채택)
  - 검색 대상 = **summary + description** (제목 + 본문 결합 색인)
- **기존 결정 충돌/deviation**:
  - product §2.4 D2 "Mecab-ko vs Lucene-Kr 도입" → 본 작업 ADR로 superseded (FR-SR-02가 SDD ANTLR superseded한 것과 동형)
  - V006 주석 "description FTS=search BC 담당, tsvector 인덱스 추가 금지" → 지금(FR-SR-04)이 추가 시점, 설계 의도와 정합
- **전수 동기화 대상 (impl/머지)**: SDD 10.2 + product §2.4 + fr-index FR-SR-04 표기 → simple+trigram, Mecab deviation 주석. verify-master-plan
- **관련 ADR**: [docs/decisions/2026-06-26-fr-sr-04-korean-fts.md](../decisions/2026-06-26-fr-sr-04-korean-fts.md) (생성됨)
- **spec에서 확정할 사항**: (1) AQL 통합 방식(`~` 의미 확장 vs 신규 텍스트 경로), (2) search_vector 갱신 방식(generated column vs 트리거 vs 쿼리타임)

## 스펙

전체 스펙. [docs/specs/2026-06-26-fr-sr-04-korean-morpheme-search.md](../specs/2026-06-26-fr-sr-04-korean-morpheme-search.md)

핵심 시나리오 3줄 요약.
- `text ~ "검색어"` 신규 AQL 필드 → summary+description 전문검색(`search_vector @@ plainto_tsquery('simple')` OR trigram 부분일치).
- `search_vector`는 issues의 STORED generated column(V032) — 앱 코드 무변경, DB 자동 색인.
- visibility 보안 술어 자동 AND(FR-SR-02 패턴), 기존 `summary ~`(V031 trigram) 무변경(회귀 0).

### ❓ Brainstorming 발견 (plan에서 처리)
- **G1 (spec 반영 완료)**: "형태소 분리 30개"를 simple+trigram 현실에 맞게 재정의(조사변형/부분문자열, 활용형은 기대 미매칭 명시). plan 테스트 케이스 설계 시 반영.
- **G2 (spec 반영 완료)**: 관련도(ts_rank) 정렬 범위 외(NFR-6).
- **G3 (plan)**: `GENERATED STORED` 컬럼 추가 = 기존 행 테이블 rewrite + lock. 이슈 수 규모 영향 검토 + 마이그레이션 주석 명시.
- **G4 (plan/게이트1)**: PR 범위 — 백엔드 D1~D5만 vs D6/D7(프론트 syntax highlight `text` 키워드 + E2E) 포함. FR-SR-02 선례=백엔드 먼저 분리 권장. plan에서 범위 제안.
- **G5 (plan, 마이너)**: 빈 검색어(`text ~ ""`)=결과 0 정책 권장. 마크다운 기호 색인(simple이 구두점 제거).

## Brainstorming Check

✅ 통과 (비판적 self-review 1회, gap 5건 — G1/G2 spec 보강, G3/G4/G5 plan 이월)

## Plan

> **PR 범위 (G4 결정)**. 이 PR = 백엔드 D1~D5. D6/D7(프론트 syntax highlight `text` 키워드 + E2E)은 후속 PR(FR-SR-02 선례 동일). 게이트1 Maxi 확인.

### Task 1. AqlFields에 `text` 가상 필드 추가 (`~` 연산자만 허용)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/search/AqlFields.kt`, `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/search/AqlFieldsTest.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/aql/AqlParserTest.kt`]
- depends-on: []

**RED**:
- `AqlFieldsTest`: `classify("text")` == SUPPORTED 기대 / `isOperatorForbidden("text", EQ)` == true(=,!=,IN,NOT_IN 금지) / `isOperatorForbidden("text", CONTAINS)` == false 단언 → 실패(text 미등록).
- `AqlParserTest`: `text ~ "검색어"` 파싱 → `Comparison(AqlField("text"), CONTAINS, [Str("검색어")])` / `text = open` → AqlSyntaxException(연산자 제약) 단언 → 실패.

**GREEN**:
- `AqlFields.MVP_FIELDS` += `"text"`.
- `FIELD_OPERATOR_CONSTRAINTS["text"]` = `setOf(EQ, NEQ, IN, NOT_IN)` (즉 `~`만 허용 — FTS 전용 가상 필드).
- 파서는 AqlFields 참조하므로 자동 통과(별도 구현 없음, 테스트가 회귀 가드).

**REFACTOR**: AqlFields KDoc에 `text`(가상 FTS 필드, summary+description 대상) 설명 추가. (NIT: AqlFieldsTest는 신규 파일 — 기존 필드(status/label/summary/priority) 동작도 함께 커버해 thin test 회피.)

**검증**: `./gradlew :backend:shared-kernel:test :backend:search-export-import:test --tests "*AqlFieldsTest" --tests "*AqlParserTest"`

---

### Task 2. V032 — `search_vector` generated column + GIN + trigram 인덱스 + init_codegen 미러

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/issue-tracking/src/main/resources/db/migration/issue-tracking/V032__issues_search_vector_fts.sql`, `backend/modules/issue-tracking/src/main/resources/db/codegen/init_codegen.sql`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/migration/IssueSearchVectorMigrationTest.kt`]
- depends-on: []

**RED**:
- 마이그레이션 테스트(Testcontainers 실DB): `issues.search_vector` 컬럼 존재 + `idx_issues_search_vector` GIN + `idx_issues_description_trgm` GIN 존재 + 행 INSERT 시 `search_vector`가 **DDL과 동일한 표현식**(`to_tsvector('simple', coalesce(summary,'')||' '||coalesce(description,''))`)으로 자동 산출 단언 → 실패. (NIT: RED 단언식을 DDL과 일치 — coalesce 포함)
- **[B3] write-path 회귀 테스트**: V032 적용 후 `IssueRepository.insert(issue)`(record 기반 `set(record)`, `IssueRepository.kt:238`) → 재조회 성공 단언 → (jOOQ가 generated 컬럼을 readonly로 안 빼면 `cannot insert non-DEFAULT into GENERATED column`으로 실패).

**GREEN**:
- V032 DDL: `ALTER TABLE issues ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (to_tsvector('simple', coalesce(summary,'')||' '||coalesce(description,''))) STORED;` (2-인자형=IMMUTABLE + coalesce/`||` immutable → generated 가능)
- `CREATE INDEX idx_issues_search_vector ON issues USING gin (search_vector);`
- **[B1 수정] description trigram 인덱스**: `CREATE INDEX idx_issues_description_trgm ON issues USING gin (lower(description) gin_trgm_ops);` — **coalesce 없이 V031 summary와 동형**. jOOQ `DESCRIPTION.likeIgnoreCase`가 `lower("description") like ?`를 생성하므로 표현식이 정확히 일치해야 인덱스 생존(coalesce 넣으면 표현식 불일치→죽은 인덱스→OR 전체 seq scan). summary는 V031 기존.
- **[B3 폴백] jOOQ generated 컬럼 write-path**: jOOQ codegen이 STORED generated를 readonly로 탐지하는지 검증. 미탐지 시 폴백 — codegen `database.excludes`에 search_vector 추가 **또는** 런타임 `Settings.withReadonlyInsert(IGNORE).withReadonlyUpdate(IGNORE)`. write-path 회귀 테스트(RED) green까지.
- `init_codegen.sql` 미러: search_vector 컬럼 + 두 인덱스(pg_trgm 확장 이미 상단). V006 `description` 컬럼 주석("tsvector 금지") 정정.

**REFACTOR**: V032 L1 주석 + generated column 테이블 rewrite(기존 행 재작성, ACCESS EXCLUSIVE lock — 1K 규모 이슈 수에서 마이그레이션 순간 락) 영향 명시(G3).

**검증**: `./gradlew :backend:issue-tracking:test --tests "*IssueSearchVectorMigrationTest" --tests "*IssueRepository*"` (write-path 회귀 포함 — 좁은 필터로 INSERT 경로 누락 금지)

---

### Task 3. `searchByAql`의 `text` → FTS + trigram 변환 (jOOQ)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/repository/IssueRepository.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/repository/IssueSearchAqlTextTest.kt`]
- depends-on: [1, 2]

**RED**:
- 통합 테스트(실DB): 본문에만 "토큰 만료"가 있는 이슈를 `text ~ "토큰 만료"`로 검색 → 매칭 / `text ~ ""`(빈) → 결과 0 단언 → 실패(text 분기 부재, repository IllegalArgument 또는 미매칭).

**GREEN**:
- `buildComparisonCondition`(IssueRepository.kt ~2334)에 `text` 필드 분기 추가. CONTAINS:
  - `DSL.condition("issues.search_vector @@ plainto_tsquery('simple', {0})", bind)` **OR** trigram 부분일치 — **쿼리 표현식을 인덱스와 일치**: `ISSUES.SUMMARY.likeIgnoreCase("%esc%")` OR `ISSUES.DESCRIPTION.likeIgnoreCase("%esc%")` (둘 다 `lower(col) like ?` 생성 → V031/idx_issues_description_trgm와 정확 일치). `%`/`_`/`\` 이스케이프 = 기존 `escapeIlikePrefix`(`:2215`, 컬럼 무관 generic) 재사용.
  - 빈/공백 검색어 → 매칭 0 Condition(`DSL.falseCondition()`)(G5).
- **[C1] text 정렬 거부**: `text`는 정렬 대상 컬럼이 없음. `buildOrderBy` 화이트리스트(`:2486`)에 추가 금지 + 파서/검증 단계에서 `ORDER BY text`를 **400으로** 거부(현재 미거부 시 catch-all 500 — `SearchExceptionHandler`에 IllegalArgumentException 핸들러 부재 `:64-275`). 정렬가능 필드와 검색가능 필드 분리.
- **[C3] content 쿼리 search_vector 제외**: content SELECT(`ISSUES.fields()`, `:2284`)가 tsvector 전송하지 않도록 명시 컬럼 선택 또는 search_vector 제외(페이로드 절감, 기능 무영향).
- 기존 summary/label/priority/status 분기 무변경(회귀 0).

**REFACTOR**: text FTS 변환을 `buildTextSearchCondition(strValue)` 헬퍼로 추출 + KDoc(FTS+trigram 하이브리드 근거 ADR 링크).

**검증**: `./gradlew :backend:issue-tracking:test --tests "*IssueSearchAqlTextTest"`

---

### Task 4. 한글 검색 케이스 30개 + EXPLAIN 인덱스 + 보안 통합 테스트

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/repository/IssueSearchKoreanFtsTest.kt`]
- depends-on: [3]

**RED→GREEN** (검증 task, 실DB):
- **케이스 30개**: (a) 조사 변형("이슈를"↔"이슈"), (b) 제목/본문 부분 문자열, (c) 영문·숫자·한글 혼용, (d) 다중 토큰 AND. **기대 미매칭** 케이스(어간 변화 활용형 "먹었다"↔"먹는다")는 `assertThat(...).isEmpty()`로 명시(spec 완료기준 #2).
- **[C2] vacuous green 차단**: 각 "기대 미매칭" 부정 케이스에는 **동일 시드 데이터로 매칭되는 양성 대조군**을 짝지어, isEmpty()가 죽은 인덱스/쿼리 에러/빈 DB 같은 엉뚱한 이유로 비는 것을 배제.
- **[B2 강화] NFR-1 EXPLAIN**: (1) tsvector로는 불가하고 **조사 변형 trigram으로만 매칭되는** 검색어로 EXPLAIN → `idx_issues_description_trgm`이 plan에 **개별 인덱스명으로** 나타남을 단언. (2) `@@` 경로는 `idx_issues_search_vector` 단언. **`SET enable_seqscan=off` 금지**(가짜 통과), 충분한 행 seed로 플래너가 인덱스 선택하게.
- **보안(S4)**: BROWSE 없는 actor → SecurityException(probe 차단) / 접근 불가 보안등급 이슈 결과 제외(visibility AND 우회 불가).
- **[EC5/C1] 미지원 연산자·정렬**: `text = "x"` → 400/IllegalArgument. `text ~ "x" ORDER BY text` → 400(catch-all 500 아님).
- **[C4] NFR-2/EC3/EC6**: 특수문자·SQL 메타문자(`'`, `%`, `_`, `;`, `--`) 포함 검색어 → 인젝션 0 + tsquery 500 누출 0(plainto_tsquery 안전 파싱). 2000자 초과 → DoS 가드.

**검증**: `./gradlew :backend:issue-tracking:test --tests "*IssueSearchKoreanFtsTest"`

---

### Task 5. 문서 전수 동기화 (SDD deviation + product 결정 명시)

**메타**.
- agent: `backend-engineer`
- files: [`docs/sdd/10-search-export-import.md`, `docs/plan/product/search-export-import.md`]
- depends-on: []

**RED→GREEN** (문서):
- SDD 10.2: simple tsvector + pg_trgm + `text` AQL 필드 명세 보강, "Mecab-ko/Lucene-Kr" → 본 작업 ADR로 superseded deviation 주석.
- product §2.4: D2 결정 명시(simple+trigram, text 필드), D1~D5 [x] 마킹은 머지 게이트에서.
- FR 카운트 불변(추가/삭제 0) → fr-index/README/CLAUDE 카운트 무변경.

**검증**: `bash scripts/verify-master-plan.sh` 통과.

## Plan 메타

- task 수: 5
- 예상 wave: 3 (Wave1: T1·T2·T5 병렬 / Wave2: T3 / Wave3: T4)
- TDD 강제: yes (T1~T4 RED→GREEN→REFACTOR, T5 문서)
- 병렬 dispatch: depends-on + files 교집합 기준. T3·T4는 issue-tracking 모듈이나 파일 분리 + depends 직렬.
- 추가 검증: ktlint, **detekt(baseline 동결만 — 신규 `DSL.condition` 코드가 새 findings 만들면 헤더 단축/구조 수정으로 해소, baseline에 묻지 말 것, C5)**, ArchUnit BC 경계(신규 cross-BC import 0 확인, C5), Testcontainers 통합(실DB), verify-master-plan
- 회귀 가드: 기존 `summary ~`(V031 trigram) + FR-SR-02 단위/통합 무변경 확인

## 리뷰 결과

### 독립 eng plan 리뷰 (적대적, 2026-06-26) — 판정: BLOCKED → plan 반영 완료

**BLOCKER 3건 (전부 plan 수정 반영).**
- **B1** description trigram 표현식 불일치(`lower(coalesce(...))` ≠ 쿼리 `lower(description)`) → 죽은 인덱스 + OR 전체 seq scan. plan 본문 자체 모순. → **Task 2**: `gin(lower(description) gin_trgm_ops)` coalesce 제거(V031 동형), **Task 3**: 쿼리 `DESCRIPTION.likeIgnoreCase`로 표현식 일치.
- **B2** EXPLAIN "또는" 단언이 죽은 description 인덱스를 못 잡음(`@@`는 항상 search_vector 사용). → **Task 4**: 조사변형 trigram-전용 케이스로 `idx_issues_description_trgm` 개별 인덱스명 단언 + seqscan off 금지.
- **B3** generated column이 jOOQ record 기반 INSERT(`set(record)`, `IssueRepository.kt:238`)를 깨면 이슈 생성 전수 500. 검증 task 0개. → **Task 2**: create→재조회 write-path 회귀 테스트 + jOOQ readonly 미탐지 시 폴백(codegen excludes 또는 `Settings.withReadonlyInsert/Update(IGNORE)`) 명문화. 검증 필터 확대.

**CONCERN 5건 (반영).**
- **C1** `text ~ ORDER BY text` → catch-all 500(정렬 화이트리스트 부재 + IllegalArgumentException 핸들러 부재). → Task 3/4 정렬 거부 400.
- **C2** 부정 케이스 vacuous green → Task 4 양성 대조군 짝.
- **C3** content 쿼리 `ISSUES.fields()`가 tsvector 전송 → Task 3 명시 컬럼/제외.
- **C4** 인젝션/2000자 테스트 부재 → Task 4 추가.
- **C5** detekt baseline 오용 + ArchUnit 미명시 → Plan 메타 명시.

**PASS 확인된 가정(리뷰가 코드로 검증).** to_tsvector 2-인자형 IMMUTABLE+coalesce/`||` → generated 가능 / BC 격리(text=AqlFields 단일출처) / escapeIlikePrefix 컬럼무관 재사용 / depends-on·wave 정합 / Task 1 RED 진정성(vacuous 아님).

**재판정.** BLOCKER 3·CONCERN 5 전부 plan 반영 완료 → 구현 착수 가능(PASS-with-fixes). 구현 중 B3 폴백 실제 동작은 write-path 테스트로 확정.

### ceo 관점 (요약)
- 가치/범위는 Maxi 사전 확정(형태소 분석기 미도입 = zero-dep 철학 일관, PR=백엔드 D1~D5 분리 = FR-SR-02 선례). 추가 taste decision 없음.
