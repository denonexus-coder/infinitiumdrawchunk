// expandToGeometryRun derived from Sodium's DefaultChunkRenderer
// -- https://github.com/CaffeineMC/sodium (PolyForm Shield License 1.0.0,
// see LICENSE.md and THIRD_PARTY_NOTICES.md). Only the bit-arithmetic body
// was taken; the surrounding class (meshing, culling, GPU device layer) was not.
package com.denonexus.infinitiumdrawchunk.merge;

/**
 * Constantes geometricas da fusao e utilitarios puros (sem estado, sem GL).
 *
 * <p>Por que 6 e 4: o template de indices compartilhado guarda um padrao monotono de quad
 * (2 triangulos por quad = 6 elementos / 4 vertices). Deslizar a janela de elementos em
 * {@code C} elementos sobe todo indice em {@code (C/6)*4} vertices, e o {@code baseVertex}
 * compensa exatamente essa subida — e' isso que permite dobrar dois draws em um.
 */
public final class Geometry {
    public static final int ELEMENTS_PER_PRIMITIVE = 6;
    public static final int VERTICES_PER_PRIMITIVE = 4;

    /** Capacidade tipica de lote (uma regiao x faces, mais folga). */
    public static final int DEFAULT_BATCH_CAPACITY = 4096;

    private Geometry() {
    }

    /** Vertices cobertos por um draw de {@code elementCount} elementos. */
    public static int vertexSpan(int elementCount) {
        return (elementCount / ELEMENTS_PER_PRIMITIVE) * VERTICES_PER_PRIMITIVE;
    }

    /**
     * Junta as faces visiveis de uma secao num UNICO intervalo contiguo de bits, para que a
     * fusao encontre uma corrida continua em vez de sub-draws esparsas (uma por face).
     *
     * <p>Pre-requisito da fusao: sem este passo, o culling deixa buracos entre as faces e a
     * igualdade {@code inBase == outBase + outSpan} nunca fecha (zero dobras).
     *
     * @param visible    mascara de faces visiveis (plano por bit)
     * @param geometryMask mascara de faces que existem de fato na secao
     * @return {@code visible} com o span da primeira a ultima face preenchido, limitado ao geometryMask
     */
    public static int expandToGeometryRun(int visible, int geometryMask) {
        visible &= geometryMask;

        // Zero has no span to fill, and a lone face is contiguous by construction.
        if ((visible & (visible - 1)) == 0) {
            return visible;
        }

        final int lo = Integer.numberOfTrailingZeros(visible);
        final int hi = Integer.SIZE - 1 - Integer.numberOfLeadingZeros(visible);
        final int span = ((1 << (hi - lo + 1)) - 1) << lo;

        return visible | (geometryMask & span);
    }
}
