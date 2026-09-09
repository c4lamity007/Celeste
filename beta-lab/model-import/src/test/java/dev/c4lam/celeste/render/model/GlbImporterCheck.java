package dev.c4lam.celeste.render.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Consumer;

/** Actual JSON+BIN accessors, skin, material and animation decoding; no Minecraft or GL context. */
public final class GlbImporterCheck {
    private static int assertions;
    private GlbImporterCheck() {}

    public static void main(String[] args) throws Exception {
        triangleAndMaterial();
        embeddedImages();
        skinAndAnimation();
        interleavedAndIntegerFormats();
        sceneSelection();
        malformedInputs();
        System.out.println("GLB importer: PASS " + assertions + " assertions (synthetic binary fixtures, no game QA)");
    }

    private static void triangleAndMaterial() throws Exception {
        Fixture fixture = new Fixture();
        GlbModel model = GlbImporter.parse(fixture.glb());
        eq(model.meshes().size(), 1, "mesh count");
        GlbModel.Primitive primitive = model.meshes().getFirst().primitives().getFirst();
        eq(primitive.vertexCount(), 3, "indexed triangle vertex count");
        truth(Arrays.equals(primitive.indices(), new int[]{0, 1, 2}), "decoded indices");
        near(primitive.positions()[3], 1, "little-endian POSITION");
        near(primitive.normals()[2], 1, "generated triangle normal");
        near(primitive.normals()[5], 1, "generated normal copied to each vertex");
        truth(primitive.joints() == null && primitive.weights() == null, "rigid geometry has no palette attributes");
        truth(Arrays.equals(model.sceneNodes(), new int[]{0}), "default scene traversal");
        GlbModel.Material material = model.materials().get(primitive.material());
        near(material.baseColorFactor()[0], .2, "base color factor");
        truth(material.doubleSided() && material.alphaMode() == GlbModel.AlphaMode.MASK, "material alpha and sidedness");
        near(material.alphaCutoff(), .3, "alpha cutoff");
        eq(material.image(), -1, "untextured material");
    }

    private static void embeddedImages() throws Exception {
        for (String format : new String[]{"png", "jpeg"}) {
            Fixture fixture = new Fixture();
            BufferedImage source = new BufferedImage(2, 1, format.equals("png") ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
            source.setRGB(0, 0, 0xffe02010); source.setRGB(1, 0, 0xffe02010);
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            truth(ImageIO.write(source, format, encoded), "standard image writer available");
            int view = fixture.view(encoded.toByteArray(), 0);
            fixture.json.add("images", JsonParser.parseString("[{\"bufferView\":" + view + ",\"mimeType\":\"image/" + format + "\"}]").getAsJsonArray());
            fixture.json.add("samplers", JsonParser.parseString("[{\"wrapS\":33071,\"wrapT\":33648,\"magFilter\":9728}]").getAsJsonArray());
            fixture.json.add("textures", JsonParser.parseString("[{\"source\":0,\"sampler\":0}]").getAsJsonArray());
            fixture.material().getAsJsonObject("pbrMetallicRoughness").add("baseColorTexture", JsonParser.parseString("{\"index\":0}"));
            GlbModel model = GlbImporter.parse(fixture.glb());
            GlbModel.Image image = model.images().getFirst();
            eq(image.width(), 2, "embedded image width"); eq(image.height(), 1, "embedded image height");
            eq(image.rgba().length, 8, "RGBA output count");
            truth(Byte.toUnsignedInt(image.rgba()[0]) > 180 && Byte.toUnsignedInt(image.rgba()[1]) < 70, "decoded PNG/JPEG red pixel");
            eq(Byte.toUnsignedInt(image.rgba()[3]), 255, "opaque alpha");
            GlbModel.Material material = model.materials().getFirst();
            eq(material.image(), 0, "base-color image reference");
            truth(material.wrapS() == 33071 && material.wrapT() == 33648 && !material.linearFiltering(), "sampler flags retained");
        }
        Fixture oversized = new Fixture();
        BufferedImage wide = new BufferedImage(4097, 1, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        truth(ImageIO.write(wide, "png", encoded), "oversized-dimension fixture encoded");
        int view = oversized.view(encoded.toByteArray(), 0);
        oversized.json.add("images", JsonParser.parseString("[{\"bufferView\":" + view + ",\"mimeType\":\"image/png\"}]").getAsJsonArray());
        reject(oversized.glb(), "4096");
    }

    private static void skinAndAnimation() throws Exception {
        Fixture fixture = new Fixture();
        fixture.rig();
        GlbModel model = GlbImporter.parse(fixture.glb());
        GlbModel.Primitive primitive = model.meshes().getFirst().primitives().getFirst();
        eq(primitive.joints().length, 12, "four influences per vertex");
        near(primitive.weights()[0], 1, "normalized unsigned byte skin weight");
        near(model.skins().getFirst().inverseBindMatrices()[12], -1, "inverse-bind translation");
        near(model.animations().getFirst().duration(), 2, "clip duration seconds");
        truth(model.animations().getFirst().channels().getFirst().target() == GlbModel.Target.TRANSLATION, "translation target");
        GlbPose pose = new GlbPose(model).sample(0, 1, false);
        near(pose.nodeMatrix(1)[12], 2, "hierarchical animation sampling");
        near(pose.skinMatrices(2)[12], 1, "bone world multiplied by inverse bind");
        float[] positions = new float[9], normals = new float[9];
        pose.skin(2, 0, positions, normals);
        near(positions[0], 1, "CPU skinned model-space vertex does not reapply mesh translation");
        near(positions[3], 2, "CPU skinned second vertex");
        near(normals[2], 1, "CPU skinned normal");
        pose.sample(-1, 0, false).skin(2, 0, positions, normals);
        near(positions[0], 0, "bind pose restored after animation");
    }

    private static void interleavedAndIntegerFormats() throws Exception {
        for (int component : new int[]{5121, 5123, 5125}) {
            Fixture fixture = new Fixture();
            ByteBuffer data = ByteBuffer.allocate(3 * 28).order(ByteOrder.LITTLE_ENDIAN);
            float[][] positions = {{0, 0, 0}, {1, 0, 0}, {0, 1, 0}};
            for (float[] position : positions) {
                data.putFloat(99);
                for (float value : position) data.putFloat(value);
                data.putFloat(0).putFloat(0).putFloat(1);
            }
            int view = fixture.view(data.array(), 28);
            int position = fixture.accessor(view, 4, 5126, "VEC3", 3, false);
            int normal = fixture.accessor(view, 16, 5126, "VEC3", 3, false);
            fixture.attributes().addProperty("POSITION", position); fixture.attributes().addProperty("NORMAL", normal);
            byte[] indexBytes = component == 5121 ? new byte[]{0, 1, 2} : component == 5123 ? shorts(0, 1, 2) : ints(0, 1, 2);
            fixture.primitive().addProperty("indices", fixture.data(indexBytes, component, "SCALAR", 3, false));
            int uv = fixture.data(shorts(0, 0, 65535, 0, 0, 65535), 5123, "VEC2", 3, true);
            fixture.attributes().addProperty("TEXCOORD_0", uv);
            GlbModel.Primitive primitive = GlbImporter.parse(fixture.glb()).meshes().getFirst().primitives().getFirst();
            near(primitive.positions()[3], 1, "interleaved position offset/stride");
            near(primitive.normals()[8], 1, "interleaved normal offset/stride");
            near(primitive.texCoords()[2], 1, "normalized uint16 UV");
            truth(Arrays.equals(primitive.indices(), new int[]{0, 1, 2}), "unsigned indices " + component);
        }
    }

    private static void sceneSelection() throws Exception {
        Fixture fixture = new Fixture();
        fixture.json.add("nodes", JsonParser.parseString("[{\"mesh\":0},{\"translation\":[9,0,0],\"children\":[2]},{\"mesh\":0}]").getAsJsonArray());
        fixture.json.add("scenes", JsonParser.parseString("[{\"nodes\":[0]},{\"nodes\":[1]}]").getAsJsonArray());
        fixture.json.addProperty("scene", 1);
        GlbModel selected = GlbImporter.parse(fixture.glb());
        truth(Arrays.equals(selected.sceneNodes(), new int[]{1, 2}), "only selected scene is visible");
        near(new GlbPose(selected).nodeMatrix(2)[12], 9, "parent transform carried to selected child");
        fixture.json.remove("scene");
        truth(Arrays.equals(GlbImporter.parse(fixture.glb()).sceneNodes(), new int[]{0}), "missing default index chooses first scene");
        fixture.json.remove("scenes");
        eq(GlbImporter.parse(fixture.glb()).sceneNodes().length, 3, "no scenes chooses all hierarchy roots");
    }

    private static void malformedInputs() throws Exception {
        reject(null, "between");
        Fixture fixture = new Fixture();
        byte[] wrongMagic = fixture.glb(); wrongMagic[0] = 0; reject(wrongMagic, "Not a binary");
        byte[] wrongVersion = fixture.glb(); wrongVersion[4] = 1; reject(wrongVersion, "version 2");
        byte[] length = fixture.glb(); length[8]++; reject(length, "length");
        byte[] badChunk = fixture.glb(); ByteBuffer.wrap(badChunk).order(ByteOrder.LITTLE_ENDIAN).putInt(12, Integer.MAX_VALUE); reject(badChunk, "chunk length");
        rejects(f -> f.json.getAsJsonArray("buffers").get(0).getAsJsonObject().addProperty("uri", "../../outside.bin"), "URIs");
        rejects(f -> f.json.add("images", JsonParser.parseString("[{\"uri\":\"https://example.invalid/texture.png\"}]").getAsJsonArray()), "URIs");
        rejects(f -> f.json.add("extensionsRequired", JsonParser.parseString("[\"KHR_draco_mesh_compression\"]").getAsJsonArray()), "Required glTF extensions");
        rejects(f -> f.primitive().add("extensions", JsonParser.parseString("{\"KHR_draco_mesh_compression\":{}}")), "compressed");
        rejects(f -> f.primitive().addProperty("mode", 5), "TRIANGLES");
        rejects(f -> f.primitive().remove("indices"), "Non-indexed");
        rejects(f -> f.primitive().add("targets", new JsonArray()), "Morph targets");
        rejects(f -> f.attributes().addProperty("JOINTS_1", 0), "four joint");
        rejects(f -> f.accessors.get(0).getAsJsonObject().add("sparse", new JsonObject()), "Sparse");
        rejects(f -> f.accessors.get(0).getAsJsonObject().addProperty("count", 1_500_001), "Accessor range");
        rejects(f -> f.accessors.get(0).getAsJsonObject().addProperty("byteOffset", 2), "alignment");
        rejects(f -> f.accessors.get(0).getAsJsonObject().addProperty("normalized", true), "normalized");
        rejects(f -> f.views.get(0).getAsJsonObject().addProperty("byteStride", 256), "stride");
        rejects(f -> f.views.get(0).getAsJsonObject().addProperty("byteLength", Integer.MAX_VALUE), "exceeds");
        rejects(f -> f.primitive().addProperty("indices", f.data(shorts(0, 1, 3), 5123, "SCALAR", 3, false)), "Triangle index");
        rejects(f -> f.attributes().addProperty("POSITION", f.data(floats(Float.NaN, 0, 0, 1, 0, 0, 0, 1, 0), 5126, "VEC3", 3, false)), "NaN");
        rejects(f -> f.attributes().addProperty("NORMAL", f.data(floats(0, 0, 1), 5126, "VEC3", 1, false)), "NORMAL count");
        rejects(f -> f.json.add("nodes", JsonParser.parseString("[{\"mesh\":0,\"children\":[1]},{\"children\":[0]}]").getAsJsonArray()), "cycle");
        rejects(f -> f.json.add("nodes", JsonParser.parseString("[{\"mesh\":0,\"children\":[2]},{\"children\":[2]},{}]").getAsJsonArray()), "multiple parents");
        rejects(f -> f.node().add("children", JsonParser.parseString("[0]").getAsJsonArray()), "self-parenting");
        rejects(f -> f.node().add("rotation", JsonParser.parseString("[0,0,0,0]").getAsJsonArray()), "quaternion");
        rejects(f -> f.json.add("nodes", JsonParser.parseString("[{\"scale\":[1e38,1e38,1e38],\"children\":[1]},{\"mesh\":0,\"scale\":[2,2,2],\"children\":[2]},{\"scale\":[2,2,2]}]").getAsJsonArray()), "overflowing pose");
        rejects(f -> f.node().addProperty("skin", -1), "Negative skin");
        rejects(f -> f.node().addProperty("mesh", 7), "mesh index");
        rejects(f -> f.json.addProperty("scene", 5), "scene index");
        rejects(f -> f.json.add("scenes", JsonParser.parseString("[{\"nodes\":[]}]").getAsJsonArray()), "no mesh");
        rejects(f -> f.material().getAsJsonObject("pbrMetallicRoughness").add("baseColorFactor", JsonParser.parseString("[2,1,1,1]")), "Base color");
        Fixture cubic = new Fixture(); cubic.rig(); cubic.sampler().addProperty("interpolation", "CUBICSPLINE"); reject(cubic.glb(), "CUBICSPLINE");
        Fixture duplicateTimes = new Fixture(); duplicateTimes.rig(); duplicateTimes.sampler().addProperty("input", duplicateTimes.data(floats(0, 0), 5126, "SCALAR", 2, false)); reject(duplicateTimes.glb(), "increase strictly");
        Fixture badJoint = new Fixture(); badJoint.rig(); byte[] jointBytes = new byte[12]; jointBytes[0] = 1; badJoint.attributes().addProperty("JOINTS_0", badJoint.data(jointBytes, 5121, "VEC4", 3, false)); reject(badJoint.glb(), "joint index");
        Fixture zeroWeight = new Fixture(); zeroWeight.rig(); zeroWeight.attributes().addProperty("WEIGHTS_0", zeroWeight.data(new byte[12], 5121, "VEC4", 3, true)); reject(zeroWeight.glb(), "positive weight");
        Fixture nonAffine = new Fixture(); nonAffine.node().add("matrix", JsonParser.parseString("[1,0,0,1,0,1,0,0,0,0,1,0,0,0,0,1]")); reject(nonAffine.glb(), "affine");
        Fixture matrixTrs = new Fixture(); matrixTrs.node().add("matrix", JsonParser.parseString("[1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1]")); matrixTrs.node().add("translation", JsonParser.parseString("[0,0,0]")); reject(matrixTrs.glb(), "combine matrix and TRS");
        byte[] duplicateJson = glb("{\"asset\":{\"version\":\"2.0\",\"version\":\"2.0\"}}", new byte[0]); reject(duplicateJson, "duplicate");
        reject(glb("[".repeat(66) + "0" + "]".repeat(66), new byte[0]), "nesting");
    }

    private static void rejects(Consumer<Fixture> change, String expected) throws Exception {
        Fixture fixture = new Fixture(); change.accept(fixture); reject(fixture.glb(), expected);
    }
    private static void reject(byte[] bytes, String expected) throws Exception {
        try { GlbImporter.parse(bytes); throw new AssertionError("Accepted invalid GLB: " + expected); }
        catch (IOException failure) { truth(failure.getMessage().toLowerCase(java.util.Locale.ROOT).contains(expected.toLowerCase(java.util.Locale.ROOT)), "helpful rejection '" + expected + "', got " + failure.getMessage()); }
    }
    private static void near(double actual, double expected, String message) { truth(Math.abs(actual - expected) < 1e-5, message + " expected=" + expected + " actual=" + actual); }
    private static void eq(int actual, int expected, String message) { truth(actual == expected, message + " expected=" + expected + " actual=" + actual); }
    private static void truth(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    private static byte[] floats(float... values) { ByteBuffer bytes = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN); for (float value : values) bytes.putFloat(value); return bytes.array(); }
    private static byte[] shorts(int... values) { ByteBuffer bytes = ByteBuffer.allocate(values.length * 2).order(ByteOrder.LITTLE_ENDIAN); for (int value : values) bytes.putShort((short) value); return bytes.array(); }
    private static byte[] ints(int... values) { ByteBuffer bytes = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN); for (int value : values) bytes.putInt(value); return bytes.array(); }
    private static byte[] glb(String text, byte[] bin) {
        byte[] json = text.getBytes(StandardCharsets.UTF_8);
        int jsonSize = (json.length + 3) & ~3, binSize = (bin.length + 3) & ~3;
        ByteBuffer result = ByteBuffer.allocate(28 + jsonSize + binSize).order(ByteOrder.LITTLE_ENDIAN);
        result.putInt(0x46546c67).putInt(2).putInt(result.capacity()).putInt(jsonSize).putInt(0x4e4f534a).put(json);
        while (result.position() < 20 + jsonSize) result.put((byte) 32);
        result.putInt(binSize).putInt(0x004e4942).put(bin);
        return result.array();
    }

    private static final class Fixture {
        final JsonObject json = JsonParser.parseString("{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"byteLength\":0}],\"meshes\":[{\"primitives\":[{\"attributes\":{},\"material\":0}]}],\"nodes\":[{\"mesh\":0}],\"scenes\":[{\"nodes\":[0]}],\"materials\":[{\"pbrMetallicRoughness\":{\"baseColorFactor\":[0.2,0.4,0.6,1]},\"alphaMode\":\"MASK\",\"alphaCutoff\":0.3,\"doubleSided\":true}]}").getAsJsonObject();
        final JsonArray views = new JsonArray(), accessors = new JsonArray();
        final ByteArrayOutputStream binary = new ByteArrayOutputStream();
        Fixture() {
            json.add("bufferViews", views); json.add("accessors", accessors);
            attributes().addProperty("POSITION", data(floats(0, 0, 0, 1, 0, 0, 0, 1, 0), 5126, "VEC3", 3, false));
            attributes().addProperty("TEXCOORD_0", data(floats(0, 0, 1, 0, 0, 1), 5126, "VEC2", 3, false));
            primitive().addProperty("indices", data(shorts(0, 1, 2), 5123, "SCALAR", 3, false));
        }
        JsonObject primitive() { return json.getAsJsonArray("meshes").get(0).getAsJsonObject().getAsJsonArray("primitives").get(0).getAsJsonObject(); }
        JsonObject attributes() { return primitive().getAsJsonObject("attributes"); }
        JsonObject material() { return json.getAsJsonArray("materials").get(0).getAsJsonObject(); }
        JsonObject node() { return json.getAsJsonArray("nodes").get(0).getAsJsonObject(); }
        JsonObject sampler() { return json.getAsJsonArray("animations").get(0).getAsJsonObject().getAsJsonArray("samplers").get(0).getAsJsonObject(); }
        int view(byte[] bytes, int stride) {
            while (binary.size() % 4 != 0) binary.write(0);
            JsonObject view = new JsonObject(); view.addProperty("buffer", 0); view.addProperty("byteOffset", binary.size()); view.addProperty("byteLength", bytes.length);
            if (stride != 0) view.addProperty("byteStride", stride);
            views.add(view); binary.writeBytes(bytes);
            return views.size() - 1;
        }
        int accessor(int view, int offset, int component, String type, int count, boolean normalized) {
            JsonObject accessor = new JsonObject(); accessor.addProperty("bufferView", view); accessor.addProperty("byteOffset", offset);
            accessor.addProperty("componentType", component); accessor.addProperty("type", type); accessor.addProperty("count", count);
            if (normalized) accessor.addProperty("normalized", true);
            accessors.add(accessor); return accessors.size() - 1;
        }
        int data(byte[] bytes, int component, String type, int count, boolean normalized) { return accessor(view(bytes, 0), 0, component, type, count, normalized); }
        void rig() {
            json.add("nodes", JsonParser.parseString("[{\"children\":[1,2]},{\"translation\":[1,0,0]},{\"mesh\":0,\"skin\":0,\"translation\":[100,0,0]}]").getAsJsonArray());
            attributes().addProperty("JOINTS_0", data(new byte[12], 5121, "VEC4", 3, false));
            attributes().addProperty("WEIGHTS_0", data(new byte[]{-1,0,0,0,-1,0,0,0,-1,0,0,0}, 5121, "VEC4", 3, true));
            int inverse = data(floats(1,0,0,0,0,1,0,0,0,0,1,0,-1,0,0,1), 5126, "MAT4", 1, false);
            json.add("skins", JsonParser.parseString("[{\"joints\":[1],\"skeleton\":0,\"inverseBindMatrices\":" + inverse + "}]").getAsJsonArray());
            int input = data(floats(0, 2), 5126, "SCALAR", 2, false), output = data(floats(1,0,0,3,0,0), 5126, "VEC3", 2, false);
            json.add("animations", JsonParser.parseString("[{\"name\":\"Walk\",\"samplers\":[{\"input\":" + input + ",\"output\":" + output + "}],\"channels\":[{\"sampler\":0,\"target\":{\"node\":1,\"path\":\"translation\"}}]}]").getAsJsonArray());
        }
        byte[] glb() { json.getAsJsonArray("buffers").get(0).getAsJsonObject().addProperty("byteLength", binary.size()); return GlbImporterCheck.glb(json.toString(), binary.toByteArray()); }
    }
}
