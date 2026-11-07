package com.denonexus.infinitiumdrawchunk.emit;

import com.denonexus.infinitiumdrawchunk.merge.MultiDrawBatch;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;

/**
 * Emite o lote (ja' fundido) no driver.
 *
 * <p>Sem JNI, sem lib nativa, sem {@code glMultiDrawElementsBaseVertex}: na PowerVR GE8320 o
 * multi-draw mediu 55-111 ms contra 17-22 ms do unroll (3x PIOR), e
 * {@code GL_EXT_draw_indirect} nem existe naquele driver. O caminho vencedor e' o core
 * ES3.2: um bind de VAO/EBO e um loop de {@code glDrawElementsBaseVertex}.
 *
 * <p>Sem chamada ao driver da fusao em si — o trabalho "extra" por draw e' ~10 us na GE8320,
 * entao cada sub-draw que a fusao elimina vale direto no frame.
 */
public final class DrawEmitter {
    /**
     * Emite todos os comandos do lote.
     *
     * @param batch       lote ja' fundido
     * @param vao         handle do VAO da malha
     * @param indexBuffer handle do buffer de indices COMPARTILHADO (o do {@code QuadIndexBuffer})
     * @param indexType   enumeracao de tipo de indice ({@code QuadIndexBuffer.indexTypeEnum()})
     * @param primitive   modo primitivo ({@code GL11C.GL_TRIANGLES})
     * @return quantos draw calls foram realmente emitidos
     */
    public int emit(MultiDrawBatch batch, int vao, int indexBuffer, int indexType, int primitive) {
        if (batch == null || batch.size <= 0) {
            return 0;
        }

        GL32C.glBindVertexArray(vao);
        GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);

        int emitted = 0;

        for (int i = 0; i < batch.size; i++) {
            final long slot = (long) i << 2;

            final int count = MemoryUtil.memGetInt(batch.pElementCount + slot);

            if (count <= 0) {
                continue;
            }

            final int baseVertex = MemoryUtil.memGetInt(batch.pBaseVertex + slot);
            final long offset = MemoryUtil.memGetAddress(batch.pElementPointer + ((long) i << Pointer.POINTER_SHIFT));

            GL32C.glDrawElementsBaseVertex(primitive, count, indexType, offset, baseVertex);

            emitted++;
        }

        return emitted;
    }

    /** Sobrecarga para triangulos (o caso do terreno). */
    public int emit(MultiDrawBatch batch, int vao, int indexBuffer, int indexType) {
        return emit(batch, vao, indexBuffer, indexType, GL11C.GL_TRIANGLES);
    }
}
