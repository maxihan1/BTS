# DB 복구 (`bts-postgres`)

> 배포 스크립트가 매 배포 직전에 뜨는 덤프로 되돌리는 절차. 정본 `infra/deploy/bts-deploy.sh`.

## 1. 백업은 무엇을 담고 무엇을 안 담나

`bash infra/deploy/bts-deploy.sh` 가 `compose build` **직전**(새 코드는 올라갔고 스키마는
아직 안 바뀐 시점)에 조건 없이 뜬다. 산출물은 서버 `/opt/bts/backups/` 에 **두 파일**이다.

| 파일 | 내용 |
|---|---|
| `bts-<stamp>.dump` | `pg_dump -Fc` — `public` 스키마 전체(테이블·데이터·인덱스·FK·Flyway 이력) |
| `bts-<stamp>.pgmq-queues.txt` | pgmq 큐 **이름 목록** |

각각 최근 10개만 보관한다.

### ★ 덤프에 pgmq 큐가 없다 — 이것이 이 문서가 존재하는 이유다

pgmq 큐 테이블은 **확장(pgmq) 소속**이라 `pg_dump` 가 DDL·데이터를 통째로 건너뛴다.
2026-08-21 프로덕션 실측 —

```
실제 DB 의 pgmq 스키마 테이블      25개 (q_* 12 · a_* 12 · meta)
확장 소속 등록 (pg_depend deptype='e')   80건
덤프 안의 pgmq 항목                 6개 — SCHEMA · EXTENSION · COMMENT 2 · DEFAULT ACL 2
덤프 안의 pgmq TABLE DATA           0개  ← 큐가 통째로 없다
덤프 안의 flyway 이력 TABLE DATA     8개  ← 이쪽은 데이터까지 담긴다
```

`--extension=pgmq` 도 `-t 'pgmq.q_*'` 도 효과가 없다(둘 다 실측 — TABLE DATA 0). pgmq 가
`pg_extension_config_dump()` 로 큐를 등록하지 않기 때문이고, 이는 pg_dump 의 설계된 동작이다.

**그래서 복원 절차에 따라 결과가 갈린다.** 아래 §2 와 §3 이 다른 문서인 이유다.

큐 **메시지**(미처리 이벤트)는 어느 절차로도 복원되지 않는다. 큐는 이벤트 전달용이라
메시지 유실은 이벤트 몇 건 유실이지만, **큐 자체의 부재는 시스템 정지**다 — 그 차이가 크다.

## 2. 같은 DB 로 되돌린다 (권장 — 큐가 살아남는다)

pgmq 스키마가 덤프에 없으므로 `--clean` 이 그것을 건드리지 않는다. **큐가 그대로 남는다.**

```bash
ssh -i ~/.ssh/ncp-bts.pem root@101.79.19.193
cd /opt/bts
docker compose -f infra/docker-compose.prod.yml --env-file infra/prod/.env stop bts-backend

DUMP=backups/bts-<stamp>.dump          # ls -1t backups/*.dump | head
docker exec -i bts-postgres pg_restore -U bts -d bts --clean --if-exists < "$DUMP"

docker compose -f infra/docker-compose.prod.yml --env-file infra/prod/.env start bts-backend
```

백엔드를 먼저 세우는 이유. 복원 중에 커넥션이 살아 있으면 `--clean` 의 DROP 이 잠기고,
그 사이 들어온 쓰기가 복원 결과와 섞인다.

## 3. 새 DB 에 복원한다 (★ 큐 재생성이 선행돼야 한다)

새 볼륨·새 인스턴스로 옮기는 경우다. **덤프만 넣으면 조용히 망가진다.**

Flyway 이력은 덤프에 담겨 있으므로, 복원 직후 Flyway 는 `V002__pgmq_queue_issue_events`
같은 큐 생성 마이그레이션을 **「적용됨」으로 보고 재실행하지 않는다.** 그런데 큐는 없다.
그 상태로 백엔드가 뜨면 `pgmq.send` 가 전부 실패해 **이슈 생성·전환·웹훅·자동화·Slack 이
동시에** 죽는다. 부팅과 `/actuator/health` 는 통과하므로 **초록불인 채로** 죽어 있다.

순서를 지킨다.

```bash
# ① 확장 먼저
docker exec -i bts-postgres psql -U bts -d bts -c "CREATE EXTENSION IF NOT EXISTS pgmq CASCADE"

# ② 큐 재생성 — 백업된 목록 그대로
#    ★큐 이름에 이미 q_ 접두사가 들어 있다(예: q_issue_events). pgmq 가 여기에 다시
#      접두사를 붙여 실제 테이블은 pgmq.q_q_issue_events 가 된다. 목록의 이름을
#      **그대로** 넘겨야 원래 구조가 재현된다 — q_ 를 떼면 다른 큐가 생긴다.
while read -r q; do
  [ -n "$q" ] && docker exec -i bts-postgres psql -U bts -d bts -c "select pgmq.create('$q')"
done < backups/bts-<stamp>.pgmq-queues.txt

# ③ 그다음 데이터 복원
docker exec -i bts-postgres pg_restore -U bts -d bts < backups/bts-<stamp>.dump
```

### 복원 후 확인 — 여기까지가 복구다

```bash
# 큐 수가 목록과 같은가
docker exec bts-postgres psql -U bts -d bts -tAc "select count(*) from pgmq.list_queues()"
wc -l < backups/bts-<stamp>.pgmq-queues.txt

# 백엔드가 재시작 루프가 아닌가 (health 만으로는 갈리지 않는다)
docker inspect bts-backend --format '{{.RestartCount}}'      # 0 이어야 한다

# 외부 진입까지 살아 있는가
curl -sS -o /dev/null -w "%{http_code}\n" https://bts.maxihan.com/     # 200
```

★ `/actuator/health` 는 큐 부재를 잡지 못한다. 실제 이벤트 경로(이슈 생성 등)를 한 번
태워 봐야 §3 의 실패 양식이 드러난다.

## 4. 덤프 자체를 믿을 수 있나

배포 스크립트가 덤프 직후 `pg_restore -l` 로 아카이브를 열어 `TABLE DATA` 가 **0건이 아닌지**
확인하고, 0이면 배포를 중단한다. 크기만 보면 「빈 DB 를 성공적으로 덤프한」 상태가 그대로
통과하기 때문이다 — 정작 복구가 필요한 순간에 못 쓰는 것이 백업의 가장 흔한 실패 방식이다.

손으로 확인할 때도 같은 방법을 쓴다.

```bash
docker exec -i bts-postgres pg_restore -l < backups/bts-<stamp>.dump | grep -c "TABLE DATA"
```
