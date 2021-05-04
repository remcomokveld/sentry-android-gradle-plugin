package io.sentry.android.gradle

import android.databinding.tool.ext.capitalizeUS
import com.android.build.gradle.AppExtension
import io.sentry.android.gradle.SentryCliProvider.getSentryCliPath
import io.sentry.android.gradle.SentryPropertiesFileProvider.getPropertiesFilePath
import io.sentry.android.gradle.tasks.SentryUploadNativeSymbolsTask
import io.sentry.android.gradle.tasks.SentryUploadProguardMappingsTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.ExtraPropertiesExtension
import java.io.File

class SentryPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create(
            "sentry",
            SentryPluginExtension::class.java,
            project
        )
        project.pluginManager.withPlugin("com.android.application") {
            val androidExtension = project.extensions.getByType(AppExtension::class.java)
            androidExtension.applicationVariants.configureEach { variant ->
                val taskSuffix = variant.name.capitalizeUS()

                val sentryProperties = getPropertiesFilePath(project, variant)

                val extraProperties = project.extensions.getByName("ext")
                    as ExtraPropertiesExtension

                val sentryOrgParameter = runCatching {
                    extraProperties.get(SENTRY_ORG_PARAMETER).toString()
                }.getOrNull()
                val sentryProjectParameter = runCatching {
                    extraProperties.get(SENTRY_PROJECT_PARAMETER).toString()
                }.getOrNull()

                val cliExecutable = getSentryCliPath(project)

                val sep = File.separator
                val assetsDirectory = project.layout.buildDirectory.dir(
                    "generated${sep}assets${sep}sentry${variant.name}"
                )


                // Setup the task that uploads the proguard mapping and UUIDs
                val uploadSentryProguardMappingsTask = project.tasks.register(
                    "uploadSentryProguardMappings$taskSuffix",
                    SentryUploadProguardMappingsTask::class.java
                ) {
                    it.workingDir(project.rootDir)
                    it.cliExecutable.set(cliExecutable)
                    it.sentryProperties.set(sentryProperties?.let { file -> project.file(file) })
                    it.outputDirectory.set(assetsDirectory)
                    it.mappingsFile.setFrom(variant.mappingFileProvider)
                    it.autoUpload.set(extension.autoUpload)
                    it.sentryOrganization.set(sentryOrgParameter)
                    it.sentryProject.set(sentryProjectParameter)
                }
                if (variant.buildType.isMinifyEnabled) {
                    androidExtension.sourceSets.named(variant.name) { it.assets.srcDirs(assetsDirectory) }
                    variant.mergeAssetsProvider.configure { it.dependsOn(uploadSentryProguardMappingsTask) }
                }

                // Setup the task to upload native symbols task after the assembling task
                val uploadNativeSymbolsTask = project.tasks.register(
                    "uploadNativeSymbolsFor$taskSuffix",
                    SentryUploadNativeSymbolsTask::class.java
                ) {
                    it.workingDir(project.rootDir)
                    it.cliExecutable.set(cliExecutable)
                    it.sentryProperties.set(
                        sentryProperties?.let { file -> project.file(file) }
                    )
                    it.includeNativeSources.set(extension.includeNativeSources.get())
                    it.variantName.set(variant.name)
                    it.sentryOrganization.set(sentryOrgParameter)
                    it.sentryProject.set(sentryProjectParameter)
                }

                // uploadNativeSymbolsTask will only be executed after the assemble task
                // and also only if `uploadNativeSymbols` is enabled, as this is an opt-in feature.
                if (extension.uploadNativeSymbols.get()) {
                    variant.assembleProvider.configure { it.finalizedBy(uploadNativeSymbolsTask) }
                    // if its a bundle aab, assemble might not be executed, so we hook into bundle task
                    project.tasks.named("bundle${variant.name.capitalizeUS()}") {
                        it.finalizedBy(uploadNativeSymbolsTask)
                    }
                } else {
                    project.logger.info("[sentry] uploadNativeSymbolsTask won't be executed")
                }
            }
        }
    }

    companion object {
        const val SENTRY_ORG_PARAMETER = "sentryOrg"
        const val SENTRY_PROJECT_PARAMETER = "sentryProject"
    }
}
