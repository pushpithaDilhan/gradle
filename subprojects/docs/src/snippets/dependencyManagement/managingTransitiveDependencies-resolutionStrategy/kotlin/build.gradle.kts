/*
 * Copyright 2019 the original author or authors.
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

plugins {
   `java-library`
}

repositories {
    mavenCentral()
}

// tag::dependencies[]
dependencies {
    implementation("org.apache.commons:commons-lang3:3.0")
    // the following dependency brings lang3 3.8.1 transitively
    implementation("com.opencsv:opencsv:4.6")
}
// end::dependencies[]

// tag::fail-on-version-conflict[]
configurations.all {
    resolutionStrategy {
        failOnVersionConflict()
    }
}
// end::fail-on-version-conflict[]

// tag::fail-on-dynamic[]
configurations.all {
    resolutionStrategy {
        failOnDynamicVersions()
    }
}
// end::fail-on-dynamic[]

// tag::fail-on-changing[]
configurations.all {
    resolutionStrategy {
        failOnChangingVersions()
    }
}
// end::fail-on-changing[]

// tag::fail-on-unstable[]
configurations.all {
    resolutionStrategy {
        failOnNonReproducibleResolution()
    }
}
// end::fail-on-unstable[]
