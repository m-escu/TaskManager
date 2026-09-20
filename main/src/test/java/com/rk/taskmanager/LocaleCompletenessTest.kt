package com.rk.taskmanager

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Fork string discipline, enforced at test time (the "locale automation"
 * idea from the Phase 6 review): every key present in the default
 * `values/strings.xml` must exist in every `values-*` locale directory,
 * and no locale may carry duplicate names. Lint's MissingTranslation check
 * is disabled in this module (upstream ships partial locales), so this test
 * is the only guard — it fails the CI build listing every gap at once.
 */
class LocaleCompletenessTest {

    private fun findResDir(): File {
        var dir: File? = File(System.getProperty("user.dir")!!)
        repeat(6) {
            val d = dir ?: return@repeat
            File(d, "src/main/res/values/strings.xml").takeIf { it.exists() }?.let {
                return it.parentFile.parentFile
            }
            // Some Gradle invocations run tests from the repo root instead
            // of the module dir.
            File(d, "main/src/main/res/values/strings.xml").takeIf { it.exists() }?.let {
                return it.parentFile.parentFile
            }
            dir = d.parentFile
        }
        throw IllegalStateException(
            "res dir not found walking up from ${System.getProperty("user.dir")}"
        )
    }

    private fun stringNames(file: File): List<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).map {
            nodes.item(it).attributes.getNamedItem("name").nodeValue
        }
    }

    @Test
    fun everyDefaultKeyExistsInEveryLocale() {
        val res = findResDir()
        val defaultNames = stringNames(File(res, "values/strings.xml"))
        assertTrue(
            "suspiciously few default strings (${defaultNames.size}) — wrong res dir?",
            defaultNames.size > 100,
        )
        val localeDirs = res.listFiles { f -> f.isDirectory && f.name.startsWith("values-") }
        assertTrue("no locale directories found under $res", !localeDirs.isNullOrEmpty())

        val problems = StringBuilder()
        val defaultsDupes = defaultNames.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (defaultsDupes.isNotEmpty()) problems.appendLine("default: duplicates $defaultsDupes")

        for (locale in localeDirs) {
            val file = File(locale, "strings.xml")
            if (!file.exists()) {
                problems.appendLine("${locale.name}: strings.xml missing")
                continue
            }
            val names = stringNames(file)
            val dupes = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            if (dupes.isNotEmpty()) problems.appendLine("${locale.name}: duplicates $dupes")
            val missing = defaultNames.toSet() - names.toSet()
            if (missing.isNotEmpty()) {
                problems.appendLine("${locale.name}: missing ${missing.sorted()}")
            }
        }

        if (problems.isNotEmpty()) fail(problems.toString())
    }
}
