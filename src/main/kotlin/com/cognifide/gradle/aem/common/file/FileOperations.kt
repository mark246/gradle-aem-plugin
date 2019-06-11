package com.cognifide.gradle.aem.common.file

import com.cognifide.gradle.aem.AemPlugin
import com.cognifide.gradle.aem.common.utils.Formats
import com.cognifide.gradle.aem.common.utils.Patterns
import net.lingala.zip4j.core.ZipFile
import net.lingala.zip4j.model.FileHeader
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.util.Zip4jConstants
import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import org.apache.commons.io.filefilter.TrueFileFilter
import org.gradle.api.Project
import org.gradle.util.GFileUtils
import org.reflections.Reflections
import org.reflections.scanners.ResourcesScanner
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object FileOperations {

    fun <T> streamFromPathOrClasspath(resourcePath: String, reader: (InputStream) -> T): T =
            fromPathOrClasspath(resourcePath).use { reader(it) }

    private fun fromPathOrClasspath(resourcePath: String): InputStream {
        val resourceFile = File(resourcePath)
        if (resourceFile.exists()) {
            return GFileUtils.openInputStream(resourceFile)
        }
        val resourceStream: InputStream? = this::class.java.getResourceAsStream(resourcePath)
        if (resourceStream != null) {
            return resourceStream
        }
        val resource: URL? = this::class.java.classLoader.getResource(resourcePath)
        if (resource != null) {
            return GFileUtils.openInputStream(File(resource.file))
        }
        throw FileException("Cannot load file: $resourcePath")
    }

    fun readResource(path: String): InputStream? {
        return javaClass.getResourceAsStream("/${AemPlugin.PKG.replace(".", "/")}/$path")
    }

    fun getResources(path: String): List<String> {
        return Reflections("${AemPlugin.PKG}.$path".replace("/", "."), ResourcesScanner()).getResources { true; }.toList()
    }

    fun eachResource(resourceRoot: String, targetDir: File, callback: (String, File) -> Unit) {
        for (resourcePath in getResources(resourceRoot)) {
            val outputFile = File(targetDir, resourcePath.substringAfterLast("$resourceRoot/"))

            callback(resourcePath, outputFile)
        }
    }

    fun copyResources(resourceRoot: String, targetDir: File, skipExisting: Boolean = false) {
        eachResource(resourceRoot, targetDir) { resourcePath, outputFile ->
            if (!skipExisting || !outputFile.exists()) {
                copy(resourcePath, outputFile)
            }
        }
    }

    private fun copy(resourcePath: String, outputFile: File) {
        GFileUtils.mkdirs(outputFile.parentFile)

        javaClass.getResourceAsStream("/$resourcePath").use { input ->
            FileOutputStream(outputFile).use { output ->
                IOUtils.copy(input, output)
            }
        }
    }

    fun amendFiles(dir: File, wildcardFilters: List<String>, amender: (File, String) -> String) {
        val files = FileUtils.listFiles(dir, TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE)
        files?.filter { Patterns.wildcard(it, wildcardFilters) }?.forEach { file ->
            val source = amender(file, file.inputStream().bufferedReader().use { it.readText() })
            file.printWriter().use { it.print(source) }
        }
    }

    fun amendFile(file: File, amender: (String) -> String) {
        val source = amender(file.inputStream().bufferedReader().use { it.readText() })
        file.printWriter().use { it.print(source) }
    }

    fun find(project: Project, dirIfFileName: String, pathOrFileNames: List<String>): File? {
        for (pathOrFileName in pathOrFileNames) {
            val file = find(project, dirIfFileName, pathOrFileName)
            if (file != null) {
                return file
            }
        }

        return null
    }

    fun find(project: Project, dirIfFileName: String, pathOrFileName: String): File? {
        if (pathOrFileName.isBlank()) {
            return null
        }

        return mutableListOf<(String) -> File>(
                { project.file(pathOrFileName) },
                { File(File(dirIfFileName), pathOrFileName) },
                { File(pathOrFileName) }
        ).map { it(pathOrFileName) }.firstOrNull { it.exists() }
    }

    fun find(dir: File, pattern: String): File? {
        return find(dir, listOf(pattern))
    }

    fun find(dir: File, patterns: List<String>): File? {
        var result: File? = null
        val files = dir.listFiles { _, name -> Patterns.wildcard(name, patterns) }
        if (files != null) {
            result = files.firstOrNull()
        }
        return result
    }

    fun isDirEmpty(dir: File): Boolean {
        return dir.exists() && isDirEmpty(Paths.get(dir.absolutePath))
    }

    fun isDirEmpty(dir: Path): Boolean {
        Files.newDirectoryStream(dir).use { dirStream -> return !dirStream.iterator().hasNext() }
    }

    fun removeDirContents(dir: File): Boolean {
        val children = dir.listFiles() ?: arrayOf()
        var result = true
        children.forEach {
            result = result && it.deleteRecursively()
        }
        return result
    }

    /**
     * Only Zip4j correctly extracts AEM backup ZIP files.
     * Gradle zipTree and Zero-Turnaround ZipUtil is not working properly in that case.
     */
    fun zipUnpackAll(zip: File, targetDir: File) {
        ZipFile(zip).extractAll(targetDir.absolutePath)
    }

    @Suppress("unchecked_cast")
    fun zipUnpackDir(zip: File, dirName: String, dir: File) {
        val dirFileName = "$dirName/"
        if (!zipContains(zip, dirFileName)) {
            return
        }

        ZipFile(zip).apply {
            (fileHeaders as List<FileHeader>).asSequence()
                    .filter { it.fileName.startsWith(dirFileName) }
                    .forEach { extractFile(it, dir.absolutePath) }
        }
    }

    fun zipContains(zip: File, fileName: String): Boolean {
        return ZipFile(zip).getFileHeader(fileName) != null
    }

    fun zipPack(zip: File, sourceDir: File) {
        ZipFile(zip).apply {
            addFolder(sourceDir, ZipParameters().apply {
                compressionMethod = Zip4jConstants.COMP_STORE
            })
        }
    }

    fun lock(file: File) = file.writeText(Formats.toJson(mapOf("locked" to Formats.date())))

    fun lock(file: File, callback: () -> Unit) {
        if (!file.exists()) {
            callback()
            lock(file)
        }
    }

    fun lockOperation(file: File, operation: (File) -> Unit): File {
        val lock = File(file.parentFile, "${file.name}.lock")
        if (!lock.exists() && file.exists()) {
            file.delete()
        }

        if (!file.exists()) {
            operation(file)
            lock.printWriter().use { it.print(Formats.toJson(mapOf("locked" to Formats.date()))) }
        }

        return file
    }
}