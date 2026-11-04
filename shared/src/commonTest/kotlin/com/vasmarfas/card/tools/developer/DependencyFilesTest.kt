package com.vasmarfas.card.tools.developer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DependencyFilesTest {
    @Test
    fun packageJsonGivesTheOwnLicense() {
        assertEquals("demo: MIT", DependencyFiles.lines("""{"name": "demo", "license": "MIT", "dependencies": {"react": "^19.0.0"}}"""))
        assertEquals("old: MIT OR Apache-2.0", DependencyFiles.lines("""{"name": "old", "licenses": [{"type": "MIT"}, {"type": "Apache-2.0"}]}"""))
        assertEquals("", DependencyFiles.lines("""{"name": "demo", "private": true}"""))
    }

    @Test
    fun packageLockListsEveryPackage() {
        val lock = """
            {
              "name": "demo", "lockfileVersion": 3,
              "packages": {
                "": {"name": "demo", "license": "ISC"},
                "node_modules/@scope/left": {"version": "1.0.0", "license": "MIT"},
                "node_modules/a/node_modules/b": {"version": "2.0.0", "license": "(MIT OR Apache-2.0)"},
                "node_modules/bare": {"version": "3.0.0"}
              }
            }
        """.trimIndent()
        assertEquals("demo: ISC\n@scope/left: MIT\nb: (MIT OR Apache-2.0)", DependencyFiles.lines(lock))
    }

    @Test
    fun cargoManifestGivesThePackageSection() {
        val cargo = """
            [dependencies]
            name = "not this"

            [package]
            name = "demo"
            version = "0.1.0"
            license = "MIT OR Apache-2.0"

            [features]
            license = "not this either"
        """.trimIndent()
        assertEquals("demo: MIT OR Apache-2.0", DependencyFiles.lines(cargo))
        assertEquals("shared: GPL-3.0-only", DependencyFiles.lines("[workspace.package]\r\nname = \"shared\"\r\nlicense = \"GPL-3.0-only\"\r\n"))
    }

    @Test
    fun licenseCheckerReports() {
        val csv = """
            "module name","license","repository"
            "react@19.0.0","MIT","https://github.com/facebook/react"
            "pkg@1.0.0","Apache-2.0*","https://example.com/pkg"
        """.trimIndent()
        assertEquals("react@19.0.0: MIT\npkg@1.0.0: Apache-2.0*", DependencyFiles.lines(csv))
        val json = """{"react@19.0.0": {"licenses": "MIT", "repository": "x"}, "pair@1.0.0": {"licenses": ["MIT", "BSD-3-Clause"]}}"""
        assertEquals("react@19.0.0: MIT\npair@1.0.0: MIT OR BSD-3-Clause", DependencyFiles.lines(json))
    }

    // the renderers of github.com/jk1/Gradle-License-Report: CsvReportRenderer, JsonReportRenderer in both modes
    @Test
    fun gradleLicenseReports() {
        val csv = """
            "artifact","moduleUrl","moduleLicense","moduleLicenseUrl",
            "org.example:lib:1.0","https://example.com","Apache License, Version 2.0","https://www.apache.org/licenses/LICENSE-2.0",
        """.trimIndent()
        assertEquals("org.example:lib:1.0: Apache License, Version 2.0", DependencyFiles.lines(csv))
        val single = """{"dependencies": [{"moduleName": "org.example:lib", "moduleVersion": "1.0", "moduleLicense": "The Apache Software License, Version 2.0"}]}"""
        assertEquals("org.example:lib:1.0: The Apache Software License, Version 2.0", DependencyFiles.lines(single))
        val many = """{"dependencies": [{"moduleName": "org.example:two", "moduleLicenses": [{"moduleLicense": "MIT"}, {"moduleLicense": "EPL-2.0"}]}]}"""
        assertEquals("org.example:two: MIT OR EPL-2.0", DependencyFiles.lines(many))
    }

    @Test
    fun plainListsAreLeftAlone() {
        assertNull(DependencyFiles.lines("react: MIT\nleft-pad\tWTFPL"))
        assertNull(DependencyFiles.lines("{ broken"))
    }

    @Test
    fun reportFeedsTheCompatibilityCheck() {
        val report = """{"dependencies": [{"moduleName": "org.example:lib", "moduleVersion": "1.0", "moduleLicense": "The Apache Software License, Version 2.0"}]}"""
        val line = LicenseCompat.checkList(report, Licenses.byId("MIT")!!, false, LicenseUsage.DISTRIBUTE).single()
        assertEquals("org.example:lib:1.0", line.label)
        assertEquals(Verdict.YES, line.result.verdict)
    }
}
