package com.denonexus.infinitiumdrawchunk.mixin;

import com.denonexus.infinitiumdrawchunk.arena.TerrainArena;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.BufferBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * H2/H3: upload (captura) e draw (sub-draw) de cada {@link VertexBuffer} de terreno.
 *
 * <ul>
 *   <li><b>upload</b>: copia os vertices crus para a arena da regiao. O upload do vanilla NAO
 *       e' cancelado - os buffers originais ficam validos, entao qualquer falha de captura ou
 *       shader sem {@code ChunkOffset} cai num caminho vanilla sempre correto (v1 aceita 2x de
 *       memoria por isso);</li>
 *   <li><b>draw</b>: quando o passe esta' aberto e existe slot, cancela o draw do vanilla e
 *       registra um sub-draw ({@code queue.submit}) para o flush de {@code renderLayer};</li>
 *   <li><b>close</b>: soltou o buffer, soltou a arena (evita vazamento em unload de mundo).</li>
 * </ul>
 *
 * <p>{@code draw} e' por nome + descritor porque VertexBuffer tem dois {@code draw} (o de
 * {@code ()V} e o de {@code (Matrix4f, Matrix4f, ShaderProgram)}).
 */
@Mixin(VertexBuffer.class)
public class VertexBufferMixin {
    @Inject(method = "upload(Lnet/minecraft/client/render/BufferBuilder$BuiltBuffer;)V",
            at = @At("HEAD"))
    private void infinitiumdrawchunk$capture(BufferBuilder.BuiltBuffer mesh, CallbackInfo ci) {
        TerrainArena.INSTANCE.capture((VertexBuffer) (Object) this, mesh);
    }

    @Inject(method = "draw()V", at = @At("HEAD"), cancellable = true)
    private void infinitiumdrawchunk$draw(CallbackInfo ci) {
        if (TerrainArena.INSTANCE.onDraw((VertexBuffer) (Object) this)) {
            ci.cancel();
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void infinitiumdrawchunk$close(CallbackInfo ci) {
        TerrainArena.INSTANCE.free((VertexBuffer) (Object) this);
    }
}
