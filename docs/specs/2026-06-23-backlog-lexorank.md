<!-- FR-BL-01 백로그 LexoRank 정렬 기술 스펙 -->
# FR-BL-01 — 백로그 우선순위 정렬 (LexoRank) — 스펙

> slug: backlog-lexorank · type: api · BC: issue-tracking
> 도메인 ADR: [docs/decisions/2026-06-23-fr-bl-01-lexorank-backlog-ordering.md](../decisions/2026-06-23-fr-bl-01-lexorank-backlog-ordering.md)
> SDD §13.2.1 · product agile-planning.md §3.1

## 결정 요약 (Maxi 확정)

1. **rank 소유** = issue-tracking BC. `issues.rank` 컬럼 + `IssueController`.
2. **알고리즘** = shared-kernel `com.bts.shared.lexorank.Rank` 순수 VO (`@JvmInline value class`).
3. **리랭크 계약** = 이웃 이슈 키. 서버가 이웃 rank 조회 후 between 계산 (클라가 rank 미계산 → 도메인 불변식 서버 통제).
4. **rebalance** = on-demand만 (중간값 고갈 시 즉시 재배포). 주기 스케줄러 없음.
5. **초기 rank** = 이슈 생성 시 자동 부여(백로그 맨 끝) + V029 마이그레이션으로 기존 이슈 created_at 순 백필. `rank` NOT NULL.
6. **컬럼 타입** = `VARCHAR(50)` (SDD §13.2.1 준수, product TEXT 표기 정정).
7. **권한** = `IssuePermission.UPDATE` + `IssueScope.Issue(key)` 재사용 (일정 필드 선례 동일).
8. **history** = rank 변경은 `SCALAR_FIELD_EXTRACTORS`에 등록하지 **않음** (백로그 정렬은 빈번 운영 액션 → 이력 noise 회피).
9. **정렬 스코프** = rank는 **프로젝트 전역 정렬 키**(모든 이슈가 보유). 백로그 뷰는 그 부분집합을 rank 순으로 표시. 이웃 이슈는 같은 프로젝트면 충분.
10. **동시성** = rank 변경은 **no-bump**(`issues.version` 불변, OCC 없음) **last-write-wins**. 이웃 rank를 요청 처리 시점에 서버가 조회하므로 stale 아님 (메모리 no-bump-sidecar-version / worklog 선례). rebalance만 `pg_advisory_xact_lock(projectId)`로 직렬화.
11. **정렬 tie-break** = `ORDER BY rank, id` — between 충돌(동시 같은 위치 삽입)로 같은 rank가 생겨도 결정적 순서 보장. `(project_id, rank)` UNIQUE는 **미강제**(드래그 충돌 시 사용자 재시도 부담 회피, 다음 이동 때 자연 해소).

## 사용자 시나리오 (Given-When-Then)

### S1. 두 이슈 사이로 이동
- Given: 백로그에 BTS-1(rank a), BTS-2(rank n), BTS-3(rank z)가 순서대로 있다.
- When: 사용자가 BTS-3을 BTS-1과 BTS-2 사이로 드래그하여 `PATCH /api/v1/issues/BTS-3/rank {previousIssueKey: "BTS-1", nextIssueKey: "BTS-2"}` 호출.
- Then: 서버가 between("a","n")="g"를 계산해 BTS-3.rank="g"로 저장. 정렬 순서 = BTS-1, BTS-3, BTS-2.

### S2. 맨 앞으로 이동
- Given: 백로그 첫 이슈 BTS-1(rank "g").
- When: BTS-5를 맨 앞으로 → `{previousIssueKey: null, nextIssueKey: "BTS-1"}`.
- Then: between(null,"g")로 "g"보다 작은 rank 부여. BTS-5가 맨 앞.

### S3. 맨 뒤로 이동
- Given: 백로그 마지막 이슈 BTS-9(rank "z").
- When: BTS-2를 맨 뒤로 → `{previousIssueKey: "BTS-9", nextIssueKey: null}`.
- Then: between("z",null)로 "z"보다 큰 rank 부여.

### S4. 새 이슈 생성
- Given: 프로젝트 백로그 최대 rank가 "x".
- When: 새 이슈 BTS-10 생성.
- Then: BTS-10.rank = between("x", null) → 백로그 맨 끝에 자동 배치.

### S5. 중간값 고갈 → on-demand rebalance
- Given: 반복 삽입으로 인접 두 rank 사이 길이가 VARCHAR(50)을 넘는 키만 생성 가능한 상태.
- When: 그 사이로 이동 요청.
- Then: 해당 프로젝트 백로그 전체 rank를 균등 간격으로 재배포(advisory lock으로 직렬화) 후 요청한 위치에 배치. 클라이언트엔 정상 200 (재시도 불필요).

## 기능 요구사항 (FR)

- **FR1**. shared-kernel `Rank` VO: 유효성 검증(알파벳 소문자 a–z, 1–50자, 끝 문자 ≠ 'a' 직전 경계 규칙), 비교(Comparable, 사전순).
- **FR2**. `Rank.between(prev: Rank?, next: Rank?): Rank` — 두 경계 사이 사전순 중간 키 생성. prev=null=시작 경계, next=null=끝 경계. 불변식: prev < result < next, 최소 길이.
- **FR3**. `Rank.initial(): Rank` — 빈 백로그 첫 키(중간값, 예 "n"). (between(null,null)과 동일 의미)
- **FR4**. between이 VARCHAR(50) 내 키를 만들 수 없으면 `RankSpaceExhaustedException`(또는 sentinel) → 호출측이 rebalance 트리거.
- **FR5**. `PATCH /api/v1/issues/{key}/rank` — 이웃 이슈 키 받아 대상 이슈 rank 갱신. UPDATE 권한 검증. OCC version 검증.
- **FR6**. 이슈 생성 시 rank 자동 부여(프로젝트 백로그 맨 끝).
- **FR7**. on-demand rebalance: 프로젝트 백로그 이슈 전체를 현재 정렬 순서대로 균등 간격 rank 재배포. advisory lock(projectId)으로 동시 rebalance 직렬화 + lock 후 재조회(TOCTOU 방지).
- **FR8**. V029 마이그레이션: `issues.rank VARCHAR(50)` 추가 → created_at 순 백필 → NOT NULL. 인덱스 `(project_id, rank)`. init_codegen.sql 미러.

## 비기능 요구사항 (NFR)

- **NFR1**. 단일 리랭크(insert/move) 평균 < 5ms (§1.1 기준). 알고리즘은 순수 계산 + 단건 UPDATE.
- **NFR2**. 1,000개 이슈 백로그에서 between/정렬 시나리오 부하 테스트 통과 (JUnit5). 반복 삽입 시 키 길이 증가가 제한적임을 검증.
- **NFR3**. rebalance(1K 이슈) 단일 트랜잭션 합리적 시간(목표 < 500ms) — 부하 테스트로 측정.
- **NFR4**. rank 인덱스로 `WHERE project_id=? ORDER BY rank` 정렬이 인덱스 스캔.

## API 인터페이스 (REST)

```
PATCH /api/v1/issues/{key}/rank
Authorization: 세션 (JWT)
Request Body:
  {
    "previousIssueKey": "BTS-5",   // nullable — null이면 맨 앞으로
    "nextIssueKey": "BTS-8"        // nullable — null이면 맨 뒤로
  }
  // 제약: 최소 하나는 의미 있는 위치. 둘 다 null = 백로그에 단독(또는 무변경 거부).

Response 200:
  { "data": { "key": "BTS-3", "rank": "g", "version": 5 } }

에러:
  400 — 이웃 순서 역전(previousRank >= nextRank), 대상==이웃, 이웃이 타 프로젝트, 둘 다 null
  403 — UPDATE 권한 없음 (IssueAccessDeniedException)
  404 — 대상 이슈 또는 이웃 이슈 미존재/소프트삭제
  (rank는 no-bump last-write-wins → 일반 리랭크에 OCC 409 없음. rebalance는 advisory lock으로 직렬화)
```

권한: `IssuePermission.UPDATE`, `IssueScope.Issue(key.value)` — `IssueApplicationService`에서 `assertPermission` (일정 PATCH 선례 동일).
동시성: rank UPDATE는 `version` 불변(no-bump). 이웃 rank는 핸들러 진입 후 서버가 조회(최신성 확보). 같은 이슈 동시 리랭크는 last-write-wins.

## 데이터 모델 변경

```sql
-- V029__issue_rank.sql (issue-tracking)
ALTER TABLE issues ADD COLUMN rank VARCHAR(50);
-- 백필: 프로젝트별 created_at 순 균등 간격 rank (db-engineer 구현 — window function 또는 Flyway 콜백)
-- ... UPDATE issues SET rank = <균등 키> ...
ALTER TABLE issues ALTER COLUMN rank SET NOT NULL;
CREATE INDEX idx_issues_project_rank ON issues (project_id, rank);
```
- init_codegen.sql의 issues 정의에 `rank VARCHAR(50)` 인라인 미러 (메모리 jooq-init-codegen-mirror).
- ⚠️ V029는 동시 브랜치(FR-SR-01 등)와 V번호 충돌 가능 — 머지 직전 재확인 (메모리 migration-vnumber-concurrent-branch-collision).

## LexoRank 알고리즘 (상세)

- **알파벳**: 소문자 `a`..`z` (base-26). 문자열 사전순 비교 == rank 순서. 경계: 하한 = `a` 이전(`` ` ``), 상한 = `z` 이후(`{`).
- **between(prev, next)**: 자리별로 prev/next 문자를 비교하며 사전순 중간 문자 탐색.
  - 같은 자리 문자가 같으면 그 문자 채택 후 다음 자리.
  - 두 문자 사이 중간 문자가 존재(차이≥2)하면 중간 문자 채택 후 종료.
  - 인접(차이==1)하면 prev 문자 채택 + 다음 자리에서 next는 상한으로 간주하고 계속(키 1자리 증가).
  - prev 소진 시 하한, next 소진 시 상한으로 채워 계산.
- **불변식**: prev < result < next(경계 포함), result는 두 경계와 다름, 가능한 최소 길이.
- **rebalance**: 프로젝트 백로그를 현재 rank 순으로 읽어 N개를 균등 간격 고정폭 키로 재배포 (예: 3자리 base-26을 균등 분할). 이후 삽입 여유 최대화.

## 엣지 케이스

- E1. 빈 백로그 첫 이슈: `Rank.initial()` (중간값).
- E2. previousIssueKey == nextIssueKey → 400.
- E3. 대상 key == previousIssueKey 또는 == nextIssueKey → 400.
- E4. 이웃 이슈가 대상과 다른 프로젝트 → 400 (백로그는 프로젝트 단위).
- E5. 이웃 이슈 미존재/소프트삭제 → 404.
- E6. previousRank >= nextRank (이웃 순서 역전, 클라 stale) → 400.
- E7. 둘 다 null → 400 (이동 위치 불명). 단 백로그에 이슈 1개뿐이면 무변경 허용 여부는 구현에서 명확화.
- E8. 중간값 고갈 → rebalance 후 재배치 (사용자에겐 투명, 200).
- E9. 대상 이슈 미존재/소프트삭제 → 404.
- E10. 동시 rebalance 2건 → advisory lock으로 직렬화, 두 번째는 첫 결과 본 뒤 진행.
- E11. between 충돌로 두 이슈가 같은 rank → tie-break `ORDER BY rank, id`로 결정적 순서, 다음 이동 때 자연 해소 (UNIQUE 미강제).
- E12. 비인접 이웃(클라가 BTS-1·BTS-5 전송, 사이에 BTS-3 존재) → 두 rank 사이 중간값 계산, 결과는 그 범위 내 어딘가 + tie-break. 인접 강제 안 함(드래그 UX 관대).

## 제약 조건

- BC 격리: rank는 issue-tracking 단독 소유. agile-planning은 (미래) 백로그 조회 시 rank를 읽기만.
- 도메인 불변식 서버 통제: 클라는 rank 문자열을 만들지 않음 (이웃 키만 전송).
- 완제품 기준: TDD, 권한 가드, OCC, advisory lock, init_codegen 미러 모두 충족.
- 이번 범위 제외: 프론트 UI(D6, FR-BL-02와 통합), 백로그 조회 전용 엔드포인트(필요 시 별도), 주기 rebalance 스케줄러.

## 측정 가능한 완료 기준

- [ ] `Rank` VO + between/initial 단위 테스트 (경계/인접/고갈 케이스 전수).
- [ ] V029 마이그레이션 + 백필 + NOT NULL + 인덱스, init_codegen 미러, 마이그레이션 테스트 통과.
- [ ] `PATCH /{key}/rank` 통합 테스트: S1~S5 + E1~E10 (권한 403, OCC 409, 타프로젝트 400 포함).
- [ ] 이슈 생성 시 rank 자동 부여 테스트.
- [ ] on-demand rebalance 테스트 (고갈 트리거 + advisory lock 직렬화).
- [ ] 1K 부하 테스트: 평균 리랭크 < 5ms, rebalance < 500ms (NFR2/NFR3).
- [ ] `./gradlew :backend:modules:issue-tracking:test` + shared-kernel test green, ktlint/detekt 통과.

## Brainstorming Check

✅ 통과 (adversarial self-review 1회 — office-hours/brainstorming 대화형 스킬은 명확한 기술 스펙에 부적합, 메모리 bts-spec-office-hours-mismatch).

발견·반영한 gap 5건.
1. 정렬 스코프 모호 → rank=프로젝트 전역 키, 백로그는 부분집합 (결정 #9).
2. rank 중복 가능(동시 같은 위치 삽입) → tie-break `ORDER BY rank, id`, UNIQUE 미강제 (결정 #11, E11).
3. OCC 과다 → rank는 no-bump last-write-wins, 이웃 rank 처리 시점 조회로 stale 차단 (결정 #10, 409 제거).
4. 락 범위 → rebalance만 advisory lock, 일반 리랭크는 락 없음.
5. 대상 이슈 소프트삭제 404 누락 → E9 추가. 비인접 이웃 처리 → E12 추가.
