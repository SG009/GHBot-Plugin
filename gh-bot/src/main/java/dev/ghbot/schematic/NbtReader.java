package dev.ghbot.schematic;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Minimal NBT reader (big-endian, optional gzip) — inverse of {@link NbtWriter}.
 * Returns nested Map<String,Object> / List / scalars / byte[] / int[].
 */
public final class NbtReader {

    private NbtReader() {}

    /** Root name and compound, reading either gzip or raw. */
    public static Result read(byte[] data) throws IOException {
        DataInputStream in;
        try {
            in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(data)));
        } catch (IOException e) {
            in = new DataInputStream(new ByteArrayInputStream(data));
        }
        int tag = in.readUnsignedByte();
        String name = in.readUTF();
        if (tag != NbtWriter.TAG_COMPOUND) throw new IOException("Root not a compound (tag=" + tag + ")");
        Map<String, Object> root = readCompound(in);
        return new Result(name, root);
    }

    public record Result(String name, Map<String, Object> root) {}

    private static Map<String, Object> readCompound(DataInputStream in) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        while (true) {
            int tag = in.readUnsignedByte();
            if (tag == NbtWriter.TAG_END) break;
            String key = in.readUTF();
            m.put(key, readValue(in, tag));
        }
        return m;
    }

    private static Object readValue(DataInputStream in, int tag) throws IOException {
        switch (tag) {
            case NbtWriter.TAG_BYTE -> { return in.readByte(); }
            case NbtWriter.TAG_SHORT -> { return in.readShort(); }
            case NbtWriter.TAG_INT -> { return in.readInt(); }
            case NbtWriter.TAG_LONG -> { return in.readLong(); }
            case NbtWriter.TAG_FLOAT -> { return in.readFloat(); }
            case NbtWriter.TAG_DOUBLE -> { return in.readDouble(); }
            case NbtWriter.TAG_BYTE_ARRAY -> {
                int n = in.readInt();
                byte[] b = new byte[n];
                in.readFully(b);
                return b;
            }
            case NbtWriter.TAG_INT_ARRAY -> {
                int n = in.readInt();
                int[] a = new int[n];
                for (int i = 0; i < n; i++) a[i] = in.readInt();
                return a;
            }
            case NbtWriter.TAG_LONG_ARRAY -> {   // v0.21.46 — Litematica BlockStates
                int n = in.readInt();
                long[] a = new long[n];
                for (int i = 0; i < n; i++) a[i] = in.readLong();
                return a;
            }
            case NbtWriter.TAG_STRING -> { return in.readUTF(); }
            case NbtWriter.TAG_LIST -> {
                int listTag = in.readUnsignedByte();
                int n = in.readInt();
                List<Object> list = new ArrayList<>(n);
                for (int i = 0; i < n; i++) list.add(readValue(in, listTag));
                return list;
            }
            case NbtWriter.TAG_COMPOUND -> { return readCompound(in); }
            default -> throw new IOException("Unknown tag " + tag);
        }
    }
}
