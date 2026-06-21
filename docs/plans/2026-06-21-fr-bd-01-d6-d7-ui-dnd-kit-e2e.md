# FR-BD-01 D6/D7 — 칸반 보드 프론트엔드 UI + E2E

> slug: fr-bd-01-d6-d7-ui-dnd-kit-e2e
> type: ui
> agent: frontend-engineer
> BC: agile-planning
> 생성: 2026-06-21

## Brief

FR-BD-01 D6(프론트 UI)/D7(E2E)를 구현한다. 백엔드는 #165(보드 CRUD/조회/이동
API)·#168(필터 API)로 완료. 이번 작업은 그 API를 호출해 보여줄 **칸반 보드 화면**
신규 구축.

- **D6**. @dnd-kit 기반 컬럼/카드 드래그앤드롭 보드 페이지. 카드 이동 = 대상 컬럼
  state_key로 전이 위임(POST .../move). resolution 필요 전이(E4)·전이 불가(E4)·
  버전 충돌(E5) 처리.
- **D7**. Playwright E2E + NFR(보드 200건 렌더 p95 < 1.5s) 검증.
- **범위 외**. FR-BD-02 필터 칩 UI(보드 안정화 후 별도 결정 — Maxi 2026-06-21).
- **신규 의존성**. @dnd-kit (product §2.1 D6 명시 선택). DEVELOPMENT.md §외부 의존성
  — spec 단계 Maxi 확인.

원문(classify): type=qa로 오판정(E2E 키워드) → ui/frontend-engineer 교정.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
