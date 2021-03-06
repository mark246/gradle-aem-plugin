package com.cognifide.gradle.aem.environment
import com.cognifide.gradle.aem.test.AemBuildTest
import org.junit.jupiter.api.Test

class EnvironmentPluginTest: AemBuildTest() {

    @Test
    fun `should apply plugin correctly`() {
        val projectDir = prepareProject("environment-minimal") {
            settingsGradle("")

            buildGradle("""
                plugins {
                    id("com.cognifide.aem.environment")
                }
                """)
        }

        runBuild(projectDir, "tasks", "-Poffline") {
            assertTask(":tasks")
        }
    }
}