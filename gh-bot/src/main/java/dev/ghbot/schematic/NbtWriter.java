package dev.ghbot.schematic;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal NBT writer (big-endian, gzip optional) — no external deps.
 * Used by the schematic codecs (Sponge v2/v3, vanilla structure, litematic
 * uses its own NBT too). Supports the subset we need: byte, short, int,
 * long, float, double, byte[], int[], string, list, compound.
 */
public final class NbtWriter {

    public static final int TAG_END = 0, TAG_BYTE = 1, TAG_SHORT = 2, TAG_INT = 3,
            TAG_LONG = 4, TAG_FLOAT = 5, TAG_DOUBLE = 6, TAG_BYTE_ARRAY = 7,
            TAG_STRING = 8, TAG_LIST = 9, TAG_COMPOUND = 10, TAG_INT_ARRAY = 11;

    private NbtWriter() {}

    public static byte[] writeRoot(String name, Map<String, Object> root, boolean gzip) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeByte(TAG_COMPOUND);
        out.writeUTF(name == null ? "" : name);
        writeCompound(out, root);
        out.flush();
        byte[] data = bos.toByteArray();
        if (gzip) {
            ByteArrayOutputStream gz = new ByteArrayOutputStream();
            try (java.util.zip.GZIPOutputStream g = new java.util.zip.GZIPOutputStream(gz)) {
                g.write(data);
            }
            return gz.toByteArray();
        }
        return data;
    }

    private static void writeCompound(DataOutputStream out, Map<String, Object> c) throws IOException {
        for (Map.Entry<String, Object> e : c.entrySet()) {
            Object v = e.getValue();
            if (v == null) continue;
            if (v instanceof Byte) {
                out.writeByte(TAG_BYTE); out.writeUTF(e.getKey()); out.writeByte((Byte) v);
            } else if (v instanceof Short) {
                out.writeByte(TAG_SHORT); out.writeUTF(e.getKey()); out.writeShort((Short) v);
            } else if (v instanceof Integer) {
                out.writeByte(TAG_INT); out.writeUTF(e.getKey()); out.writeInt((Integer) v);
            } else if (v instanceof Long) {
                out.writeByte(TAG_LONG); out.writeUTF(e.getKey()); out.writeLong((Long) v);
            } else if (v instanceof Float) {
                out.writeByte(TAG_FLOAT); out.writeUTF(e.getKey()); out.writeFloat((Float) v);
            } else if (v instanceof Double) {
                out.writeByte(TAG_DOUBLE); out.writeUTF(e.getKey()); out.writeDouble((Double) v);
            } else if (v instanceof byte[]) {
                byte[] b = (byte[]) v;
                out.writeByte(TAG_BYTE_ARRAY); out.writeUTF(e.getKey()); out.writeInt(b.length); out.write(b);
            } else if (v instanceof int[]) {
                int[] a = (int[]) v;
                out.writeByte(TAG_INT_ARRAY); out.writeUTF(e.getKey()); out.writeInt(a.length);
                for (int i : a) out.writeInt(i);
            } else if (v instanceof String) {
                out.writeByte(TAG_STRING); out.writeUTF(e.getKey()); out.writeUTF((String) v);
            } else if (v instanceof List<?>) {
                out.writeByte(TAG_LIST); out.writeUTF(e.getKey());
                writeList(out, (List<?>) v);
            } else if (v instanceof Map<?, ?>) {
                out.writeByte(TAG_COMPOUND); out.writeUTF(e.getKey());
                writeCompound(out, stringify((Map<?, ?>) v));
            }
        }
        out.writeByte(TAG_END);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stringify(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void writeList(DataOutputStream out, List<?> list) throws IOException {
        if (list.isEmpty()) {
            out.writeByte(TAG_END);
            out.writeInt(0);
            return;
        }
        Object first = list.get(0);
        int tag;
        if (first instanceof Byte) tag = TAG_BYTE;
        else if (first instanceof Short) tag = TAG_SHORT;
        else if (first instanceof Integer) tag = TAG_INT;
        else if (first instanceof Long) tag = TAG_LONG;
        else if (first instanceof Float) tag = TAG_FLOAT;
        else if (first instanceof Double) tag = TAG_DOUBLE;
        else if (first instanceof byte[]) tag = TAG_BYTE_ARRAY;
        else if (first instanceof String) tag = TAG_STRING;
        else if (first instanceof List<?>) tag = TAG_LIST;
        else tag = TAG_COMPOUND;
        out.writeByte(tag);
        out.writeInt(list.size());
        for (Object item : list) {
            switch (tag) {
                case TAG_BYTE -> out.writeByte((Byte) item);
                case TAG_SHORT -> out.writeShort((Short) item);
                case TAG_INT -> out.writeInt((Integer) item);
                case TAG_LONG -> out.writeLong((Long) item);
                case TAG_FLOAT -> out.writeFloat((Float) item);
                case TAG_DOUBLE -> out.writeDouble((Double) item);
                case TAG_BYTE_ARRAY -> { byte[] b = (byte[]) item; out.writeInt(b.length); out.write(b); }
                case TAG_STRING -> out.writeUTF((String) item);
                case TAG_LIST -> writeList(out, (List<?>) item);
                case TAG_COMPOUND -> writeCompound(out, stringify((Map<?, ?>) item));
            }
        }
    }
}
