<!-- automation BC — TCA(Trigger-Condition-Action) 자동화 엔진 7 FR -->

# automation BC

**소속 FR**. 7개 (AT 7).
**책임**. 트리거-조건-액션 규칙, 실행 이력, 스케줄링, GitOps(YAML), PR 머지 연동.
**SDD 참조**. 08장 (자동화 엔진).
**다른 BC와의 경계**. 모든 BC에 액션 호출. 다른 BC의 pgmq 이벤트를 트리거로 수신. **import 금지 — 이벤트만**.

## §0 진입 조건

- [ ] identity-access §4.4 (FR-PM-04 자동화 관리 권한) 완료
- [ ] issue-tracking §2~§6 (이슈 변경 이벤트 발행) 완료
- [ ] project-workflow §2 (상태 전이 이벤트) 완료
- [ ] notification-dashboard §1 (pgmq consumer 패턴 확립) 완료
- [ ] AT는 다른 BC의 후행 작업. 가능한 한 마지막 진입 권장.

## §1 기술 검증

이 BC 자체의 PoC는 없음. 다른 BC의 pgmq 패턴을 그대로 활용.

## §2 자동화 규칙 (FR-AT, 7개)

### §2.1 FR-AT-01 — 트리거 (생성/변경/댓글/스케줄/Webhook)

**우선순위**. 필수 | **선행**. §0 | **Plan slug**. `automation/triggers`

- [ ] D1. 도메인 — Trigger 다형성 (책임. backend-engineer)
- [ ] D2. 명세 — 5종 트리거 (CREATED/UPDATED/COMMENTED/SCHEDULED/WEBHOOK) (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `automation_rules(trigger_type, config)` (책임. db-engineer)
- [ ] D4. 백엔드 — pgmq consumer + Spring `@Scheduled` + Webhook 엔드포인트 (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 — Testcontainers (책임. backend-engineer)
- [ ] D6. 프론트 UI — 트리거 선택 UI (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §2.2 FR-AT-02 — 액션 (필드 변경/담당자/댓글/API 호출)

**우선순위**. 필수 | **선행**. §2.1 | **Plan slug**. `automation/actions`

- [ ] D1. 도메인 — Action 다형성 (책임. backend-engineer)
- [ ] D2. 명세 — 4종 액션 + 권한 가드 (다른 BC 권한 위반 금지) (책임. backend-engineer + security-engineer)
- [ ] D3. 데이터 모델 — `automation_actions(action_type, config)` (책임. db-engineer)
- [ ] D4. 백엔드 — Action executor + dry-run 모드 (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 — 권한 부족 시 reject (책임. backend-engineer + security-engineer)
- [ ] D6. 프론트 UI — 액션 빌더 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §2.3 FR-AT-03 — 조건 분기 (if-else, 표현식)

**우선순위**. 필수 | **선행**. §2.1, §2.2 | **Plan slug**. `automation/conditions`

- [ ] D1. 도메인 — Condition + Expression (책임. backend-engineer)
- [ ] D2. 명세 — 표현식 문법 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `automation_conditions(expression)` (책임. db-engineer)
- [ ] D4. 백엔드 — 표현식 평가 엔진 (Spring SpEL 또는 자체) (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 — 표현식 케이스 50개 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 조건 빌더 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §2.4 FR-AT-04 — 규칙 충돌 정적 분석

**우선순위**. 필수 | **선행**. §2.1~§2.3 | **Plan slug**. `automation/conflict-analysis`

- [ ] D1. 도메인 — RuleConflict (책임. backend-engineer)
- [ ] D2. 명세 — 사이클/우선순위 모호성/필드 충돌 검출 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — (활용) (책임. db-engineer)
- [ ] D4. 백엔드 — 규칙 저장 전 lint (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 저장 전 경고 모달 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §2.5 FR-AT-05 — 실행 이력 + 디버깅 (재실행, 단계별 추적)

**우선순위**. 필수 | **선행**. §2.1~§2.3 | **Plan slug**. `automation/execution-history`

- [ ] D1. 도메인 — RuleExecution (책임. backend-engineer)
- [ ] D2. 명세 — trace context + 재실행 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — `rule_executions(rule_id, trigger_event, result, ...)` (책임. db-engineer)
- [ ] D4. 백แอ — 실행 이력 저장 + `POST /api/v1/automation/executions/{id}/replay` (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. backend-engineer)
- [ ] D6. 프론트 UI — 실행 이력 + 단계별 trace + 재실행 버튼 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §2.6 FR-AT-06 — YAML 가져오기/내보내기 (GitOps)

**우선순위**. 높음 | **선행**. §2.1~§2.4 | **Plan slug**. `automation/yaml-gitops`

- [ ] D1. 도메인 (책임. backend-engineer)
- [ ] D2. 명세 — YAML 스키마 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — (활용) (책임. db-engineer)
- [ ] D4. 백엔드 — `POST /api/v1/automation/import` + `GET .../export` (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 — round-trip (책임. backend-engineer)
- [ ] D6. 프론트 UI — YAML 업로드/다운로드 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §2.7 FR-AT-07 — PR 머지 연동 (Fix Version 자동 설정)

**우선순위**. 높음 | **선행**. §2.1 (WEBHOOK 트리거), §2.2 (액션) | **Plan slug**. `automation/pr-merge`

- [ ] D1. 도메인 — GitWebhookEvent (책임. backend-engineer)
- [ ] D2. 명세 — GitHub/GitLab Webhook 처리. 커밋 메시지에서 이슈 키 추출 (책임. backend-engineer)
- [ ] D3. 데이터 모델 — (활용. webhook secret 저장) (책임. db-engineer)
- [ ] D4. 백엔드 — `POST /api/v1/webhooks/git` + 서명 검증 (책임. backend-engineer + security-engineer)
- [ ] D5. 백엔드 테스트 — 가짜 페이로드 (책임. backend-engineer)
- [ ] D6. 프론트 UI — Webhook URL 생성 페이지 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

## §NFR automation BC 완료 게이트

### 측정값 기록표

| 항목 | 임계 | 실측 (p95) | 비고 |
|---|---|---|---|
| 트리거 → 액션 처리 지연 | 5s | ___ | pgmq consumer + Action |
| 규칙 충돌 정적 분석 | 1s | ___ | 100개 규칙 |
| 실행 이력 재실행 | 1s | ___ | 단일 규칙 |
| YAML import (100 규칙) | 10s | ___ | (대량 케이스) |
| Webhook 응답 | 200ms | ___ | (Git PR 머지) |
| 권한 위반 액션 차단율 | 100% | ___ | 보안 가드 |

### BC 완료 조건

- [ ] §2 (FR-AT 7개) 모두 `[x]` 마킹
- [ ] §NFR 측정표 모든 항목 임계 통과
- [ ] CHANGELOG.md 정리
- [ ] README.md §7 변경 이력에 "automation BC 완료 — YYYY-MM-DD" 추가
- [ ] Maxi 1인 선언 — "automation BC 완료"
