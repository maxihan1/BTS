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

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
