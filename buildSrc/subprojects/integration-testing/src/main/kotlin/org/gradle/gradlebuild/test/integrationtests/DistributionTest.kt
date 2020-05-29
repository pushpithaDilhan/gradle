/*
 * Copyright 2016 the original author or authors.
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

package org.gradle.gradlebuild.test.integrationtests

import org.gradle.api.Named
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.options.Option
import org.gradle.api.tasks.testing.Test
import org.gradle.gradlebuild.testing.integrationtests.cleanup.DaemonTracker
import org.gradle.kotlin.dsl.*
import org.gradle.process.CommandLineArgumentProvider
import java.io.File
import java.util.SortedSet


/**
 * Base class for all tests that check the end-to-end behavior of a Gradle distribution.
 */
abstract class DistributionTest : Test() {

    @get:Internal
    abstract val prefix: String

    @Internal
    val binDistributionZip = DistributionZipEnvironmentProvider(project, "bin")

    @Internal
    val allDistributionZip = DistributionZipEnvironmentProvider(project, "all")

    @Internal
    val docsDistributionZip = DistributionZipEnvironmentProvider(project, "docs")

    @Internal
    val srcDistributionZip = DistributionZipEnvironmentProvider(project, "src")

    @Internal
    val gradleInstallationForTest = GradleInstallationForTestEnvironmentProvider(project, this)

    @Internal
    val toolingApi = ToolingApiEnvironmentProvider(project)

    @get:Internal
    abstract val tracker: Property<DaemonTracker>

    @get:Internal
    @get:Option(option = "rerun", description = "Always rerun the task")
    val rerun: Property<Boolean> = project.objects.property<Boolean>()
        .convention(
            project.providers.systemProperty("idea.active")
                .map { true }
                .orElse(project.provider { false })
        )

    @Option(option = "no-rerun", description = "Only run the task when necessary")
    fun setNoRerun(value: Boolean) {
        rerun.set(!value)
    }

    init {
        jvmArgumentProviders.add(gradleInstallationForTest)
        jvmArgumentProviders.add(toolingApi)
        jvmArgumentProviders.add(binDistributionZip)
        jvmArgumentProviders.add(allDistributionZip)
        jvmArgumentProviders.add(docsDistributionZip)
        jvmArgumentProviders.add(srcDistributionZip)
        outputs.upToDateWhen {
            !rerun.get()
        }
    }

    override fun executeTests() {
        addTestListener(tracker.get().newDaemonListener())
        super.executeTests()
    }
}


class ToolingApiEnvironmentProvider(project: Project) : CommandLineArgumentProvider, Named {

    @Internal
    val localRepo = project.files()

    @get:Classpath
    val jars: SortedSet<File>
        get() = localRepo.asFileTree.matching { include("**/*.jar") }.files.toSortedSet()

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val metadatas: FileCollection
        get() = localRepo.asFileTree.matching {
                include("**/*.pom")
                include("**/*.xml")
                include("**/*.module")
            }

    override fun asArguments() =
        if (!localRepo.isEmpty) mapOf("integTest.localRepository" to localRepo.singleFile).asSystemPropertyJvmArguments()
        else emptyList()

    @Internal
    override fun getName() =
        "libsRepository"
}


class GradleInstallationForTestEnvironmentProvider(project: Project, private val testTask: DistributionTest) : CommandLineArgumentProvider, Named {

    @Internal
    val gradleHomeDir = project.files()

    @Internal
    val gradleUserHomeDir = project.objects.directoryProperty()

    @Internal
    val gradleSnippetsDir = project.objects.directoryProperty()

    @Internal
    val daemonRegistry = project.objects.directoryProperty()

    @get:Nested
    val gradleDistribution = GradleDistribution(gradleHomeDir)

    @Internal
    val distZipVersion = project.version.toString()

    override fun asArguments(): Iterable<String> {
        val distributionDir = if (gradleHomeDir.files.size == 1) gradleHomeDir.singleFile else null
        val distributionName = if (distributionDir != null) {
            // complete distribution is used from 'build/bin distribution'
            distributionDir.parentFile.parentFile.name
        } else {
            // gradle-runtime-api-info.jar in 'build/libs'
            testTask.classpath.filter { it.name.startsWith("gradle-runtime-api-info") }.singleFile.parentFile.parentFile.parentFile.name
        }
        return (
            (if (distributionDir != null) mapOf("integTest.gradleHomeDir" to distributionDir) else emptyMap()) + mapOf(
                "integTest.gradleUserHomeDir" to absolutePathOf(gradleUserHomeDir.dir(distributionName)),
                "integTest.samplesdir" to absolutePathOf(gradleSnippetsDir),
                "org.gradle.integtest.daemon.registry" to absolutePathOf(daemonRegistry.dir(distributionName)),
                "integTest.distZipVersion" to distZipVersion
            )
        ).asSystemPropertyJvmArguments()
    }

    @Internal
    override fun getName() =
        "gradleInstallationForTest"
}


class DistributionZipEnvironmentProvider(project: Project, private val distributionType: String) : CommandLineArgumentProvider, Named {

    @Classpath
    val distributionZip = project.files()

    override fun asArguments() =
        if (distributionZip.isEmpty) {
            emptyList()
        } else {
            mapOf("integTest.${distributionType}Distribution" to distributionZip.singleFile).asSystemPropertyJvmArguments()
        }

    @Internal
    override fun getName() =
        "${distributionType}Distribution"
}


private
fun absolutePathOf(provider: Provider<Directory>) =
    provider.get().asFile.absolutePath


internal
fun <K, V> Map<K, V>.asSystemPropertyJvmArguments(): Iterable<String> =
    map { (key, value) -> "-D$key=$value" }
