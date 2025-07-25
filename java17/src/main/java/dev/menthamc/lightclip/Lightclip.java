package dev.menthamc.lightclip;

import dev.menthamc.lightclip.integrated.leavesclip.logger.Logger;
import dev.menthamc.lightclip.integrated.leavesclip.logger.SimpleLogger;
import dev.menthamc.lightclip.integrated.leavesclip.mixin.*;
import dev.menthamc.lightclip.integrated.leavesclip.mixin.plugins.condition.BuildInfoInjector;
import org.leavesmc.plugin.mixin.condition.condition.ConditionChecker;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.FabricUtil;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public final class Lightclip {
    private static final Logger leavesLogger = new SimpleLogger("main");

    public static void main(final String[] args) {
        if (Path.of("").toAbsolutePath().toString().contains("!")) {
            System.err.println("Lightclip may not run in a directory containing '!'. Please rename the affected folder.");
            System.exit(1);
        }

        final URL[] classpathUrls = setupClasspath();

        final ClassLoader parentClassLoader = Lightclip.class.getClassLoader();
        final URLClassLoader selectedClassLoader = getClassLoaderForServer(classpathUrls, parentClassLoader);

        final String mainClassName = findMainClass();
        System.out.println("Starting " + mainClassName);

        bootstrap(mainClassName, selectedClassLoader, args);
    }

    private static void bootstrap(String mainClassName, ClassLoader selectedClassLoader, String... args) {

        final Thread runThread = new Thread(() -> {
            try {
                final Class<?> mainClass = Class.forName(mainClassName, true, selectedClassLoader);
                final MethodHandle mainHandle = MethodHandles.lookup()
                    .findStatic(mainClass, "main", MethodType.methodType(void.class, String[].class))
                    .asFixedArity();
                mainHandle.invoke((Object) args);
            } catch (final Throwable t) {
                throw Util.sneakyThrow(t);
            }
        }, "ServerMain");
        runThread.setContextClassLoader(selectedClassLoader);
        runThread.start();
    }

    private static URL[] setupClasspath() {
        final var repoDir = Path.of(System.getProperty("bundlerRepoDir", ""));

        final PatchEntry[] patches = findPatches();
        DownloadContext downloadContext = findDownloadContext(false);
        if (patches.length > 0 && downloadContext == null) {
            throw new IllegalArgumentException("patches.list file found without a corresponding original-url file");
        }

        final Path baseFile;
        if (downloadContext != null) {
            try {
                downloadContext.download(repoDir);
            } catch (final IOException e) {
                System.out.println("Failed to download jar with auto matched download context! Trying using default download context");
                downloadContext = findDownloadContext(true);

                if (downloadContext == null) {
                    throw new IllegalStateException("Default download context not found!");
                }

                try {
                    downloadContext.download(repoDir);
                } catch (IOException ex2) {
                    throw Util.fail("Failed to download original jar", ex2);
                }
            }
            baseFile = downloadContext.getOutputFile(repoDir);
        } else {
            baseFile = null;
        }

        final Map<String, Map<String, URL>> classpathUrls = extractAndApplyPatches(baseFile, patches, repoDir);

        // Exit if user has set `paperclip.patchonly` system property to `true`
        if (Boolean.getBoolean("lightclip.patchonly")) {
            System.exit(0);
        }

        // Keep versions and libraries separate as the versions must come first
        // This is due to change we make to some library classes inside the versions jar
        final Collection<URL> versionUrls = classpathUrls.get("versions").values();
        final Collection<URL> libraryUrls = classpathUrls.get("libraries").values();

        final URL[] emptyArray = new URL[0];
        final URL[] urls = new URL[versionUrls.size() + libraryUrls.size()];
        System.arraycopy(versionUrls.toArray(emptyArray), 0, urls, 0, versionUrls.size());
        System.arraycopy(libraryUrls.toArray(emptyArray), 0, urls, versionUrls.size(), libraryUrls.size());
        return urls;
    }

    private static PatchEntry[] findPatches() {
        final InputStream patchListStream = Lightclip.class.getResourceAsStream("/META-INF/patches.list");
        if (patchListStream == null) {
            return new PatchEntry[0];
        }

        try (patchListStream) {
            return PatchEntry.parse(new BufferedReader(new InputStreamReader(patchListStream)));
        } catch (final IOException e) {
            throw Util.fail("Failed to read patches.list file", e);
        }
    }

    private static URLClassLoader getClassLoaderForServer(URL[] setupClasspathUrls, ClassLoader parentClassLoader) {
        if (Boolean.getBoolean("leavesclip.enable.mixin")) {
            BuildInfoInjector.inject();
            overrideAsmVersion();
            PluginResolver.extractMixins();
            MixinJarResolver.resolveMixinJars();

            System.setProperty("mixin.bootstrapService", MixinServiceKnotBootstrap.class.getName());
            System.setProperty("mixin.service", MixinServiceKnot.class.getName());

            final URL[] classpathUrls = Arrays.copyOf(setupClasspathUrls, setupClasspathUrls.length + MixinJarResolver.jarUrls.length);
            System.arraycopy(MixinJarResolver.jarUrls, 0, classpathUrls, setupClasspathUrls.length, MixinJarResolver.jarUrls.length);

            MixinServiceKnot.classLoader = Lightclip.class.getClassLoader();

            MixinBootstrap.init();
            MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.SERVER);

            var ret = new MixinURLClassLoader(classpathUrls, parentClassLoader);

            ConditionChecker.setClassLoader(ret);
            MixinServiceKnot.classLoader = ret;
            Mixins.addConfiguration("mixin-extras.init.mixins.json");
            MixinJarResolver.mixinConfigs.forEach(Mixins::addConfiguration);
            decorateMixinConfigWithPluginId();
            AccessWidenerManager.initAccessWidener(ret);

            return ret;
        } else {
            return new URLClassLoader(setupClasspathUrls, parentClassLoader);
        }
    }

    private static void decorateMixinConfigWithPluginId() {
        Mixins.getConfigs().forEach(config -> {
            String mixinConfigName = config.getName();
            String pluginId = MixinJarResolver.getPluginId(mixinConfigName);
            if (pluginId == null) return;

            IMixinConfig mixinConfig = config.getConfig();
            mixinConfig.decorate(FabricUtil.KEY_MOD_ID, pluginId);
            mixinConfig.decorate(FabricUtil.KEY_COMPATIBILITY, FabricUtil.COMPATIBILITY_LATEST);
        });
    }

    private static void overrideAsmVersion() {
        try {
            Class<?> asmClass = Class.forName("org.spongepowered.asm.util.asm.ASM");
            Field minorVersionField = asmClass.getDeclaredField("implMinorVersion");
            minorVersionField.setAccessible(true);
            minorVersionField.setInt(null, 5);

        } catch (Exception e) {
            leavesLogger.error("Failed to override asm version", e);
        }
    }

    private static String getDownloadContextFileName(boolean ignoreCountry) {
        final String country = Util.getCountryByIp();
        String base = "download-context";
        final String customized = System.getProperty("lightclip.downloadContext");

        if (ignoreCountry) {
            return base;
        }

        if (customized != null) {
            return customized;
        }

        if (country.equals("China")) {
            return base + "-cn";
        }

        return base;
    }

    private static DownloadContext findDownloadContext(boolean ignoreCountry) {
        String line;
        try {
            line = Util.readResourceText("/META-INF/" + getDownloadContextFileName(ignoreCountry));
        } catch (final IOException e) {
            // other download source does not found
            try {
                line = Util.readResourceText("/META-INF/" + getDownloadContextFileName(true));
            }catch (IOException e1) {
                throw Util.fail("Failed to read download-context file", e1);
            }
        }

        return DownloadContext.parseLine(line);
    }

    private static FileEntry[] findVersionEntries() {
        return findFileEntries("versions.list");
    }
    private static FileEntry[] findLibraryEntries() {
        return findFileEntries("libraries.list");
    }
    private static FileEntry[] findFileEntries(final String fileName) {
        final InputStream libListStream = Lightclip.class.getResourceAsStream("/META-INF/" + fileName);
        if (libListStream == null) {
            return null;
        }

        try (libListStream) {
            return FileEntry.parse(new BufferedReader(new InputStreamReader(libListStream)));
        } catch (final IOException e) {
            throw Util.fail("Failed to read " + fileName + " file", e);
        }
    }

    private static String findMainClass() {
        final String mainClassName = System.getProperty("bundlerMainClass");
        if (mainClassName != null) {
            return mainClassName;
        }

        try {
            return Util.readResourceText("/META-INF/main-class");
        } catch (final IOException e) {
            throw Util.fail("Failed to read main-class file", e);
        }
    }

    private static Map<String, Map<String, URL>> extractAndApplyPatches(final Path originalJar, final PatchEntry[] patches, final Path repoDir) {
        if (originalJar == null && patches.length > 0) {
            throw new IllegalArgumentException("Patch data found without patch target");
        }

        // First extract any non-patch files
        final Map<String, Map<String, URL>> urls = extractFiles(patches, originalJar, repoDir);

        // Next apply any patches that we have
        applyPatches(urls, patches, originalJar, repoDir);

        return urls;
    }

    private static Map<String, Map<String, URL>> extractFiles(final PatchEntry[] patches, final Path originalJar, final Path repoDir) {
        final var urls = new HashMap<String, Map<String, URL>>();

        try {
            final FileSystem originalJarFs;
            if (originalJar == null) {
                originalJarFs = null;
            } else {
                originalJarFs = FileSystems.newFileSystem(originalJar);
            }

            try {
                final Path originalRootDir;
                if (originalJarFs == null) {
                    originalRootDir = null;
                } else {
                    originalRootDir = originalJarFs.getPath("/");
                }

                final var versionsMap = new HashMap<String, URL>();
                urls.putIfAbsent("versions", versionsMap);
                final FileEntry[] versionEntries = findVersionEntries();
                extractEntries(versionsMap, patches, originalRootDir, repoDir, versionEntries, "versions");

                final FileEntry[] libraryEntries = findLibraryEntries();
                final var librariesMap = new HashMap<String, URL>();
                urls.putIfAbsent("libraries", librariesMap);
                extractEntries(librariesMap, patches, originalRootDir, repoDir, libraryEntries, "libraries");
            } finally {
                if (originalJarFs != null) {
                    originalJarFs.close();
                }
            }
        } catch (final IOException e) {
            throw Util.fail("Failed to extract jar files", e);
        }

        return urls;
    }

    private static void extractEntries(
        final Map<String, URL> urls,
        final PatchEntry[] patches,
        final Path originalRootDir,
        final Path repoDir,
        final FileEntry[] entries,
        final String targetName
    ) throws IOException {
        if (entries == null) {
            return;
        }

        final String targetPath = "/META-INF/" + targetName;
        final Path targetDir = repoDir.resolve(targetName);

        for (final FileEntry entry : entries) {
            entry.extractFile(urls, patches, targetName, originalRootDir, targetPath, targetDir);
        }
    }

    private static void applyPatches(
        final Map<String, Map<String, URL>> urls,
        final PatchEntry[] patches,
        final Path originalJar,
        final Path repoDir
    ) {
        if (patches.length == 0) {
            return;
        }
        if (originalJar == null) {
            throw new IllegalStateException("Patches provided without patch target");
        }

        try (final FileSystem originalFs = FileSystems.newFileSystem(originalJar)) {
            final Path originalRootDir = originalFs.getPath("/");

            for (final PatchEntry patch : patches) {
                patch.applyPatch(urls, originalRootDir, repoDir);
            }
        } catch (final IOException e) {
            throw Util.fail("Failed to apply patches", e);
        }
    }
}
