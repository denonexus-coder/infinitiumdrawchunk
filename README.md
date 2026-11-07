# InfinitiumDrawChunk

> Mod de Minecraft **1.20.1 / Fabric** para **desenho ultra-rápido de chunks**: dobra
> sub-draws contíguas numa única chamada de draw.
> **Independente do Sodium** (sem fork, sem dependência, sem JNI, sem lib nativa).
> Alvo: **Android / PowerVR Rogue GE8320** (`OpenGL ES 3.2 build 1.13@5776728`).

**Status: F1 + F2 aplicados — build é feito EXCLUSIVAMENTE no CI (nunca local).**

```bash
gh run list   --repo denonexus-coder/infinitiumdrawchunk
gh run watch  <run-id> --repo denonexus-coder/infinitiumdrawchunk
```

---

## O que é, em uma frase

Quem emite os draws do terreno entrega **N sub-draws** (uma por seção/fase); este mod
**funde as contíguas** (`baseVertex` exatamente encadeado no mesmo buffer de índices) e
**emite menos chamadas ao driver** — porque na GE8320 **cada draw call custa ~10 µs**.

| Medido na GPU real (1920×1080, 1000 seções, shader real do MC) | Valor |
|---|---|
| 1000 draws (unroll core ES3.2) | 17,6 ms |
| 715 draws (fusão) | 7,5 ms |
| **1 draw (fusão total)** | **0,23 ms** |
| `glMultiDrawElementsEXT` (1 chamada) | 55–111 ms ❌ 3× pior |
| `glMultiDrawElementsIndirectEXT` | não executa (`GL_EXT_draw_indirect` ausente) |
| Otimizar instruções do shader | **0 de ganho** (frame é draw-call bound) |

---

## O que está dentro (e o que ficou de fora)

**Dentro — só o que faz a fusão funcionar:**

| Arquivo | Origem |
|---|---|
| `merge/MultiDrawBatch.java` | 🔵 **cópia 1:1 do Sodium** (só o package mudou) — o fold e o sort |
| `merge/Geometry.java` (`expandToGeometryRun`) | 🔵 corpo copiado do Sodium (bit math) |
| `merge/QuadIndexBuffer.java` | 🟢 reescrito — template de índices monotônico (o do Sodium puxa 9 classes `Gl*`) |
| `merge/MergeGuard.java` | 🟢 reescrito — quebra o lote se uniform por-draw mudar |
| `queue/SubDrawQueue.java` | 🟢 reescrito — API `begin/submit/fold` |
| `emit/DrawEmitter.java` | 🟢 reescrito — loop de `glDrawElementsBaseVertex` (core ES3.2) |
| `emit/MergeStats.java` | 🟢 reescrito — `subDrawsIn → subDrawsOut` (a prova do ganho) |
| `gradle/`, `gradlew*`, workflow | 🔵 Gradle Wrapper oficial + CI espelhado do fork |

**Fora de propósito** (não é fusão): meshing, WorldSlice, iluminação, geração de mundo,
a camada de dispositivo `Gl*` do Sodium, `SectionRenderDataUnsafe` (Unsafe do formato de
malha do Sodium), GUI/options do Sodium, JNI, `libmobileglues.so`.

**Proibições deste projeto:** nada de clone do Sodium, nada de build local, nada de JNI.

---

## Regras de ouro (tiradas do código, não de cabeça)

1. **Ordem obrigatória:** `submits → [sortByBaseVertex] → mergeContiguousCommands() → ensureCapacity() DEPOIS`
   — o comando fundido reporta `elementCount` **maior**, o template tem que cobrir.
2. **Regra de dobra:** `elementPointerIgual && inBase == outBase + (count/6)*4`.
3. `pElementPointer` **nunca é escrito** = `0` em todos (é o template compartilhado).
4. **Sort nunca no passe translúcido** (a ordem é a ordem de pintura).
5. **Guard de uniform:** fundir draws com uniform diferente cola seção na outra — `MergeGuard` impede.
6. **Nada de multi-draw/indirect:** medidos 3× pior ou inexistentes na GE8320.

---

## Roadmap

| Fase | Entrega | Status |
|---|---|---|
| **F1** | Esqueleto compila (loom 1.9.2, yarn 1.20.1, Java 17) | 🔄 **CI** |
| **F2** | Fusão offline: teste prova `1000 → 1`, `gap → 2`, `sort → 1`, `window → 2` | 🔄 **CI** (JUnit) |
| **F3** | `DrawEmitter` + `QuadIndexBuffer` na GPU real | ⬜ |
| **F4** | Alimentador: **(A)** a mod assume o arena do terreno do vanilla ou **(B)** expõe `submit()` para outros mods | ⬜ **decisão pendente** |
| **F5** | Números finais + opções (`merge`, `sort`, `widen`) | ⬜ |

---

## Licença (atualizado — não é LGPL)

O Sodium é **PolyForm Shield License 1.0.0** (eu tinha escrito LGPL por engano no plano
anterior; corrigido). Texto completo em [`LICENSE.md`](LICENSE.md).

Obrigação que este repo cumpre: [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)
lista exatamente os 2 arquivos derivados e avisa sobre a cláusula **Noncompete**
(não pode competir com o Sodium nem ser comercializado como substituto dele).

---

## Comandos de controle (CI only)

```bash
gh run list  --repo denonexus-coder/infinitiumdrawchunk          # status
gh run watch <id> --repo denonexus-coder/infinitiumdrawchunk      # acompanhar
gh run view  <id> --repo denonexus-coder/infinitiumdrawchunk --log-failures   # erro
gh release create <tag> <jar> --repo ...                          # entregar o jar
```
