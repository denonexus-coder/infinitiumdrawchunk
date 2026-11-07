package com.denonexus.infinitiumdrawchunk.merge;

/**
 * Guarda de uniformes: o lote so pode ser fundido se TODOS os sub-draws compartilharem o
 * mesmo estado de uniform.
 *
 * <p>Se dois draws tiverem uniformes diferentes (ex.: um deslocamento de secao que muda por
 * draw), fundi-los fazendo os dois usarem o primeiro conjunto deixa a imagem errada — as
 * secoes colariam umas nas outras. Este guard e' o interruptor de seguranca que quebra o
 * lote exatamente no ponto em que o estado muda.
 *
 * <p>O fingerprint e' um hash dos valores de uniform do draw (quem chama calcula); nao
 * interpretamos nada aqui, so' comparamos.
 */
public final class MergeGuard {
    /** Valor sentinela: nenhum draw observado ainda. */
    private static final long UNSET = Long.MIN_VALUE;

    private long fingerprint = UNSET;

    /**
     * Observa o estado de uniformes do proximo sub-draw.
     *
     * @param uniformFingerprint hash do conjunto de uniformes deste draw
     * @return {@code true} se o lote em curso precisa ser EMITIDO ANTES (o estado mudou)
     */
    public boolean uniformsChanged(long uniformFingerprint) {
        final boolean changed = this.fingerprint != UNSET && this.fingerprint != uniformFingerprint;
        this.fingerprint = uniformFingerprint;
        return changed;
    }

    /** Limpa o estado (início de frame/passe). */
    public void reset() {
        this.fingerprint = UNSET;
    }

    public long current() {
        return this.fingerprint;
    }
}
