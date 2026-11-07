package com.denonexus.infinitiumdrawchunk.merge;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Buffer de indices COMPARTILHADO com o padrao monotono de quad (6 elementos / 4 vertices).
 *
 * <p>Escrito do zero para LWJGL puro: o {@code SharedQuadIndexBuffer} do Sodium depende da
 * camada de dispositivo Gl* inteira (CommandList, GlMutableBuffer, GlIndexType...), que nao
 * queremos arrastar para este mod. A regra e a mesma: o template so cresce, nunca encolhe.
 *
 * <p>Ordem obrigatoria (copiada do Sodium, {@code DefaultChunkRenderer:316-331}):
 * <pre>
 *   sub-draws  ->  [sortByBaseVertex]  ->  mergeContiguousCommands()  ->  ensureCapacity(...)  DEPOIS
 * </pre>
 * Porque o comando fundido reporta um {@code elementCount} MAIOR do que qualquer entrada,
 * o template precisa cobrir a maior contagem pos-fusao — chamar antes nao serve.
 *
 * <p>Indices gravados como 32 bits (cobre corridas gigantes sem estourar; o custo de banda
 * contra 16 bits fica por conta do F3, medido na GPU real antes de trocar).
 */
public final class QuadIndexBuffer {
    private int handle;
    private int maxPrimitives;

    /** Aloca o buffer GL (id empilha em zero ate' o primeiro uso). */
    public void create() {
        if (this.handle == 0) {
            this.handle = GL15C.glGenBuffers();
        }
    }

    public int handle() {
        return this.handle;
    }

    public int maxPrimitives() {
        return this.maxPrimitives;
    }

    /**
     * Garante que o template cobre {@code elementCount} elementos. Chamar DEPOIS da fusao.
     *
     * @param elementCount a maior contagem de elementos do lote ({@code batch.getIndexBufferSize()})
     * @return o handle do buffer, ja' com capacidade suficiente
     */
    public int ensureCapacity(int elementCount) {
        create();

        if (elementCount <= 0) {
            return this.handle;
        }

        final int primitiveCount = (elementCount + (Geometry.ELEMENTS_PER_PRIMITIVE - 1))
                / Geometry.ELEMENTS_PER_PRIMITIVE;

        if (primitiveCount <= this.maxPrimitives) {
            return this.handle;
        }

        // Cobre alem' do pedido para nao realocar a cada frame (mesma heuristica do Sodium:
        // dobra + folga), com teto de over-allocation.
        grow(Math.max(this.maxPrimitives * 2, primitiveCount + 16384));

        return this.handle;
    }

    private void grow(int primitiveCount) {
        // Desvincula o VAO ativo: escrever GL_ELEMENT_ARRAY_BUFFER com um VAO ligado alteraria
        // o binding de elemento dele.
        GL32C.glBindVertexArray(0);

        final int elements = primitiveCount * Geometry.ELEMENTS_PER_PRIMITIVE;

        // MemoryUtil.memAlloc so' aceita int (por isso o cast): calcula em long e recusa
        // explicitamente em vez de estourar silenciosamente.
        final long bytes = (long) elements * (long) Integer.BYTES;
        if (bytes > Integer.MAX_VALUE) {
            throw new IllegalStateException("Template de indices grande demais: " + bytes + " bytes");
        }

        final ByteBuffer buffer = MemoryUtil.memAlloc((int) bytes);

        try {
            for (int quad = 0; quad < primitiveCount; quad++) {
                final int v = quad * 4;

                buffer.putInt(v)
                        .putInt(v + 1)
                        .putInt(v + 2)
                        .putInt(v + 2)
                        .putInt(v + 3)
                        .putInt(v);
            }

            buffer.flip();

            GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, this.handle);
            GL15C.glBufferData(GL15C.GL_ELEMENT_ARRAY_BUFFER, buffer, GL15C.GL_STATIC_DRAW);
        } finally {
            MemoryUtil.memFree(buffer);
        }

        this.maxPrimitives = primitiveCount;
    }

    public void delete() {
        if (this.handle != 0) {
            GL15C.glDeleteBuffers(this.handle);
            this.handle = 0;
            this.maxPrimitives = 0;
        }
    }

    /** Enumeracao de indice usada pelo {@link QuadIndexBuffer} (32 bits). */
    public static int indexTypeEnum() {
        return GL11C.GL_UNSIGNED_INT;
    }
}
