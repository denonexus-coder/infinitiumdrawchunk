package com.denonexus.infinitiumdrawchunk.mixin;

import com.denonexus.infinitiumdrawchunk.arena.TerrainArena;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.chunk.ChunkBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * H1: registra o vinculo {@code VertexBuffer -> secao} em
 * {@code ChunkBuilder$BuiltChunk.getBuffer(RenderLayer)}.
 *
 * <p>O upload (H2) so' tem o buffer; sem a origem da secao nao ha' como saber em qual arena
 * gravar nem quanto deslocar as posicoes. A origem e' lida fresca ({@code getOrigin()}) no
 * momento do capture, entao o valor nunca envelhece.
 *
 * <p>Target por nome: o oficial {@code fmp$c.a(Lfkf;)Leim;} -> intermediary {@code method_3656}
 * -> Yarn {@code getBuffer} foi verificado no yarn.tiny (o outro metodo com a mesma
 * assinatura chama-se {@code method_22779} e nao e' este).
 */
@Mixin(ChunkBuilder.BuiltChunk.class)
public class BuiltChunkMixin {
    @Inject(method = "getBuffer", at = @At("RETURN"))
    private void infinitiumdrawchunk$noteOrigin(RenderLayer layer,
                                                CallbackInfoReturnable<VertexBuffer> cir) {
        final VertexBuffer buffer = cir.getReturnValue();
        if (buffer != null) {
            TerrainArena.INSTANCE.noteOrigin(buffer, (ChunkBuilder.BuiltChunk) (Object) this);
        }
    }
}
