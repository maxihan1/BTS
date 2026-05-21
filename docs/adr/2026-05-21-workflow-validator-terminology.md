<!-- ADR: 워크플로우 게이트 용어 통일 — Validator 채택 (Guard/Gate 거부) -->

# ADR — 워크플로우 전이 검증 용어 통일 (Validator 채택)

**일자**. 2026-05-21
**상태**. Accepted
**관련 PR**. `project-workflow-bc-fr-wf-01-fsm-1-pr`
**작성자**. Maxi + Claude (backend-engineer)

## 컨텍스트

`project-workflow` BC 신규 모듈 구현 시 "전이 가능 여부 검증" 역할의 컴포넌트 명명이 필요했다.

출처 문서 간 표현이 일치하지 않는 상태였다.

- `docs/sdd/07-workflow-engine.md` §7.3 — "Validator" 표현을 일관 사용 (`RequiredField`, `Permission`, `NotStatusCategory`, `CustomExpression` Validator 표).
- `Maxi_wiki/BTS/domain/project-workflow.md` — "WorkflowGate (조건/검증 규칙)", "전이 게이트" 표현 혼재.
- `Maxi_wiki/BTS/glossary.md` — "게이트" 항목이 일반 개념으로 정의됨.

구현 클래스 명명을 단일화하지 않으면 코드와 문서 사이에 지속적 혼동이 발생한다.

## 후보

| 후보 | 출처 | 거부 사유 |
|---|---|---|
| **Validator** | SDD §7.3 (일관 사용) | — (채택) |
| Guard | State Machine 문헌 표준 | Spring Security의 SecurityGuard / AuthorizationManager 등과 혼동 가능. BTS 헌법이 Spring 생태계 표준 우선을 명시. |
| Gate | 전이 게이트 개념에 부합 | CLAUDE.md / SKILL.md에서 "게이트 1/2" (Maxi 검토 게이트) 로 이미 사용 중. 동음이의 충돌로 거부. |

## 결정

**Validator 채택.**

구현 클래스 명명.

- `WorkflowValidator` — 인터페이스 (SPI)
- `RequiredFieldValidator` — 특정 필드 입력 필수 검증
- `PermissionValidator` — 권한 보유 검증 (PermissionResolver outbound port 경유)
- `NotStatusCategoryValidator` — 특정 카테고리 진입 불가 검증
- `CustomExpressionValidator` — SpEL 표현식 평가 검증

## 근거

1. **SDD 일관성** — SDD §7.3이 Validator 표현을 이미 일관 사용. 구현이 SDD와 동일 용어를 쓰면 신규 팀원이 문서 → 코드 탐색 시 매핑 비용 0.
2. **Spring 생태계 표준** — `jakarta.validation`, `spring-validation`, Bean Validation 등 Spring 생태계 전반에서 "Validator" 패턴이 표준. BTS 백엔드 팀이 익숙한 어휘.
3. **책임 명확성** — "전이 가능 여부 검증"이라는 책임을 "Validator"가 가장 직접적으로 표현. Guard는 방어/차단, Gate는 통과 제어의 뉘앙스로 검증 계산 역할과 미묘하게 달라 오해 소지 있음.

## 영향

### 긍정

- 코드 ↔ SDD 용어 완전 일치 → 문서 탐색 비용 감소.
- Spring 생태계 개발자가 직관적으로 역할을 파악 가능.

### 후속 정정 필요 항목

다음 항목들은 Obsidian 단방향 미러 룰 (Phase 0 수동) 에 따라 별도 sync-obsidian 또는 후속 PR에서 정정한다.

- `Maxi_wiki/BTS/domain/project-workflow.md` — "WorkflowGate", "게이트 (조건/검증 규칙)" 표현을 "WorkflowValidator" 로 정정. "ANTLR 4 게이트 표현식 파서" 도 SpEL 채택(ADR `2026-05-21-workflow-expression-parser-spel`) 에 맞춰 정정.
- `Maxi_wiki/BTS/glossary.md` "워크플로우 / 자동화" 섹션 — "WorkflowValidator" 신규 항목 추가. "Guard/Gate" 표현이 구현 클래스를 지칭하는 문맥이라면 삭제 또는 정정.

### 영향 없는 항목

- `docs/sdd/07-workflow-engine.md` §7.3 — 이미 Validator 표현 사용 중. 본 ADR과 일치. 변경 불필요.
- `Maxi_wiki/BTS/glossary.md` "게이트" 항목 (일반 개념 정의) — "전이에 걸린 조건의 일반 개념"으로 그대로 유지. 구현 클래스 명은 Validator이지만 일반 개념어로서의 "게이트"는 그대로 공존 가능.

## 관련

- `docs/sdd/07-workflow-engine.md` §7.3 Validator 표준 표
- `Maxi_wiki/BTS/domain/project-workflow.md` — 후속 정정 대상
- ADR `2026-05-21-workflow-expression-parser-spel` — CustomExpressionValidator 의 평가 엔진 결정 (SpEL 채택)
- ADR `2026-05-21-workflow-bc-cross-bc-port` — PermissionValidator 가 사용하는 outbound port 패턴
