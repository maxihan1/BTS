// 젠킨스 자산의 두 목록이 서로를 검사하는지 대조 — 플러그인 · 파이프라인 동시성
//
// ## ① plugins.txt ⊄ plugins.lock.txt — 2026-09-11 실측
//
// `plugins.txt` 는 **우리가 원하는 것**, `plugins.lock.txt` 는 **컨테이너에 실제로 깔린 것**
// (의존까지 풀린 폐포)이다. 전자가 후자에 포함돼야 하는데 실측에서 1건이 빠져 있었다.
//
//     comm -23 <(plugins.txt) <(plugins.lock.txt)  →  pipeline-graph-view
//
// `pipeline-graph-view` 는 「배포 stage 의 input 승인이 그래프 위 클릭 가능한 승인 버튼이
// 된다」는 이유로 `plugins.txt` 에 적힌 것이다. lock 에 없다는 건 **그 기능이 실제로는
// 안 깔려 있다**는 뜻이고, 그래서 승인 버튼이 안 보이는 증상이 설명된다.
//
// 두 파일이 서로를 검사하지 않아 이 어긋남이 조용했다.
//
// ## ② bts-e2e 의 abortPrevious 를 아무도 안 봤다
//
// `ci-concurrency-coverage.test.ts` 가 `const PIPELINE = 'Jenkinsfile'` 하나만 읽었다.
// `Jenkinsfile.e2e` 의 `disableConcurrentBuilds(abortPrevious: true)` 는 **지워도 전량
// 초록**이었다. 그런데 그것이 정책 1의 「CD 이후 E2E 진행 중 새 빌드가 나오면 중단하고
// 새 빌드로 다시」를 구현하는 유일한 자리다.
import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const JENKINS_DIR = join(ROOT, 'infra/jenkins');

/** 주석·빈 줄을 걷어내고 플러그인 이름만 뽑는다(`name:version` 서식). */
function pluginNames(file: string): string[] {
  return readFileSync(join(JENKINS_DIR, file), 'utf-8')
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => l !== '' && !l.startsWith('#'))
    .map((l) => l.split(':')[0] as string)
    .sort();
}

/** 저장소 루트의 파이프라인 정의 실물. 이름을 열거하지 않는다. */
function pipelineFiles(): string[] {
  return readdirSync(ROOT)
    .filter((f) => f === 'Jenkinsfile' || f.startsWith('Jenkinsfile.'))
    .sort();
}

describe('① 플러그인 선언 ⟺ 실제 설치 (차집합)', () => {
  test('★비-공허 확인 — 두 목록을 실제로 읽었다', () => {
    assert.ok(pluginNames('plugins.txt').length > 5, 'plugins.txt 를 못 읽었다.');
    assert.ok(pluginNames('plugins.lock.txt').length > 20, 'plugins.lock.txt 를 못 읽었다.');
  });

  test('★★선언한 플러그인이 전부 lock 에 있다', () => {
    const want = new Set(pluginNames('plugins.txt'));
    const have = new Set(pluginNames('plugins.lock.txt'));
    const missing = [...want].filter((p) => !have.has(p)).sort();
    assert.deepEqual(
      missing,
      [],
      `plugins.txt 에 있는데 lock 에 없는 플러그인: ${missing.join(', ')}\n` +
        '★`plugins.txt` 는 **원하는 것**, `lock` 은 **실제로 깔린 것**이다.\n' +
        '  빠진 것은 그 기능이 실제로는 없다는 뜻인데, 두 파일이 서로를 안 봐서 조용하다.\n' +
        '  처방. 컨테이너를 다시 만들고 lock 을 재생성하거나, 그 줄을 plugins.txt 에서 뺀다.',
    );
  });
});

describe('② 동시성 보장이 모든 파이프라인에 걸린다', () => {
  test('★비-공허 확인 — 파이프라인이 둘 이상 실재한다', () => {
    const files = pipelineFiles();
    assert.ok(
      files.length >= 2,
      `파이프라인 파일을 ${files.length}개밖에 못 찾았다: ${files.join(', ')}\n` +
        '  아래 루프가 공허해진다.',
    );
  });

  test('★★모든 파이프라인이 abortPrevious 로 동시 빌드를 막는다', () => {
    // ★**코드 줄**만 본다. 주석이 그 호출을 인용하는 경우가 있어(다른 파이프라인을
    //   설명하는 자리), 파일 전체를 보면 옵션 블록을 지워도 통과한다.
    const offenders = pipelineFiles().filter((f) => {
      const code = readFileSync(join(ROOT, f), 'utf-8')
        .split('\n')
        .filter((l) => !/^\s*(\/\/|\*|\/\*)/.test(l))
        .join('\n');
      return !/disableConcurrentBuilds\(\s*abortPrevious:\s*true\s*\)/.test(code);
    });
    assert.deepEqual(
      offenders,
      [],
      `abortPrevious 가 없는 파이프라인: ${offenders.join(', ')}\n` +
        '★2코어 머신에 executor 가 1개다. 동시 빌드를 허용하면 서로 자원을 뺏고,\n' +
        '  조립 부팅의 postgres 포트가 충돌한다.\n' +
        '★`Jenkinsfile.e2e` 에서는 더 중요하다 — 정책의 「CD 이후 E2E 진행 중 새 빌드가\n' +
        '  나오면 중단하고 새 빌드로 다시」를 구현하는 **유일한 자리**다.\n' +
        '  종전 판별식은 `Jenkinsfile` 하나만 읽어 그 자리를 지우면 전량 초록이었다.',
    );
  });
});
