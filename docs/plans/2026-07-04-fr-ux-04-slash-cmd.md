# FR-UX-04 Slash 명령어 (Cmd+K 명령 팔레트)

> slug: fr-ux-04-slash-cmd
> type: feature
> agent: backend-engineer (UI task는 frontend-engineer, E2E는 qa-engineer 지정)
> primary_bc: personalization
> 생성: 2026-07-04

## Brief

FR-UX-04 — Slash 명령어. `Cmd+K`로 여는 명령 팔레트(command palette).
`/issue`, `/search`, `/goto` 등 슬래시 명령을 한 곳에서 실행하는 UX 편의 기능.

product 문서(docs/plan/product/personalization.md §4.2) D 단계.
- D1. 도메인 — Command (backend-engineer)
- D2. 명세 — `/issue`, `/search`, `/goto` 등 (backend-engineer)
- D3. 데이터 모델 — 활용(명령어 정의는 코드 상수), 신규 테이블 없음
- D4. 백엔드 — `POST /api/v1/commands/execute` (backend-engineer)
- D5. 백엔드 테스트 (backend-engineer)
- D6. 프론트 UI — cmdk 명령 팔레트 (`Cmd+K`) (designer → frontend-engineer)
- D7. E2E (qa-engineer)

**분류 메모**. classify 자동판정 qa(입력 "E2E" 키워드 오판) → feature 수동정정.
learnings.md 반복 함정(FR-SR-02·FR-RP-01 동일). agent는 plan task별 지정.

**선결 이슈** (domain/spec에서 확정).
- personalization BC의 첫 backend 부트스트랩 여부 (기존 모듈 없음)
- cmdk 신규 외부 의존성 도입 여부 (DEVELOPMENT.md §외부 의존성 → Maxi 확인)
- `/issue`, `/search`, `/goto` 명령 범위 확정

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
