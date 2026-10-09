import java.util.Properties

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    id("vkid.manifest.placeholders") version "1.1.0" apply true
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun prop(key: String, default: String): String {
    val raw = localProps.getProperty(key)
    return if (raw.isNullOrBlank()) default else raw
}

val resolvedClientId: String = prop("VKIDClientID", "0")
val resolvedClientSecret: String = prop("VKIDClientSecret", "placeholder")
val resolvedRedirectHost: String = prop("VKIDRedirectHost", "vk.ru")
val resolvedRedirectScheme: String = prop("VKIDRedirectScheme", "vk$resolvedClientId")

vkidManifestPlaceholders {
    init(
        clientId = resolvedClientId,
        clientSecret = resolvedClientSecret,
    )
    vkidRedirectHost = resolvedRedirectHost
    vkidRedirectScheme = resolvedRedirectScheme
}

extra["vkidClientId"] = resolvedClientId
extra["vkidClientSecret"] = resolvedClientSecret
extra["vkidConfigured"] = resolvedClientId != "0" && resolvedClientSecret != "placeholder"
