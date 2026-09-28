import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        val android = extensions.getByName("android") as CommonExtension
        android.buildFeatures.compose = true
        dependencies {
            val bom = platform(libs.library("compose-bom"))
            add("implementation", bom)
            add("testImplementation", bom)
            add("implementation", libs.library("compose-ui"))
            add("implementation", libs.library("compose-ui-tooling-preview"))
            add("implementation", libs.library("compose-material3"))
            add("implementation", libs.library("compose-material-icons-extended"))
            add("debugImplementation", libs.library("compose-ui-tooling"))
        }
    }
}
