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

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
