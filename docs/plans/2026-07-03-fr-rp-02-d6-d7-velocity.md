# FR-RP-02 D6/D7 — 벨로시티 차트 (Velocity Chart) 프론트엔드

> slug: fr-rp-02-d6-d7-velocity
> type: ui
> agent: frontend-engineer
> 생성: 2026-07-03

## Brief

FR-RP-02 D6/D7 — 벨로시티 차트 프론트엔드(recharts 바 차트) + E2E.
백엔드는 #222로 머지 완료: `GET /api/v1/projects/{projectKey}/velocity`, 신규 포트 `SprintVelocityLookupPort`.
막대=계획(Commitment, 스프린트 현재 가시이슈 추정합) vs 완료(Completed, DONE 카테고리 추정합) 2막대, 지표=추정시간(초).
notification-dashboard.md §4.2 D6/D7 미완료 상태.

classify: type=qa 오분류(E2E 제목) → ui/frontend-engineer 정정 (FR-RP-01 D6/D7 선례).
선례: FR-RP-01 D6/D7 번다운 차트 프론트(#220) — 코드기반 라우팅·recharts jsdom width0·계약 정합.

## 도메인 정리

- **BC**: notification-dashboard(논리) / 프론트는 `apps/web`. 백엔드는 agile-planning 구현(#222, 머지 완료).
- **영향 범위**: 프론트엔드 전용(apps/web). 신규 백엔드/도메인 엔티티 0 — 기존 계약 소비.
- **용어**: `벨로시티(Velocity)` / `계획(Commitment)` / `완료(Completed)` — **모두 glossary 등재 완료**(#222). 신규 용어 0.
- **기존 결정 충돌**: 없음. 프론트는 FR-RP-01 D6/D7 라우팅·recharts 패턴 재사용.
- **관련 ADR**: [docs/decisions/2026-07-02-fr-rp-02-velocity.md](../decisions/2026-07-02-fr-rp-02-velocity.md) (백엔드, 기존). **프론트 신규 ADR 불필요**.

### 백엔드 계약 (확보 완료, #222)
- `GET /api/v1/projects/{projectKey}/velocity?limit=10` → `DataResponse<VelocityResponse>` (외피 `{ data: {...} }`)
- `VelocityResponse`: `projectKey: string`, `averageCommitmentSeconds: number`(비-null, 0 fallback), `averageCompletedSeconds: number`, `sprints: VelocityPointResponse[]`
- `VelocityPointResponse`: `sprintId: UUID`, `name: string`, `startDate: LocalDate?`(**nullable→`.nullish()`**), `endDate: LocalDate?`, `commitmentSeconds: number`, `completedSeconds: number`
- 상태: 200 / 401(미인증) / 403(BROWSE). limit 클램프 [1,50]은 서비스 책임.

### FR-RP-01 D6/D7 미러 청사진 (프론트 파일 구조)
| FR-RP-01 (번다운, 스프린트 단위) | FR-RP-02 (벨로시티, 프로젝트 단위) |
|---|---|
| `api/burndown.ts` (Zod+순수변환+client) | `api/velocity.ts` |
| `components/burndown/BurndownChart.tsx` (recharts 라인) | `components/velocity/VelocityChart.tsx` (recharts **바**) |
| `routes/projects.$projectKey.sprints.$sprintId.burndown.tsx` | `routes/projects.$projectKey.velocity.tsx` |
| `mocks/burndown-handlers.ts` | `mocks/velocity-handlers.ts` |
| `i18n/burndown-labels.ts` | `i18n/velocity-labels.ts` |
| 진입: `SprintColumn.tsx` "번다운" 버튼 (스프린트 단위) | 진입: **프로젝트 단위 위치** — spec에서 확정 |
| `e2e/sprint-burndown.spec.ts` | `e2e/project-velocity.spec.ts` |

**★spec 결정거리**: 벨로시티는 프로젝트 전체(여러 완료 스프린트) 집계라 진입점이 SprintColumn(스프린트 단위)이 아님. 프로젝트 단위 진입 위치(백로그 보드 헤더 / 프로젝트 보드 등)를 spec에서 확정.

## 스펙

전체 스펙. [docs/specs/2026-07-03-fr-rp-02-d6-d7-velocity.md](../specs/2026-07-03-fr-rp-02-d6-d7-velocity.md)

핵심 시나리오 3줄 요약.
- 백로그 '프로젝트 뷰 전환' nav의 '벨로시티' 링크 → `/projects/$projectKey/reports/velocity` 이동.
- 스프린트별 계획(commitment)/완료(completed) 2막대 바 차트 + 평균 참조선 2개(recharts BarChart).
- 스프린트 0개면 빈 상태, BROWSE 권한 없으면 403 안내(데이터 노출 0).

의도적 범위 결정 (게이트1 확인 대상).
- 차트 전용(데이터 테이블 미포함, 번다운 선례). `limit` 컨트롤 미노출(백엔드 기본 10). 토글 없음.

## Brainstorming Check

✅ 통과 (포커스 갭 점검). 차단 갭 0건, 의도적 범위 결정 2건 노트.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
