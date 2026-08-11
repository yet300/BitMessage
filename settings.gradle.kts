enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
rootProject.name = "BitMessage"

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

include(":androidApp")

include(":sharedLogic")
include(":sharedUI")

include(":core")
include(":core:common")
include(":core:foundation")
include(":core:model")
include(":core:testing")

include(":protocol:bitchat")

include(":transport")
include(":transport:api")

include(":engine")
include(":engine:mesh")

include(":feature")
include(":feature:root")
