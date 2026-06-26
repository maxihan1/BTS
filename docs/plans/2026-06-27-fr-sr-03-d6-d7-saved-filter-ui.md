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

## 도메인 정리 (← /bts-domain 채움 — fast-track 스킵 후보: 신규 도메인 용어 0)

## 스펙 (← /bts-spec 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
