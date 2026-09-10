<!-- 운영 VM(101.79.19.193) 노출면 축소 절차 — 현재 상태·적용 내역·되돌리기 -->

# 운영 VM 하드닝 (`101.79.19.193`)

> 적용 2026-09-09. 계기는 젠킨스 도입 검토 중 「8080 같은 위험한 포트가 열려 있나」 확인이었다.
> **결론은 8080 은 애초에 안 열려 있었다** — 그러나 그 이유가 위험했다. 아래 §1.

## 1. 실측 — 안전해 *보였던* 이유

2026-09-09 조사 시점.

| 층 | 상태 |
|---|---|
| NCP 보안그룹(ACG) | **22 / 80 / 443 만 개방.** 111·5432·8080·9000·19001·3310 전부 외부 차단 |
| 호스트 방화벽 | **없음.** firewalld 미가동 · `iptables -P INPUT ACCEPT` |
| Docker publish | `bts-caddy` 만 `0.0.0.0:80`·`0.0.0.0:443`. `bts-backend` 의 8080 은 컨테이너 내부 전용 |
| `DOCKER-USER` 체인 | `-j RETURN` 하나 — **비어 있음** |

**즉 방어가 ACG 한 겹뿐이었다.** 8080 이 안 열린 것은 backend 컨테이너를 publish 하지
않았기 때문이고, 그 판단이 다음에도 지켜진다는 보장은 어디에도 없었다.

## 2. 적용한 것

### 2-1. `rpcbind` 제거 — 쓰지 않는 공격 표면

`0.0.0.0:111` 을 점유하고 있었고 **NFS 마운트는 0건**, `rpcinfo` 등록 서비스도 portmapper
자기 자신뿐이었다. rpcbind 는 UDP 증폭 DDoS 의 대표 벡터라 그대로 둘 이유가 없다.

```bash
systemctl disable --now rpcbind.socket rpcbind
```

되돌리기 `systemctl enable --now rpcbind.socket rpcbind`.
적용 후 확인 — 컨테이너 6개 전부 healthy · `https://bts.maxihan.com/` HTTP 200 (32ms).

### 2-2. `DOCKER-USER` 심층방어

정본 [`infra/security/bts-docker-user.sh`](../../infra/security/bts-docker-user.sh) ·
유닛 [`bts-docker-user.service`](../../infra/security/bts-docker-user.service).
설치 위치는 `/usr/local/sbin/` 과 `/etc/systemd/system/`.

**Docker 는 publish 포트를 `nat/PREROUTING`·`FORWARD` 에 직접 넣어 `INPUT` 정책을 건너뛴다.**
그래서 호스트 방화벽에서 8080 을 막아도 `docker run -p 8080:8080` 에는 효력이 없다.
`DOCKER-USER` 는 Docker 가 그 목적으로 남겨 둔 체인이라 여기에 걸어야 실제로 막힌다.

```
-A DOCKER-USER -i eth0 -m conntrack --ctstate RELATED,ESTABLISHED -j RETURN
-A DOCKER-USER -i eth0 -p tcp -m tcp --dport 80  -j RETURN
-A DOCKER-USER -i eth0 -p tcp -m tcp --dport 443 -j RETURN
-A DOCKER-USER -i eth0 -j DROP
-A DOCKER-USER -j RETURN          ← Docker 가 만든 원본
```

### ★ firewalld 를 켜지 않은 이유

Docker 가 실행 중인 호스트에서 firewalld 를 시작하면 iptables 체인이 재구성돼 Docker
네트워킹이 깨지고, 복구에 `systemctl restart docker` — 즉 **운영 컨테이너 6개 재시작**이
필요하다. `DOCKER-USER` 는 다운타임 0 으로 같은 층을 만든다. 필요해지면 §5 절차로 켠다.

### ★ SSH 락아웃이 발생할 수 없는 이유

`DOCKER-USER` 는 **`FORWARD` 체인에서만** 참조된다. 호스트 자신에게 오는 트래픽(sshd)은
`INPUT` 이라 이 규칙의 영향을 받지 않는다. 규칙을 잘못 넣어도 SSH 는 살아 있다.

## 3. 규칙이 공허하지 않은지 — 패킷 카운터로 본다

「규칙을 넣었다」와 「규칙이 트래픽 경로에 있다」는 다르다. 카운터가 0 이면 그 규칙은 장식이다.

```bash
iptables -L DOCKER-USER -n -v --line-numbers
```

적용 직후 실측 — conntrack RETURN 21패킷 · `dport 80` 1패킷 · `dport 443` 2패킷.
**DROP 규칙은 0 이 정상**이다. ACG 가 앞에서 막고 있어 다른 포트로 오는 패킷이 아직 없다 —
이 규칙의 목적은 지금 막는 것이 아니라 **ACG 가 열렸을 때 두 번째 겹이 되는 것**이다.

## 4. 되돌리기

```bash
systemctl disable --now bts-docker-user.service
iptables -F DOCKER-USER && iptables -A DOCKER-USER -j RETURN
systemctl enable --now rpcbind.socket rpcbind      # 필요할 때만
```

적용 전 `iptables-save` 백업이 `/root/iptables.backup.<epoch>` 에 있다.

## 5. 남은 것

- **firewalld 미가동.** 호스트 `INPUT` 은 여전히 ACCEPT 다. 켜려면 컨테이너 재시작을
  감수해야 하므로 배포 창을 잡아서 한다 — `--add-service={ssh,http,https}` 를 **permanent 로
  먼저 넣고** 시작한 뒤 `systemctl restart docker`.
- **LLMNR(`0.0.0.0:5355`).** systemd-resolved 가 연다. 링크로컬 멀티캐스트라 인터넷에서
  도달하지 않고 ACG 도 막고 있어 우선순위를 낮췄다. 끄려면
  `/etc/systemd/resolved.conf.d/` 에 `LLMNR=no`.
- **ACG 규칙은 저장소 밖이다.** NCP 콘솔에만 있어 이 문서가 그 스냅샷을 옮겨 적은 것이고,
  콘솔에서 바뀌면 여기는 따라오지 않는다. 젠킨스 도입 시 ACG 를 건드리면 이 절을 함께 고친다.

## 관련

- 젠킨스는 **`127.0.0.1:18081` 로 바인딩**한다. 위 DROP 규칙에 기대지 않고 애초에
  외부 인터페이스에 붙이지 않는 것이 1차 방어다 — `bts-minio` 가 이미 같은 패턴이다
  (`127.0.0.1:19001->9001`).
- 배포 절차 [`infra/deploy/bts-deploy.sh`](../../infra/deploy/bts-deploy.sh)
- 러너 운영 [`self-hosted-runner.md`](self-hosted-runner.md)
