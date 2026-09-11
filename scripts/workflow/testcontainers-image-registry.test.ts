// Testcontainers 이미지가 **사라진 레지스트리**를 가리키지 않는지 대조
//
// ## 왜 있나 — 2026-09-11 실측
//
// GHA 로 백엔드 검증을 되살리자 3모듈이 동시에 죽었다. 오류는
// `ContainerFetchException` → `NotFoundException` 이었고, 처음에는 Docker Hub 익명 pull
// 한도로 오독했다. 실제 원인은 다르다.
//
//     docker manifest inspect alpine:latest              → OK   (네트워크·인증 정상)
//     docker manifest inspect quay.io/tembo/pg16-pgmq    → OK
//     curl hub.docker.com/v2/repositories/minio/minio/   → **object not found**
//     docker manifest inspect quay.io/minio/minio:latest → 존재
//
// **MinIO 가 Docker Hub 에서 이미지를 내렸다.** 배포는 quay.io 로 계속된다.
// 태그를 고정해 둔 파일도 함께 죽었다 — 사라진 것은 태그가 아니라 저장소 전체였다.
//
// ## ★젠킨스에서는 왜 안 보였나
//
// 같은 러너를 계속 쓰면 이미지가 로컬 캐시에 남는다. 레지스트리에서 사라져도 **이미 받은
// 것은 계속 돈다.** 그래서 이 결함은 「깨끗한 머신에서 처음 받을 때」만 드러나고,
// self-hosted 러너는 그 조건을 거의 만들지 않는다. GHA 가 매번 새 머신이라 즉시 드러났다.
//
// ## 무엇을 강제하나
//
// ① 알려진 **이전된 저장소**를 직접 가리키지 않는다 — 지금은 `minio/minio` 하나다.
// ② 그 이미지를 쓰는 곳은 `quay.io/minio/minio` 로 간다.
// ③ **비-공허 짝** — 가짜 소스로 판정이 실제로 red 를 내는지 확인한다.
//
// ★네트워크로 실재를 확인하지 않는다. 판별식이 외부 상태에 의존하면 레지스트리가 잠깐
//   느려질 때마다 빨간불이 되고, 그 빨간불은 저장소의 잘못이 아니라 읽는 법을 흐린다.
//   여기서 막는 것은 「이미 사라진 것으로 확인된 곳을 새로 가리키는 일」뿐이다.
import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const BACKEND = path.join(REPO_ROOT, 'backend')

/**
 * Docker Hub 에서 사라진 것이 확인된 저장소와, 그 대체 경로.
 *
 * 추가할 때는 **실측 근거를 주석으로 남겨라** — 「아마 없을 것」으로 넣으면 그 자체가
 * 검증되지 않은 목록이 된다.
 */
const MOVED: ReadonlyArray<{ gone: string; moved: string; checkedOn: string }> = [
  // 2026-09-11 실측 — hub.docker.com API 가 `object not found`, quay.io 에는 존재.
  { gone: 'minio/minio', moved: 'quay.io/minio/minio', checkedOn: '2026-09-11' },
]

/** `backend` 아래 Kotlin 소스 전량. */
function kotlinSources(dir: string = BACKEND, out: string[] = []): string[] {
  if (!fs.existsSync(dir)) return out
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.name === 'build' || e.name === '.gradle' || e.name === 'generated') continue
    const full = path.join(dir, e.name)
    if (e.isDirectory()) kotlinSources(full, out)
    else if (e.name.endsWith('.kt')) out.push(full)
  }
  return out
}

/**
 * 소스에서 「이전된 저장소를 직접 가리키는」 자리를 찾는다.
 *
 * 대체 경로(`quay.io/minio/minio`)는 문자열 안에 옛 이름을 포함하므로, **접두가 레지스트리
 * 호스트가 아닌 경우만** 문제로 센다. `asCompatibleSubstituteFor("minio/minio")` 같은
 * 호환 선언도 옛 이름을 쓰지만 그것은 Testcontainers 가 요구하는 형태라 정상이다 —
 * 따옴표 뒤에 `:태그` 가 붙은 **이미지 참조**만 본다.
 */
export function findMovedRefs(source: string): string[] {
  const hits: string[] = []
  for (const { gone, moved } of MOVED) {
    const re = new RegExp(`"([\\w.\\-/]*)${gone.replace('/', '\\/')}:([\\w.\\-]+)"`, 'g')
    for (const m of source.matchAll(re)) {
      const prefix = m[1] ?? ''
      if (moved.startsWith(prefix + gone) && prefix !== '') continue // quay.io/ 등 대체 경로
      if (prefix === '') hits.push(m[0])
    }
  }
  return hits
}

describe('Testcontainers 이미지가 사라진 레지스트리를 안 가리킨다', () => {
  const sources = kotlinSources()

  test('★양성 대조군 — 훑기가 비어 있지 않다', () => {
    assert.ok(sources.length > 50, `Kotlin 소스를 ${sources.length}개밖에 못 찾았다 — 훑기가 깨졌다`)
    const withContainers = sources.filter((f) => /Container\(/.test(fs.readFileSync(f, 'utf-8')))
    assert.ok(
      withContainers.length > 5,
      `Testcontainers 사용처를 ${withContainers.length}개밖에 못 찾았다 — 대조가 공허하다`,
    )
  })

  test('★★① 이전된 저장소를 직접 가리키지 않는다', () => {
    const offenders: string[] = []
    for (const f of sources) {
      const hits = findMovedRefs(fs.readFileSync(f, 'utf-8'))
      if (hits.length > 0) offenders.push(`${path.relative(REPO_ROOT, f)} — ${hits.join(', ')}`)
    }
    assert.deepEqual(
      offenders,
      [],
      'Docker Hub 에서 사라진 것이 확인된 저장소를 직접 가리킨다.\n' +
        MOVED.map((m) => `  ${m.gone} → ${m.moved} (${m.checkedOn} 실측)`).join('\n') +
        '\n\n★깨끗한 머신에서만 드러난다 — 러너에 이미지가 캐시돼 있으면 계속 통과한다.\n' +
        `해당 자리 전수.\n${offenders.join('\n')}`,
    )
  })

  test('★★② 비-공허 짝 — 가짜 소스에서 실제로 잡는다', () => {
    assert.deepEqual(
      findMovedRefs('val c = MinIOContainer("minio/minio:RELEASE.2023-09-04T19-57-37Z")'),
      ['"minio/minio:RELEASE.2023-09-04T19-57-37Z"'],
      '★비-공허 확인 실패 — Docker Hub 직접 참조를 못 잡는다. ①은 아무것도 지키지 않는다.',
    )
  })

  test('★③ 대체 경로와 호환 선언은 통과시킨다 — 거짓 red 를 막는다', () => {
    assert.deepEqual(
      findMovedRefs('DockerImageName.parse("quay.io/minio/minio:RELEASE.2023-09-04T19-57-37Z")'),
      [],
      'quay.io 대체 경로를 위반으로 읽었다 — 고친 코드가 red 가 된다',
    )
    assert.deepEqual(
      findMovedRefs('.asCompatibleSubstituteFor("minio/minio")'),
      [],
      '호환 선언을 위반으로 읽었다 — Testcontainers 가 요구하는 형태다',
    )
  })
})
