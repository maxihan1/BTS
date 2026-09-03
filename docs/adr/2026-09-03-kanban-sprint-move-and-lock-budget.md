<!-- 칸반에 남은 스프린트를 스크럼 보드로 이관하고 advisory lock 에 대기 상한을 두는 결정 -->

# ADR — 칸반에 남은 스프린트는 이관한다 · advisory lock 은 상한을 갖는다

> 날짜. 2026-09-03
> 상태. **채택 (Active)** — 2026-09-03 Maxi 인가. 티어 **T3**(마이그레이션).
> 관련 FR. **FR-BD-04** 후속 (보드 종류 · 활성 스프린트 보드). 신설 FR 없음.
> BC. agile-planning 단독
> 근거 규칙. `CLAUDE.md` 작업 티어 T3 · `DATA.md §4` 마이그레이션 · `DATA.md §5` jOOQ raw SQL 예외 · `docs/design/jira-parity-contract.md` §1
> 관련 스키마. `V505__board_type.sql` · `V506__sprint_board_id.sql`
> 선행 ADR. [2026-09-01 보드는 종류를 갖는다](2026-09-01-board-type-and-active-sprint.md) · [2026-05-26 jOOQ execute advisory lock 예외](2026-05-26-jooq-execute-advisory-lock-exception.md)
> 관련 spec/plan. [spec](../specs/2026-09-03-kanban-sprint-move-and-lock-budget.md) · [plan](../plans/2026-09-03-kanban-sprint-move-and-lock-budget.md)
> 닫는 부채. **165** · **166** (`TODOS.md` · `docs/plans/2026-08-12-debt24-master.md:224-225`)

## 맥락

### 왜 지금인가

선행 ADR(2026-09-01)의 D5 가 「활성 스프린트는 보드당 1개」를 정하고 PR **#431** 이 그것을
결선했다. 그런데 #431 은 **생성 경로만** 막았고, 그 사실을 스스로 KDoc 에 적어 두었다
(`SprintApplicationService.kt:426-433`) — *"새로 만드는 것만 막고 있는 것은 둔다. 그 결과 남는
구멍(…)은 `TODOS.md` 에 별건으로 등재했다."*

그 별건이 부채 **165** 다. 그리고 같은 PR 이 넣은 advisory lock 이 **무한 대기**라는 것이 부채
**166** 이다. 둘 다 `SprintApplicationService.start` 한 함수를 가리키므로 한 결정으로 닫는다.

### 실측 (2026-09-03)

| 항목 | 실측 |
|---|---|
| `agile-planning` 의 `@Transactional(isolation=)` 명시 | **0건**. identity-access 는 **37곳** 명시 |
| 저장소 전역 `lock_timeout` · `statement_timeout` | **각 0건** |
| 저장소 전역 `55P03` 매핑 | **0건** |
| 경계 있는 advisory lock | `WorkflowCache.kt:162` **단 1곳**. 나머지 **8곳**은 blocking |
| 운영 `spring.datasource.hikari.*` | **0건** (오토컨피그 기본 `maximumPoolSize=10`, 9개 BC 공유) |
| `agile-planning` 테스트 DataSource | `DriverManagerDataSource` — **풀이 없다**(`AgilePlanningTestcontainersConfig.kt:108`) |

### Jira Cloud 실물 조회 (2026-09-03 · 신규 J14~J18)

근거 표는 [spec](../specs/2026-09-03-kanban-sprint-move-and-lock-budget.md) `## Jira 대조` 가 정본이다.
결정에 직접 걸리는 두 줄만 옮긴다.

- **J17** — Jira 는 스프린트의 **origin board 를 바꾸는 조작을 제공하지 않는다.** 공식 답변은
  「새 스프린트를 만들어 이슈를 옮겨라」다. ⇒ **대응 없음.**
- **J18** (DC 전용 · Cloud 아님) — parallel sprints 를 껐는데도 한 보드에 ACTIVE 가 여럿 보이는 것은
  Jira 에서 **정상**이고, 처방은 *"disassociate sprint … from issue"* 즉 **스프린트 상태를 안 건드린다.**

## 결정

### D1. 칸반에 남은 스프린트를 스크럼 보드로 **이관한다** — 가드만 걸지 않는다

`start` 에 종류 가드만 걸면 그 행의 주인은 **시작도 표시도 안 되는 막다른 길**에 영구히 갇힌다.
UI 만 감추면 API 는 그대로 열려 다음 사람이 같은 곳에 떨어진다. 데이터를 살리면서 불변식을
잠그는 조합은 **이관 + 가드** 하나뿐이다.

**J17 이 「대응 없음」인데도 이관하는 이유.** Jira 에 없는 것은 **사용자 조작**이다. 이 PR 의 이관은
사용자 조작이 아니라 **데이터 정정**이고, `V506__sprint_board_id.sql:49-53` 이 이미 같은 일(선재
스프린트 전 행 `board_id` 백필)을 했다. 선례의 확장이지 새 조작이 아니다.

### D2. 이관이 ACTIVE 충돌을 만나면 **이관 대상을 `PLANNED` 로 내린다**

`findActiveByBoard` 는 `orderBy(created_at asc).limit(1)` 이다. ACTIVE 를 유지한 채 옮기면
목표 보드에 ACTIVE 가 둘이 되고 화면은 먼저 만든 것 하나만 그린다 — **고치려던 결함이 자리만
옮긴다.** 게다가 그 스프린트는 이미 ACTIVE 라 `start` 가 409 로 막혀 손댈 방법이 없고,
선행 스프린트를 완료하는 날 **예고 없이 진행 중으로 나타난다.**

규칙:

- 목표 스크럼 보드에 **기존 ACTIVE 가 있으면** 이관 대상 ACTIVE 는 전부 `PLANNED`
- 없으면 이관 대상 ACTIVE 중 `created_at` 최오래 **1건만** ACTIVE 유지, 나머지 `PLANNED`
- **기존 스크럼 보드의 원래 ACTIVE 는 어떤 경우에도 건드리지 않는다**

> 🛑 **의도적 편차 `X9` 를 신설한다.** Jira 는 ACTIVE 다중을 데이터로 막지 않고 **표시로 흡수**한다
> (J18). BTS 는 `limit(1)` 이라 흡수가 불가능하므로 그 방식을 준용할 수 없다.
> **근거는 Jira 원문이 아니라 BTS 자체 일관성이다** — 그 사실을 숨기지 않고 여기 적는다.
> `V506:66-68` 의 「마이그레이션이 기존 데이터를 조용히 바꾸지 않는다」 원칙과도 어긋나므로,
> **이 ADR 이 그 예외를 명시적으로 승인한다.** status 를 바꾸는 범위는 「칸반에서 옮겨 오는 행」에
> 한정되고 기존 스크럼 행에는 미치지 않는다.

### 기각한 대안

| 대안 | 기각 사유 |
|---|---|
| **A-1. `start` 가드만** | 그 행이 시작도 표시도 안 되는 막다른 길에 영구히 갇힌다. 「있는 것은 둔다」(2026-09-02 확정)를 뒤집는 것이기도 하다 |
| **A-2. UI 에서 시작 버튼만 감춤** | API 가 그대로 200 을 돌려주고 DB 에 ACTIVE 가 박힌다. 근본 미해결 |
| **A-3. 이관하되 ACTIVE 유지** | J18·`V506:66-68` 과 정합하지만 `limit(1)` 때문에 결함이 자리만 옮긴다. 이관 후에도 안 보이고 409 로 손도 못 대며 2주 뒤 예고 없이 튀어나온다 |
| **A-4. 충돌 행은 안 옮김** | 남겨둔 행이 D3 의 가드에까지 걸려 완전한 막다른 길이 된다 |
| **A-5. 충돌 행을 `COMPLETED` 로 종료** | 사용자가 끝낸 적 없는 스프린트를 마이그레이션이 임의 종료한다. `COMPLETED` 는 FSM 상 되돌릴 수 없다 |
| **A-6. `findActiveByBoard` 의 `limit(1)` 을 없애 Jira 처럼 흡수** | 편차 `X5`(직접 소속)를 다시 여는 일이고 보드 화면·백로그 계약이 통째로 바뀐다. 부채 165 의 범위를 크게 넘는다 |

### D3. `start` 는 소속 보드가 `SCRUM` 이 아니면 **409** 로 거부한다

가드는 FSM 검증 **뒤**, 락 **앞**에 둔다 — 「잘못된 전환 요청이 남의 시작을 막아 세우지 않는다」는
기존 계약(`SprintApplicationService.kt:292` · `SprintApplicationServiceTest.kt:1071`)을 유지한다.

**404 가 아니라 409 인 이유.** 스프린트는 **존재한다.** 404 는 거짓말이고
`permission-assert-before-existence-makes-403-lie` 와 같은 양식의 오도다.
`resolveTargetBoard` 의 404 는 「요청이 지정한 보드를 못 찾음」이라 의미가 다르다.

**읽기 경로는 대칭으로 맞추지 않는다.** #431 KDoc(`:426-433`)이 적은 비대칭 — 읽기까지 막으면
칸반 보드 백로그가 통째로 404 가 된다 — 는 의도이고 이 ADR 이 재확인한다.

### D4. advisory lock 은 **200ms 예산**을 갖는다 · 두 락 모두

`SprintRepository.acquireSprintStartLock` 과 형제 락
`BoardRepository.acquireProjectScrumBoardLock` 둘 다에 건다. 한쪽만 고치면 나머지가 같은 부채로
다시 등재된다.

**방식 — `set_config('lock_timeout', ?, true)` + 기존 blocking `pg_advisory_xact_lock`.**
`WorkflowCache` 의 `pg_try_advisory_xact_lock` + 폴링 루프를 복제하지 않는다.

**근거는 실측이다** (2026-09-03 · `bts-postgres-dev` = `quay.io/tembo/pg16-pgmq`, 홀더 세션이
`pg_advisory_xact_lock(987654321)` 을 쥔 상태):

| 시험 | 결과 |
|---|---|
| `SET LOCAL lock_timeout='200ms'` → `pg_advisory_xact_lock` | **206.9ms 에 취소** |
| `set_config('lock_timeout','200ms',true)` → 같은 락 (`pg_locks` 홀더 1건 확인) | **206.5ms 에 취소** |
| SQLSTATE | **`55P03: canceling statement due to lock timeout`** |

⇒ `lock_timeout` 은 advisory lock 대기에 **실제로 걸린다.** 「걸리는지 모른다」를 가정으로 남기지
않고 측정했다 — 이 부채가 애초에 **「가정만 한다」**로 등재됐기 때문이다.

**`SET LOCAL` 이 아니라 `set_config` 인 이유.** `DATA.md §5` 의 정식 예외는 (i) `?` 바인딩 +
(ii) jOOQ 미지원 PostgreSQL 함수를 **둘 다** 요구한다. `SET LOCAL` 은 리터럴만 받아 (i) 를
못 채우고, `set_config(text,text,bool)` 은 함수이면서 바인딩이 되므로 둘 다 만족한다.

**락 획득 직후 `'0'` 으로 원복한다.** `set_config(...,true)` 는 트랜잭션 스코프라 그대로 두면
뒤따르는 `findActiveByBoard`·`updateStatus` 의 **행 락 대기까지** 200ms 에 끊긴다. 부채 166 이
지적한 것은 advisory lock 무한 대기뿐이고, 행 락까지 끊으면 정상 경합이 503 을 받는 **신규 회귀**다.

**키 계산은 `hashtextextended(text, 0)` 를 유지한다.** `WorkflowCache` 의 `key.hashCode().toLong()`
(JVM int 폭)로 바꾸면 형제 락들과 잠금 공간이 갈린다.

### D5. `start` 는 격리 수준을 **명시**하고 락 뒤에 스프린트를 **재조회**한다

`@Transactional(isolation = Isolation.READ_COMMITTED)` 는 identity-access 37곳의 관용구를 승계한다.
그러나 **진짜 처방은 재조회**다 — 락 뒤에 `findById` 로 `status`·`version` 을 다시 읽으면 격리
수준이 무엇이든 안전하다. 격리 명시는 방어층이고, 판별식은 재조회 쪽에 건다(자기 파일을 읽어
애너테이션을 단언하는 검사는 리뷰에서만 도는 약한 판정이다).

## 마이그레이션

`V507__move_kanban_sprints_to_scrum_board.sql` — **DDL 변경 없음. 데이터만 옮긴다.**

1. 칸반 소속 스프린트를 가진 프로젝트 중 SCRUM 보드가 없는 곳에 신설 + 컬럼 복제
   (`V506` ②③ 형태 · `LATERAL` 로 가장 오래된 활성 칸반 보드에서 `wip_limit` 까지)
2. `board_id` 를 목표 스크럼 보드로 갱신
3. D2 규칙대로 `status` 를 `PLANNED` 로, `version = version + 1`, `updated_at = now()`
4. 기존 스크럼 보드 원래 행은 `WHERE` 로 제외

**멱등**하게 쓴다(`NOT EXISTS`). 부채 **161** 이 지적한 「`V506` 을 되돌렸다 재적용하면 스크럼
보드가 중복 생성된다」와 같은 함정을 `V507` 이 반복하지 않기 위해서다. **161 자체는 이 PR 이
닫지 않는다.**

`idx_sprints_board_active`(`V506:63-64`)는 **UNIQUE 로 승격하지 않는다.** `V506:66-68` 이 적은
사유(PR #182 Deviation ⑤ 의 선재 다중 ACTIVE 보존)가 여전히 유효하고, 이 PR 은 데이터를 정리할
뿐 스키마로 유일성을 못박지 않는다.

## 결과

**좋아지는 것**
- 칸반에 잘못 매달렸던 스프린트가 화면에 돌아온다. 담긴 이슈도 함께 보인다.
- 「200 을 받았는데 아무 일도 안 일어남」이 사라진다 — 409 로 정직해진다.
- 격리 수준이 바뀌어도 활성 스프린트 1개 불변식이 살아남는다.
- 느린 트랜잭션 하나가 연결 풀(기본 10 · 9개 BC 공유)을 말려 무관한 엔드포인트까지 죽이는 경로가 닫힌다.
- 저장소 최초의 `lock_timeout` 사용례와 `55P03` → 503 매핑이 생긴다.

**나빠지는 것 · 감수하는 것**
- 이관 대상 중 ACTIVE 였던 것이 `PLANNED` 로 내려가 사용자가 시작 버튼을 한 번 더 눌러야 한다.
  단 그 스프린트는 **기본 UI 경로로는** 화면에 나온 적이 없어 「진행 중」으로 인식된 적이 없다.
  🔴 **절대문으로 적지 않는다** — 이 PR 이 `R11` 로 일부러 보존한 읽기 경로
  (`BacklogApplicationService.resolveBoardScope:238-243`)가 종류를 안 보므로 `?board=<칸반>` URL
  직접 입력·북마크로는 도달했고, 그 화면의 `sprintComparator`(`ACTIVE=0`)가 ACTIVE 를 맨 위에
  그린다. 프론트 스위처가 스크럼만 노출할 뿐이다(게이트 2 ceo 렌즈 지적).
- **배포 공지가 없다.** 마이그레이션이 몇 건을 바꿨는지 배포자에게 알리는 수단이 `RAISE NOTICE` 도
  카운트 로그도 0건이다. 위 `updated_at` 공유 시각으로 사후 조회는 되지만 그것을 아는 사람은 SQL 을
  끝까지 읽은 사람뿐이다. 폭발 반경이 좁아(E1 — 대상 0건이 가장 흔하다) 감수하되, 적어 둔다.
- 스크럼 보드 이름 문자열의 사본이 2개에서 **3개**가 된다 → 부채 **162** 본문을 갱신해 장부가
  실제를 따라가게 한다.
- `agile-planning` 에 락 타임아웃 예외 타입이 하나 늘어난다.

**범위 밖으로 남기는 것**
부채 **156**(sprint_issues 고아 행) · **157**(BoardRepository 줄수) · **161**(V506 비멱등) ·
**162**(이름 이중화 · 본문만 갱신) · **164**(기본 보드 규칙) · **167** 의 ①③ ·
Hikari 풀 설정(`docs/specs/2026-05-20-fr-au-09-…:204` 의 dev 10 / prod 30 미이행) ·
전역 `statement_timeout` · 편차 `X5` 재설계 · 무중단 롤링 배포(단일 호스트라 해당 없음).

## 의도적 편차

| # | 편차 | 근거 |
|---|---|---|
| **X5** (승계) | 스프린트는 보드에 **직접 소속**한다. Jira 는 필터 기반 표시 + origin board | 저장 필터·AQL 은 search BC 소관이라 BC 격리상 참조 불가 (`X1` 승계) |
| **X9** (신설) | ACTIVE 충돌을 **데이터에서** 해소한다. Jira 는 표시로 흡수(J18) | `findActiveByBoard` 가 `limit(1)` 이라 흡수가 구조적으로 불가능하다. 근거는 Jira 원문이 아니라 BTS 자체 일관성이다 |
