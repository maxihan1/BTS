# FR-SR-03 D6/D7 — 저장 필터 저장/공유 모달 + 별표(FILTER) UI

> slug: fr-sr-03-d6-d7-saved-filter-ui
> type: ui
> agent: frontend-engineer (D6) + qa-engineer (D7 E2E)
> primary_bc: search-export-import
> 생성: 2026-06-27

## Brief

사용자 원문: "fr-sr-03 d6, d7 진행해줘 백엔드 구현이 잘되어있는지도 확인하고"

FR-SR-03 "필터 저장 및 공유"의 D6(프론트 UI) + D7(E2E). D1~D5(백엔드 PR1 #191 + PR2 #193)는 완료.
백엔드 검증 완료 — code-reviewer 적대적 검증에서 BLOCKER 3종(B1 단건가시성/B2 shares적재N+1/B3 단일술어) 전부
코드 반영 + 가짜그린 방지장치 확인. NIT 1건(POST 응답 dedupe前 echo, GET 정본이라 무해)만 잔존.

저장 필터 = 단일 AQL 문자열(aqlQuery) + projectKey + shares[] → /search AQL 검색 페이지에 부착.

### Maxi 결정 (게이트 전 사전 확정, AskUserQuestion 2026-06-27)
- Q1. UI 노출 범위 = **/search 페이지 통합만** (전용 /filters 관리 페이지 없음).
- Q2. 공유 대상 picker = **AUTHENTICATED + PROJECT(이 필터의 프로젝트)만**. GROUP 보류
       (그룹 목록 API admin전용 + 프로젝트 목록 API 부재 + group UUID 직접입력 UX 불량).
- Q3. 별표(FILTER 즐겨찾기) = **이번에 활성화** (FR-UX-02 백엔드 FILTER 이미 지원, 프론트만 배선).

### ★ 핵심 함의 (구현 시 필수)
- 공유는 replace-all(전체 교체). 편집 시 PROJECT/AUTHENTICATED만 보내면 백엔드에 남은 GROUP share가 지워짐
  → 공유 모달은 응답에 온 미편집(GROUP 등) share를 보존해 재전송해야 함.

## 도메인 정리

> 신규 도메인 용어/ADR **0건**. grill-with-docs 풀 세션 스킵(프론트 연속 작업, BC·ADR 기확립).

- **BC**: search-export-import (PR1 #191 부트스트랩 + PR2 #193 공유 확장). 프론트는 이 BC의 view layer 소비만.
- **유비쿼터스 언어** (기확립): "저장된 필터(SavedFilter)" = 이름붙은 AQL 쿼리 + 실행 projectKey. "공유(Share)" = 대상지정 PROJECT/GROUP/AUTHENTICATED. "가시성 4경로". 프론트 신규 용어 도입 없음.
- **관련 ADR**: `docs/decisions/2026-06-26-fr-sr-03-saved-filters.md` (PR1/PR2 설계 정본). 프론트 PR 신규 ADR 불필요 — view layer 결정만.
- **BC 격리**: 순수 프론트(apps/web). 백엔드 변경 0 (Maxi Q2 결정으로 GROUP picker 백엔드 미추가). cross-BC 없음.
- **재사용 자산** (frontend): FR-UX-02 favorites(FILTER 타입, 백엔드 기지원) · AQL 검색 클라이언트(`api/search.ts`) · Radix Dialog · TanStack Router code-based adapter 패턴.

## 스펙

전체 스펙. [docs/specs/2026-06-27-fr-sr-03-d6-d7-saved-filter-ui.md](../specs/2026-06-27-fr-sr-03-d6-d7-saved-filter-ui.md)

핵심 5줄.
- `/search` 검색바에 "저장"(현재 AQL+projectKey) + "필터" 드롭다운(내 필터 GET `/filters` · 공유받은 GET `/shared`).
- 불러오기 = `/search?filterId=<id>` 딥링크 → SearchPage가 GET `/{id}` 해석해 AQL·projectKey 세팅 후 실행(전용 실행 엔드포인트 미사용).
- 공유 모달 = AUTHENTICATED + PROJECT(자기 projectKey) 토글, **replace-all이라 미편집 GROUP share 보존** 필수(EC4).
- 별표 = `FAVORITE_TARGET_TYPES`에 FILTER 추가 + FavoriteButton 재사용 + Header ⭐ FILTER 그룹(이름은 GET `/{id}` 조회, 404 숨김).
- 백엔드 변경 0. Zod는 SavedFilterDtos.kt 1:1(`{data:}` 래퍼 없음, null 명시 → `.nullable()`).

## Brainstorming Check

✅ 통과 (1 iteration, 적대적 자체 검토). Maxi 결정 gap 0. 세부는 plan 흡수(저장쿼리 의미·빈상태·길이검증·MSW store·E2E 회귀0).

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
