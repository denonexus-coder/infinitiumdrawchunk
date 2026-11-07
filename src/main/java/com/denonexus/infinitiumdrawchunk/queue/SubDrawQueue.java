package com.denonexus.infinitiumdrawchunk.queue;

import com.denonexus.infinitiumdrawchunk.emit.MergeStats;
import com.denonexus.infinitiumdrawchunk.merge.Geometry;
import com.denonexus.infinitiumdrawchunk.merge.MergeGuard;
import com.denonexus.infinitiumdrawchunk.merge.MultiDrawBatch;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;

/**
 * Fila de sub-draws + fusao + estatistica. E' a API publica do mod:
 * quem estiver renderizando chama {@link #submit} por sub-draw e {@link #flush} para
 * obter o lote fundido pronto para o {@link com.denonexus.infinitiumdrawchunk.emit.DrawEmitter}.
 *
 * <p>Ciclo:
 * <pre>
 *   queue.begin();                         // zera o lote do frame/passe
 *   queue.submit(count, offset, baseVertex);   // uma vez por sub-draw
 *   queue.flush();                         // [sort] -> fold -> stats
 *   emitter.emit(queue.batch(), ...);      // emite
 * </pre>
 *
 * <p>Registra o custo da fusao em {@link #stats()} — se a fusao comecar a custar mais do
 * que economiza, esse numero entrega.
 */
public final class SubDrawQueue {
    /** Capacidade padrao de lote (uma regiao x faces, com folga). */
    public static final int DEFAULT_CAPACITY = Geometry.DEFAULT_BATCH_CAPACITY;

    private final MultiDrawBatch batch;
    private final MergeGuard guard = new MergeGuard();
    private final MergeStats stats = new MergeStats();

    private int pendingBefore;
    private long pendingNanos;

    public SubDrawQueue() {
        this(DEFAULT_CAPACITY);
    }

    public SubDrawQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0: " + capacity);
        }
        this.batch = new MultiDrawBatch(capacity);
    }

    /** Comeca um lote novo (frame ou passe). */
    public void begin() {
        this.batch.clear();
        this.guard.reset();
        this.pendingBefore = 0;
        this.pendingNanos = 0;
    }

    /**
     * Adiciona uma sub-draw.
     *
     * @param elementCount  quantos elementos o draw le (multiplo de 6 para o template de quad)
     * @param elementOffset offset em BYTES dentro do buffer de indices compartilhado
     * @param baseVertex    vertice base da secao (e' isso que a fusao exige contiguo)
     * @return {@code false} se o lote esta' cheio (a sub-draw foi DESCARTADA — nada e' escrito
     *         fora dos limites)
     */
    public boolean submit(int elementCount, long elementOffset, int baseVertex) {
        if (this.batch.size >= this.batch.capacity() || elementCount <= 0) {
            return false;
        }

        final long slot = (long) this.batch.size << 2;

        MemoryUtil.memPutInt(this.batch.pElementCount + slot, elementCount);
        MemoryUtil.memPutInt(this.batch.pBaseVertex + slot, baseVertex);
        MemoryUtil.memPutAddress(this.batch.pElementPointer + ((long) this.batch.size << Pointer.POINTER_SHIFT), elementOffset);

        this.batch.size++;
        this.pendingBefore++;

        return true;
    }

    /**
     * Observa o estado de uniformes do proximo draw; se mudou, o lote atual precisa ser
     * emitido antes de continuar (senao os draws fundidos usariam o uniform errado).
     *
     * @return {@code true} se o lote atual deve ser emitido AGORA
     */
    public boolean uniformsChanged(long uniformFingerprint) {
        return this.guard.uniformsChanged(uniformFingerprint);
    }

    /**
     * Funde as sub-draws do lote.
     *
     * @param sortByBaseVertex ordena por vertice base antes de dobrar (encontra corridas mais
     *                         longas; NUNCA usar no passe translucido, onde a ordem e' a ordem
     *                         de pintura)
     * @return numero de draws apos a fusao
     */
    public int fold(boolean sortByBaseVertex) {
        final int before = this.batch.size;

        final long t0 = System.nanoTime();

        if (sortByBaseVertex) {
            this.batch.sortByBaseVertex();
        }

        this.batch.mergeContiguousCommands();

        final long elapsed = System.nanoTime() - t0;

        this.stats.record(before, this.batch.size, elapsed);

        this.pendingBefore = 0;
        this.pendingNanos += elapsed;

        return this.batch.size;
    }

    /** Conveniencia: funde sem ordenar (o caso padrao, identico ao Sodium com sort desligado). */
    public int fold() {
        return fold(false);
    }

    /** Libera a memoria off-heap do lote (chamar ao descartar a fila). */
    public void delete() {
        this.batch.delete();
    }

    public MultiDrawBatch batch() {
        return this.batch;
    }

    public MergeGuard guard() {
        return this.guard;
    }

    public MergeStats stats() {
        return this.stats;
    }
}
