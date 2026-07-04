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

## 도메인 정리

- **BC**: personalization (논리) — 물리 구현은 `apps/web` 프론트 전용. 백엔드 모듈 없음.
- **아키텍처 결정** (Maxi 확정, AskUserQuestion): **프론트 전용**. product D4 `POST /api/v1/commands/execute` 미도입.
  명령 레지스트리=프론트 코드 상수, cmdk 팔레트가 기존 라우터/검색·이슈 라우트로 직접 dispatch.
- **명령 범위** (Maxi 확정): `/goto` · `/search` · `/issue` 3종, 전부 네비게이션(즉석 mutation 없음).
  `/issue`는 새 이슈 폼으로 이동(제목 프리필) — "빠른 이동만(액션 명령 제외)"과 양립.
- **영향 파일(신규, 프론트)**: `CommandPalette.tsx`(cmdk 팔레트) + `commands.ts`(명령 레지스트리/파서) + 전역 `Cmd+K` 훅.
- **cmdk**: 이미 설치·실사용 중(`cmdk ^1.1.1`, FR-IS-09 `LabelAutocompleteInput.tsx`). 신규 의존성 0.
- **새 용어**(glossary 추가 대기 — Maxi 승인 필요):
  - 명령 팔레트 (Command Palette) — `Cmd+K`로 여는 슬래시 명령 실행 UI.
  - 슬래시 명령 (Slash Command) — `/goto` `/search` `/issue` 형식의 네비게이션 명령. Slack의 서버측 slash 명령과 다름(클라이언트 전용).
- **기존 결정 충돌**: 없음. 단 **product 문서 deviation 발생** — `personalization.md §4.2` D3~D5(데이터모델/백엔드/백엔드테스트) → "프론트 전용" 조정. 구현 PR 내 전수 동기화(fr-index 카운트/BC 불변).
- **관련 ADR**: [docs/decisions/2026-07-04-fr-ux-04-slash-cmd.md](../decisions/2026-07-04-fr-ux-04-slash-cmd.md) (생성됨)
- **agent 배정**: frontend-engineer(UI) + qa-engineer(E2E). backend-engineer 태스크 0.

## 스펙 (← /bts-spec Phase A 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
