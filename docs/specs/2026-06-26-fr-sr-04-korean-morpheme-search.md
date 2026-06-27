<!-- FR-SR-04 한글 형태소 기반 전문 검색 스펙 — simple tsvector+pg_trgm, AQL text 필드, generated column -->

# FR-SR-04 — 한글 형태소 기반 전문 검색 스펙

- 날짜: 2026-06-26
- FR: FR-SR-04 (search-export-import BC / 물리 issue-tracking)
- 선행: FR-SR-02(AQL 파서·`~` 검색), FR-SR-01(필터)
- 관련 ADR: [docs/decisions/2026-06-26-fr-sr-04-korean-fts.md](../decisions/2026-06-26-fr-sr-04-korean-fts.md)

## 결정 요약 (Maxi 확정 2026-06-26)

| 항목 | 결정 |
|---|---|
| 형태소 방식 | `to_tsvector('simple')` + pg_trgm 보강 (외부 형태소 분석기 미도입, zero-dep) |
| 검색 대상 | `issues.summary` + `issues.description` (제목 + 본문) |
| AQL 노출 | 신규 `text` 필드 — `text ~ "검색어"` (기존 `summary ~` 무변경, 회귀 0) |
| 색인 갱신 | `search_vector` STORED generated column (DB 자동 갱신, 앱 코드 무변경) |
| BC | 논리 search / 물리 issue-tracking, `IssueSearchPort` 재사용 |

## 사용자 시나리오 (Given-When-Then)

### S1. 본문 전문 검색 (핵심)
- **Given** PROJ 프로젝트에 본문이 "로그인 화면에서 토큰 만료 오류"인 이슈가 있고
- **When** 사용자가 검색창에 `text ~ "토큰 만료"`를 입력하면
- **Then** 해당 이슈가 결과에 포함된다 (제목에 없어도 본문 매칭).

### S2. 한글 조사 변형 매칭 (trigram 보강)
- **Given** 본문에 "로그인을 시도했으나 실패"가 있고
- **When** `text ~ "로그인"` 검색하면
- **Then** "로그인을"의 조사가 붙어 `simple` 토큰은 불일치하지만, **pg_trgm trigram 부분일치로 매칭**되어 결과에 포함된다.

### S3. 제목·본문 동시 대상
- **Given** 이슈 A는 제목에 "결제", 이슈 B는 본문에만 "결제"가 있을 때
- **When** `text ~ "결제"` 검색하면
- **Then** A, B 모두 결과에 포함된다.

### S4. 권한 우회 불가 (보안)
- **Given** actor가 BROWSE 권한이 없거나 특정 보안 등급 이슈에 접근 불가일 때
- **When** `text ~ "..."` 검색하면
- **Then** 권한 없으면 SecurityException(probe 차단), 접근 불가 보안등급 이슈는 결과에서 제외(visibility 술어 AND 우회 불가).

### S5. AQL 복합 결합
- **Given** `text ~ "버그" AND status = open` 같은 복합 쿼리
- **When** 검색하면
- **Then** 전문 검색 조건이 기존 필드 조건과 AND/OR/NOT·괄호로 자유 결합된다(AST Comparison 재사용).

## 기능 요구사항 (FR)

- **FR-1.** AQL에 신규 가상 필드 `text`를 추가한다. `text ~ <문자열>`은 `summary`+`description` 전문 검색을 의미한다.
- **FR-2.** `text` 필드는 `~`(CONTAINS) 연산자만 허용한다. `=`/`!=`/`IN`/`NOT IN`은 금지(FTS 전용 필드).
- **FR-3.** 전문 검색은 `search_vector @@ plainto_tsquery('simple', q)` **OR** trigram 부분일치(summary+description)로 매칭한다(SDD 10.2 하이브리드).
- **FR-4.** `search_vector`는 `issues`의 STORED generated column으로, `to_tsvector('simple', coalesce(summary,'') || ' ' || coalesce(description,''))` 로 자동 산출된다. INSERT/UPDATE 경로 코드 변경 없음.
- **FR-5.** 기존 `summary ~`(제목 부분일치, V031 trigram)는 동작·인덱스 모두 무변경(회귀 0).
- **FR-6.** visibility 보안 술어(BROWSE 게이트 + accessibleLevels AND)를 FR-SR-02와 동일하게 자동 결합한다.
- **FR-7.** 결과는 기존 AQL 검색과 동일한 정렬/페이지네이션 계약(`ORDER BY`, page/size)을 따른다.

## 비기능 요구사항 (NFR)

- **NFR-1 (성능).** `text ~` 검색이 GIN 인덱스를 사용한다. EXPLAIN으로 `search_vector` GIN + trigram 표현식 인덱스 사용을 통합 테스트에서 검증한다(FR-SR-02 D3 죽은 인덱스 교훈 — 쿼리 표현식과 인덱스 표현식 일치 필수). product §2.4 목표 p95(AQL 100만건 1s) 회귀 없음.
- **NFR-2 (보안).** SQL injection 0 — `plainto_tsquery`(사용자 입력을 안전하게 파싱, `to_tsquery` 미사용)와 jOOQ 바인딩. tsquery 구문 오류로 인한 500 누출 0.
- **NFR-3 (DoS 가드).** 기존 AQL 입력 가드(깊이 50 / 토큰 1000 / 2000자) 재사용.
- **NFR-4 (회귀).** 기존 FR-SR-02 단위/통합/E2E 무회귀.
- **NFR-5 (인프라).** Docker 이미지·외부 의존성 변경 0.
- **NFR-6 (정렬 범위).** 관련도(`ts_rank`) 정렬은 **범위 외**. 기존 AQL 정렬 계약(`ORDER BY` 명시 / 기본 정렬)을 그대로 유지한다. 관련도 랭킹은 후속 FR 후보.

## API 인터페이스

신규 엔드포인트 없음. 기존 `POST /api/v1/search/aql`(FR-SR-02)이 `text` 필드를 포함한 AQL을 그대로 수용한다. 요청/응답 DTO 변경 없음.

```
POST /api/v1/search/aql
{ "query": "text ~ \"토큰 만료\" AND status = open", "page": 0, "size": 20 }
→ 200 { items: [...], page, size, total }   // 기존 AqlSearchResponse
→ 400 미지원 연산자(text =) / 구문 오류
→ 403 BROWSE 권한 없음
```

## 데이터 모델 변경

issue-tracking 모듈, **V032** (현재 최신 V031).

```sql
-- 1. search_vector generated column (simple, summary+description 결합)
ALTER TABLE issues
  ADD COLUMN search_vector tsvector
  GENERATED ALWAYS AS (
    to_tsvector('simple', coalesce(summary, '') || ' ' || coalesce(description, ''))
  ) STORED;

-- 2. FTS GIN 인덱스
CREATE INDEX idx_issues_search_vector ON issues USING gin (search_vector);

-- 3. trigram 보강 인덱스 (description / 결합 표현식 — summary+description ILIKE 가속)
--    구체 표현식은 plan에서 확정 (쿼리 표현식과 일치 필수, EXPLAIN 검증).
```

- `to_tsvector('simple', ...)` 2-인자 형태는 IMMUTABLE → generated column 사용 가능 (1-인자형은 STABLE이라 불가).
- pg_trgm 확장은 V031에서 이미 설치됨(추가 CREATE EXTENSION 불필요, 단 init_codegen 미러 점검).
- **init_codegen.sql 미러 필수** (jooq-init-codegen-mirror 교훈 — 컬럼/인덱스 추가는 codegen에도 반영, 확장은 인덱스 DDL보다 먼저).
- V006 `description` 컬럼 주석("tsvector 인덱스 추가 금지")은 본 FR로 해소 — 주석 갱신 또는 V032에서 정정.

## 엣지 케이스

- **EC1.** `description` NULL → `coalesce(description,'')`로 안전(summary만 색인).
- **EC2.** 빈/공백 검색어(`text ~ ""`) → `plainto_tsquery`가 빈 쿼리, trigram도 빈 패턴 → 결과 정책 결정(매칭 0 또는 거부). plan에서 확정.
- **EC3.** 특수문자/연산자 기호 포함 검색어 → `plainto_tsquery` 안전 파싱(구문 오류 없음), ILIKE는 `%`/`_` 이스케이프 필요.
- **EC4.** 영문·숫자·한글 혼용 → `simple`은 소문자화+토큰화, trigram 보강.
- **EC5.** `text` 필드에 `=`/`IN` 등 사용 → 400 (AqlFields 연산자 제약, FR-SR-02 패턴).
- **EC6.** 매우 긴 검색어 → DoS 가드(2000자) 적용.
- **EC7.** 다중 토큰 검색어(`text ~ "토큰 만료"`) → `plainto_tsquery`는 토큰 AND, trigram은 전체 문자열 부분일치. 매칭 의미 plan 명시.

## 제약 조건

- BC 격리 — search 모듈은 jOOQ 직접 접근 불가. FTS 변환·실행은 issue-tracking `IssueSearchAdapter`/`IssueRepository.searchByAql`.
- `AqlFields`(shared-kernel) 단일 진실출처 — `text`를 MVP_FIELDS에 추가 + 연산자 제약(`~`만). drift 0.
- AST 구조(`AqlNode`) 변경 금지 — `text`는 기존 `Comparison(AqlField("text"), CONTAINS, [Str])`로 표현.
- 외부 형태소 분석기·새 의존성·Docker 변경 금지.

## 측정 가능한 완료 기준

1. `text ~ "검색어"`가 제목·본문 양쪽 매칭(S1/S3 통합 테스트 green).
2. **한글 검색 케이스 30개** 테스트(product §2.4 D5 — "형태소 분리"를 본 방식 현실에 맞게 재정의). 커버 대상: (a) 조사 변형("이슈를"↔"이슈", trigram 2글자+ 겹침), (b) 제목/본문 부분 문자열, (c) 영문·숫자·한글 혼용, (d) 다중 토큰 AND. **한계 명시**: 어간이 바뀌는 활용형("먹었다"↔"먹는다")은 trigram 겹침 부족으로 미매칭 — 형태소 분석기 미도입의 의도적 trade-off(ADR D1). 테스트 케이스는 이 현실 능력 안에서 설계하며, 미매칭 케이스는 "기대 미매칭"으로 명시(가짜 통과 방지).
3. EXPLAIN으로 GIN/trigram 인덱스 사용 확인(NFR-1 통합 테스트).
4. visibility 우회 불가 + BROWSE 게이트 통합 테스트(S4).
5. `text =` 등 미지원 연산자 400(EC5).
6. 기존 FR-SR-02 단위/통합/E2E 무회귀(NFR-4).
7. 문서 전수 동기화 + `bash scripts/verify-master-plan.sh` 통과.
