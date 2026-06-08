<!-- BTS 마스터 구현 계획 — Atlas Issues 완제품 기준 (BC 단위 진척 추적, 살아있는 문서) -->

# BTS 마스터 구현 계획 (Atlas Issues 완제품)

> **위치**. 헌법(CLAUDE/DEVELOPMENT/DATA) ↔ SDD(v0.5.0 봉인) 사이의 **실행층**.
> **책임**. "어떤 기능을 어떤 순서로, 어떤 단계까지 만들어야 완료인가" 추적.
> **구조 결정**. SDD 17장의 Phase 0~4 분할 대신 **9개 BC 단위로 완제품 기준 계획**. PoC 항목은 각 BC 파일 안 "§1 기술 검증" 섹션으로 흡수 (2026-05-20 결정).
> **갱신**. 각 작업의 시작/완료 시점에 체크박스 마킹. `/bts-impl` 진입/`/bts-codereview` 통과 시점.

## §0. 사용 가이드

### §0.1 이 문서의 위치

```
헌법 (CLAUDE.md / DEVELOPMENT.md / DATA.md)           ← 절대 규칙
   │
SDD (docs/sdd/, v0.5.0 봉인)                           ← 설계 명세 26개 챕터
   │
ㅡㅡㅡ 실행층 ㅡㅡㅡ
   │
docs/plan/  ← 이 디렉토리                              ← 기능 구현 진척 (이 문서)
   ├ README.md       (이 파일 — 인덱스 + NFR 게이트 + 변경 이력)
   ├ fr-index.md     (121 FR 역인덱스 + BC 매핑)
   └ product/        (9개 BC 파일 — 각 BC가 자기 FR을 완전 추적)
   │
docs/poc/   (dependencies.md, checklist.md, context-notes.md)  ← 의존성 도입 순서
   │
docs/adr/   (개별 결정)                                ← 결정 이력
   │
Maxi_wiki/BTS/   (Obsidian 미러)                       ← 외부 단방향 사본
```

- **docs/plan/product/**. 9개 바운디드 컨텍스트(BC)별 파일. 각 BC가 자기에 속한 모든 FR을 우선순위 + 의존 순서로 정렬해 D1~D7 단계로 추적.
- **docs/poc/checklist.md**. 의존성 도입 순서 (Gradle/pnpm 빌드 파일에 어떤 라이브러리를 언제 넣을지). BC 파일과 §x.y 번호로 cross-link.

### §0.2 체크박스 마커 (4종)

| 마커 | 의미 | 갱신 시점 |
|---|---|---|
| `[ ]` | 미진행 | (초기) |
| `[~]` | 진행중 | `/bts-impl` 진입 시 |
| `[x]` | 머지 완료 | `/bts-codereview` 통과 직후 |
| `[!]` | 차단 | 의존성 미충족/사고 — `docs/poc/context-notes.md`에 사유 기록 |

4종 외 마커는 거부 (`scripts/verify-master-plan.sh`가 정규식 검증).

### §0.3 `/bts` 워크플로우와의 인터페이스

`/bts <자연어>` → start → domain → spec → plan → review-plan → 🛑게이트1 → impl → codereview → 🛑게이트2 → merge.

| `/bts` 단계 | 이 문서 갱신 |
|---|---|
| start | 해당 FR 항목 찾기. 없으면 누락 — Maxi 확인 |
| domain | D1 ☐→☐ (변동 없음. 도메인 문서 작성만) |
| spec | D2 ☐→☐ |
| plan / review-plan | (변동 없음) |
| impl 진입 | 해당 FR의 모든 단계 마커 `[ ]` → `[~]` |
| codereview 통과 | 완료된 단계 `[~]` → `[x]` |
| merge | 검증 기준 확인 후 §x.x 헤더 라인 `[x]` 마킹 + §7 변경 이력 1행 추가 |

### §0.4 업데이트 책임자

- 체크박스 갱신. Claude Code (`/bts-impl`, `/bts-codereview` 워크플로우 내). Maxi 사후 확인.
- §7 변경 이력 (이 파일). Claude Code 자동 append.
- BC 완료 선언. Maxi 1인 결정 — BC 안 모든 FR `[x]` + §NFR 게이트 통과 확인 후.

### §0.5 FR 누락 탐지 (자동 검증)

```bash
bash scripts/verify-master-plan.sh
# exit 0이어야 통과
# 내부 동작.
#   SDD 02-requirements.md에서 FR ID 117개 추출
#   docs/plan/product/*.md + fr-index.md에서 FR ID 추출
#   diff 0이어야 통과
```

### §0.6 BC 격리 강제 가이드

- **한 PR = 한 BC = 한 슬러그**. 다른 BC 호출은 pgmq 이벤트 발행만 (직접 import 금지).
- 9개 BC. identity-access, issue-tracking, project-workflow, agile-planning, automation, notification-dashboard, slack-integration, personalization, search-export-import. (Wiki는 v0.5+ 별도 SDD 예정)
- 한 FR이 여러 BC에 걸치면. 주(主) BC에서 추적하고, 다른 BC는 이벤트 계약으로만 참여 (`docs/sdd/06-scenarios.md` 참조).
- 동시 진행 시. BC 의존 그래프 (fr-index.md §A.2) 확인 후 진입 순서 결정.

### §0.7 BC별 진입 순서 권장 (의존 그래프)

```
identity-access  ─┐
                  ├─► issue-tracking ─► project-workflow ─► agile-planning
                  │                                       │
                  └─► notification-dashboard ◄────────────┘
                                │                    ▲
                                ▼                    │
                          slack-integration   automation (모든 BC에 액션)
                                                     │
                          personalization ◄──────────┘
                          search-export-import (issue-tracking 의존)
```

**근거**. identity-access는 모든 BC의 권한 게이트. issue-tracking은 도메인 코어. notification-dashboard는 모든 이벤트의 수신자. automation은 다른 BC에 액션 호출하므로 마지막 즈음.

## §1. 9개 BC 진척

| BC | 파일 | FR 수 | 기술 검증 (PoC) | 진척 |
|---|---|---|---|---|
| identity-access | [product/identity-access.md](product/identity-access.md) | 24 (AU 10 + MF 5 + PM 9) | AuthN Provider + Keycloak | ☐ |
| issue-tracking | [product/issue-tracking.md](product/issue-tracking.md) | 31 (IS 10 + CM 4 + VR 4 + AC 2 + MN 2 + WT 1 + LK 2 + HS 2 + TM 2 + MV 2) | (없음 — pgmq 이벤트 의존) | ☐ |
| project-workflow | [product/project-workflow.md](product/project-workflow.md) | 3 (WF 3) | 워크플로우 FSM + pgmq 트랜잭션 | ☐ |
| agile-planning | [product/agile-planning.md](product/agile-planning.md) | 14 (BD 3 + BL 2 + EP 2 + TL 3 + TT 2 + PL 2) | LexoRank + @dnd-kit 1K + Gantt 비교 | ☐ |
| automation | [product/automation.md](product/automation.md) | 7 (AT 7) | (없음) | ☐ |
| notification-dashboard | [product/notification-dashboard.md](product/notification-dashboard.md) | 13 (NT 4 + DB 3 + RP 4 + UX-02,03 2) | STOMP WebSocket | ☐ |
| slack-integration | [product/slack-integration.md](product/slack-integration.md) | 6 (SL 6) | (없음) | ☐ |
| personalization | [product/personalization.md](product/personalization.md) | 12 (PR 4 + PF 3 + CA 2 + UX-01,04,05 3) | (없음) | ☐ |
| search-export-import | [product/search-export-import.md](product/search-export-import.md) | 12 (SR 4 + EX 2 + IM 2 + API 4) | AQL 파서 + ANTLR 4 | ☐ |
| (메타) | (이 README §0~§6) | — | CLAUDE.md/Skills/검토 사이클 — Maxi 관찰 | 진행중 |

**합계**. 122 FR.

## §A. 부록

→ [fr-index.md](fr-index.md) — 122 FR 역인덱스 (FR ID → BC → §x.y) + Open Questions.

## §6. NFR 검증 3중 게이트

SDD `02-requirements.md §2.3` 임계를 강제. 3중 게이트로 PR 단위 회귀 차단 + BC 완료 strict 검증 + GA 종합 감사.

### §6.1 게이트 표

| 게이트 | 주기 | 측정 도구 | 임계 출처 | 차단력 |
|---|---|---|---|---|
| **PR-lite** | 매 PR (`/bts-codereview`) | axe-core (a11y) / bundle-analyzer (FE) / Pino slow-query (BE) | 회귀 0% (이전 PR 대비) | CI fail → 머지 차단 |
| **BC-strict** | 각 BC 완료 (`product/<bc>.md §NFR`) | k6 (API/검색) / Lighthouse CI (LCP/INP/CLS) / Playwright trace | SDD §2.3.1 절대 임계 | BC 완료 선언 차단 |
| **GA-comprehensive** | 완제품 직전 1회 (전 BC 완료 후) | 위 전체 + 100 동접 시뮬레이션 + 보안 감사 (Trivy + Dependabot) + 마이그레이션 리허설 | SDD 17.7 + §2.3 전체 | GA 출시 차단 |

### §6.2 임계 매트릭스 (SDD §2.3.1 인용)

| NFR | 임계 | 적용 BC | 측정 도구 |
|---|---|---|---|
| 이슈 단건 조회 p95 | 200ms | issue-tracking | k6 |
| 이슈 목록 50건 p95 | 500ms | issue-tracking | k6 |
| AQL 100만건 p95 | 1s | search-export-import | k6 + `pg_stat_statements` |
| 보드 200건 p95 | 1.5s | agile-planning | k6 + Playwright trace |
| 타임라인 500건 p95 | 2s | agile-planning | k6 + Playwright trace |
| API 단순 GET p95 | 100ms | 전 BC | k6 |
| WebSocket 알림 지연 | 1s | notification-dashboard | Playwright + STOMP client trace |
| LCP / INP / CLS | 2.5s / 200ms / 0.1 | 전 프론트 | Lighthouse CI |
| 메인 번들 gzip | 200KB | 전 프론트 | `rollup-plugin-visualizer` |
| WCAG 2.1 AA | 0 violations | 전 프론트 | axe-core (Playwright 통합) |

### §6.3 운영 규칙

- PR-lite는 자동 차단 (CI).
- BC-strict는 Maxi 1인이 측정 → 체크 → §7 변경 이력에 수치 기록.
- 1인 개발 부담 고려. 매 PR 전체 k6 실행은 제외 (BC-strict에서만).
- 회귀 발견 시 즉시 차단. BC 완료 게이트는 "이 BC에서 다룬 FR이 모두 임계 통과" 가 조건.

## §7. 변경 이력 (append-only)

- 2026-05-20. **재편성**. SDD 17장의 Phase 0~4 분할 대신 9개 BC 단위 완제품 계획으로 전환. 기존 `phase-0-poc.md`, `phase-1-mvp.md` 폐기. `product/` 디렉토리 신설.
- 2026-05-20. **초안**. `docs/plan/` 디렉토리 신설. 117개 FR 전수 매핑. NFR 3중 게이트 정의. `scripts/verify-master-plan.sh` 작성. `CLAUDE.md` 진입 트리 1행 추가.
