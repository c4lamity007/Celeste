package dev.celestebeta.linkage;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads declaration metadata only; no dependencies, resolution, or class definition. */
final class ClassSchema {
    private ClassSchema() { }

    static Map<String, Object> read(byte[] bytes, String expectedName) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 0xcafebabe) throw new IOException("Invalid captured class format");
            int minor = in.readUnsignedShort(), major = in.readUnsignedShort();
            Object[] pool = new Object[in.readUnsignedShort()];
            for (int index = 1; index < pool.length; index++) {
                switch (in.readUnsignedByte()) {
                    case 1 -> pool[index] = in.readUTF();
                    case 7 -> pool[index] = in.readUnsignedShort();
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
                    case 5, 6 -> { in.skipNBytes(8); index++; }
                    case 8, 16, 19, 20 -> in.skipNBytes(2);
                    case 15 -> in.skipNBytes(3);
                    default -> throw new IOException("Unsupported captured constant pool tag");
                }
            }
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("minor", minor);
            schema.put("major", major);
            schema.put("access", in.readUnsignedShort());
            String name = className(pool, in.readUnsignedShort());
            if (!name.equals(expectedName.replace('.', '/'))) throw new IOException("Captured class identity mismatch");
            schema.put("name", name);
            schema.put("super", className(pool, in.readUnsignedShort()));
            List<String> interfaces = new ArrayList<>();
            for (int count = in.readUnsignedShort(); count > 0; count--) interfaces.add(className(pool, in.readUnsignedShort()));
            schema.put("interfaces", interfaces);
            schema.put("fields", members(in, pool));
            schema.put("methods", members(in, pool));
            skipAttributes(in);
            if (in.available() != 0) throw new IOException("Trailing captured class data");
            return schema;
        } catch (IndexOutOfBoundsException | ClassCastException failure) {
            throw new IOException("Malformed captured class schema", failure);
        }
    }

    private static String className(Object[] pool, int index) {
        return index == 0 ? "" : (String) pool[(Integer) pool[index]];
    }

    private static List<Map<String, Object>> members(DataInputStream in, Object[] pool) throws IOException {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int count = in.readUnsignedShort(); count > 0; count--) {
            Map<String, Object> member = new LinkedHashMap<>();
            member.put("access", in.readUnsignedShort());
            member.put("name", pool[in.readUnsignedShort()]);
            member.put("descriptor", pool[in.readUnsignedShort()]);
            result.add(member);
            skipAttributes(in);
        }
        return result;
    }

    private static void skipAttributes(DataInputStream in) throws IOException {
        for (int count = in.readUnsignedShort(); count > 0; count--) {
            in.readUnsignedShort();
            in.skipNBytes(Integer.toUnsignedLong(in.readInt()));
        }
    }
}
