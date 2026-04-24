package devpilot.util

import java.io.File

fun resolveFilePath(path: String, baseDir: String = System.getProperty("user.dir")): File {
    val workingDir = File(baseDir)
    val directFile = File(path)
    if (directFile.isAbsolute && directFile.exists()) return directFile.canonicalFile

    val trimmedPath = path.trimStart('/')
    if (trimmedPath != path) {
        val relativeFile = File(workingDir, trimmedPath)
        if (relativeFile.exists()) return relativeFile.canonicalFile
    }

    return File(workingDir, path).canonicalFile
}

fun walkDirectory(dir: File, base: File, maxDepth: Int = 5, currentDepth: Int = 0): List<String> {
    if (currentDepth >= maxDepth) return emptyList()
    val entries = dir.listFiles() ?: return emptyList()
    val files = mutableListOf<String>()
    for (entry in entries) {
        val relativePath = entry.relativeTo(base).path
        if (entry.isDirectory && !shouldExclude(relativePath)) {
            files.add("$relativePath/")
            files.addAll(walkDirectory(entry, base, maxDepth, currentDepth + 1))
        } else if (!entry.isDirectory) {
            files.add(relativePath)
        }
    }
    return files
}

private fun shouldExclude(path: String): Boolean {
    val excludedDirs = setOf(".git", ".devenv", "build", "node_modules", ".idea", ".gradle")
    return excludedDirs.any { path == it || path.startsWith("$it/") || path.contains("/$it/") }
}
