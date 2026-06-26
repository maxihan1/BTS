<!-- FR-SR-04 한글 전문 검색: 형태소 분석기 미도입(simple tsvector+pg_trgm) + BC 경계 + 검색 대상 결정 ADR -->

# ADR — FR-SR-04 한글 형태소 기반 전문 검색: tsvector 방식 + BC 경계

- 날짜: 2026-06-26
- 상태: 채택 (Accepted)
- 관련 FR: FR-SR-04
- 관련 PR: #195
- 선행 ADR: [2026-06-25-fr-sr-02-aql-parser-and-bc.md](2026-06-25-fr-sr-02-aql-parser-and-bc.md), [2026-06-23-fr-sr-01-issue-filter-bc.md](2026-06-23-fr-sr-01-issue-filter-bc.md)

## 맥락 (Context)

FR-SR-04는 "한글 형태소 기반 전문 검색"이다. 명세 두 곳이 서로 다른 방향을 가리킨다.

1. **SDD 10.2 (설계 원안).** `to_tsvector('simple')`(어휘 분석 없는 단순 토큰화) + pg_trgm 부분 문자열 매칭 보강 + 동의어는 application 레이어 확장. 즉 **외부 형태소 분석기 미도입**.
2. **product §2.4 D2 (FR 정의).** "Mecab-ko vs Lucene-Kr 비교 후 선택. PostgreSQL FTS 통합." 즉 **외부 형태소 분석기 도입** 검토.

추가 맥락.

- **FR-SR-02 ADR D3이 FR-SR-04를 예약.** AQL MVP 범위에서 "한글 형태소(FR-SR-04)·pg_trgm 유사도"를 후속 PR로 명시 분리했다. 지금이 그 시점이다.
- **issues.description이 FTS를 이 시점까지 미뤄둠.** `V006__issue_body_priority_labels.sql:25` 컬럼 주석 — `'FTS 인덱싱은 search BC 담당 — 이 컬럼에 tsvector 인덱스 추가 금지.'` 즉 본문 전문 검색은 의도적으로 FR-SR-04로 유보됐다.
- **BTS 의존성 0 철학.** Kafka→pgmq, OpenSearch→PostgreSQL FTS(SDD 03/16장), ANTLR→손수 파서(FR-SR-02 ADR D1), Gantt→자체 SVG. 무거운 인프라를 일관 회피.
- **현재 검색 인프라.** `tsvector`/`to_tsvector`/`search_vector`는 아직 없음(FTS 미구현). pg_trgm trigram 인덱스(`idx_issues_summary_trgm = gin(lower(summary) gin_trgm_ops)`, V031)만 존재.

## 결정 (Decision)

### D1. 형태소 분석기 미도입 — `simple` tsvector + pg_trgm (SDD 10.2 채택)

한글 전문 검색은 외부 형태소 분석기(Mecab-ko/Lucene-Kr/PG extension) 없이 PostgreSQL 기본 `simple` 설정의 `to_tsvector` + 기존 pg_trgm trigram 보강으로 구현한다. **zero-dep, Docker 이미지 무변경.**

- `simple` 토큰화는 조사를 분리하지 못하므로(예. "이슈를"≠"이슈"), pg_trgm trigram 부분 문자열 매칭으로 조사/활용 변형을 보강한다(`@@ plainto_tsquery('simple', q) OR <col> % q`).
- 근거. (1) BTS 미니멀 인프라 철학과 일관(FR-SR-02 ANTLR 기각 선례), (2) SDD 10.2가 정확히 이 방식을 명세, (3) 1,000명 규모 이슈 트래커 검색에 형태소 분석기 정밀도의 ROI가 운영 부담(Docker 커스텀·사전 유지·PG 업글 재빌드)을 정당화하지 못함.
- **product §2.4 D2의 "Mecab-ko vs Lucene-Kr 도입"은 본 ADR로 superseded.** FR-SR-02 ADR이 SDD 10.1 "ANTLR 4"를 superseded한 것과 동형. FR 제목의 "형태소 기반"은 deviation 명시 후 유지하되(전수 동기화), 실제 구현은 `simple`+trigram 하이브리드다.

### D2. 검색 대상 — summary + description 결합 (제목 + 본문)

전문 검색 대상은 `issues.summary`(제목) + `issues.description`(본문)이다. "전문(full-text)" 검색의 본래 의미이며, V006이 description FTS를 이 시점까지 유보한 설계 의도와 정합한다. 두 컬럼을 결합한 tsvector를 색인한다(예. `to_tsvector('simple', coalesce(summary,'') || ' ' || coalesce(description,''))`).

### D3. BC 경계 — 논리 search-export-import / 물리 issue-tracking (FR-SR-01/02 패턴 계승)

- **tsvector 컬럼 + GIN 인덱스 마이그레이션은 issue-tracking 모듈.** 대상이 `issues` 테이블이고, jOOQ 생성 코드(`Tables.ISSUES`)·Flyway가 issue-tracking 전용이므로 BC 격리상 여기 둘 수밖에 없다. V006 주석의 "search BC 담당"은 **논리 책임**을 뜻하며, FR-SR 시리즈 확립 패턴(논리 search / 물리 issue-tracking)과 모순되지 않는다.
- **검색 실행은 `IssueSearchPort`(shared-kernel) 경로 재사용/확장.** search 모듈은 AQL 텍스트→AST만 담당하고, FTS tsquery 변환·실행·visibility 보안 술어 AND 결합은 issue-tracking 어댑터가 수행한다(FR-SR-02 D2 동형).
- 새 BC 신설 없음. fr-index의 FR-SR-04 BC 매핑(search-export-import) 무변경.

### D4. AQL 통합 방식 — spec에서 정밀화

product §2.4 D6 "§2.2와 통합 — 검색창 동일". FTS를 AQL에 노출하는 방식(기존 `~` 연산자를 trigram→tsvector FTS로 의미 확장할지, 신규 텍스트 매칭 경로를 추가할지, summary 단독이던 `~` 대상을 summary+description으로 넓힐지)은 **bts-spec에서 확정**한다. 본 ADR은 방향(같은 검색창·같은 보안 술어)만 고정한다.

### D5. 권한/가시성 — visibility 보안 술어 자동 AND (불변)

FR-SR-01/02와 동일. FTS 조건이 무엇이든 actor가 접근 가능한 보안 등급 술어가 항상 AND로 결합되어 우회 불가. BROWSE 권한 게이트 유지.

## 결과 (Consequences)

- issue-tracking에 신규 마이그레이션(tsvector 컬럼 + GIN 인덱스 + 색인 트리거/생성컬럼). pg_trgm은 이미 설치되어 추가 확장 불필요. `init_codegen.sql` 미러 필수(jooq-init-codegen-mirror 교훈).
- `search_vector` 컬럼 갱신 방식(STORED generated column vs 트리거 vs 쿼리타임 `to_tsvector`)은 spec/plan에서 결정. generated column이면 INSERT/UPDATE 경로 무변경(자동 갱신).
- SDD 10.2 + product §2.4 + fr-index FR-SR-04 표기를 본 ADR 기준으로 전수 동기화(`simple`+trigram, Mecab-ko deviation 주석). verify-master-plan 통과.
- Docker/인프라 변경 0. NFR(검색 응답) 측정은 EXPLAIN으로 GIN 인덱스 사용 검증(FR-SR-02 D3 표현식 인덱스 교훈 — `to_tsvector` 컬럼/표현식과 쿼리 표현식 일치 필수, 불일치 시 죽은 인덱스).

## 대안 (Rejected)

- **PostgreSQL 형태소 extension(mecab-ko 등) 채택(product §2.4 D2 직역).** 기각 — Docker 이미지 커스텀 빌드 + 사전(수십 MB) + PG 업그레이드마다 재빌드. BTS zero-dep 철학 정면 충돌. 1K 규모 ROI 부족.
- **애플리케이션 레이어 형태소 라이브러리(은전한닢/Lucene-Kr).** 기각 — JVM 의존성 + 사전 데이터 + 색인/검색 양쪽 형태소 처리 코드. 의존성 회피 기조 위반. simple+trigram 대비 정밀도 이득이 복잡도를 정당화 못함.
- **summary 단독 FTS.** 기각 — 본문 미검색으로 "전문 검색" 취지 약화 + 기존 V031 trigram(summary)과 중복.
