package com.wingman.core.model

import java.io.File

/** Locates the repo's `contracts/` folder from the test working directory (Gradle uses `android/app`). */
object ContractFiles {
    fun contractsDir(): File {
        System.getProperty("wingman.contractsDir")?.let { return File(it) }
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "contracts/openapi.yaml")
            if (candidate.isFile) return candidate.parentFile
            dir = dir.parentFile
        }
        error("contracts/openapi.yaml not found above ${File("").absolutePath}; set -Dwingman.contractsDir")
    }

    fun examplesDir(): File = File(contractsDir(), "examples")

    fun openApiText(): String = File(contractsDir(), "openapi.yaml").readText()
}
