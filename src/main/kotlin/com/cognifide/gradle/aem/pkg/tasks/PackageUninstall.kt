package com.cognifide.gradle.aem.pkg.tasks

import com.cognifide.gradle.aem.common.instance.checkAvailable
import com.cognifide.gradle.aem.common.instance.names
import com.cognifide.gradle.aem.common.tasks.PackageTask
import com.cognifide.gradle.aem.common.utils.fileNames
import org.gradle.api.execution.TaskExecutionGraph
import org.gradle.api.tasks.TaskAction

open class PackageUninstall : PackageTask() {

    override fun taskGraphReady(graph: TaskExecutionGraph) {
        if (graph.hasTask(this)) {
            aem.prop.checkForce(this)
        }
    }

    @TaskAction
    fun uninstall() {
        instances.checkAvailable()
        sync { packageManager.uninstall(it) }
        aem.notifier.notify("Package uninstalled", "${packages.fileNames} from ${instances.names}")
    }

    init {
        description = "Uninstalls AEM package on instance(s)."
        awaited = aem.prop.boolean("package.uninstall.awaited") ?: true
    }

    companion object {
        const val NAME = "packageUninstall"
    }
}
