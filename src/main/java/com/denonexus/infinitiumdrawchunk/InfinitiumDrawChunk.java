package com.denonexus.infinitiumdrawchunk;

import com.denonexus.infinitiumdrawchunk.queue.SubDrawQueue;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entrypoint do mod.
 *
 * <p>InfinitiumDrawChunk NAO depende do Sodium, nao usa JNI e nao carrega nenhuma
 * lib nativa: todo o caminho de emissao e Java/LWJGL puro. Ele contem apenas o que
 * e necessario para dobrar sub-draws contiguas e emitir o draw fundido.
 */
public final class InfinitiumDrawChunk implements ModInitializer {
    public static final String MOD_ID = "infinitiumdrawchunk";

    private static final Logger LOGGER = LoggerFactory.getLogger("InfinitiumDrawChunk");

    @Override
    public void onInitialize() {
        LOGGER.info("InfinitiumDrawChunk carregado — nucleo de merge ativo (capacidade padrao: {} sub-draws)",
                SubDrawQueue.DEFAULT_CAPACITY);
    }
}
