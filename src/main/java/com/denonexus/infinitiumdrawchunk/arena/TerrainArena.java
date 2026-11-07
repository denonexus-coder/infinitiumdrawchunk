package com.denonexus.infinitiumdrawchunk.arena;

import com.denonexus.infinitiumdrawchunk.emit.DrawEmitter;
import com.denonexus.infinitiumdrawchunk.merge.Geometry;
import com.denonexus.infinitiumdrawchunk.merge.QuadIndexBuffer;
import com.denonexus.infinitiumdrawchunk.queue.SubDrawQueue;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.minecraft.client.gl.GlUniform;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.opengl.GL30C;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * F4-A: ponte entre o renderer de terreno do vanilla e o pipeline de fusao do mod.
 *
 * <p>O vanilla desenha uma secao de cada vez ({@code VertexBuffer.draw()}), um draw por
 * BufferBuilder por camada. Este coletor troca esses draws por draws da arena da regiao,
 * fundindo-os em lote:
 *
 * <ol>
 *   <li>{@link #noteOrigin} - {@code BuiltChunk.getBuffer(RenderLayer)} registra qual secao
 *       origina cada {@link VertexBuffer} (caminho H1);</li>
 *   <li>{@link #capture} - {@code VertexBuffer.upload(BuiltBuffer)} copia os vertices crus
 *       para a arena da regiao (caminho H2). O upload do vanilla NAO e' cancelado: os buffers
 *       originais continuam validos, o que torna o fallback sempre correto (2x de memoria em
 *       v1, aceito);</li>
 *   <li>{@link #onDraw} - {@code VertexBuffer.draw()} vira um {@code queue.submit} quando o
 *       passe esta' aberto e ha' slot (caminho H3);</li>
 *   <li>{@link #beginLayer}/{@link #endLayer} - {@code WorldRenderer.renderLayer} abre e fecha
 *       o passe; no fechamento ordena, faz o bake, dobra o lote e emite (caminho H4).</li>
 * </ol>
 *
 * <p>Corretude primeiro: se o shader do frame nao expuser {@code ChunkOffset} (h2, ex. shader
 * pack estranho), o passe inteiro cai no caminho vanilla - nunca ha' desenho quebrado.
 */
public final class TerrainArena {
    public static final TerrainArena INSTANCE = new TerrainArena();

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Regiao = cubo de 512 blocos ({@code >> 9}): dentro dela o ChunkOffset e' constante. */
    private static final int REGION_SHIFT = 9;
    /** Sessenta frames de espaco para coordenadas de regiao (±58.640 cabem em 17 bits). */
    private static final long REGION_BIAS = 1L << 16;

    /** Opaco: ordena por regiao e depois por baseVertex - maximiza o fold. */
    private static final Comparator<Slot> OPAQUE_ORDER = (a, b) -> {
        final int c = Long.compare(a.region.key, b.region.key);
        return c != 0 ? c : Integer.compare(a.baseVertex, b.baseVertex);
    };

    /** buffer -> secao que o originou (posicao lida fresca; o mapa e' concorrente: H1 roda na
     * thread de rebuild). */
    private final ConcurrentHashMap<VertexBuffer, ChunkBuilder.BuiltChunk> origins =
            new ConcurrentHashMap<>();

    /** Apenas render thread: capture/draw/endLayer. */
    private final IdentityHashMap<VertexBuffer, Slot> slots = new IdentityHashMap<>();
    private final HashMap<Long, RegionArena> regions = new HashMap<>();

    private final QuadIndexBuffer template = new QuadIndexBuffer();
    private final DrawEmitter emitter = new DrawEmitter();
    private final SubDrawQueue queue = new SubDrawQueue();

    /** Estado do passe atual. */
    private boolean inLayer;
    private boolean translucent;
    private double camX;
    private double camY;
    private double camZ;
    private GlUniform chunkOffset;
    private RegionArena batchRegion;

    private Slot[] pending = new Slot[1024];
    private int pendingCount;

    private int passes;
    private int fallbacks;
    private boolean loggedCapture;
    /** Kill-switch: {@code -Dinfinitiumdrawchunk.disable=true} volta 100% ao caminho vanilla. */
    private final boolean disabled = Boolean.getBoolean("infinitiumdrawchunk.disable");

    private TerrainArena() {
    }

    // ---------------------------------------------------------------- H1: origem da secao

    /**
     * Registra o vinculo buffer -> secao. Chamado no RETURN de
     * {@code ChunkBuilder$BuiltChunk.getBuffer(RenderLayer)} (thread de rebuild e' possivel).
     */
    public void noteOrigin(VertexBuffer buffer, ChunkBuilder.BuiltChunk chunk) {
        if (buffer == null || chunk == null) {
            return;
        }
        this.origins.putIfAbsent(buffer, chunk);
    }

    // ---------------------------------------------------------------- H2: captura no upload

    /**
     * Chamado no HEAD de {@code VertexBuffer.upload(BuiltBuffer)}. Copia os vertices para a
     * arena da regiao da secao. Nunca cancela o upload do vanilla (fallback intacto).
     */
    public void capture(VertexBuffer buffer, BufferBuilder.BuiltBuffer mesh) {
        if (this.disabled || buffer == null || mesh == null) {
            return;
        }
        if (!RenderSystem.isOnRenderThread()) {
            return;
        }
        try {
            captureUnsafe(buffer, mesh);
        } catch (Throwable t) {
            LOGGER.warn("[infinitiumdrawchunk] capture falhou; secao volta ao caminho vanilla", t);
            freeSlot(buffer);
        }
    }

    private void captureUnsafe(VertexBuffer buffer, BufferBuilder.BuiltBuffer mesh) {
        final ChunkBuilder.BuiltChunk chunk = this.origins.get(buffer);
        if (chunk == null) {
            freeSlot(buffer);
            return;
        }
        final BlockPos origin = chunk.getOrigin();
        if (origin == null || mesh.isEmpty()) {
            freeSlot(buffer);
            return;
        }

        final BufferBuilder.DrawParameters params = mesh.getParameters();
        // So' o formato monotonamente indexado de quads: qualquer outro caso (malha de linhas,
        // indices proprios ordenados p/ translucido) fica com o vanilla.
        if (params.mode() != VertexFormat.DrawMode.QUADS || !params.sequentialIndex()) {
            freeSlot(buffer);
            return;
        }

        final int vertexCount = params.vertexCount();
        if (vertexCount <= 0 || (vertexCount & 3) != 0) {
            freeSlot(buffer);
            return;
        }

        final VertexFormat format = params.format();
        final int stride = format.getVertexSizeByte();
        if (stride < 12 || (stride & 3) != 0) {
            freeSlot(buffer);
            return;
        }

        final ByteBuffer vertices = mesh.getVertexBuffer();
        final int bytes = vertexCount * stride;
        if (vertices == null || vertices.remaining() < bytes) {
            freeSlot(buffer);
            return;
        }

        // SOLTA A SLOT ANTIGA PRIMEIRO: se ela era a unica viva da regiao antiga, a regiao e'
        // destruida aqui — uma referencia ja' pega usaria um VAO/VBO mortos.
        freeSlot(buffer);

        final int regionX = origin.getX() >> REGION_SHIFT;
        final int regionY = origin.getY() >> REGION_SHIFT;
        final int regionZ = origin.getZ() >> REGION_SHIFT;
        final long key = packRegion(regionX, regionY, regionZ);

        RegionArena region = this.regions.get(key);
        if (region == null) {
            this.template.create();
            region = new RegionArena(key, regionX << REGION_SHIFT, regionY << REGION_SHIFT,
                    regionZ << REGION_SHIFT, format, this.template.handle());
            this.regions.put(key, region);
        } else if (region.format != format) {
            // As 4 camadas de terreno compartilham um unico VertexFormat; se algum dia deixarem
            // de compartilhar, a secao simplesmente fica no caminho vanilla.
            return;
        }

        final int offset = region.alloc(bytes);
        if (offset < 0) {
            return;
        }
        region.write(offset, vertices, bytes);

        final int indexCount =
                (vertexCount / Geometry.VERTICES_PER_PRIMITIVE) * Geometry.ELEMENTS_PER_PRIMITIVE;

        if (!this.loggedCapture) {
            this.loggedCapture = true;
            LOGGER.info("[infinitiumdrawchunk] capture ativo: arena por regiao + fold no flush de renderLayer");
        }

        this.slots.put(buffer, new Slot(buffer, region, offset, bytes, vertexCount, indexCount,
                origin.getX(), origin.getY(), origin.getZ()));
    }

    /** Chamado no HEAD de {@code VertexBuffer.close}: soltou o buffer, solta a arena. */
    public void free(VertexBuffer buffer) {
        if (buffer == null) {
            return;
        }
        freeSlot(buffer);
        this.origins.remove(buffer);
    }

    private void freeSlot(VertexBuffer buffer) {
        final Slot slot = this.slots.remove(buffer);
        if (slot == null) {
            return;
        }
        slot.region.free(slot.offsetBytes, slot.sizeBytes);
        if (slot.region.liveSlots() <= 0) {
            this.regions.remove(slot.region.key);
            slot.region.destroy();
        }
    }

    // ---------------------------------------------------------------- H3: draw por secao

    /**
     * Chamado no HEAD de {@code VertexBuffer.draw()}.
     *
     * @return {@code true} => cancela o draw do vanilla: a arena assume o sub-draw.
     */
    public boolean onDraw(VertexBuffer buffer) {
        if (!this.inLayer || buffer == null) {
            return false;
        }
        final Slot slot = this.slots.get(buffer);
        if (slot == null || slot.indexCount <= 0) {
            return false;
        }
        addPending(slot);
        return true;
    }

    // ---------------------------------------------------------------- H4: passe de camada

    public void beginLayer(RenderLayer layer, double x, double y, double z) {
        this.inLayer = true;
        this.translucent = layer == RenderLayer.getTranslucent();
        this.camX = x;
        this.camY = y;
        this.camZ = z;
        this.pendingCount = 0;
        this.batchRegion = null;
        this.queue.begin();
    }

    public void endLayer() {
        this.inLayer = false;
        final int submitted = this.pendingCount;
        try {
            if (submitted == 0 || this.disabled) {
                if (submitted > 0) {
                    fallbackVanilla();
                }
                return;
            }

            final ShaderProgram shader = RenderSystem.getShader();
            final GlUniform uniform = shader == null ? null : shader.chunkOffset;
            if (uniform == null) {
                // Sem ChunkOffset nao da' para posicionar a arena: desenha como o vanilla.
                this.fallbacks++;
                fallbackVanilla();
                return;
            }

            // Opaco: ordena (regiao, baseVertex) para maximizar o fold. Translucido preserva a
            // ordem do vanilla (pintar por ordem e' semantica da camada).
            if (!this.translucent) {
                Arrays.sort(this.pending, 0, this.pendingCount, OPAQUE_ORDER);
            }

            this.chunkOffset = uniform;
            shader.bind();
            try {
                for (int i = 0; i < this.pendingCount; i++) {
                    final Slot slot = this.pending[i];

                    // Fecha o lote se ele e' de OUTRA regiao. O guard e' a fonte da verdade,
                    // mas flushBatch() chama queue.begin() que zera o fingerprint - por isso o
                    // re-semeio logo apos fechar (sem ele, um lote de regiao A poderia receber
                    // um sub-draw da regiao B com o uniform errado).
                    if (this.queue.batch().isEmpty()
                            || this.queue.guard().current() != slot.region.key) {
                        flushBatch();
                        this.queue.uniformsChanged(slot.region.key);
                        this.batchRegion = slot.region;
                    }

                    bake(slot);

                    if (!this.queue.submit(slot.indexCount, 0L, slot.baseVertex)) {
                        // Lote cheio: emite o atual, re-semeia e recomeca com este sub-draw.
                        flushBatch();
                        this.queue.uniformsChanged(slot.region.key);
                        this.batchRegion = slot.region;
                        this.queue.submit(slot.indexCount, 0L, slot.baseVertex);
                    }
                }
                flushBatch();
                // Devolve o uniforme ao estado em que o vanilla deixou (0,0,0).
                uniform.set(0.0f, 0.0f, 0.0f);
                uniform.upload();
            } finally {
                shader.unbind();
                GL30C.glBindVertexArray(0);
            }
        } finally {
            this.pendingCount = 0;
            this.batchRegion = null;
            this.chunkOffset = null;
            maybeLog(submitted);
        }
    }

    private void flushBatch() {
        if (this.queue.batch().isEmpty()) {
            return;
        }
        final RegionArena region = this.batchRegion;
        if (region == null || this.chunkOffset == null) {
            this.queue.begin();
            return;
        }

        // A ordem Java ja' esta' certa (sort feito no inicio do flush); so' dobra.
        this.queue.fold(false);

        // O template precisa cobrir o elementoCount JA FUNDIDO (e' maior que qualquer entrada).
        final int ebo = this.template.ensureCapacity(this.queue.batch().getIndexBufferSize());

        // ChunkOffset = regiao - camera (a mesma aritmetica do vanilla, em float).
        this.chunkOffset.set(
                (float) (region.originX - this.camX),
                (float) (region.originY - this.camY),
                (float) (region.originZ - this.camZ));
        this.chunkOffset.upload();

        this.emitter.emit(this.queue.batch(), region.vao(), ebo, QuadIndexBuffer.indexTypeEnum());

        this.queue.begin();
        this.batchRegion = null;
    }

    private void bake(Slot slot) {
        if (slot.baked) {
            return;
        }
        // Posicoes seccionais -> espaco da regiao; o uniforme completa ate' a camera.
        slot.region.bake(slot.offsetBytes, slot.vertexCount,
                slot.originX - slot.region.originX,
                slot.originY - slot.region.originY,
                slot.originZ - slot.region.originZ);
        slot.baked = true;
    }

    private void fallbackVanilla() {
        for (int i = 0; i < this.pendingCount; i++) {
            final VertexBuffer buffer = this.pending[i].buffer;
            buffer.bind();
            buffer.draw();
        }
        VertexBuffer.unbind();
    }

    private void addPending(Slot slot) {
        if (this.pendingCount == this.pending.length) {
            this.pending = Arrays.copyOf(this.pending, this.pending.length * 2);
        }
        this.pending[this.pendingCount++] = slot;
    }

    private void maybeLog(int submitted) {
        this.passes++;
        if (this.passes % 600 != 0) {
            return;
        }
        LOGGER.info("[infinitiumdrawchunk] passes={} pendentes={} regioes={} slots={} fallbacks={} stats={}",
                this.passes, submitted, this.regions.size(), this.slots.size(),
                this.fallbacks, this.queue.stats());
    }

    private static long packRegion(int x, int y, int z) {
        return ((x + REGION_BIAS) << 34) | ((y + REGION_BIAS) << 17) | (z + REGION_BIAS);
    }

    /** Slot de arena: uma copia de uma secao dentro de uma {@link RegionArena}. */
    private static final class Slot {
        final VertexBuffer buffer;
        final RegionArena region;
        final int offsetBytes;
        final int sizeBytes;
        final int vertexCount;
        final int indexCount;
        /** baseVertex = offset / stride: o fold exige offsets contiguos em vertices. */
        final int baseVertex;
        final int originX;
        final int originY;
        final int originZ;
        boolean baked;

        Slot(VertexBuffer buffer, RegionArena region, int offsetBytes, int sizeBytes,
             int vertexCount, int indexCount, int originX, int originY, int originZ) {
            this.buffer = buffer;
            this.region = region;
            this.offsetBytes = offsetBytes;
            this.sizeBytes = sizeBytes;
            this.vertexCount = vertexCount;
            this.indexCount = indexCount;
            this.baseVertex = offsetBytes / region.format.getVertexSizeByte();
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
        }
    }
}
