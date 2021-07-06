/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016, 2017, 2018 FabricMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.fabricmc.loom.configuration.providers.minecraft;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;

import com.google.common.collect.ImmutableMap;
import io.github.fukkitmc.crusty.CrustyExtension;
import org.gradle.api.Project;

import net.fabricmc.loom.configuration.DependencyProvider;
import net.fabricmc.loom.configuration.providers.MinecraftProviderImpl;
import net.fabricmc.loom.configuration.providers.mappings.MappingsProviderImpl;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.TinyRemapperMappingsHelper;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;

public class MinecraftMappedProvider extends DependencyProvider {
	public static Path buildData;

	private static final Map<String, String> JSR_TO_JETBRAINS = new ImmutableMap.Builder<String, String>()
		.put("javax/annotation/Nullable", "org/jetbrains/annotations/Nullable")
		.put("javax/annotation/Nonnull", "org/jetbrains/annotations/NotNull")
		.put("javax/annotation/concurrent/Immutable", "org/jetbrains/annotations/Unmodifiable")
		.build();

	private File minecraftMappedJar;
	private File minecraftIntermediaryJar;

	private MinecraftProviderImpl minecraftProvider;

	private final CrustyExtension crusty;

	public MinecraftMappedProvider(Project project) {
		super(project);
		this.crusty = this.getProject().getExtensions().getByType(CrustyExtension.class);
	}

	@Override
	public void provide(DependencyInfo dependency, Consumer<Runnable> postPopulationScheduler) throws Exception {
		if(!getExtension().getMappingsProvider().tinyMappings.exists()) {
			throw new RuntimeException("mappings file not found");
		}

		if(!getExtension().getMinecraftProvider().getServerJar().exists()) {
			throw new RuntimeException("input merged jar not found");
		}

		if(!minecraftMappedJar.exists() || !getIntermediaryJar().exists() || isRefreshDeps()) {
			if(minecraftMappedJar.exists()) {
				minecraftMappedJar.delete();
			}

			minecraftMappedJar.getParentFile().mkdirs();

			if(minecraftIntermediaryJar.exists()) {
				minecraftIntermediaryJar.delete();
			}

			try {
				mapMinecraftJar();
				this.crusty.getCrustyJar(buildData);
			} catch(Throwable t) {
				// Cleanup some some things that may be in a bad state now
				minecraftMappedJar.delete();
				minecraftIntermediaryJar.delete();
				getExtension().getMappingsProvider().cleanFiles();
				throw new RuntimeException("Failed to remap minecraft", t);
			}
		}

		if(!minecraftMappedJar.exists()) {
			throw new RuntimeException("mapped jar not found");
		}

		addDependencies(dependency, postPopulationScheduler);
	}

	public File getIntermediaryJar() {
		return minecraftIntermediaryJar;
	}

	private void mapMinecraftJar() throws IOException {
		String fromM = "official";

		MappingsProviderImpl mappingsProvider = getExtension().getMappingsProvider();

		Path input = minecraftProvider.getServerJar().toPath();
		Path outputIntermediary = minecraftIntermediaryJar.toPath();

		getProject().getLogger().lifecycle(":remapping minecraft (TinyRemapper, " + fromM + " -> " + "intermediary" + ")");

		Files.deleteIfExists(outputIntermediary);

		TinyRemapper remapper = getTinyRemapper(fromM, "intermediary");

		try(OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(outputIntermediary).build()) {
			outputConsumer.addNonClassFiles(input);
			remapper.readClassPath(getRemapClasspath());
			remapper.readInputs(input);
			remapper.apply(outputConsumer);
		} catch(Exception e) {
			throw new RuntimeException("Failed to remap JAR " + input + " with mappings from " + mappingsProvider.tinyMappings, e);
		} finally {
			remapper.finish();
		}
	}

	protected void addDependencies(DependencyInfo dependency, Consumer<Runnable> postPopulationScheduler) {
		getProject().getDependencies()
				.add(Constants.Configurations.MINECRAFT_NAMED,
				     getProject().getDependencies().module("net.minecraft:minecraft:" + getJarVersionString("mapped")));
	}

	public TinyRemapper getTinyRemapper(String fromM, String toM) throws IOException {
		return TinyRemapper.newRemapper()
				       .withMappings(TinyRemapperMappingsHelper.create(getExtension().getMappingsProvider().getMappings(), fromM, toM, true))
				       .withMappings(out -> JSR_TO_JETBRAINS.forEach(out::acceptClass))
				       .renameInvalidLocals(true)
				       .rebuildSourceFilenames(true)
				       .build();
	}

	private static final Path[] EMPTY = new Path[0];
	public Path[] getRemapClasspath() {
		return EMPTY;
	}

	protected String getJarVersionString(String type) {
		return String.format("%s-%s-%s-%s",
		                     minecraftProvider.minecraftVersion(),
		                     type,
		                     getExtension().getMappingsProvider().mappingsName,
		                     getExtension().getMappingsProvider().mappingsVersion);
	}

	@Override
	public String getTargetConfig() {
		return Constants.Configurations.MINECRAFT_NAMED;
	}

	public void initFiles(MinecraftProviderImpl minecraftProvider, MappingsProviderImpl mappingsProvider) {
		this.minecraftProvider = minecraftProvider;
		minecraftIntermediaryJar = new File(getExtension().getUserCache(), "minecraft-" + getJarVersionString("intermediary") + ".jar");
		minecraftMappedJar = this.crusty.getCrustyJar(buildData).toFile();
	}

	protected File getJarDirectory(File parentDirectory, String type) {
		return new File(parentDirectory, getJarVersionString(type));
	}

	public File getMappedJar() {
		return minecraftMappedJar;
	}
}
