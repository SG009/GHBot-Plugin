package dev.ghbot.schematic;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Little-endian uncompressed NBT — Bedrock Edition's on-disk format.
 * Sibling of {@link NbtWriter} (big-endian, optional gzip) used by Java
 * .schem/.nbt/.litematic. Strings are UTF-8 with a little-endian unsigned
 * short length (NOT Java modified-UTF-8).
 *
 * <p>v0.27.1 — needed for {@code .mcstructure} export. The existing
 * {@link NbtWriter} cannot emit Bedrock files: even an uncompressed BE
 * compound is rejected by Bedrock structure blocks.
 */
public final class LeNbtWriter {

    public static final int TAG_END = NbtWriter.TAG_END,
            TAG_BYTE = NbtWriter.TAG_BYTE, TAG_SHORT = NbtWriter.TAG_SHORT,
            TAG_INT = NbtWriter.TAG_INT, TAG_LONG = NbtWriter.TAG_LONG,
            TAG_FLOAT = NbtWriter.TAG_FLOAT, TAG_DOUBLE = NbtWriter.TAG_DOUBLE,
            TAG_BYTE_ARRAY = NbtWriter.TAG_BYTE_ARRAY, TAG_STRING = NbtWriter.TAG_STRING,
            TAG_LIST = NbtWriter.TAG_LIST, TAG_COMPOUND = NbtWriter.TAG_COMPOUND,
            TAG_INT_ARRAY = NbtWriter.TAG_INT_ARRAY, TAG_LONG_ARRAY = NbtWriter.TAG_LONG_ARRAY;

    /** Cap on list/array length so a corrupt file cannot OOM the importer. */
    static final int MAX_LIST = 16_000_000;

    private LeNbtWriter() {}

    public static byte[] writeRoot(String name, Map<String, Object> root) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TAG_COMPOUND);
        writeString(out, name == null ? "" : name);
        writeCompound(out, root);
        return out.toByteArray();
    }

    /**
     * True when {@code data} looks like an uncompressed little-endian NBT
     * compound (Bedrock). Gzip / Java big-endian files return false.
     * Sniff: compound tag + the first key's name-length is stored LE
     * (low byte first, high byte 0 for the short ASCII keys we emit).
     */
    public static boolean looksLittleEndian(byte[] data) {
        if (data == null || data.length < 8) return false;
        if ((data[0] & 0xFF) != TAG_COMPOUND) return false;
        int rootLenLE = (data[1] & 0xFF) | ((data[2] & 0xFF) << 8);
        int rootLenBE = ((data[1] & 0xFF) << 8) | (data[2] & 0xFF);
        int keyLenAt;
        if (rootLenLE == 0 && rootLenBE == 0) {
            keyLenAt = 4;                         // empty root name: [3]=tag, [4..5]=key length
        } else if (rootLenLE > 0 && rootLenLE < 256 && rootLenLE != rootLenBE) {
            int tagOff = 3 + rootLenLE;
            if (tagOff + 3 >= data.length) return false;
            keyLenAt = tagOff + 1;
        } else {
            return false;
        }
        if (keyLenAt + 1 >= data.length) return false;
        int keyLenLE = (data[keyLenAt] & 0xFF) | ((data[keyLenAt + 1] & 0xFF) << 8);
        int keyLenBE = ((data[keyLenAt] & 0xFF) << 8) | (data[keyLenAt + 1] & 0xFF);
        return keyLenLE > 0 && keyLenLE < 128 && keyLenLE != keyLenBE;
    }

    /** Inverse of {@link #writeRoot} — little-endian, never gzip. */
    public static NbtReader.Result read(byte[] data) throws IOException {
        if (data == null || data.length < 3) throw new IOException("truncated little-endian NBT");
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        try {
            int tag = buf.get() & 0xFF;
            String name = readString(buf);
            if (tag != TAG_COMPOUND) throw new IOException("Root not a compound (tag=" + tag + ")");
            return new NbtReader.Result(name, readCompound(buf));
        } catch (java.nio.BufferUnderflowException e) {
            throw new IOException("truncated little-endian NBT", e);
        }
    }

    private static void writeCompound(ByteArrayOutputStream out, Map<String, Object> c) throws IOException {
        if (c != null) {
            for (Map.Entry<String, Object> e : c.entrySet()) {
                Object v = e.getValue();
                if (v == null) continue;
                int tag = tagOf(v);
                out.write(tag);
                writeString(out, e.getKey());
                writeValue(out, v, tag);
            }
        }
        out.write(TAG_END);
    }

    private static int tagOf(Object v) {
        if (v instanceof Byte) return TAG_BYTE;
        if (v instanceof Short) return TAG_SHORT;
        if (v instanceof Integer) return TAG_INT;
        if (v instanceof Long) return TAG_LONG;
        if (v instanceof Float) return TAG_FLOAT;
        if (v instanceof Double) return TAG_DOUBLE;
        if (v instanceof byte[]) return TAG_BYTE_ARRAY;
        if (v instanceof int[]) return TAG_INT_ARRAY;
        if (v instanceof long[]) return TAG_LONG_ARRAY;
        if (v instanceof String) return TAG_STRING;
        if (v instanceof List<?>) return TAG_LIST;
        return TAG_COMPOUND;
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(ByteArrayOutputStream out, Object v, int tag) throws IOException {
        switch (tag) {
            case TAG_BYTE -> out.write((Byte) v);
            case TAG_SHORT -> writeShort(out, (Short) v);
            case TAG_INT -> writeInt(out, (Integer) v);
            case TAG_LONG -> writeLong(out, (Long) v);
            case TAG_FLOAT -> writeInt(out, Float.floatToIntBits((Float) v));
            case TAG_DOUBLE -> writeLong(out, Double.doubleToLongBits((Double) v));
            case TAG_BYTE_ARRAY -> {
                byte[] b = (byte[]) v;
                writeInt(out, b.length);
                out.write(b);
            }
            case TAG_INT_ARRAY -> {
                int[] a = (int[]) v;
                writeInt(out, a.length);
                for (int i : a) writeInt(out, i);
            }
            case TAG_LONG_ARRAY -> {
                long[] a = (long[]) v;
                writeInt(out, a.length);
                for (long l : a) writeLong(out, l);
            }
            case TAG_STRING -> writeString(out, (String) v);
            case TAG_LIST -> writeList(out, (List<?>) v);
            case TAG_COMPOUND -> writeCompound(out, stringify((Map<?, ?>) v));
            default -> throw new IOException("unknown NBT tag " + tag);
        }
    }

    private static void writeList(ByteArrayOutputStream out, List<?> list) throws IOException {
        if (list == null || list.isEmpty()) {
            out.write(TAG_END);
            writeInt(out, 0);
            return;
        }
        int tag = tagOf(list.get(0));
        out.write(tag);
        writeInt(out, list.size());
        for (Object item : list) writeValue(out, item, tag);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stringify(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (m != null) m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private static void writeString(ByteArrayOutputStream out, String s) throws IOException {
        byte[] b = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        if (b.length > 65535) throw new IOException("NBT string too long (" + b.length + ")");
        writeShort(out, b.length);
        out.write(b);
    }

    private static void writeShort(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 24) & 0xFF);
    }

    private static void writeLong(ByteArrayOutputStream out, long v) {
        for (int i = 0; i < 8; i++) out.write((int) ((v >>> (8 * i)) & 0xFF));
    }

    private static Map<String, Object> readCompound(ByteBuffer buf) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        while (true) {
            int tag = buf.get() & 0xFF;
            if (tag == TAG_END) break;
            String key = readString(buf);
            m.put(key, readValue(buf, tag));
        }
        return m;
    }

    private static Object readValue(ByteBuffer buf, int tag) throws IOException {
        switch (tag) {
            case TAG_BYTE -> { return buf.get(); }
            case TAG_SHORT -> { return buf.getShort(); }
            case TAG_INT -> { return buf.getInt(); }
            case TAG_LONG -> { return buf.getLong(); }
            case TAG_FLOAT -> { return buf.getFloat(); }
            case TAG_DOUBLE -> { return buf.getDouble(); }
            case TAG_BYTE_ARRAY -> {
                int n = checkLen(buf.getInt());
                byte[] b = new byte[n];
                buf.get(b);
                return b;
            }
            case TAG_INT_ARRAY -> {
                int n = checkLen(buf.getInt());
                int[] a = new int[n];
                for (int i = 0; i < n; i++) a[i] = buf.getInt();
                return a;
            }
            case TAG_LONG_ARRAY -> {
                int n = checkLen(buf.getInt());
                long[] a = new long[n];
                for (int i = 0; i < n; i++) a[i] = buf.getLong();
                return a;
            }
            case TAG_STRING -> { return readString(buf); }
            case TAG_LIST -> {
                int listTag = buf.get() & 0xFF;
                int n = checkLen(buf.getInt());
                List<Object> list = new ArrayList<>(Math.min(n, 1024));
                for (int i = 0; i < n; i++) list.add(readValue(buf, listTag));
                return list;
            }
            case TAG_COMPOUND -> { return readCompound(buf); }
            default -> throw new IOException("Unknown little-endian NBT tag " + tag);
        }
    }

    private static String readString(ByteBuffer buf) throws IOException {
        int len = Short.toUnsignedInt(buf.getShort());
        if (len > 65535) throw new IOException("NBT string too long");
        byte[] b = new byte[len];
        buf.get(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static int checkLen(int n) throws IOException {
        if (n < 0 || n > MAX_LIST) throw new IOException("NBT list/array too large: " + n);
        return n;
    }
}
