/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.jarjar.gradle;

import net.minecraftforge.gradleutils.shared.Closures;
import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.CopySpec;
import org.gradle.api.file.FileCopyDetails;
import org.gradle.api.plugins.BasePlugin;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.SkipWhenEmpty;
import org.gradle.api.tasks.TaskDependency;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.jvm.tasks.Jar;
import org.gradle.language.base.plugins.LifecycleBasePlugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.UnknownNullability;
import org.jetbrains.annotations.VisibleForTesting;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@VisibleForTesting
@ApiStatus.Experimental
public abstract class JarJar extends org.gradle.api.tasks.bundling.Jar implements JarJarTask {
    static TaskProvider<JarJar> register(JarJarContainerInternal container, Action<? super JarJar> taskAction) {
        var project = container.getProject();
        var jar = container.getJar();
        var tasks = project.getTasks();

        var jarJar = tasks.register(jar.getName() + "Jar", JarJar.class, task -> {
            task.setDescription("Combines an assembled jar archive with the resolved Jar-in-Jar dependencies.");
            task.setGroup(jar.map(Task::getGroup).getOrElse(BasePlugin.BUILD_GROUP));
            task.getArchiveClassifier().set(jar.flatMap(Jar::getArchiveClassifier).filter(s -> !s.isBlank()).map(s -> s + "-all").orElse("all"));

            task.dependsOn(jar);
            task.with(jar.get());
            task.getSourceArchive().set(jar.flatMap(Jar::getArchiveFile));

            task.setConfiguration(container.getResolvableConfiguration());

            taskAction.execute(task);
        });

        tasks.named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME::equals).configureEach(it -> it.dependsOn(jarJar));

        project.afterEvaluate(p -> {
            var jarJarTask = jarJar.get();

            if (jarJarTask.configurationBuildDependencies != null)
                jarJarTask.dependsOn(jarJarTask.configurationBuildDependencies);

            // Fabric ignores the Jar-in-Jar metadata file, relying on the parent mod's fabric.mod.json instead.
            // Only Forge and NeoForge consume META-INF/jarjar/metadata.json, so we skip generating it otherwise.
            if (LoaderJarTransformer.isFabric(jarJarTask.getLoader().getOrElse(LoaderJarTransformer.FORGE)))
                return;

            var metadata = p.getTasks().register(jarJar.getName() + "Metadata", JarJarMetadata.class, task -> {
                task.setDescription("Generates the Jar-in-Jar metadata to be used by task '%s'".formatted(jarJar.getName()));
                task.getResolvedDependencies().set(jarJarTask.resolvedDependencies);
            });

            jar.get().configure(Closures.<Jar>consumer(jarTask -> {
                jarTask.dependsOn(metadata);

                jarTask.from(metadata.flatMap(JarJarMetadata::getMetadataFile), copy -> copy
                    .into("META-INF/jarjar")
                );

                jarJarTask.setManifest(jarTask.getManifest());
            }));
        });

        return jarJar;
    }

    private final CopySpec jarJarCopySpec = this.getMainSpec().addChild().into("META-INF/jarjar");

    protected abstract @InputFiles @Classpath @SkipWhenEmpty ConfigurableFileCollection getIncludedClasspath();

    /// The modloader the resulting jar targets. Determines how embedded jars are made loadable at runtime:
    ///
    ///  - `forge` / `neoforge` (default): embeds `META-INF/jarjar/metadata.json` and marks plain libraries with
    ///    `FMLModType: LIBRARY` so FancyModLoader places them on the classpath.
    ///  - `fabric`: patches the parent mod's `fabric.mod.json` to reference the nested jars and synthesizes a
    ///    `fabric.mod.json` inside any embedded jar that is not already a Fabric mod.
    public abstract @Input @Optional Property<String> getLoader();

    /// The assembled (base) jar archive this task augments. Used to read the parent `fabric.mod.json` for patching.
    protected abstract @org.gradle.api.tasks.Internal org.gradle.api.file.RegularFileProperty getSourceArchive();

    private transient @UnknownNullability TaskDependency configurationBuildDependencies;
    final SetProperty<ResolvedDependencyInfo> resolvedDependencies = this.getObjectFactory().setProperty(ResolvedDependencyInfo.class);

    public void setConfiguration(Configuration configuration) {
        this.configurationBuildDependencies = configuration.getBuildDependencies();
        this.resolvedDependencies.set(this.getProviders().provider(() -> configuration).map(c -> ResolvedDependencyInfo.from(this.problems, this.getProject().getConfigurations(), c)));
        this.getIncludedClasspath().setFrom(this.resolvedDependencies.map(ResolvedDependencyInfo::getFiles));
    }

    // TODO figure out making this lazy?
    public void setConfiguration(Provider<? extends Configuration> configuration) {
        this.setConfiguration(configuration.get());
    }

    private final JarJarProblems problems = this.getObjectFactory().newInstance(JarJarProblems.class);

    protected abstract @Inject ProviderFactory getProviders();

    public JarJar() { }

    @Override
    protected void copy() {
        var loader = this.getLoader().getOrElse(LoaderJarTransformer.FORGE);

        try {
            var nestedPaths = this.transformIncludedJars(loader);
            if (LoaderJarTransformer.isFabric(loader))
                this.patchParentFabricModJson(nestedPaths);
        } catch (IOException e) {
            throw new org.gradle.api.UncheckedIOException(e);
        }

        super.copy();
    }

    /// Rewrites each embedded jar for the target loader, stages the results under the task's temporary directory, and
    /// points the Jar-in-Jar copy spec at them. Returns the in-archive paths of the staged jars (for Fabric patching).
    private List<String> transformIncludedJars(String loader) throws IOException {
        var coords = this.coordsByArtifact();

        var staging = new File(this.getTemporaryDir(), "jarjar");
        if (!staging.isDirectory() && !staging.mkdirs())
            throw new IOException("Failed to create staging directory: " + staging);

        var staged = new ArrayList<File>();
        var nestedPaths = new ArrayList<String>();
        for (var src : this.getIncludedClasspath()) {
            var dst = new File(staging, src.getName());
            var coord = coords.getOrDefault(src, new LoaderJarTransformer.Coord(null, src.getName(), null));
            nestedPaths.add(LoaderJarTransformer.transformNested(src, dst, loader, coord));
            staged.add(dst);
        }

        // Replace the raw included jars with the loader-transformed ones.
        this.jarJarCopySpec.from(staged);
        return nestedPaths;
    }

    private Map<File, LoaderJarTransformer.Coord> coordsByArtifact() {
        var map = new HashMap<File, LoaderJarTransformer.Coord>();
        for (var dependency : this.resolvedDependencies.get()) {
            if (dependency.constraint)
                continue;
            map.put(dependency.artifact, new LoaderJarTransformer.Coord(
                dependency.module.getGroup(), dependency.module.getName(), dependency.version
            ));
        }
        return map;
    }

    /// Adds the nested jar paths to the parent mod's `fabric.mod.json` under `"jars"`, replacing the original entry in
    /// the assembled archive with the patched copy.
    private void patchParentFabricModJson(List<String> nestedPaths) throws IOException {
        var archive = this.getSourceArchive().get().getAsFile();
        var original = LoaderJarTransformer.readEntry(archive, "fabric.mod.json");
        if (original == null) {
            this.getLogger().warn("[JarJar] loader is 'fabric' but the assembled jar has no fabric.mod.json; nested jars will not be loaded by Fabric.");
            return;
        }

        var patched = new File(this.getTemporaryDir(), "fabric.mod.json");
        java.nio.file.Files.write(patched.toPath(), LoaderJarTransformer.patchFabricModJson(original, nestedPaths));

        // Drop the original fabric.mod.json brought in from the base jar, then add the patched copy at the archive root.
        this.eachFile(this::excludeOriginalFabricModJson);
        this.from(patched);
    }

    private void excludeOriginalFabricModJson(FileCopyDetails details) {
        if (details.getRelativePath().getPathString().equals("fabric.mod.json")
            && !details.getFile().getParentFile().equals(this.getTemporaryDir()))
            details.exclude();
    }
}
