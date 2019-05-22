package com.cognifide.gradle.aem.common

import com.cognifide.gradle.aem.pkg.Package
import com.fasterxml.jackson.annotation.JsonIgnore
import java.io.File
import java.io.Serializable

class PackageOptions(aem: AemExtension) : Serializable {

    var rootDir: File = aem.project.file("src/main/content")

    @get:JsonIgnore
    val jcrRootDir: File
        get() = File(rootDir, Package.JCR_ROOT)

    @get:JsonIgnore
    val vltRootDir: File
        get() = File(rootDir, Package.VLT_PATH)

    /**
     * CRX package name conventions (with wildcard) indicating that package can change over time
     * while having same version specified. Affects CRX packages composed and satisfied.
     */
    var snapshots: List<String> = aem.props.list("package.snapshots") ?: listOf()

    /**
     * Custom path to Vault files that will be used to build CRX package.
     * Useful to share same files for all packages, like package thumbnail.
     */
    var metaCommonRoot: String = "${aem.configCommonDir}/${Package.META_RESOURCES_PATH}"

    /**
     * Content path for OSGi bundle jars being placed in CRX package.
     *
     * Default convention assumes that subprojects have separate bundle paths, because of potential re-installation of subpackages.
     * When all subprojects will have same bundle path, reinstalling one subpackage may end with deletion of other bundles coming from another subpackage.
     *
     * Beware that more nested bundle install directories are not supported by AEM by default (up to 4th depth level).
     * That's the reason of using dots in subproject names to avoid that limitation.
     */
    var installPath: String = if (aem.project == aem.project.rootProject) {
        "/apps/${aem.project.rootProject.name}/install"
    } else {
        "/apps/${aem.project.rootProject.name}/${aem.projectName}/install"
    }

    /**
     * Configures a local repository from which unreleased JARs could be added as 'compileOnly' dependency
     * and be deployed within CRX package deployment.
     */
    var installRepository: Boolean = true

    /**
     * Define patterns for known exceptions which could be thrown during package installation
     * making it impossible to succeed.
     *
     * When declared exception is encountered during package installation process, no more
     * retries will be applied.
     */
    var errors: List<String> = (aem.props.list("package.errors") ?: listOf(
            "javax.jcr.nodetype.*Exception",
            "org.apache.jackrabbit.oak.api.*Exception",
            "org.apache.jackrabbit.vault.packaging.*Exception",
            "org.xml.sax.*Exception"
    ))

    /**
     * Determines number of lines to process at once during reading html responses.
     *
     * The higher the value, the bigger consumption of memory but shorter execution time.
     * It is a protection against exceeding max Java heap size.
     */
    var responseBuffer = aem.props.int("package.responseBuffer") ?: 4096
}