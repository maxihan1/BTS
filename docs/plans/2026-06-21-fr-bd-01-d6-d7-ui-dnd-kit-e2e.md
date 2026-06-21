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

## 도메인 정리

- **BC**: agile-planning (보드 소유). 카드 이동은 cross-BC 전이 위임(issue-tracking)을
  이미 백엔드(#165)가 처리 — 프론트는 백엔드 API 호출만.
- **영향 엔티티**: 신규 0. Board / BoardColumn / Card(=이슈 뷰) 모두 백엔드에 존재.
  이번 작업은 **프레젠테이션 레이어**(D6/D7)만 — 도메인 모델 무변경.
- **새 용어**: 없음. board/칸반/컬럼/카드는 SDD §13.1·glossary 기존 용어. LexoRank는
  glossary 등록됨(이번 범위 밖).
- **기존 결정 충돌**: 없음. FR-BD-01 ADR
  (`2026-06-20-fr-bd-01-agile-planning-bootstrap`)의 결정을 그대로 시각화.

### ADR가 프론트에 강제하는 도메인 제약 (구현 시 필수 준수)

- **결정 2 — 컬럼 = 상태 매핑**. 카드는 `currentStateKey`가 매핑된 컬럼에 배치. 어떤
  컬럼에도 매핑 안 되는 상태의 이슈는 보드에서 제외(spec E2 = 백엔드가 이미 제외).
- **결정 3 — 카드 드래그 = 컬럼 간 이동 = 워크플로우 전이**. 카드를 A→B 컬럼으로
  드래그하면 B 컬럼의 `stateKey`로 **전이 위임**(`POST /api/v1/boards/{id}/cards/{issueKey}/move`).
  **직접 상태 UPDATE 금지**(불변식 우회 — patch-merge-domain-bypass 반례). 프론트는
  move API만 호출, 응답으로 카드 갱신.
- **컬럼 내 재정렬(LexoRank) 범위 제외**(ADR 결정 3 / FR-BL-01). → @dnd-kit은 **컬럼 간
  이동에만** 사용. 같은 컬럼 내 카드 순서 변경 드롭은 no-op(서버 정렬 priority ASC 유지).
- **전이 결과 분기**(백엔드 spec E3~E5): 같은 컬럼=no-op(200), 전이 불가=409,
  resolution 필요=422, 버전 충돌=409. 프론트가 각각 처리(드래그 원복 + 모달/토스트).

- 관련 ADR: [docs/decisions/2026-06-20-fr-bd-01-agile-planning-bootstrap.md](../decisions/2026-06-20-fr-bd-01-agile-planning-bootstrap.md) (기존, 무변경)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
