// 젠킨스 compose 의 named volume 마운트 경로 ⟺ bootstrap.sh 가 소유권을 고치는 경로 차집합 판별식
//
// ## 무엇을 막는가
//
// Docker 는 **이미지에 없는 경로**에 named volume 을 붙일 때 그 디렉터리를 root:root 로 만든다.
// 컨테이너의 jenkins(uid 1000)는 거기에 못 쓴다. 그런데 이 실패는 기동에서 안 난다 —
// 컨테이너는 정상으로 뜨고, 파이프라인도 돌다가, **그 경로를 처음 쓰는 순간** 죽는다.
//
// 2026-09-09 빌드 #16 이 그 실측이다. `jenkins-gradle` 볼륨이 root 소유였고,
// 프론트 전량 30분을 다 돌고 나서 백엔드 시작 0초 만에 이걸로 떨어졌다.
//   Could not create parent directory for lock file /var/jenkins_home/.gradle/wrapper/….lck
// 배포키가 root 소유라 JCasC 가 조용히 빈 문자열로 대체했던 사고와 같은 양식이다 —
// **「붙었다」와 「쓸 수 있다」는 다르다.**
//
// ## 왜 차집합인가
//
// compose 에 볼륨을 하나 더 추가하면 그 경로도 root 소유로 생긴다. bootstrap 의
// `VOLUME_PATHS` 를 같이 안 고치면 같은 사고가 반복되고, 그때도 실패는 30분 뒤에 나온다.
// 두 목록이 서로를 검사하지 않으면 조용히 갈린다 — 이 저장소의 지배 결함 양식이다.
//
// ## 대상에서 빼는 것
//
// bind mount(`./casc.yaml` 같은 `.` 로 시작하는 좌변)는 제외한다. 호스트 파일이므로
// 디렉터리가 root 로 새로 생기는 문제가 없고, 배포키는 bootstrap 이 따로 chown 한다.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const COMPOSE = resolve(ROOT, 'infra/jenkins/docker-compose.jenkins.yml');
const BOOTSTRAP = resolve(ROOT, 'infra/jenkins/bootstrap.sh');

/** compose 의 `volumes:` 항목 중 named volume 의 컨테이너측 마운트 경로만 뽑는다. */
function namedVolumeMountPaths(yaml: string): string[] {
  const out: string[] = [];
  for (const raw of yaml.split('\n')) {
    const m = raw.match(/^\s*-\s+([^\s#]+):([^\s:#]+)(?::(ro|rw))?\s*(?:#.*)?$/);
    if (!m) continue;
    const [, left, mountPath] = m;
    if (left.startsWith('.') || left.startsWith('/')) continue; // bind mount
    out.push(mountPath);
  }
  return out;
}

/** bootstrap.sh 의 `VOLUME_PATHS="…"` 한 줄을 읽는다. */
function bootstrapChownPaths(sh: string): string[] {
  const m = sh.match(/^VOLUME_PATHS="([^"]*)"/m);
  assert.ok(m, 'bootstrap.sh 에 VOLUME_PATHS="…" 줄이 없다 — 볼륨 소유권 조정이 통째로 사라졌다.');
  return m[1].split(/\s+/).filter(Boolean);
}

test('compose 의 named volume 마운트 경로가 전부 bootstrap.sh 의 chown 대상이다', () => {
  const mounts = namedVolumeMountPaths(readFileSync(COMPOSE, 'utf8'));
  const chowned = bootstrapChownPaths(readFileSync(BOOTSTRAP, 'utf8'));

  // 양성 대조군. 파서가 아무것도 못 읽고 있으면 차집합은 언제나 0 이라 이 판별식이 공허해진다.
  assert.ok(mounts.length > 0, 'compose 에서 named volume 을 하나도 못 읽었다 — 파서가 고장났다.');
  assert.ok(chowned.length > 0, 'bootstrap.sh 에서 chown 경로를 하나도 못 읽었다 — 파서가 고장났다.');

  // `jenkins-home` 은 이미지가 이미 갖고 있는 경로라 Docker 가 소유권을 복사해 준다.
  const IMAGE_OWNED = new Set(['/var/jenkins_home']);
  const needChown = mounts.filter((p) => !IMAGE_OWNED.has(p));

  const missing = needChown.filter((p) => !chowned.includes(p));
  assert.deepEqual(
    missing,
    [],
    `compose 가 붙이는데 bootstrap.sh 가 소유권을 안 고치는 볼륨 경로가 있다.\n` +
      `그 경로는 root:root 로 생기고 컨테이너의 jenkins 가 못 쓴다. 기동은 성공하고\n` +
      `그 경로를 처음 쓰는 스테이지에서 죽는다 — 빌드 #16 은 30분 뒤에 죽었다.\n` +
      `bootstrap.sh 의 VOLUME_PATHS 에 추가하라: ${missing.join(' ')}`,
  );

  const stale = chowned.filter((p) => !mounts.includes(p));
  assert.deepEqual(
    stale,
    [],
    `bootstrap.sh 가 chown 하는데 compose 에 없는 경로가 있다 — 볼륨을 지웠는데\n` +
      `bootstrap 이 안 따라온 자리다. 남겨 두면 다음 사람이 있다고 착각한다: ${stale.join(' ')}`,
  );
});

test('bootstrap.sh 는 jenkins uid 를 상수로 적지 않는다 (컨테이너에게 묻는다)', () => {
  const sh = readFileSync(BOOTSTRAP, 'utf8');
  const assign = sh.match(/^JENKINS_UID=.*/m);
  assert.ok(assign, 'JENKINS_UID 대입 줄이 없다.');
  assert.match(
    assign[0],
    /docker run/,
    'JENKINS_UID 를 리터럴로 적었다. 이미지의 jenkins uid 가 바뀌면 그 숫자가 두 번째 목록이 된다 — ' +
      '컨테이너에게 `id -u` 로 물어야 한다.',
  );
  assert.doesNotMatch(assign[0], /=\s*["']?\d+/, 'JENKINS_UID 에 숫자 리터럴이 박혀 있다.');
});

// ─────────────────────────────────────────────────────────────────────────
// 잡 적용이 「트리거했다」가 아니라 「재등록됐다」를 확인하는지
//
// 2026-09-10 사고. `bootstrap.sh job` 이 등록 빌드를 걸고 HTTP 201 만 보고
// 「✅ 적용 완료」라고 찍었다. 그 빌드가 `Jenkinsfile not found` 로 죽었고,
// 그 뒤 잡은 폴링도 파라미터도 없는 껍데기였다 — 증상은 빨간불이 아니라 **침묵**이었다.
// 아무 빌드도 안 걸리는 상태라, 로그를 봐도 안 보인다.
// ─────────────────────────────────────────────────────────────────────────
test('★★잡 적용이 등록 빌드의 결과까지 확인한다 (201 은 큐에 들어간 것뿐)', () => {
  const sh = readFileSync(BOOTSTRAP, 'utf8');
  const jobBlock = sh.slice(sh.indexOf('  job)'));

  assert.match(
    jobBlock,
    /ParametersDefinitionProperty/,
    'bootstrap.sh job 이 재등록 **결과**를 확인하지 않는다.\n' +
      '★HTTP 201 은 「큐에 들어갔다」일 뿐이다. 등록 빌드가 죽으면 파이프라인이 파싱되지\n' +
      '  않아 parameters·triggers 가 안 살아나고, 그때 잡은 **조용히 아무것도 안 돈다.**\n' +
      '  두 파이프라인 모두 `parameters` 를 선언하므로 그 속성의 재등장이 파싱 성공의 증거다.',
  );
  assert.match(
    jobBlock,
    /미등록[\s\S]*?exit 3/,
    '재등록을 확인은 하는데 **실패해도 안 죽는다** — 경고는 읽히지 않는다.\n' +
      '  종전 코드도 실패 경로에 `⚠️` 를 찍었고, 그래서 아무도 못 봤다.',
  );
});
