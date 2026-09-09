package dev.c4lam.celeste.beta.fixture;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import net.fabricmc.loader.impl.transformer.PackageAccessFixer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/**
 * Fixture-only equivalent of Fabric's named-development package access fix.
 * Does not patch JAR files, attach instrumentation, initialize a mod loader,
 * authenticate, create a game window, or install production code in a client.
 */
public final class NamedMinecraftContractLauncher {
    private NamedMinecraftContractLauncher() { }

    public static void main(String[] args) throws Throwable {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        ClassLoader fixtureLoader = new NamedFixtureLoader(NamedMinecraftContractLauncher.class.getClassLoader());
        Thread.currentThread().setContextClassLoader(fixtureLoader);
        try {
            if (args.length > 0 && args[0].equals("members")) {
                Class<?> members = fixtureLoader.loadClass("dev.c4lam.celeste.compat.MinecraftMembersCheck");
                members.getMethod("run", String.class).invoke(null, args[1]);
                return;
            }
            Class<?> logging = fixtureLoader.loadClass("dev.c4lam.celeste.compat.RuntimeLoggingCheck");
            logging.getMethod("run").invoke(null);
            Class<?> texture = fixtureLoader.loadClass("dev.c4lam.celeste.compat.BetaTextureLeaseCheck");
            texture.getMethod("run").invoke(null);
            Class<?> contract = fixtureLoader.loadClass("dev.c4lam.celeste.beta.resources.MemoryResourcePackContractCheck");
            contract.getMethod("main", String[].class).invoke(null, (Object) args);
            Class<?> hostContract = fixtureLoader.loadClass("dev.c4lam.celeste.beta.resources.InternalResourcesHostCheck");
            hostContract.getMethod("run").invoke(null);
            Class<?> pipelines = fixtureLoader.loadClass("dev.c4lam.celeste.compat.BetaRenderPipelinesCheck");
            pipelines.getMethod("run", String.class).invoke(null, args[0]);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private static final class NamedFixtureLoader extends ClassLoader {
        NamedFixtureLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            boolean minecraft = name.startsWith("net.minecraft.") || name.startsWith("com.mojang.blaze3d.");
            if (!minecraft && !name.startsWith("dev.c4lam.celeste.beta.resources.")
                    && !name.startsWith("dev.c4lam.celeste.compat.")
                    && !name.startsWith("dev.c4lam.celeste.mixin.")) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    String resource = name.replace('.', '/') + ".class";
                    try (InputStream stream = getParent().getResourceAsStream(resource)) {
                        if (stream == null) {
                            throw new ClassNotFoundException(name);
                        }
                        byte[] bytes = stream.readAllBytes();
                        if (minecraft) {
                            ClassReader reader = new ClassReader(bytes);
                            ClassWriter writer = new ClassWriter(reader, 0);
                            reader.accept(new PackageAccessFixer(Opcodes.ASM9, writer), 0);
                            bytes = writer.toByteArray();
                        }
                        loaded = defineClass(name, bytes, 0, bytes.length);
                    } catch (IOException failure) {
                        throw new ClassNotFoundException(name, failure);
                    }
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }
}
