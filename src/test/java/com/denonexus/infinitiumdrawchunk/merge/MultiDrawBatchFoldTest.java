package com.denonexus.infinitiumdrawchunk.merge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Prova offline (sem GPU) de que a regra de dobra faz o que promete.
 *
 * <p>Cenario 1000 sub-draws de 750 elementos (125 quad) com baseVertex avancando de 500 em
 * 500 — exatamente o layout medido no PowerVR GE8320 (1000 secoes / 500k vertices).
 */
class MultiDrawBatchFoldTest {
    /** 125 quad = 750 elementos; span = (750/6)*4 = 500 vertices. */
    private static final int COUNT = 750;
    private static final int SPAN = 500;

    private MultiDrawBatch batch;

    private void create(int capacity) {
        this.batch = new MultiDrawBatch(capacity);
    }

    private static void add(MultiDrawBatch batch, int count, long elementOffset, int baseVertex) {
        final int index = batch.size;

        MemoryUtil.memPutInt(batch.pElementCount + ((long) index << 2), count);
        MemoryUtil.memPutInt(batch.pBaseVertex + ((long) index << 2), baseVertex);
        MemoryUtil.memPutAddress(batch.pElementPointer + ((long) index << Pointer.POINTER_SHIFT), elementOffset);

        batch.size = index + 1;
    }

    @AfterEach
    void tearDown() {
        if (this.batch != null) {
            this.batch.delete();
            this.batch = null;
        }
    }

    @Test
    void contiguousRunFoldsToSingleDraw() {
        create(1024);

        for (int i = 0; i < 1000; i++) {
            add(this.batch, COUNT, 0L, i * SPAN);
        }

        this.batch.mergeContiguousCommands();

        assertEquals(1, this.batch.size, "1000 sub-draws contiguas tem que virar 1 draw");
    }

    @Test
    void gapBetweenRunsStopsTheFold() {
        create(16);

        add(this.batch, COUNT, 0L, 0);
        add(this.batch, COUNT, 0L, SPAN);      // contiguo -> dobra
        add(this.batch, COUNT, 0L, SPAN * 8);  // buraco  -> nunca dobra

        this.batch.mergeContiguousCommands();

        assertEquals(2, this.batch.size, "buraco de vertices tem que parar a dobra (imagem primeiro)");
    }

    @Test
    void differentIndexWindowStopsTheFold() {
        create(16);

        add(this.batch, COUNT, 0L, 0);
        add(this.batch, COUNT, 1500L, SPAN);   // base contiguo, mas janela de indice diferente

        this.batch.mergeContiguousCommands();

        assertEquals(2, this.batch.size, "so' dobra quem le o MESMO buffer de indices");
    }

    @Test
    void sortBeforeFoldFindsTheLongestRun() {
        create(16);

        add(this.batch, COUNT, 0L, SPAN * 2);  // fora de ordem
        add(this.batch, COUNT, 0L, 0);
        add(this.batch, COUNT, 0L, SPAN);

        this.batch.sortByBaseVertex();
        this.batch.mergeContiguousCommands();

        assertEquals(1, this.batch.size, "ordenar por baseVertex antes de dobrar acha a corrida");
    }

    @Test
    void foldedCommandReportsLargerIndexWindow() {
        create(16);

        add(this.batch, COUNT, 0L, 0);
        add(this.batch, COUNT, 0L, SPAN);

        this.batch.mergeContiguousCommands();

        assertEquals(COUNT * 2, this.batch.getIndexBufferSize(),
                "e' por isso que ensureCapacity() so' pode vir DEPOIS da fusao");
    }
}
