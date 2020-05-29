/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.gradlebuild.packaging

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.DocsType
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.file.CopySpec
import org.gradle.api.internal.runtimeshaded.PackageListGenerator
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.Zip
import org.gradle.build.docs.dsl.source.ExtractDslMetaDataTask
import org.gradle.build.docs.dsl.source.GenerateApiMapping
import org.gradle.build.docs.dsl.source.GenerateDefaultImports
import org.gradle.gradlebuild.PublicApi
import org.gradle.gradlebuild.docs.GradleUserManualPlugin
import org.gradle.gradlebuild.packaging.GradleDistributionSpecs.binDistributionSpec
import org.gradle.gradlebuild.packaging.GradleDistributionSpecs.allDistributionSpec
import org.gradle.gradlebuild.packaging.GradleDistributionSpecs.docsDistributionSpec
import org.gradle.gradlebuild.packaging.GradleDistributionSpecs.srcDistributionSpec
import org.gradle.gradlebuild.versioning.buildVersion
import org.gradle.kotlin.dsl.*
import java.util.jar.Attributes


@Suppress("unused")
open class GradleDistributionsPlugin : Plugin<Project> {

    @Suppress("UNUSED_VARIABLE")
    override fun apply(project: Project): Unit = project.run {
        val runtimeApiJarName = "gradle-runtime-api-info"

        // Configurations to define dependencies
        val coreRuntimeOnly by bucket()
        val pluginsRuntimeOnly by bucket()
        val gradleScripts by bucket(listOf(":launcher"))

        // Configurations to resolve dependencies
        val runtimeClasspath by libraryResolver(listOf(coreRuntimeOnly, pluginsRuntimeOnly))
        val coreRuntimeClasspath by libraryResolver(listOf(coreRuntimeOnly))
        val gradleScriptPath by startScriptResolver(listOf(gradleScripts))
        val sourcesPath by sourcesResolver(listOf(coreRuntimeOnly, pluginsRuntimeOnly))
        val docsPath by docsResolver()

        // Tasks to generate metadata about the distribution that is required at runtime
        val generateGradleApiPackageList by tasks.registering(PackageListGenerator::class) {
            classpath = runtimeClasspath
            outputFile = file(generatedTxtFileFor("api-relocated"))
        }

        val dslMetaData by tasks.registering(ExtractDslMetaDataTask::class) {
            source(sourcesPath.incoming.artifactView { lenient(true) }.files.asFileTree.matching {
                // Filter out any non-public APIs
                include(PublicApi.includes)
                exclude(PublicApi.excludes)
            })
            destinationFile.set(generatedBinFileFor("dsl-meta-data.bin"))
        }

        val apiMapping by tasks.registering(GenerateApiMapping::class) {
            metaDataFile.set(dslMetaData.flatMap(ExtractDslMetaDataTask::getDestinationFile))
            mappingDestFile.set(generatedTxtFileFor("api-mapping"))
            excludedPackages.set(GradleUserManualPlugin.getDefaultExcludedPackages())
        }

        val defaultImports = tasks.register("defaultImports", GenerateDefaultImports::class) {
            metaDataFile.set(dslMetaData.flatMap(ExtractDslMetaDataTask::getDestinationFile))
            importsDestFile.set(generatedTxtFileFor("default-imports"))
            excludedPackages.set(GradleUserManualPlugin.getDefaultExcludedPackages())
        }

        val pluginsManifest by pluginsManifestTask(runtimeClasspath, coreRuntimeClasspath, GradleModuleApiAttribute.API)
        val implementationPluginsManifest by pluginsManifestTask(runtimeClasspath, coreRuntimeClasspath, GradleModuleApiAttribute.IMPLEMENTATION)

        val emptyClasspathManifest by tasks.registering(ClasspathManifest::class) {
            // At runtime, Gradle expects each Gradle jar to have a classpath manifest.
            this.manifestFile.set(generatedPropertiesFileFor("$runtimeApiJarName-classpath"))
        }

        // Jar task that packages all metadata in 'gradle-runtime-api-info.jar'
        val runtimeApiInfoJar by tasks.registering(Jar::class) {
            val baseVersion = rootProject.buildVersion.baseVersion
            archiveVersion.set(baseVersion)
            manifest.attributes(mapOf(
                Attributes.Name.IMPLEMENTATION_TITLE.toString() to "Gradle",
                Attributes.Name.IMPLEMENTATION_VERSION.toString() to baseVersion))
            archiveBaseName.set(runtimeApiJarName)
            into("org/gradle/api/internal/runtimeshaded") {
                from(generateGradleApiPackageList)
            }
            from(apiMapping)
            from(defaultImports)
            from(pluginsManifest)
            from(implementationPluginsManifest)
            from(emptyClasspathManifest)
        }

        // A standard Java runtime variant for embedded integration testing
        consumableVariant("runtime", LibraryElements.JAR, Bundling.EXTERNAL, listOf(coreRuntimeOnly, pluginsRuntimeOnly), runtimeApiInfoJar)
        // To make all source code of a distribution accessible transitively
        consumableSourcesVariant("transitiveSources", listOf(coreRuntimeOnly, pluginsRuntimeOnly))
        // A platform variant without 'runtime-api-info' artifact such that distributions can depend on each other
        consumablePlatformVariant("runtimePlatform", listOf(coreRuntimeOnly, pluginsRuntimeOnly))

        val buildDists by tasks.registering

        configureDistribution("bin", binDistributionSpec(), buildDists)
        configureDistribution("all", allDistributionSpec(), buildDists)
        configureDistribution("docs", docsDistributionSpec(), buildDists)
        configureDistribution("src", srcDistributionSpec(), buildDists)
    }

    private
    fun Project.pluginsManifestTask(runtimeClasspath: Configuration, coreRuntimeClasspath: Configuration, api: GradleModuleApiAttribute) =
        tasks.registering(PluginsManifest::class) {
            pluginsClasspath.from(runtimeClasspath.incoming.artifactView {
                lenient(true)
                attributes.attribute(GradleModuleApiAttribute.attribute, api)
            }.files)
            coreClasspath.from(coreRuntimeClasspath)
            manifestFile.set(generatedPropertiesFileFor("gradle${if (api == GradleModuleApiAttribute.API) "" else "-implementation"}-plugins"))
        }

    private
    fun Project.configureDistribution(name: String, distributionSpec: CopySpec, buildDistLifecycleTask: TaskProvider<Task>) {
        val zipRootFolder = "gradle-$version"

        val installation = tasks.register<Sync>("${name}Installation") {
            group = "distribution"
            into(layout.buildDirectory.dir("$name distribution"))
            with(distributionSpec)
        }

        val distributionZip = tasks.register<Zip>("${name}DistributionZip") {
            archiveBaseName.set("gradle")
            archiveClassifier.set(name)
            archiveVersion.set(rootProject.buildVersion.baseVersion)

            destinationDirectory.set(project.layout.buildDirectory.dir("distributions"))

            into(zipRootFolder) {
                with(distributionSpec)
            }
        }

        buildDistLifecycleTask.configure {
            dependsOn(distributionZip)
        }

        // A variant providing a folder where the distribution is present in the final format for forked integration testing
        consumableVariant("${name}Installation", "gradle-$name-installation", Bundling.EMBEDDED, emptyList(), mapOf(
                // TODO: https://github.com/gradle/gradle/issues/13275: missing property in Sync task - assembleBinDistribution.flatMap(Sync::getDestinationDirectory())
                "file" to installation.get().destinationDir,
                "builtBy" to installation)
        )
        consumableVariant("${name}DistributionZip", "gradle-$name-distribution-zip", Bundling.EMBEDDED, emptyList(), distributionZip)
    }

    private
    fun Project.generatedBinFileFor(name: String) =
        layout.buildDirectory.file("generated-resources/$name/$name.bin")

    private
    fun Project.generatedTxtFileFor(name: String) =
        layout.buildDirectory.file("generated-resources/$name/$name.txt")

    private
    fun Project.generatedPropertiesFileFor(name: String) =
        layout.buildDirectory.file("generated-resources/$name/$name.properties")

    private
    fun Project.bucket(defaultProjectDependencies: List<String> = emptyList()) =
        configurations.creating {
            isCanBeResolved = false
            isCanBeConsumed = false
            isVisible = false
            withDependencies {
                defaultProjectDependencies.forEach { add(this@bucket.dependencies.create(project(it))) }
            }
        }

    private
    fun Project.libraryResolver(extends: List<Configuration>) =
        configurations.creating {
            attributes {
                attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
                attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
                attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
            }
            isCanBeResolved = true
            isCanBeConsumed = false
            isVisible = false
            extends.forEach { extendsFrom(it) }
        }

    private
    fun Project.startScriptResolver(extends: List<Configuration>) =
        configurations.creating {
            attributes {
                attribute(Usage.USAGE_ATTRIBUTE, objects.named("start-scripts"))
            }
            isCanBeResolved = true
            isCanBeConsumed = false
            isVisible = false
            extends.forEach { extendsFrom(it) }
        }

    private
    fun Project.sourcesResolver(extends: List<Configuration>) =
        configurations.creating {
            attributes {
                attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
                attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.DOCUMENTATION))
                attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named("gradle-source-folders"))
            }
            isCanBeResolved = true
            isCanBeConsumed = false
            isVisible = false
            extends.forEach { extendsFrom(it) }
        }

    private
    fun Project.docsResolver() =
        configurations.creating {
            attributes {
                attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
                attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.DOCUMENTATION))
                attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named("gradle-documentation"))
            }
            isCanBeResolved = true
            isCanBeConsumed = false
            isVisible = false
            withDependencies {
                add(this@docsResolver.dependencies.create(project(":docs")))
            }
        }

    private
    fun Project.consumableVariant(name: String, elements: String, bundling: String, extends: List<Configuration>, artifact: Any) =
        configurations.create("${name}Elements") {
            attributes {
                attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
                attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
                attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(elements))
                attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(bundling))
            }
            isCanBeResolved = false
            isCanBeConsumed = true
            isVisible = false
            extends.forEach { extendsFrom(it) }
            outgoing.artifact(artifact)
        }

    private
    fun Project.consumableSourcesVariant(name: String, extends: List<Configuration>) =
        configurations.create("${name}Elements") {
            attributes {
                attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
                attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.DOCUMENTATION))
                attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named("gradle-source-folders"))
            }
            isCanBeResolved = false
            isCanBeConsumed = true
            isVisible = false
            extends.forEach { extendsFrom(it) }
        }

    private
    fun Project.consumablePlatformVariant(name: String, extends: List<Configuration>) =
        configurations.create("${name}Elements") {
            attributes {
                attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
                attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.REGULAR_PLATFORM))
            }
            isCanBeResolved = false
            isCanBeConsumed = true
            isVisible = false
            extends.forEach { extendsFrom(it) }
        }
}
