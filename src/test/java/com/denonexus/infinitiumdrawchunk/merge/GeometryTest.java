package com.denonexus.infinitiumdrawchunk.merge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GeometryTest {
    @Test
    void loneFaceIsAlreadyContiguous() {
        assertEquals(0b00010000, Geometry.expandToGeometryRun(0b00010000, 0xFF));
    }

    @Test
    void zeroHasNoSpan() {
        assertEquals(0, Geometry.expandToGeometryRun(0, 0xFF));
    }

    @Test
    void sparseFacesExpandIntoOneRun() {
        // faces 0 e 4 visiveis -> corrida 0..4 preenchida, limitada ao que existe na secao
        assertEquals(0b00011111, Geometry.expandToGeometryRun(0b00010001, 0xFF));
    }

    @Test
    void spanNeverExceedsGeometryMask() {
        // existem so' as faces 0 e 5: o span nao pode inventar as do meio
        final int visible = 0b100001;
        final int geometryMask = 0b100001;

        assertEquals(geometryMask, Geometry.expandToGeometryRun(visible, geometryMask));
    }

    @Test
    void vertexSpanMatchesQuadPattern() {
        assertEquals(500, Geometry.vertexSpan(750), "(750/6)*4 = 500 vertices");
        assertEquals(0, Geometry.vertexSpan(5));
    }
}
