package com.denonexus.infinitiumdrawchunk.mixin;

import com.denonexus.infinitiumdrawchunk.arena.TerrainArena;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * H4: abre e fecha o passe de fusao ao redor de {@code WorldRenderer.renderLayer}.
 *
 * <p>O vanilla chama este metodo uma vez por camada de terreno (solido, cutout, cutout_mipped,
 * translucido) por quadro; e' exatamente a granularidade em que o Sodium faz o flush do
 * {@code MultiDrawBatch}. O HEAD guarda camera/camada; o RETURN faz o flush - no ponto em que
 * o shader do vanilla ja' esta' com os uniformes resolvidos para a camada.
 *
 * <p>Sem hook aqui o mod continuaria funcionando (os draws individuais viram submits que nunca
 * sao emitidos), mas nao haveria fusao - por isso e' requisito obrigatorio (require = 1).
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {
    /**
     * {@code renderLayer(RenderLayer, MatrixStack, double camX, double camY, double camZ,
     * Matrix4f)}. Alvo por nome (sem descritor): o metodo e' unico em WorldRenderer e o bytecode
     * oficial {@code fjv.a(fkf, eij, D, D, D, Matrix4f)} foi verificado no jar 1.20.1.
     */
    private static final String RENDER_LAYER = "renderLayer";

    @Inject(method = RENDER_LAYER, at = @At("HEAD"))
    private void infinitiumdrawchunk$beginLayer(RenderLayer layer, MatrixStack matrices,
                                                double x, double y, double z, Matrix4f projection,
                                                CallbackInfo ci) {
        TerrainArena.INSTANCE.beginLayer(layer, x, y, z);
    }

    @Inject(method = RENDER_LAYER, at = @At("RETURN"))
    private void infinitiumdrawchunk$endLayer(RenderLayer layer, MatrixStack matrices,
                                              double x, double y, double z, Matrix4f projection,
                                              CallbackInfo ci) {
        TerrainArena.INSTANCE.endLayer();
    }
}
