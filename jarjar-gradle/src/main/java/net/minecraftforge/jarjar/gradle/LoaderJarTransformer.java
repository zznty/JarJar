/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.jarjar.gradle;

import groovy.json.JsonOutput;
import groovy.json.JsonSlurper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/// Loader-specific rewriting of jars for Jar-in-Jar runtime loading.
///
/// The Jar-in-Jar metadata format (`META-INF/jarjar/metadata.json`) and the physical nesting of jars under
/// `META-INF/jarjar/` is identical across Forge and NeoForge. However, each modloader only loads a nested jar onto the
/// classpath if it carries a loader-specific marker:
///
///  - Forge / NeoForge: a plain (non-mod) library must declare `FMLModType: LIBRARY` in its `MANIFEST.MF`.
///  - Fabric: ignores `metadata.json` entirely. The parent mod's `fabric.mod.json` must list nested jars under
///    `"jars"`, and each nested jar must itself be a Fabric mod (own `fabric.mod.json`).
///
/// This helper performs those minimal, loader-specific rewrites so an ordinary Maven dependency can be embedded and
/// actually loaded at runtime, regardless of loader.
final class LoaderJarTransformer {
    static final String FORGE = "forge";
    static final String NEOFORGE = "neoforge";
    static final String FABRIC = "fabric";

    private static final String NESTED_DIR = "META-INF/jarjar/";

    private LoaderJarTransformer() { }

    static boolean isFabric(String loader) {
        return FABRIC.equalsIgnoreCase(loader);
    }

    /// Coordinates of an embedded dependency, used to synthesize loader metadata.
    record Coord(String group, String name, String version) {
        String fabricId() {
            // Fabric mod ids must match ^[a-z][a-z0-9-_]{1,63}$. Derive a stable id from group + name.
            var raw = ((group == null || group.isBlank() ? "" : group + "_") + name).toLowerCase(Locale.ROOT);
            var sb = new StringBuilder(raw.length());
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                sb.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' ? c : '_');
            }
            // Must start with a letter.
            if (sb.length() == 0 || sb.charAt(0) < 'a' || sb.charAt(0) > 'z')
                sb.insert(0, 'j');
            if (sb.length() > 64)
                sb.setLength(64);
            return sb.toString();
        }

        String fabricVersion() {
            return version == null || version.isBlank() ? "0.0.0" : version;
        }

        String automaticModuleName() {
            var raw = name.toLowerCase(Locale.ROOT);
            var sb = new StringBuilder(raw.length());
            boolean prevDot = true;
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                    sb.append(c);
                    prevDot = false;
                } else if (!prevDot) {
                    sb.append('.');
                    prevDot = true;
                }
            }
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '.')
                sb.setLength(sb.length() - 1);
            return sb.length() == 0 ? "jarjar.nested" : sb.toString();
        }
    }

    /// Rewrites a nested jar so the given loader will load it, writing the result to {@code dst}.
    /// Returns the path (relative to the parent jar root) at which the nested jar is placed.
    static String transformNested(File src, File dst, String loader, Coord coord) throws IOException {
        byte[] in = Files.readAllBytes(src.toPath());
        byte[] out = isFabric(loader) ? fabricize(in, coord) : markAsLibrary(in, coord);
        Files.write(dst.toPath(), out);
        return NESTED_DIR + dst.getName();
    }

    /// Forge / NeoForge: ensure a plain library jar carries {@code FMLModType: LIBRARY} so FML loads it.
    /// Jars that are already mods (have a {@code mods.toml}/{@code neoforge.mods.toml}) or already declare an
    /// {@code FMLModType} are returned unchanged.
    private static byte[] markAsLibrary(byte[] jar, Coord coord) throws IOException {
        Manifest manifest = null;
        boolean isMod = false;
        try (var jin = new JarInputStream(new ByteArrayInputStream(jar))) {
            manifest = jin.getManifest();
            JarEntry e;
            while ((e = jin.getNextJarEntry()) != null) {
                var n = e.getName();
                if (n.equals("META-INF/mods.toml") || n.equals("META-INF/neoforge.mods.toml"))
                    isMod = true;
            }
        }

        if (isMod)
            return jar;
        if (manifest != null && manifest.getMainAttributes().getValue("FMLModType") != null)
            return jar;

        var newManifest = manifest != null ? new Manifest(manifest) : new Manifest();
        var attrs = newManifest.getMainAttributes();
        if (attrs.getValue(Attributes.Name.MANIFEST_VERSION) == null)
            attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.putValue("FMLModType", "LIBRARY");
        if (attrs.getValue("Automatic-Module-Name") == null)
            attrs.putValue("Automatic-Module-Name", coord.automaticModuleName());

        return rewrite(jar, newManifest, null);
    }

    /// Fabric: ensure a nested jar is itself a Fabric mod by synthesizing a {@code fabric.mod.json} if absent.
    /// Jars that already contain a {@code fabric.mod.json} are returned unchanged.
    private static byte[] fabricize(byte[] jar, Coord coord) throws IOException {
        try (var jin = new JarInputStream(new ByteArrayInputStream(jar))) {
            JarEntry e;
            while ((e = jin.getNextJarEntry()) != null) {
                if (e.getName().equals("fabric.mod.json"))
                    return jar;
            }
        }

        var mod = new LinkedHashMap<String, Object>();
        mod.put("schemaVersion", 1);
        mod.put("id", coord.fabricId());
        mod.put("version", coord.fabricVersion());
        mod.put("name", coord.name());
        var custom = new LinkedHashMap<String, Object>();
        custom.put("fabric-loom:generated", true);
        mod.put("custom", custom);

        var extra = Map.of("fabric.mod.json",
            JsonOutput.toJson(mod).getBytes(StandardCharsets.UTF_8));
        return rewrite(jar, null, extra);
    }

    /// Patches a parent mod's {@code fabric.mod.json} bytes to add the given nested jar paths under {@code "jars"}.
    @SuppressWarnings("unchecked")
    static byte[] patchFabricModJson(byte[] original, List<String> nestedPaths) {
        var parsed = new JsonSlurper().parse(original);
        if (!(parsed instanceof Map))
            throw new IllegalStateException("fabric.mod.json root is not a JSON object");
        var mod = new LinkedHashMap<String, Object>((Map<String, Object>) parsed);

        var jars = new ArrayList<Object>();
        var existing = mod.get("jars");
        if (existing instanceof List<?> l)
            jars.addAll(l);

        for (var path : nestedPaths) {
            boolean present = jars.stream().anyMatch(o ->
                o instanceof Map<?, ?> m && path.equals(m.get("file")));
            if (!present) {
                var entry = new LinkedHashMap<String, Object>();
                entry.put("file", path);
                jars.add(entry);
            }
        }
        mod.put("jars", jars);

        return JsonOutput.prettyPrint(JsonOutput.toJson(mod)).getBytes(StandardCharsets.UTF_8);
    }

    /// Reads {@code fabric.mod.json} from a built jar archive, returning {@code null} if absent.
    static byte[] readEntry(File jar, String entryName) throws IOException {
        try (var jin = new JarInputStream(Files.newInputStream(jar.toPath()))) {
            JarEntry e;
            while ((e = jin.getNextJarEntry()) != null) {
                if (e.getName().equals(entryName))
                    return readAll(jin);
            }
        }
        return null;
    }

    /// Copies {@code jar}, optionally replacing the manifest and adding/overwriting extra entries.
    private static byte[] rewrite(byte[] jar, Manifest newManifest, Map<String, byte[]> extra) throws IOException {
        var bos = new ByteArrayOutputStream(jar.length + 512);
        var added = extra == null ? Map.<String, byte[]>of() : extra;

        try (var jin = new JarInputStream(new ByteArrayInputStream(jar))) {
            var manifest = newManifest != null ? newManifest : jin.getManifest();
            JarOutputStream jout;
            if (manifest != null) {
                jout = new JarOutputStream(bos, manifest);
            } else {
                jout = new JarOutputStream(bos);
            }
            try {
                JarEntry e;
                while ((e = jin.getNextJarEntry()) != null) {
                    var n = e.getName();
                    // The manifest is written by the JarOutputStream constructor above.
                    if (n.equalsIgnoreCase("META-INF/MANIFEST.MF"))
                        continue;
                    if (added.containsKey(n))
                        continue; // overwritten below
                    var ne = new JarEntry(n);
                    if (e.getTime() != -1)
                        ne.setTime(e.getTime());
                    jout.putNextEntry(ne);
                    jout.write(readAll(jin));
                    jout.closeEntry();
                }
                for (var entry : added.entrySet()) {
                    jout.putNextEntry(new JarEntry(entry.getKey()));
                    jout.write(entry.getValue());
                    jout.closeEntry();
                }
            } finally {
                jout.close();
            }
        }
        return bos.toByteArray();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        var bos = new ByteArrayOutputStream();
        var buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1)
            bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}
