plugins {
    alias(libs.plugins.local.kotlin.multiplatform)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.foundation)
            implementation(projects.core.model)
        }

        androidHostTest {
            dependencies {
                implementation(projects.core.testing)
            }
            resources.srcDir(rootProject.layout.projectDirectory.dir("compatibility"))
        }
    }
}
