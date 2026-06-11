# FR-HS-01 이슈 변경 이력 기록

> slug: fr-hs-01-issue-history
> type: backend
> agent: backend-engineer
> primary_bc: issue-tracking
> 생성: 2026-06-11

## Brief

**원문 요청**. FR-HS-01 이슈 변경 이력 기록 구현. 이슈의 필드 변경(상태/담당자/우선순위/본문 등)이 발생하면 변경 이력(누가/언제/무엇을/이전값→새값)을 이력 테이블에 기록하는 백엔드 전용 기능.

**FR**. FR-HS-01 (이슈 변경 이력) — SDD `02-requirements.md` §5.1.1, issue-tracking BC.
**선행**. FR-IS-01 (이슈 CRUD) 완료.
**후속 unblock**. FR-HS-02 (히스토리 조회 UI), FR-MV-02 (이슈 이동 시 히스토리 보존).

**병행 작업 (충돌 회피 대상)**.
- FR-MF-01 (identity-access, worktree `fr-mf-01-totp-authenticator`, PR #113) — 다른 BC, 충돌 없음.
- FR-MN-01 (issue-tracking 멘션, worktree `fr-mn-01-mention-notify`, PR #114) — **같은 BC**. Flyway 마이그레이션 V번호 충돌 + 같은 모듈 파일 충돌 주의.
  - issue-tracking 최신 마이그레이션 V017(FR-VR-03 조인테이블)까지 확인. 새 마이그레이션은 FR-MN-01이 선점할 번호를 피해 배정.
  - 본 작업은 **백엔드 전용**(이력 기록 리스너 + 테이블) — FR-MN-01의 이슈 상세 프론트와 표면 비중첩.

## 도메인 정리

- **BC**. issue-tracking (단일, 명확)
- **영향 엔티티**. Issue(기존, 변경 메서드에 이력 호출 추가), IssueChangeGroup(신규), IssueChangeItem(신규)
- **용어**. domain/issue-tracking.md가 이미 `IssueHistory`를 핵심 엔티티로 등재 → 신규 용어 아님. 하위 모델명 IssueChangeGroup/IssueChangeItem는 glossary 갱신 후보(머지 시 Obsidian sync).
- **기존 결정 충돌**. 없음. domain "이슈+히스토리+알림 한 트랜잭션" 규칙 + DATA.md append-only와 정합.
- **재사용 선례**. FR-AU-10 `auth_audit_logs`(append-only, BIGINT IDENTITY, FK 없음, JSONB, NamedParameterJdbcTemplate).

### 확정된 도메인 결정 (Maxi, 2026-06-11)

| # | 결정 | 선택 | 비고 |
|---|---|---|---|
| 1 | 기록 메커니즘 | **서비스 레이어 동기 기록** (같은 트랜잭션) | 이전값→새값 정확 + 이벤트 미발행 경로 커버 |
| 2 | 데이터 모델 | **Jira식 2테이블** (change_group + change_item) | 한 PATCH=1그룹, 필드별 from→to=N아이템 |
| 3 | 추적 범위 | **전 필드 + 생명주기** | 전 편집 필드 + 생성/소프트삭제 |
| 4 | 보존 | **append-only** | 삭제/수정 금지, 이슈 소프트삭제 후에도 보존 |

### 구현 시 ground truth (조사 결과)

- **변경 진입점**(이력 호출 추가 대상). `IssueApplicationService.updateIssue:365` / `transitionIssue:531` / `changeAssignee:645` / `changeComponents:710` / `changeAffectsVersions:764` / `changeFixVersions:805` / `assignSecurityLevel`(updateIssue 내) / `createIssue:149`(생성) / `softDeleteIssue:605`(삭제).
- **갭**. changeAssignee/Components/Versions는 현재 pgmq 이벤트 미발행 → 동기 기록 방식이 이를 자연 커버.
- **변경 감지**. `IssueApplicationService.buildChangedFields:1021`가 이미 existing↔request 비교 로직 보유 → from/to 추출에 확장 활용.
- **마이그레이션**. issue-tracking 최신 V017. FR-MN-01(PR #114) worktree 새 마이그레이션 없음 확인 → **V018 후보**. 단 FR-MN-01이 먼저 V018을 쓰면 rebase 필요(머지 직전 재확인).

- **관련 ADR**. [docs/adr/2026-06-11-issue-change-history-model.md](../adr/2026-06-11-issue-change-history-model.md) (생성됨)

## 스펙

전체 스펙. [docs/specs/2026-06-11-fr-hs-01-issue-history.md](../specs/2026-06-11-fr-hs-01-issue-history.md)

핵심 요약.
- **기록만** 담당(부수효과). 공개 조회 API/UI는 FR-HS-02. 검증은 통합테스트가 변경 후 이력 테이블 직접 조회.
- 8개 변경 진입점(create/update/transition/assignee/components/affects·fixVersions/softDelete)에서 **이슈 변경과 같은 트랜잭션**에 이력 INSERT.
- 모델: `issue_change_group`(actor/issue/created_at) + `issue_change_item`(field/from_value/to_value/**from_label/to_label**). 한 변경=1그룹, 바뀐 필드별 N아이템. no-op이면 이력 0.
- 값=원시 ID/스칼라, 라벨=변경 당시 표시 이름 박제(Jira식). customFields는 키별 분해. 컬렉션은 정렬 JSON 배열. lifecycle은 created/deleted 단일 마커.
- append-only(UPDATE/DELETE 부재), 이슈 소프트삭제 후에도 보존. FK는 group→item만, issues로의 FK 없음.
- 마이그레이션 V018(FR-MN-01 #114와 조율) + init_codegen 미러.

## Brainstorming Check

✅ 통과 (셀프 적대적 sanity check 1회). gap 2건(표시값 손실·customFields 입자) Maxi 확정 해소.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
