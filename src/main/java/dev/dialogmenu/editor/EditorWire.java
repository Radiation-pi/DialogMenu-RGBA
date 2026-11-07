package dev.dialogmenu.editor;

import java.io.*;
import java.util.*;

/** Bukkit 插件消息与原版自定义载荷共用的有界分帧。 */
public final class EditorWire {
    public static final String CHANNEL = "dialogmenu:editor";
    public static final int CHUNK = 24_000, MAX = 8 * 1024 * 1024;
    private static final int MAGIC = 0x444D4501;
    private EditorWire() {}

    public static List<byte[]> frames(byte[] message) throws IOException {
        if (message.length > MAX) throw new IOException("Editor response exceeds 8 MiB");
        int count = Math.max(1, (message.length + CHUNK - 1) / CHUNK);
        UUID id = UUID.randomUUID();
        List<byte[]> frames = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeLong(id.getMostSignificantBits());
            out.writeLong(id.getLeastSignificantBits());
            out.writeInt(i);
            out.writeInt(count);
            out.writeInt(message.length);
            out.write(message, i * CHUNK, Math.min(CHUNK, message.length - i * CHUNK));
            frames.add(bytes.toByteArray());
        }
        return frames;
    }

    public static final class Receiver {
        private UUID id;
        private byte[][] parts;
        private int total, received;
        private long started;

        public synchronized byte[] accept(byte[] frame, long now) throws IOException {
            if (frame.length < 32 || frame.length > CHUNK + 32) throw new IOException("Invalid editor frame size");
            var in = new DataInputStream(new ByteArrayInputStream(frame));
            if (in.readInt() != MAGIC) throw new IOException("Unsupported editor protocol");
            UUID next = new UUID(in.readLong(), in.readLong());
            int index = in.readInt(), count = in.readInt(), length = in.readInt();
            if (length < 0 || length > MAX || count != Math.max(1, (length + CHUNK - 1) / CHUNK)
                || index < 0 || index >= count) throw new IOException("Invalid editor frame header");
            int expected = index + 1 == count ? length - index * CHUNK : CHUNK;
            if (in.available() != expected) throw new IOException("Truncated editor frame");
            if (id != null && now - started > 15_000) clear();
            if (id == null) {
                if (index != 0) throw new IOException("Missing first editor frame");
                id = next; parts = new byte[count][]; total = length; started = now;
            }
            if (!id.equals(next) || count != parts.length || total != length || parts[index] != null)
                throw new IOException("Conflicting or repeated editor frame");
            parts[index] = in.readAllBytes();
            if (++received != count) return null;
            var bytes = new ByteArrayOutputStream(total);
            for (byte[] part : parts) bytes.write(part);
            clear();
            return bytes.toByteArray();
        }

        public synchronized void clear() { id = null; parts = null; total = received = 0; }
    }
}
