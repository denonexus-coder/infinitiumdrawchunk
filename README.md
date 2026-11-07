# InfinitiumDrawChunk

> Mod de Minecraft **1.20.1 / Fabric** para **desenho ultra-rápido de chunks**.
> Base estrita: **Sodium** (CaffeineMC) — LGPL-3.0.
> Alvo de hardware: **Android / PowerVR Rogue GE8320** (driver `OpenGL ES 3.2 build 1.13@5776728`).

**Status: Fase 0 — planejamento.** Este repo nasce **apenas com este plano**. Nenhum código do
Sodium entra aqui até que o plano seja aprovado (decisão explícita do autor).

---

## 1. Objetivo

Eliminar o gargalo de **emissão de draw calls** no render de terreno.

Todo o planejamento abaixo deriva de medições feitas na GPU real, não de suposição:

| Fato medido (GE8320, 1920×1080, 1000 seções / 500k vértices, shader real do MC) | Valor |
|---|---|
| Custo fixo por draw call | **~10 µs** (1000 draws ≈ 10–21 ms) |
| Merge (EBO compartilhado + `baseVertex`, 1000 → 1 draw) | **0,23 ms** |
| Merge realista (1000 → 715 draws) | **7,5 ms** |
| Unroll (1000 draws core ES3.2) | 17,6 ms |
| `glMultiDrawElementsEXT` / `BaseVertexEXT` (1 chamada) | 55–111 ms ❌ 3× pior |
| `glMultiDrawElementsIndirectEXT` | não executa (`GL_EXT_draw_indirect` ausente) |
| Otimizar instruções do shader (remover varying morto, compor matrizes) | **0 de ganho** |

**Conclusão que guia o projeto:** o frame é *draw-call bound*.
O ganho está em **emitir menos draws**, não em shaders mais espertos, e **não** em extensões
multi-draw (as disponíveis na GE8320 são lentas ou inexistentes).

### Metas

| Métrica | Baseline hoje | Meta |
|---|---|---|
| Custos por sub-draw no lote | ~10 µs | **≤ 3 µs** |
| Draws emitidos / frame (cena de 1000 seções) | 715–1000 | **≤ 64** |
| Custo do lote inteiro | 7,5 ms | **≤ 2,0 ms** |
| Overhead de CPU do próprio mod | medido no F2 | ≤ 0,5 ms/frame |

---

## 2. Escopo

**Dentro do escopo**
- Batch/fold determinístico de draws de terreno (regra *baseVertex* contíguo + EBO compartilhado).
- Caminho nativo opcional (detecta `libmobileglues.so`; sem ele, degrada para o caminho Java/OpenGL do Sodium).
- Telemetria e benchmark embutidos para provar cada ganho.
- Compatível com shaderpacks: qualquer programa que seja *merge-safe* continua funcionando.

**Fora do escopo (não-objetivos)**
- Gerência de mundo, iluminação (Starlight), meshing (greedy) — já resolvidos por Sodium/forks.
- Compilar shader no build time.
- Depender de extensões que a GE8320 não tem (`GL_EXT_draw_indirect`, `GL_EXT_base_instance`).
- Touch na engine de partículas/entidades.

---

## 3. Base estrita no Sodium — o que é aproveitado

Referências são caminhos **do fork atual** (`denonexus-coder/sodium`, branch `ultra-greedy-v4-sodium`):

| Arquivo | Papel no mod novo |
|---|---|
| `sodium/.../client/gl/device/GLRenderDevice.java` (`:301`, `:321`) | **Ponto de integração**: onde o desenho sai para o driver (hoje chama `MGNativeDrawer.drawBatchZeroCopy`) |
| `sodium/.../client/gl/device/MultiDrawBatch.java` (`:124 mergeContiguousCommands`) | **Algoritmo de dobra** — regra autoritativa de o que pode ser fundido |
| `sodium/.../render/chunk/DefaultChunkRenderer.java` (`:255`, `:331`, `:413`) | Montagem do lote: um sub-draw por conjunto de faces |
| `sodium/.../client/gl/tessellation/GlVertexArrayTessellation.java` | Handle real do VAO passado ao caminho nativo (zero-copy) |
| `sodium/.../render/chunk/shader/ChunkShaderInterface.java` / `ChunkShaderOptions.java` | Interface de shader — precisa permanecer *merge-safe* |
| `sodium/.../render/chunk/afk/AFKBenchRunner.java` | Harness de benchmark já existente — vira baseline do F2 |
| `LICENSE.md` (LGPL-3.0) | **Preservado integralmente** em qualquer cópia de código |

**Regra de ouro:** enquanto o plano estiver aqui, este repo é só documentação. A cópia do código
só acontece na Fase 1, com a licença preservada e sem alterar a branch `ultra-greedy-v4-sodium`.

---

## 4. Arquitetura alvo (4 camadas)

```
L3  Shader interface (Sodium + shaderpacks)        ← precisa ser merge-safe
L2  Nativo opcional (libmobileglues.so)            ← detectado, não obrigatório
L1  Batch/fold engine (o coração do mod)           ← regra baseVertex contíguo
L0  Emissão (GLRenderDevice)                       ← o Sodium já entrega isto
```

- **L0 — Emissão:** permanece a do Sodium. Só é tocado para redirecionar para L1/L2.
- **L1 — Batch/fold:** estende `mergeContiguousCommands` para
  (a) ordenar por `baseVertex` antes de dobrar;
  (b) **quebrar o lote quando qualquer uniform por-draw mudar** (é o que impede colisão de seções);
  (c) respeitar o limite de tamanho do EBO (guarda já existente: "correctness first, the fold second").
- **L2 — Nativo opcional:** zero-copy via `MGNativeDrawer.drawBatchZeroCopy`; se a lib não existe,
  `isSupported()` → false e o mod segue 100% pelo caminho Java/OpenGL (nenhuma dependência dura).
- **L3 — Shader:** os programas do terreno **não podem ler estado por-draw**
  (`gl_VertexID`, `gl_InstanceID`, `gl_DrawID`, `gl_BaseVertex`). Verificado: o ESSL atual do
  Sodium **não lê nenhum** ✅. Validador automático no F6.

---

## 5. Roadmap (fases com critério de aceitação)

| Fase | Entrega | Critério de aceitação |
|---|---|---|
| **F0** | Este plano | Plano aprovado pelo autor |
| **F1** | Repo com base do Sodium (cópia nova, `LICENSE.md` intacta, build verde) | `gradlew build` OK; mod carrega no 1.20.1 Fabric |
| **F2** | Telemetria + benchmark: contadores `subDrawsIn/Out`, `µs/frame`, harness no GE8320 | Número baseline reproduzido (17,6 ms unroll / 7,5 ms merge) com ±30% |
| **F3** | Fold determinístico com **quebra por uniform** | Imagem idêntica ao baseline (screenshot diff = 0) e draws ≤ 64 por lote |
| **F4** | Caminho nativo zero-copy (opcional, detecta a lib) | Sem lib: igual a F3. Com lib: custo do lote ≤ 2,0 ms |
| **F5** | Amortização: pré-alocação de buffers, VAO caching, upload de uniforms uma vez por lote | Overhead CPU ≤ 0,5 ms/frame |
| **F6** | Validador de shaderpack (`mg_shaderc`: glslang → SPIR-V → SPIRV-Cross → ESSL) | Pack inválido reportado **antes** de entrar no jogo |

Cada fase só começa quando a anterior passa no critério **na GPU real**.

---

## 6. Metodologia de benchmark (obrigatória)

1. Sempre no driver real: `LD_LIBRARY_PATH=/system/lib64`, via `gpubench.sh`. **Nunca emulado.**
2. Mínimo de **3 rodadas intercaladas** — a variância do SoC é ±30% (relógio/térmica).
3. Comparar sempre contra a mesma cena (1000 seções, 500k vértices, shader real do MC convertido
   pelo pipeline do próprio MG: glslang → SPIR-V → SPIRV-Cross → ESSL).
4. Um "ganho" só é ganho se sobreviver ao teste de imagem (screenshot diff) **e** ao teste térmico.

---

## 7. Riscos

| Risco | Mitigation |
|---|---|
| O frame é *draw-call bound* — shader leve não ajuda (medido) | Todo esforço em L0/L1, não em L3 |
| Extensões multi-draw não prestam na GE8320 (medido) | Só chamadas core ES3.2 (`glDrawElementsBaseVertex`) |
| Dobrar draws com uniform diferente quebra a imagem | Quebra de lote por mudança de uniform (F3) |
| Layout de índice errado anula o merge (medição: EBO por-seção fez Merge cair de 0,23 → 13 ms) | Congelar o layout *EBO compartilhado + `baseVertex`* |
| UBO (`u_baseVertices`) está morto: shader declara `binding = 0`, o MG grava em `3`, e nada lê | F4: corrigir binding **e** fazer o shader ler, ou remover o bloco |
| Iris ativo pode sequestrar a emissão | Teste de F6 com pack ativo: contador `subDrawsIn → Out` tem que continuar caindo |
| CI do fork falha com erros de mappings Java (pré-existente) | Não bloqueia F0/F2; tratar à parte |

---

## 8. Licença e compliance

- Sodium é **LGPL-3.0**: qualquer código copiado mantém `LICENSE.md`, atribuição e alterações
  publicadas (obrigação LGPL ao distribuir binário).
- Repos proibidos de push neste projeto: **MobileGlues/Mobrender** (nunca) e `origin` do Sodium
  (CaffeineMC) — push de Sodium só no remote `fork`.
- Este repo recebe push apenas a si mesmo.

---

## 9. Estrutura alvo (entra na Fase 1)

```
infinitiumdrawchunk/
├── README.md                 ← este plano
├── LICENSE.md                ← LGPL-3.0 (Sodium)
├── docs/
│   └── BENCHMARKS.md          ← toda medição, com data + GPU
├── src/main/java/...          ← código derivado do Sodium
├── src/main/resources/fabric.mod.json   ← id: infinitiumdrawchunk
└── gradle/ build.gradle.kts
```

---

## 10. Comandos

```bash
# criar o repo (feito)
gh repo create infinitiumdrawchunk --public

# depois de aprovado o plano (Fase 1)
gh repo clone denonexus-coder/infinitiumdrawchunk
# copiar a base do fork do Sodium preservando LICENSE.md
# NUNCA: git push origin  (CaffeineMC)
# NUNCA: push no repo do MobileGlues/Mobrender
```

---

*Plano F0 — pronto para revisão. Próximo passo: aprovação e Fase 1.*
