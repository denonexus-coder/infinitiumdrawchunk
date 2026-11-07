package com.denonexus.infinitiumdrawchunk.emit;

/**
 * Contadores do merge: a prova numerica de que a fusao esta' acontecendo.
 *
 * <p>Equivalente simples ao {@code FrameTrace} do Sodium (sem arquivo, sem overlay): o
 * numero que interessa e' {@code subDrawsIn - subDrawsOut}, o mesmo que o Sodium publica
 * em {@code SUBDRAWS_FOLDED}.
 */
public final class MergeStats {
    private long subDrawsIn;
    private long subDrawsOut;
    private long batches;
    private long mergeNanos;

    /** Registra um lote: sub-draws antes da fusao, depois da fusao e custo da fusao em ns. */
    public void record(int before, int after, long nanos) {
        this.subDrawsIn += before;
        this.subDrawsOut += after;
        this.batches++;
        this.mergeNanos += nanos;
    }

    public long subDrawsIn() {
        return this.subDrawsIn;
    }

    public long subDrawsOut() {
        return this.subDrawsOut;
    }

    public long folded() {
        return this.subDrawsIn - this.subDrawsOut;
    }

    public long batches() {
        return this.batches;
    }

    public long mergeNanos() {
        return this.mergeNanos;
    }

    public double mergeMillis() {
        return this.mergeNanos / 1_000_000.0;
    }

    public void reset() {
        this.subDrawsIn = 0;
        this.subDrawsOut = 0;
        this.batches = 0;
        this.mergeNanos = 0;
    }

    @Override
    public String toString() {
        return "MergeStats{batches=" + this.batches
                + ", subDrawsIn=" + this.subDrawsIn
                + ", subDrawsOut=" + this.subDrawsOut
                + ", folded=" + folded()
                + ", mergeMs=" + String.format("%.3f", mergeMillis())
                + '}';
    }
}
