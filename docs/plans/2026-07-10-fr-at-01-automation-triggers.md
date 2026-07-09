# FR-AT-01 — 자동화 트리거 (automation BC 첫 기능)

> slug: fr-at-01-automation-triggers
> type: api
> agent: backend-engineer
> primary_bc: automation
> 생성: 2026-07-10

## Brief

FR-AT-01 자동화 트리거. automation BC(TCA — Trigger-Condition-Action 엔진, 7 FR)의 첫 기능이자
BTS 9번째 Gradle 모듈(`backend/modules/automation`) 착수 지점.

5종 트리거: CREATED / UPDATED / COMMENTED / SCHEDULED / WEBHOOK.
백엔드: pgmq consumer + Spring @Scheduled + Webhook 엔드포인트.
데이터 모델: automation_rules(trigger_type, config).
진입조건 §0: MANAGE_AUTOMATION 권한 동반 결선(ADR D1, dead 시드 회피).

D1~D7: 도메인·명세·데이터모델·백엔드·테스트·UI·E2E.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
