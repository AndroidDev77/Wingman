package com.wingman.experimental

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.jar.JarFile

/**
 * WING-031 (D-023, D-026): the experimental unofficial-API connector is compiled only by the
 * experimental build. Runs in BOTH builds and checks the build it runs in:
 *
 *  - default build (`wingman.experimental` unset, CI): the compiled app classes contain no
 *    `com.wingman.integrations.hinge` class outside `notifications/`, and none can be loaded;
 *  - experimental build (`-Pwingman.experimental=true`): they are there.
 *
 * Plus static checks on the source tree that hold in either build. The Gradle task
 * `checkNoExperimentalClasses` does the same class scan on the debug and release outputs.
 */
class ExperimentalSourceSetTest {
    private val appDir = File("src").let { if (it.isDirectory) File(".") else File("android/app") }

    /** `com.wingman.BuildConfig.WINGMAN_EXPERIMENTAL`, read reflectively (false when absent). */
    private fun experimentalBuild(): Boolean = runCatching {
        Class.forName("com.wingman.BuildConfig").getField("WINGMAN_EXPERIMENTAL").getBoolean(null)
    }.getOrDefault(false)

    /**
     * Class files (relative paths) where the app's main classes were loaded from: a directory (the
     * local JVM verifier) or a jar (AGP bundles the app's classes into one for unit tests).
     */
    private fun appClassFiles(): List<String> {
        val root = File(Class.forName("com.wingman.AppInfo").protectionDomain.codeSource.location.toURI())
        return if (root.isDirectory) {
            root.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }
                .map { it.relativeTo(root).invariantSeparatorsPath }.toList()
        } else {
            JarFile(root).use { jar -> jar.entries().toList().map { it.name }.filter { it.endsWith(".class") } }
        }
    }

    private fun experimentalConnectorClasses(files: List<String>) = files.filter {
        it.startsWith("com/wingman/integrations/hinge/") && !it.startsWith("com/wingman/integrations/hinge/notifications/")
    }

    private val representative = listOf(
        "com.wingman.integrations.hinge.HingeConnector",
        "com.wingman.integrations.hinge.HingeBuildFlags",
        "com.wingman.integrations.hinge.SendbirdChatTransport",
        "com.wingman.integrations.hinge.EncryptedFileHingeSessionStore",
    )

    private fun loadable(name: String) = runCatching { Class.forName(name) }.isSuccess

    @Test fun `the compiled app matches the build - no connector class unless experimental`() {
        val files = appClassFiles()
        assertTrue("scan saw no app classes", files.any { it.startsWith("com/wingman/core/") })
        val connector = experimentalConnectorClasses(files)
        if (experimentalBuild()) {
            assertTrue("experimental build without the connector", connector.size > 10)
            for (name in representative) assertTrue("$name missing from the experimental build", loadable(name))
        } else {
            assertEquals("default build contains experimental classes", emptyList<String>(), connector)
            for (name in representative) assertFalse("$name is loadable in the default build", loadable(name))
        }
        // The notification-assisted fallback ships in every build.
        assertTrue(loadable("com.wingman.integrations.hinge.notifications.HingeNotificationPlugin"))
    }

    @Test fun `static - src-main holds only the Hinge notification plugin`() {
        val hingeMain = File(appDir, "src/main/java/com/wingman/integrations/hinge")
        assertTrue("source tree not found from ${File("").absolutePath}", hingeMain.isDirectory)
        val files = hingeMain.walkTopDown().filter { it.isFile }.map { it.relativeTo(hingeMain).invariantSeparatorsPath }.toList()
        assertTrue(files.isNotEmpty())
        assertEquals(emptyList<String>(), files.filterNot { it.startsWith("notifications/") })
    }

    private fun withoutComments(src: String): String =
        src.replace(Regex("""/\*[\s\S]*?\*/"""), "").lines().joinToString("\n") { it.substringBefore("//") }

    @Test fun `static - nothing in main or standard names an experimental class`() {
        val roots = listOf("src/main/java", "src/standard/java").map { File(appDir, it) }
        val offenders = roots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.mapNotNull { f ->
                val code = withoutComments(f.readText()) // comments may name them; code may not
                val bad = Regex("""com\.wingman\.integrations\.hinge\.(?!notifications\b)""").containsMatchIn(code) ||
                    Regex("""\b(HingeModule|HingeConnector|HingeExperimentalActivity|HingeBuildFlags)\b""").containsMatchIn(code)
                f.relativeTo(appDir).invariantSeparatorsPath.takeIf { bad }
            }.toList()
        }
        assertEquals(emptyList<String>(), offenders)
        val manifest = File(appDir, "src/main/AndroidManifest.xml").readText()
        assertFalse("main manifest declares an experimental component", manifest.contains("integrations.hinge.android"))
    }

    @Test fun `static - the experimental and standard stubs expose the same members`() {
        fun members(path: String) = Regex("""(const val \w+|fun \w+\([^)]*\))""")
            .findAll(File(appDir, path).readText()).map { it.value.replace(Regex("\\s+"), " ") }.toList()
        val standard = members("src/standard/java/com/wingman/experimental/ExperimentalModules.kt")
        val experimental = members("src/experimental/java/com/wingman/experimental/ExperimentalModules.kt")
        assertEquals(listOf("const val INCLUDED", "fun init(context: Context, container: AppContainer)", "fun settingsEntries()"), standard)
        assertEquals(standard, experimental)
        val gradle = File(appDir, "build.gradle.kts").readText()
        assertTrue(gradle.contains("""java.srcDir(if (wingmanExperimental) "src/experimental/java" else "src/standard/java")"""))
        assertTrue(gradle.contains("""if (wingmanExperimental) java.srcDir("src/testExperimental/java")"""))
    }
}
