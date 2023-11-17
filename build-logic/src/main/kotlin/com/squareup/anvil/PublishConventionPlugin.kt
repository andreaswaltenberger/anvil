package com.squareup.anvil

import com.rickbusarow.kgx.mustRunAfter
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.Platform
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.tasks.GenerateModuleMetadata
import org.gradle.api.tasks.bundling.Jar
import org.jetbrains.kotlin.gradle.internal.KaptTask
import javax.inject.Inject

open class PublishConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    target.extensions.create("publish", PublishExtension::class.java)

    // if (target.rootProject.name != "anvil") return

    target.plugins.apply("com.vanniktech.maven.publish.base")
    target.plugins.apply("org.jetbrains.dokka")

    target.tasks.named("dokkaHtml")
      .mustRunAfter(target.tasks.withType(KaptTask::class.java))

    val mavenPublishing = target.extensions
      .getByType(MavenPublishBaseExtension::class.java)

    val pluginPublishId = "com.gradle.plugin-publish"

    @Suppress("UnstableApiUsage")
    mavenPublishing.pomFromGradleProperties()
    mavenPublishing.signAllPublications()

    val javadocJar = if (target.isInMainBuild) {
      JavadocJar.Dokka("dokkaHtml")
    } else {
      // skip Dokka if this is just publishing for integration tests
      JavadocJar.None()
    }

    target.plugins.withId("org.jetbrains.kotlin.jvm") {
      when {
        target.plugins.hasPlugin(pluginPublishId) -> {
          // Gradle's 'plugin-publish' plugin creates its own publication.  We only apply this plugin
          // in order to get all the automated POM configuration.
        }

        else -> {
          configurePublication(
            target,
            mavenPublishing,
            KotlinJvm(javadocJar = javadocJar, sourcesJar = true),
          )
        }
      }
    }

    // We publish all artifacts to `anvil/build/m2` for the plugin integration tests.
    target.gradlePublishingExtension.repositories { repositories ->
      repositories.maven {
        it.name = "buildM2"
        it.setUrl(target.rootProject.layout.buildDirectory.dir("m2"))
      }
    }
    // No one wants to type all that
    target.tasks.register("publishToBuildM2") {
      it.group = "Publishing"
      it.description = "Delegates to the publishAllPublicationsToBuildM2Repository task " +
        "on projects where publishing is enabled."
      it.dependsOn("publishAllPublicationsToBuildM2Repository")
    }
  }

  @Suppress("UnstableApiUsage")
  private fun configurePublication(
    target: Project,
    mavenPublishing: MavenPublishBaseExtension,
    platform: Platform,
  ) {
    mavenPublishing.configure(platform)

    target.tasks.withType(GenerateModuleMetadata::class.java).configureEach {
      it.mustRunAfter(target.tasks.withType(Jar::class.java))
      it.mustRunAfter("dokkaJavadocJar")
      it.mustRunAfter("kotlinSourcesJar")
    }
  }
}

private val Project.isInMainBuild: Boolean
  get() = rootProject.name == "anvil"
private val Project.gradlePublishingExtension: PublishingExtension
  get() = extensions.getByType(PublishingExtension::class.java)

open class PublishExtension @Inject constructor(
  private val target: Project
) {
  fun configurePom(args: Map<String, Any>) {
    // if (target.rootProject.name != "anvil") return

    val artifactId = args.getValue("artifactId") as String
    val pomName = args.getValue("pomName") as String
    val pomDescription = args.getValue("pomDescription") as String

    target.gradlePublishingExtension
      .publications.withType(MavenPublication::class.java)
      .configureEach { publication ->

        // Gradle plugin publications have their own artifactID convention,
        // and that's handled automatically.
        if (!publication.name.endsWith("PluginMarkerMaven")) {
          publication.artifactId = artifactId
        }

        publication.pom {
          it.name.set(pomName)
          it.description.set(pomDescription)
        }
      }
  }
}
