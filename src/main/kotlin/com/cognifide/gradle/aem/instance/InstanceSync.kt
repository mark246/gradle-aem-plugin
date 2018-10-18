package com.cognifide.gradle.aem.instance

import com.cognifide.gradle.aem.api.AemConfig
import com.cognifide.gradle.aem.internal.Patterns
import com.cognifide.gradle.aem.internal.ProgressCountdown
import com.cognifide.gradle.aem.internal.file.FileException
import com.cognifide.gradle.aem.internal.file.downloader.HttpFileDownloader
import com.cognifide.gradle.aem.internal.http.PreemptiveAuthInterceptor
import com.cognifide.gradle.aem.pkg.PackagePlugin
import com.cognifide.gradle.aem.pkg.deploy.*
import org.apache.commons.io.IOUtils
import org.apache.http.HttpEntity
import org.apache.http.HttpResponse
import org.apache.http.HttpStatus
import org.apache.http.NameValuePair
import org.apache.http.auth.AuthScope
import org.apache.http.auth.UsernamePasswordCredentials
import org.apache.http.client.HttpClient
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.entity.UrlEncodedFormEntity
import org.apache.http.client.methods.*
import org.apache.http.conn.ssl.NoopHostnameVerifier
import org.apache.http.conn.ssl.SSLConnectionSocketFactory
import org.apache.http.entity.mime.MultipartEntityBuilder
import org.apache.http.impl.client.BasicCredentialsProvider
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.message.BasicNameValuePair
import org.apache.http.ssl.SSLContextBuilder
import org.gradle.api.Project
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.zeroturnaround.zip.ZipUtil
import java.io.File
import java.io.FileNotFoundException
import java.util.*

class InstanceSync(val project: Project, val instance: Instance) {

    val config = AemConfig.of(project)

    val logger = project.logger

    var basicUser = instance.user

    var basicPassword = instance.password

    var connectionTimeout = config.instanceConnectionTimeout

    var connectionUntrustedSsl = config.instanceConnectionUntrustedSsl

    var connectionRetries = true

    var requestConfigurer: (HttpRequestBase) -> Unit = { _ -> }

    var responseHandler: (HttpResponse) -> Unit = { _ -> }

    fun get(path: String): String {
        return fetch(HttpGet(composeUrl(path)))
    }

    fun head(path: String): String {
        return fetch(HttpHead(composeUrl(path)))
    }

    fun delete(path: String): String {
        return fetch(HttpDelete(composeUrl(path)))
    }

    fun put(path: String): String {
        return fetch(HttpPut(composeUrl(path)))
    }

    fun patch(path: String): String {
        return fetch(HttpPatch(composeUrl(path)))
    }

    fun postUrlencoded(url: String, params: Map<String, Any> = mapOf()): String {
        return post(url, createEntityUrlencoded(params))
    }

    fun postMultipart(url: String, params: Map<String, Any> = mapOf()): String {
        return post(url, createEntityMultipart(params))
    }

    private fun postMultipartInstall(url: String, params: Map<String, Any> = mapOf()): InstallResponse {
        return postInstall(url, createEntityMultipart(params))
    }

    private fun post(url: String, entity: HttpEntity): String {
        return fetch(HttpPost(composeUrl(url)).apply { this.entity = entity })
    }

    private fun postInstall(url: String, entity: HttpEntity): InstallResponse {
        return fetchInstallBody(HttpPost(composeUrl(url)).apply { this.entity = entity })
    }

    /**
     * Fix for HttpClient's: 'escaped absolute path not valid'
     * https://stackoverflow.com/questions/13652681/httpclient-invalid-uri-escaped-absolute-path-not-valid
     */
    private fun composeUrl(url: String): String {
        return "${instance.httpUrl}${url.replace(" ", "%20")}"
    }

    private fun fetch(method: HttpRequestBase): String {
        return execute(method) { response ->
            val body = IOUtils.toString(response.entity.content) ?: ""

            if (response.statusLine.statusCode == HttpStatus.SC_OK) {
                return@execute body
            } else {
                logger.debug(body)
                throw DeployException("Unexpected response from $instance: ${response.statusLine}")
            }
        }
    }

    private fun fetchInstallBody(method: HttpRequestBase): InstallResponse {
        return execute(method) { response ->
            if (response.statusLine.statusCode == HttpStatus.SC_OK) {
                return@execute InstallResponseBuilder.buildFromStream(response.entity.content)
            } else {
                throw DeployException("Unexpected response from $instance: ${response.statusLine}")
            }
        }
    }

    private fun <T> execute(method: HttpRequestBase, success: (HttpResponse) -> T): T {
        try {
            requestConfigurer(method)

            val client = createHttpClient()
            val response = client.execute(method)

            responseHandler(response)

            return success(response)
        } catch (e: Exception) {
            throw DeployException("Failed request to $instance: ${e.message}", e)
        } finally {
            method.releaseConnection()
        }
    }

    fun createHttpClient(): HttpClient {
        val builder = HttpClientBuilder.create()
                .addInterceptorFirst(PreemptiveAuthInterceptor())
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setSocketTimeout(connectionTimeout.toInt())
                        .setConnectTimeout(connectionTimeout.toInt())
                        .setConnectionRequestTimeout(connectionTimeout.toInt())
                        .build()
                )
                .setDefaultCredentialsProvider(BasicCredentialsProvider().apply {
                    setCredentials(AuthScope.ANY, UsernamePasswordCredentials(basicUser, basicPassword))
                })
        if (connectionUntrustedSsl) {
            builder.setSSLSocketFactory(createSslConnectionSocketFactory())
        }
        if (!connectionRetries) {
            builder.disableAutomaticRetries()
        }

        return builder.build()
    }

    private fun createSslConnectionSocketFactory(): SSLConnectionSocketFactory {
        val sslContext = SSLContextBuilder()
                .loadTrustMaterial(null, { _, _ -> true })
                .build()
        return SSLConnectionSocketFactory(sslContext, NoopHostnameVerifier.INSTANCE)
    }

    private fun createEntityUrlencoded(params: Map<String, Any>): HttpEntity {
        return UrlEncodedFormEntity(params.entries.fold(ArrayList<NameValuePair>()) { result, e ->
            result.add(BasicNameValuePair(e.key, e.value.toString())); result
        })
    }

    private fun createEntityMultipart(params: Map<String, Any>): HttpEntity {
        val builder = MultipartEntityBuilder.create()
        for ((key, value) in params) {
            if (value is File) {
                if (value.exists()) {
                    builder.addBinaryBody(key, value)
                }
            } else {
                val str = value.toString()
                if (str.isNotBlank()) {
                    builder.addTextBody(key, str)
                }
            }
        }

        return builder.build()
    }

    fun determineRemotePackage(): ListResponse.Package? {
        return resolveRemotePackage({ response ->
            response.resolvePackage(project, ListResponse.Package(project))
        }, true)
    }

    fun determineRemotePackagePath(): String {
        if (!config.packageRemotePath.isBlank()) {
            return config.packageRemotePath
        }

        val pkg = determineRemotePackage()
                ?: throw DeployException("Package is not uploaded on AEM instance.")

        return pkg.path
    }

    fun determineRemotePackage(file: File, refresh: Boolean = true): ListResponse.Package? {
        if (!ZipUtil.containsEntry(file, PackagePlugin.VLT_PROPERTIES)) {
            throw DeployException("File is not a valid CRX package: $file")
        }

        val xml = ZipUtil.unpackEntry(file, PackagePlugin.VLT_PROPERTIES).toString(Charsets.UTF_8)
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())

        val group = doc.select("entry[key=group]").text()
        val name = doc.select("entry[key=name]").text()
        val version = doc.select("entry[key=version]").text()

        return resolveRemotePackage({ response ->
            response.resolvePackage(project, ListResponse.Package(group, name, version))
        }, refresh)
    }

    private fun resolveRemotePackage(resolver: (ListResponse) -> ListResponse.Package?, refresh: Boolean): ListResponse.Package? {
        logger.debug("Asking for uploaded packages on $instance")

        if (instance.packages == null || refresh) {
            val json = postMultipart(PKG_MANAGER_LIST_JSON)
            instance.packages = try {
                ListResponse.fromJson(json)
            } catch (e: Exception) {
                throw DeployException("Cannot ask for uploaded packages on $instance.", e)
            }
        }

        return resolver(instance.packages!!)
    }

    fun uploadPackage(file: File): UploadResponse {
        lateinit var exception: DeployException
        for (i in 0..config.uploadRetry.times) {
            try {
                return uploadPackageOnce(file)
            } catch (e: DeployException) {
                exception = e

                if (i < config.uploadRetry.times) {
                    logger.warn("Cannot upload package $file to $instance.")
                    logger.debug("Upload error", e)

                    val header = "Retrying upload (${i + 1}/${config.uploadRetry.times}) after delay."
                    val countdown = ProgressCountdown(project, header, config.uploadRetry.delay(i + 1))
                    countdown.run()
                }
            }
        }

        throw exception
    }

    fun uploadPackageOnce(file: File): UploadResponse {
        val url = "$PKG_MANAGER_JSON_PATH/?cmd=upload"

        logger.info("Uploading package $file to $instance'")

        val json = try {
            postMultipart(url, mapOf(
                    "package" to file,
                    "force" to (config.uploadForce || isSnapshot(file))
            ))
        } catch (e: FileNotFoundException) {
            throw DeployException("Package file $file to be uploaded not found!", e)
        } catch (e: Exception) {
            throw DeployException("Cannot upload package $file to $instance. Reason: request failed.", e)
        }

        val response = try {
            UploadResponse.fromJson(json)
        } catch (e: Exception) {
            throw DeployException("Malformed response after uploading package $file to $instance.", e)
        }

        if (!response.isSuccess) {
            throw DeployException("Cannot upload package $file to $instance. Reason: ${response.msg}.")
        }

        return response
    }

    fun downloadPackage(remotePath: String, targetFile: File) {
        lateinit var exception: FileException
        val url = instance.httpUrl + remotePath

        for (i in 0..config.downloadRetry.times) {
            try {
                downloadPackageOnce(url, targetFile)
                return
            } catch (e: FileException) {
                exception = e

                if (i < config.downloadRetry.times) {
                    logger.warn("Cannot download package $remotePath from $instance.")
                    logger.debug("Download error", e)

                    val header = "Retrying download (${i + 1}/${config.downloadRetry.times}) after delay."
                    val countdown = ProgressCountdown(project, header, config.downloadRetry.delay(i + 1))
                    countdown.run()
                }
            }
        }

        throw exception
    }

    fun downloadPackageOnce(url: String, targetFile: File) {
        logger.info("Downloading package from $url to file $targetFile")

        with(HttpFileDownloader(project)) {
            username = basicUser
            password = basicPassword
            preemptiveAuthentication = true

            download(url, targetFile)
        }

        if (!targetFile.exists()) {
            throw FileException("Downloaded package missing: ${targetFile.path}")
        }
    }

    fun buildPackage(remotePath: String): PackageBuildResponse {
        val url = "$PKG_MANAGER_JSON_PATH$remotePath/?cmd=build"

        logger.info("Building package $remotePath on $instance")

        val json = try {
            postMultipart(url, mapOf())
        } catch (e: Exception) {
            throw DeployException("Cannot build package $remotePath on $instance. Reason: request failed.", e)
        }

        val response = try {
            PackageBuildResponse.fromJson(json)
        } catch (e: Exception) {
            throw DeployException("Malformed response after building package $remotePath on $instance.", e)
        }

        if (!response.isSuccess) {
            throw DeployException("Cannot build package $remotePath on $instance. Reason: ${response.msg}.")
        }
        return response
    }

    fun installPackage(remotePath: String): InstallResponse {
        lateinit var exception: DeployException
        for (i in 0..config.installRetry.times) {
            try {
                return installPackageOnce(remotePath)
            } catch (e: DeployException) {
                exception = e
                val criticalErrors = exception.criticalInstallationErrors
                if(criticalErrors.isNotEmpty()){
                    throw exception
                }
                else if (i < config.installRetry.times) {
                    logger.warn("Cannot install package $remotePath on $instance.")
                    logger.debug("Install error", e)

                    val header = "Retrying install (${i + 1}/${config.installRetry.times}) after delay."
                    val countdown = ProgressCountdown(project, header, config.installRetry.delay(i + 1))
                    countdown.run()
                }
            }
        }

        throw exception
    }

    fun installPackageOnce(remotePath: String): InstallResponse {
        val url = "$PKG_MANAGER_HTML_PATH$remotePath/?cmd=install"

        logger.info("Installing package $remotePath on $instance")

        val response = try {
            postMultipartInstall(url, mapOf("recursive" to config.installRecursive))
        } catch (e: Exception) {
            throw DeployException("Cannot install package $remotePath on $instance. Reason: request failed.", e)
        }
        if (!response.success) {
            throw DeployException("Cannot install package $remotePath on $instance. Status: ${response.status}. Errors: ${response.errors}.",response.errors)
        }

        return response
    }

    fun isSnapshot(file: File): Boolean {
        return Patterns.wildcard(file, config.packageSnapshots)
    }

    fun deployPackage(file: File) {
        installPackage(uploadPackage(file).path)
    }

    fun distributePackage(file: File) {
        val packagePath = uploadPackage(file).path

        installPackage(packagePath)
        activatePackage(packagePath)
    }

    fun activatePackage(remotePath: String): UploadResponse {
        val url = "$PKG_MANAGER_JSON_PATH$remotePath/?cmd=replicate"

        logger.info("Activating package $remotePath on $instance")

        val json = try {
            postMultipart(url)
        } catch (e: Exception) {
            throw DeployException("Cannot activate package $remotePath on $instance. Reason: request failed.", e)
        }

        val response = try {
            UploadResponse.fromJson(json)
        } catch (e: Exception) {
            throw DeployException("Malformed response after activating package $remotePath on $instance.", e)
        }

        if (!response.isSuccess) {
            throw DeployException("Cannot activate package $remotePath on $instance. Reason: ${response.msg}.")
        }

        return response
    }

    fun deletePackage(remotePath: String): DeleteResponse {
        val url = "$PKG_MANAGER_HTML_PATH$remotePath/?cmd=delete"

        logger.info("Deleting package $remotePath on $instance")

        val rawHtml = try {
            postMultipart(url)
        } catch (e: Exception) {
            throw DeployException("Cannot delete package $remotePath from $instance. Reason: request failed.", e)
        }

        val response = try {
            DeleteResponse(rawHtml)
        } catch (e: Exception) {
            throw DeployException("Malformed response after deleting package $remotePath from $instance.", e)
        }

        if (!response.success) {
            throw DeployException("Cannot delete package $remotePath from $instance. Status: ${response.status}. Errors: ${response.errors}.")
        }

        return response
    }

    fun uninstallPackage(remotePath: String): UninstallResponse {
        val url = "$PKG_MANAGER_HTML_PATH$remotePath/?cmd=uninstall"

        logger.info("Uninstalling package using command: $url")

        val rawHtml = try {
            postMultipart(url, mapOf("recursive" to config.installRecursive))
        } catch (e: Exception) {
            throw DeployException("Cannot uninstall package $remotePath on $instance. Reason: request failed.", e)
        }

        val response = try {
            UninstallResponse(rawHtml)
        } catch (e: Exception) {
            throw DeployException("Malformed response after uninstalling package $remotePath from $instance.", e)
        }

        if (!response.success) {
            throw DeployException("Cannot uninstall package $remotePath from $instance. Status: ${response.status}. Errors: ${response.errors}.")
        }

        return response
    }

    fun determineInstanceState(): InstanceState {
        return InstanceState(this, instance)
    }

    fun determineBundleState(): BundleState {
        logger.debug("Asking for OSGi bundles on $instance")

        return try {
            BundleState.fromJson(get(OSGI_BUNDLES_PATH))
        } catch (e: Exception) {
            logger.debug("Cannot determine OSGi bundles state on $instance", e)
            BundleState.unknown(e)
        }
    }

    fun determineComponentState(): ComponentState {
        logger.debug("Asking for OSGi components on $instance")

        return try {
            ComponentState.fromJson(get(OSGI_COMPONENTS_PATH))
        } catch (e: Exception) {
            logger.debug("Cannot determine OSGi components state on $instance", e)
            ComponentState.unknown()
        }
    }

    fun reload() {
        shutdown(OSGI_VMSTAT_SHUTDOWN_RESTART)
    }

    fun stop() {
        shutdown(OSGI_VMSTAT_SHUTDOWN_STOP)
    }

    private fun shutdown(type: String) {
        try {
            logger.info("Triggering shutdown of $instance.")
            postUrlencoded(OSGI_VMSTAT_PATH, mapOf("shutdown_type" to type))
        } catch (e: DeployException) {
            throw InstanceException("Cannot trigger shutdown of $instance.", e)
        }
    }

    companion object {
        const val PKG_MANAGER_PATH = "/crx/packmgr/service"

        const val PKG_MANAGER_JSON_PATH = "$PKG_MANAGER_PATH/.json"

        const val PKG_MANAGER_HTML_PATH = "$PKG_MANAGER_PATH/.html"

        const val PKG_MANAGER_LIST_JSON = "/crx/packmgr/list.jsp"

        const val OSGI_BUNDLES_PATH = "/system/console/bundles.json"

        const val OSGI_COMPONENTS_PATH = "/system/console/components.json"

        const val OSGI_VMSTAT_PATH = "/system/console/vmstat"

        const val OSGI_VMSTAT_SHUTDOWN_STOP = "Stop"

        const val OSGI_VMSTAT_SHUTDOWN_RESTART = "Restart"
    }

}

fun Collection<Instance>.sync(project: Project, callback: (InstanceSync) -> Unit) {
    return map { InstanceSync(project, it) }.parallelStream().forEach(callback)
}