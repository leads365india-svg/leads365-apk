package com.leads365.apk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EnglishOnlyUiTest {

    @Test
    fun testNoHindiOrDevanagariCharactersInAppUiAndResources() {
        val projectRoot = File(System.getProperty("user.dir") ?: ".")
        val mainSrcDir = File(projectRoot, "src/main")
        assertTrue("main source directory must exist at ${mainSrcDir.absolutePath}", mainSrcDir.exists())

        val devanagariRegex = Regex("[\\u0900-\\u097F]")
        val violations = mutableListOf<String>()

        // Scan all .kt and .xml files in src/main
        mainSrcDir.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .forEach { file ->
                file.useLines { lines ->
                    lines.forEachIndexed { index, line ->
                        if (devanagariRegex.containsMatchIn(line)) {
                            val relativePath = file.relativeTo(projectRoot).path
                            violations.add("$relativePath:${index + 1} -> ${line.trim()}")
                        }
                    }
                }
            }

        assertEquals(
            "Found Devanagari / Hindi characters in app source files:\n" + violations.joinToString("\n"),
            0,
            violations.size
        )
    }
}
