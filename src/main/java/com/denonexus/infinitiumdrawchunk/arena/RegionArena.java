package com.denonexus.infinitiumdrawchunk.arena;

import net.minecraft.client.render.VertexFormat;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;

/**
 * Arena de vertices de UMA regiao (cubo de 512 blocos): VBO + VAO proprios, alocacao bump com
 * free-list coalescida e um "shadow" CPU em heap.
 *
 * <p>Por que regiao: o fold so' e' valido entre draws que compartilham o mesmo uniforme
 * {@code ChunkOffset}. Dentro de uma regiao o offset e' {@code regiao - camera} (constante para
 * todas as secoes da regiao), entao todo o conteudo da regiao cabe em um unico lote; entre
 * regioes o uniforme muda e o MergeGuard quebra o lote - exatamente o desenho do Sodium.
 *
 * <p>Por que shadow: o GLES3 nao tem {@code glGetBufferSubData} (o caminho de leitura de volta
 * usado pelo OpenGL desktop). Para crescer o VBO sem ler do driver, mantemos uma copia CPU exata
 * do conteudo; no {@link #grow} reenviamos a copia inteira com {@code glBufferData} +
 * {@code glBufferSubData}.
 *
 * <p>Posicoes: o dado gravado aqui e' SECCIONAL (0..15). O ajuste para o espaco da regiao
 * ({@code + (origemDaSecao - origemDaRegiao)}) e' feito uma unica vez, na primeira fusao
 * ({@link #bake}), e o uniforme do shader cobre o resto ({@code regiao - camera}).
 */
final class RegionArena {
    /** VBO inicial de 4 MiB; dobra a cada crescimento. */
    private static final int INITIAL_CAPACITY = 4 * 1024 * 1024;

    /** Chave embebida (rx, ry, rz) - mesma embalagem usada pelo MergeGuard. */
    final long key;
    final int originX;
    final int originY;
    final int originZ;
    /** Formato compartilhado das 4 camadas de terreno; identidade conferida no capture. */
    final VertexFormat format;

    private final int stride;
    /** Free-list ordenada e coalescida: {offset, tamanho} em bytes. */
    private final ArrayList<int[]> freeList = new ArrayList<>();

    private int vbo;
    private int vao;
    private ByteBuffer shadow;
    private int capacity;
    private int bump;
    private int liveSlots;

    RegionArena(long key, int originX, int originY, int originZ, VertexFormat format, int templateIndexBuffer) {
        this.key = key;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.format = format;
        this.stride = format.getVertexSizeByte();

        this.vao = GL30C.glGenVertexArrays();
        this.vbo = GL15C.glGenBuffers();

        // Mesma sequencia do upload do vanilla: VAO ligado -> VBO ligado -> format.setupState()
        // (os ponteiros de atributo apontam para o ARRAY_BUFFER ativo). O EBO compartilhado do
        // template ja' nasce ligado neste VAO, entao emitir nao precisa mexer em atributos.
        GL30C.glBindVertexArray(this.vao);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, this.vbo);
        this.format.setupState();
        GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, templateIndexBuffer);
        GL30C.glBindVertexArray(0);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);
    }

    int vao() {
        return this.vao;
    }

    int liveSlots() {
        return this.liveSlots;
    }

    /**
     * Reserva {@code bytes} alinhados ao stride e devolve o offset, ou {@code -1} se falhar.
     */
    int alloc(int bytes) {
        if (bytes <= 0) {
            return -1;
        }
        final int size = align(bytes);

        for (int i = 0; i < this.freeList.size(); i++) {
            final int[] range = this.freeList.get(i);
            if (range[1] >= size) {
                final int start = range[0];
                if (range[1] == size) {
                    this.freeList.remove(i);
                } else {
                    range[0] += size;
                    range[1] -= size;
                }
                this.liveSlots++;
                return start;
            }
        }

        if (this.bump + size > this.capacity) {
            grow(this.bump + size);
        }

        final int start = this.bump;
        this.bump += size;
        this.liveSlots++;
        return start;
    }

    void free(int offset, int size) {
        this.liveSlots--;

        final int end = offset + size;
        int insertAt = 0;
        while (insertAt < this.freeList.size() && this.freeList.get(insertAt)[0] < offset) {
            insertAt++;
        }

        int[] range = {offset, size};

        // Coalesce com o vizinho seguinte.
        if (insertAt < this.freeList.size()) {
            final int[] next = this.freeList.get(insertAt);
            if (next[0] == end) {
                range[1] += next[1];
                this.freeList.remove(insertAt);
            }
        }
        // Coalesce com o vizinho anterior.
        if (insertAt > 0) {
            final int[] prev = this.freeList.get(insertAt - 1);
            if (prev[0] + prev[1] == offset) {
                prev[1] += range[1];
                return;
            }
        }
        this.freeList.add(insertAt, range);
    }

    /**
     * Copia {@code bytes} de {@code src} para a arena e faz o upload da sub-regiao.
     * Os bytes sao crus: as posicoes continuam seccionais ate' o {@link #bake}.
     */
    void write(int offset, ByteBuffer src, int bytes) {
        final ByteBuffer view = this.shadow.duplicate();
        view.clear();
        view.position(offset);
        view.limit(offset + bytes);

        final ByteBuffer data = src.duplicate();
        data.limit(data.position() + bytes);
        view.put(data);

        uploadSub(offset, bytes);
    }

    /**
     * Ajusta as posicoes dos vertices desta secao do espaco SECCIONAL para o espaco da regiao:
     * {@code pos += (origemDaSecao - origemDaRegiao)}. Feito uma unica vez por slot.
     */
    void bake(int offset, int vertexCount, int dx, int dy, int dz) {
        final int bytes = vertexCount * this.stride;
        final ByteBuffer view = this.shadow.duplicate();
        view.clear();
        view.position(offset);
        view.limit(offset + bytes);

        // Posicao e' o primeiro elemento de POSITION_COLOR_TEXTURE_LIGHT_NORMAL (offset 0).
        final FloatBuffer floats = view.slice().order(ByteOrder.nativeOrder()).asFloatBuffer();
        final int floatsPerVertex = this.stride >> 2;
        for (int v = 0; v < vertexCount; v++) {
            final int i = v * floatsPerVertex;
            floats.put(i, floats.get(i) + dx);
            floats.put(i + 1, floats.get(i + 1) + dy);
            floats.put(i + 2, floats.get(i + 2) + dz);
        }

        uploadSub(offset, bytes);
    }

    void destroy() {
        if (this.vbo != 0) {
            GL15C.glDeleteBuffers(this.vbo);
            this.vbo = 0;
        }
        if (this.vao != 0) {
            GL30C.glDeleteVertexArrays(this.vao);
            this.vao = 0;
        }
        if (this.shadow != null) {
            MemoryUtil.memFree(this.shadow);
            this.shadow = null;
        }
        this.freeList.clear();
        this.capacity = 0;
        this.bump = 0;
        this.liveSlots = 0;
    }

    private void uploadSub(int offset, int bytes) {
        final ByteBuffer view = this.shadow.duplicate();
        view.clear();
        view.position(offset);
        view.limit(offset + bytes);

        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, this.vbo);
        GL15C.glBufferSubData(GL15C.GL_ARRAY_BUFFER, offset, view);
    }

    private void grow(int minBytes) {
        final int newCapacity = Math.max(minBytes, Math.max(INITIAL_CAPACITY, this.capacity * 2));
        final ByteBuffer newShadow = MemoryUtil.memAlloc(newCapacity);
        if (this.shadow != null) {
            final ByteBuffer old = this.shadow.duplicate();
            old.clear();
            newShadow.put(old);
            MemoryUtil.memFree(this.shadow);
        }
        newShadow.clear();
        this.shadow = newShadow;
        this.capacity = newCapacity;

        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, this.vbo);
        // Orfandade + novo tamanho; em seguida devolve o conteudo do shadow.
        GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, (long) this.capacity, GL15C.GL_DYNAMIC_DRAW);
        if (this.bump > 0) {
            final ByteBuffer view = this.shadow.duplicate();
            view.clear();
            view.limit(this.bump);
            GL15C.glBufferSubData(GL15C.GL_ARRAY_BUFFER, 0, view);
        }
    }

    private int align(int bytes) {
        final int stride = this.stride;
        if (stride <= 0) {
            return bytes;
        }
        final int remainder = bytes % stride;
        return remainder == 0 ? bytes : bytes + (stride - remainder);
    }
}
