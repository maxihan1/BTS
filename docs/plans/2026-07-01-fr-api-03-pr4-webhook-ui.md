# FR-API-03 PR4 — 아웃바운드 Webhook 관리 UI + 발송 이력 화면 + E2E

> slug: fr-api-03-pr4-webhook-ui
> type: ui
> agent: frontend-engineer
> 생성: 2026-07-01

## Brief

FR-API-03(구독형 아웃바운드 Webhook)의 마지막 PR(PR4). PR1(shared 추출)·PR2(구독 CRUD 백엔드)·PR3(발송 실행 계층)이 모두 머지됨. 이 PR은 프론트엔드 관리 UI + 발송 이력 화면 + E2E를 구현하고, 완료 시 FR-API-03 전체가 종료된다.

- 대상: `apps/web` SPA (React 19 / TS strict)
- 담당: frontend-engineer (E2E는 impl 말미 qa-engineer 부수 추가)
- 접근 제어: 전 엔드포인트 SYSTEM_ADMIN 전용 → whoami `isSystemAdmin` 게이팅으로 비-admin에게 화면 미노출 (FR-AU-05 선례 재사용)

### 프론트 계약 (백엔드 PR2/PR3 확정)
- `GET /api/v1/webhooks` — 구독 목록 (raw List + offset 페이지네이션 DEFAULT20/MAX100)
- `POST /api/v1/webhooks` — 구독 생성
- `GET /api/v1/webhooks/{id}` — 단건 조회
- `PUT /api/v1/webhooks/{id}` — 수정 (secret 3-state: 생략/blank유지/값재암호화)
- `DELETE /api/v1/webhooks/{id}` — 소프트 삭제
- `GET /api/v1/webhooks/{id}/deliveries` — 발송 이력 (offset page/size)

### 응답 DTO
- `WebhookResponse` — hasSecret boolean만 (secret 원문 미노출)
- `WebhookDeliveryResponse` — status enum(SUCCEEDED/FAILED), responseCode nullable, errorDetail(정규화 문구), deliveredAt

### spec 근거
- 전체 스펙: `docs/specs/2026-07-01-fr-api-03-outbound-webhook.md` §0 PR 분할 표 (PR4 = 관리 UI + 이력 화면 + E2E)

### 함정 후보 (과거 learnings/memory)
- `@JsonInclude`↔Zod `.nullish` 정합 (nullable 필드)
- MSW 계약 drift (적대 리뷰에서 표면화)
- MSW 영속 E2E는 SPA 내부이동 (reload=가짜그린)
- filter-aware queryKey (이력 페이지네이션 파라미터)
- admin 게이팅 UI: whoami isSystemAdmin 재사용

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
