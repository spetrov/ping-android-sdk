/*
 * Copyright (c) 2026 Ping Identity Corporation. All rights reserved.
 *
 * This software may be modified and distributed under the terms
 * of the MIT license. See the LICENSE file for details.
 */

package com.pingidentity.samples.pingsampleapp.config

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pingidentity.android.ContextProvider
import com.pingidentity.davinci.DaVinci
import com.pingidentity.davinci.plugin.DaVinci
import com.pingidentity.journey.Journey
import com.pingidentity.logger.Logger
import com.pingidentity.logger.STANDARD
import com.pingidentity.oidc.JsonConfigKey
import com.pingidentity.oidc.OidcClient
import com.pingidentity.oidc.OidcWebClient
import com.pingidentity.oidc.OidcDeviceClient
import com.pingidentity.oidc.toScopesJsonArray
import com.pingidentity.samples.pingsampleapp.settingDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonArray


// ---------------------------------------------------------------------------
// Config state data classes
// ---------------------------------------------------------------------------

data class JourneyConfigState(
    val serverUrl: String = "",
    val realm: String = "",
    val cookie: String = "",
    val clientId: String = "",
    val discoveryEndpoint: String = "",
    val scopes: String = "",
    val redirectUri: String = "",
    val display: String = "",
    val par: Boolean = false,
)

data class OidcConfigState(
    val clientId: String = "",
    val discoveryEndpoint: String = "",
    val scopes: String = "",
    val redirectUri: String = "",
    val display: String = "",
    val arcValue: String = "",
    val par: Boolean = false,
)

data class DeviceAuthConfigState(
    val clientId: String = "",
    val discoveryEndpoint: String = "",
    val scopes: String = "",
    val display: String = "Device Authorization",
    val authorizationEndpoint: String = "",
    val tokenEndpoint: String = "",
    val userInfoEndpoint: String = "",
    val endSessionEndpoint: String = "",
    val revocationEndpoint: String = "",
    val deviceAuthorizationEndpoint: String = "",
    val acrValues: String = "",
)

// ---------------------------------------------------------------------------
// JSON asset file loader
// ---------------------------------------------------------------------------

internal data class AssetConfigs(
    val journey: List<JourneyConfigState> = emptyList(),
    val davinci: List<OidcConfigState> = emptyList(),
    val web: List<OidcConfigState> = emptyList(),
    val deviceAuth: List<DeviceAuthConfigState> = emptyList(),
)

/**
 * Scans all `*.json` files in the `assets/` directory and parses each one as an SDK-native
 * configuration object. The results are surfaced as read-only presets in the configuration UI.
 *
 * Each file must follow the SDK's native JSON shape:
 * ```json
 * {
 *   "journey": { "serverUrl": "...", "realm": "...", "cookieName": "..." },
 *   "oidc":    { "clientId": "...", "discoveryEndpoint": "...", ... }
 * }
 * ```
 *
 * The **filename** (without the `.json` extension) is used as the display name in the UI.
 * For example, `journey-prod.json` appears as `journey-prod` in the preset list.
 *
 * A single file can populate multiple config types — checks are independent:
 * - `"journey"` key present → adds a [JourneyConfigState]
 * - `"oidc.openId"` key present → adds a [DeviceAuthConfigState]
 * - No `"journey"` key → adds a DaVinci [OidcConfigState]
 * - A Web [OidcConfigState] config will always be added.
 *
 * Files that do not contain an `"oidc"` object are silently skipped.
 * Any parse error for an individual file is silently ignored; other files are still processed.
 *
 * @return [AssetConfigs] containing the parsed configs grouped by type.
 */
internal fun loadAssetConfigs(): AssetConfigs {
    val context = ContextProvider.context
    val fileNames = context.assets.list("") ?: return AssetConfigs()
    val journey = mutableListOf<JourneyConfigState>()
    val davinci = mutableListOf<OidcConfigState>()
    val web = mutableListOf<OidcConfigState>()
    val deviceAuth = mutableListOf<DeviceAuthConfigState>()

    fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content ?: ""

    for (fileName in fileNames) {
        if (!fileName.endsWith(".json")) continue
        val displayName = fileName.removeSuffix(".json")
        runCatching {
            val root = Json.parseToJsonElement(
                context.assets.open(fileName).bufferedReader().use { it.readText() }
            ).jsonObject
            val isDaVinci = root.contains("journey").not()
            val oidc = root["oidc"]?.jsonObject ?: return@runCatching
            val journeyObj = root["journey"]?.jsonObject
            val openIdObj = oidc["openId"]?.jsonObject

            val scopes = oidc["scopes"]?.let { el ->
                runCatching { el.jsonArray.joinToString(",") { it.jsonPrimitive.content } }
                    .getOrElse { el.jsonPrimitive.content }
            } ?: ""
            val clientId = oidc.str("clientId")
            val discoveryEndpoint = oidc.str("discoveryEndpoint")
            val redirectUri = oidc.str("redirectUri")

            if (journeyObj != null) journey.add(JourneyConfigState(
                serverUrl = journeyObj.str("serverUrl"),
                realm = journeyObj.str("realm"),
                cookie = journeyObj.str("cookieName"),
                clientId = clientId,
                discoveryEndpoint = discoveryEndpoint,
                scopes = scopes,
                redirectUri = redirectUri,
                display = displayName,
                par = oidc["par"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
            ))
            if (openIdObj != null || isDaVinci) deviceAuth.add(DeviceAuthConfigState(
                clientId = clientId,
                discoveryEndpoint = discoveryEndpoint,
                scopes = scopes,
                display = displayName,
                acrValues = oidc.str("acrValues"),
                authorizationEndpoint = openIdObj?.str("authorizationEndpoint") ?: "",
                tokenEndpoint = openIdObj?.str("tokenEndpoint") ?: "",
                userInfoEndpoint = openIdObj?.str("userInfoEndpoint") ?: "",
                endSessionEndpoint = openIdObj?.str("endSessionEndpoint") ?: "",
                revocationEndpoint = openIdObj?.str("revocationEndpoint") ?: "",
                deviceAuthorizationEndpoint = openIdObj?.str("deviceAuthorizationEndpoint") ?: "",
            ))
            if (isDaVinci) davinci.add(OidcConfigState(
                clientId = clientId,
                discoveryEndpoint = discoveryEndpoint,
                scopes = scopes,
                redirectUri = redirectUri,
                display = displayName,
                arcValue = oidc.str("acrValues"),
            ))

            web.add(OidcConfigState(
                clientId = clientId,
                discoveryEndpoint = discoveryEndpoint,
                scopes = scopes,
                redirectUri = redirectUri,
                display = displayName,
                arcValue = oidc.str("acrValues"),
                par = oidc["par"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
            ))
        }
    }
    return AssetConfigs(journey, davinci, web, deviceAuth)
}

// ---------------------------------------------------------------------------
// Global SDK instance holders (consumed by other ViewModels)
// ---------------------------------------------------------------------------

var journey: Journey? = null
var oidcClient: OidcClient? = null
var daVinci: DaVinci? = null
var web: OidcWebClient? = null
var oidcDeviceClient: OidcDeviceClient? = null
/** Used by Journey's IdP (social identity provider) callback. Set only by [buildJourney]. */
var redirectUri: Uri = Uri.EMPTY
/** Used by DaVinci's Social Login button. Set only by [buildDaVinci]. Never overwritten by Journey. */
var daVinciRedirectUri: Uri = Uri.EMPTY

// ---------------------------------------------------------------------------
// Package-level SDK builders (called both at app startup and from ViewModel)
// ---------------------------------------------------------------------------

internal fun buildJourney(config: JourneyConfigState) {
    Journey(
        buildJsonObject {
            put(JsonConfigKey.LOG, "STANDARD")
            put(JsonConfigKey.JOURNEY, buildJsonObject {
                put(JsonConfigKey.SERVER_URL, config.serverUrl)
                put(JsonConfigKey.REALM, config.realm)
                if (config.cookie.isNotBlank()) put(JsonConfigKey.COOKIE_NAME, config.cookie)
            })
            put(JsonConfigKey.OIDC, buildJsonObject {
                put(JsonConfigKey.CLIENT_ID, config.clientId)
                put(JsonConfigKey.DISCOVERY_ENDPOINT, config.discoveryEndpoint)
                put(JsonConfigKey.SCOPES, config.scopes.toScopesJsonArray())
                put(JsonConfigKey.REDIRECT_URI, config.redirectUri)
                put(JsonConfigKey.DISPLAY, config.display)
                put(JsonConfigKey.PAR, config.par)
            })
        }
    ).onSuccess { journey = it }
        .onFailure {
            Logger.STANDARD.d("Failed to create Journey instance: ${it.message}")
            journey = null
        }
    OidcClient(
        buildJsonObject {
            put(JsonConfigKey.OIDC, buildJsonObject {
                put(JsonConfigKey.CLIENT_ID, config.clientId)
                put(JsonConfigKey.DISCOVERY_ENDPOINT, config.discoveryEndpoint)
                put(JsonConfigKey.SCOPES, config.scopes.toScopesJsonArray())
                put(JsonConfigKey.REDIRECT_URI, config.redirectUri)
                put(JsonConfigKey.DISPLAY, config.display)
            })
        }
    ).onSuccess { oidcClient = it }
        .onFailure {
            Logger.STANDARD.d("Failed to create OIDC client instance: ${it.message}")
            oidcClient = null
        }
    redirectUri = config.redirectUri.toUri()
}

internal fun buildDaVinci(config: OidcConfigState) {
    DaVinci(
        buildJsonObject {
            put(JsonConfigKey.LOG, "STANDARD")
            put(JsonConfigKey.OIDC, buildJsonObject {
                put(JsonConfigKey.CLIENT_ID, config.clientId)
                put(JsonConfigKey.DISCOVERY_ENDPOINT, config.discoveryEndpoint)
                put(JsonConfigKey.SCOPES, config.scopes.toScopesJsonArray())
                put(JsonConfigKey.REDIRECT_URI, config.redirectUri)
                put(JsonConfigKey.DISPLAY, config.display)
                if (config.arcValue.isNotBlank()) put(JsonConfigKey.ACR_VALUES, config.arcValue)
            })
        }
    ).onSuccess { daVinci = it }
        .onFailure { Logger.STANDARD.d("Failed to create DaVinci instance: ${it.message}") }
    // Store in the DaVinci-specific global so it never overwrites Journey's redirectUri
    daVinciRedirectUri = config.redirectUri.toUri()
}

internal fun buildWeb(config: OidcConfigState) {
    OidcWebClient(
        buildJsonObject {
            put(JsonConfigKey.LOG, "STANDARD")
            put(JsonConfigKey.OIDC, buildJsonObject {
                put(JsonConfigKey.CLIENT_ID, config.clientId)
                put(JsonConfigKey.DISCOVERY_ENDPOINT, config.discoveryEndpoint)
                put(JsonConfigKey.SCOPES, config.scopes.toScopesJsonArray())
                put(JsonConfigKey.REDIRECT_URI, config.redirectUri)
                put(JsonConfigKey.DISPLAY, config.display)
                if (config.arcValue.isNotBlank()) put(JsonConfigKey.ACR_VALUES, config.arcValue)
                put(JsonConfigKey.PAR, config.par)
            })
        }
    ).onSuccess { web = it }
        .onFailure {
            Logger.STANDARD.d("Failed to create OIDC Web client instance: ${it.message}")
            web = null
        }
}

internal fun buildDeviceAuthClient(config: DeviceAuthConfigState) {
    OidcDeviceClient(
        buildJsonObject {
            put(JsonConfigKey.LOG, "STANDARD")
            put(JsonConfigKey.OIDC, buildJsonObject {
                put(JsonConfigKey.CLIENT_ID, config.clientId)
                put(JsonConfigKey.DISCOVERY_ENDPOINT, config.discoveryEndpoint)
                put(JsonConfigKey.SCOPES, config.scopes.toScopesJsonArray())
                put(JsonConfigKey.DISPLAY, config.display)
                if (config.acrValues.isNotBlank()) put(JsonConfigKey.ACR_VALUES, config.acrValues)
                put(JsonConfigKey.OPEN_ID, buildJsonObject {
                    if (config.authorizationEndpoint.isNotBlank()) put(
                        JsonConfigKey.AUTHORIZATION_ENDPOINT,
                        config.authorizationEndpoint
                    )
                    if (config.tokenEndpoint.isNotBlank()) put(
                        JsonConfigKey.TOKEN_ENDPOINT,
                        config.tokenEndpoint
                    )
                    if (config.userInfoEndpoint.isNotBlank()) put(
                        JsonConfigKey.USER_INFO_ENDPOINT,
                        config.userInfoEndpoint
                    )
                    if (config.endSessionEndpoint.isNotBlank()) put(
                        JsonConfigKey.END_SESSION_ENDPOINT,
                        config.endSessionEndpoint
                    )
                    if (config.revocationEndpoint.isNotBlank()) put(
                        JsonConfigKey.REVOCATION_ENDPOINT,
                        config.revocationEndpoint
                    )
                    if (config.deviceAuthorizationEndpoint.isNotBlank()) put(
                        JsonConfigKey.DEVICE_AUTHORIZATION_ENDPOINT,
                        config.deviceAuthorizationEndpoint
                    )
                })
            })
        }
    ).onSuccess { oidcDeviceClient = it }
        .onFailure {
            Logger.STANDARD.d("Failed to create OIDC Device client instance: ${it.message}")
            oidcDeviceClient = null
        }
}

// ---------------------------------------------------------------------------
// App-startup initializer — call from Application.onCreate()
// ---------------------------------------------------------------------------

/**
 * Loads the last-saved configs from DataStore and applies them to the global
 * SDK instances so all flows are ready immediately when the app starts,
 * before the user ever visits the Configuration screen.
 * If no config has been saved yet, no SDK instance is built.
 */
suspend fun initConfigs() {
    val prefs = ContextProvider.context.settingDataStore.data.first()

    val jConfig = prefs[stringPreferencesKey("j_clientId")]?.let { clientId ->
        JourneyConfigState(
            serverUrl = prefs[stringPreferencesKey("j_serverUrl")] ?: "",
            realm = prefs[stringPreferencesKey("j_realm")] ?: "",
            cookie = prefs[stringPreferencesKey("j_cookie")] ?: "",
            clientId = clientId,
            discoveryEndpoint = prefs[stringPreferencesKey("j_discoveryEndpoint")] ?: "",
            scopes = prefs[stringPreferencesKey("j_scopes")] ?: "",
            redirectUri = prefs[stringPreferencesKey("j_redirectUri")] ?: "",
            display = prefs[stringPreferencesKey("j_display")] ?: "",
            par = prefs[stringPreferencesKey("j_par")]?.toBooleanStrictOrNull() ?: false,
        )
    }

    val dvConfig = prefs[stringPreferencesKey("dv_clientId")]?.let { clientId ->
        OidcConfigState(
            clientId = clientId,
            discoveryEndpoint = prefs[stringPreferencesKey("dv_discoveryEndpoint")] ?: "",
            scopes = prefs[stringPreferencesKey("dv_scopes")] ?: "",
            redirectUri = prefs[stringPreferencesKey("dv_redirectUri")] ?: "",
            display = prefs[stringPreferencesKey("dv_display")] ?: "",
            arcValue = prefs[stringPreferencesKey("dv_arcValue")] ?: "",
        )
    }

    val wConfig = prefs[stringPreferencesKey("w_clientId")]?.let { clientId ->
        OidcConfigState(
            clientId = clientId,
            discoveryEndpoint = prefs[stringPreferencesKey("w_discoveryEndpoint")] ?: "",
            scopes = prefs[stringPreferencesKey("w_scopes")] ?: "",
            redirectUri = prefs[stringPreferencesKey("w_redirectUri")] ?: "",
            display = prefs[stringPreferencesKey("w_display")] ?: "",
            arcValue = prefs[stringPreferencesKey("w_arcValue")] ?: "",
            par = prefs[stringPreferencesKey("w_par")]?.toBooleanStrictOrNull() ?: false,
        )
    }

    val daConfig = prefs[stringPreferencesKey("da_clientId")]?.let { clientId ->
        DeviceAuthConfigState(
            clientId = clientId,
            discoveryEndpoint = prefs[stringPreferencesKey("da_discoveryEndpoint")] ?: "",
            scopes = prefs[stringPreferencesKey("da_scopes")] ?: "",
            display = prefs[stringPreferencesKey("da_display")] ?: "",
            authorizationEndpoint = prefs[stringPreferencesKey("da_authorizationEndpoint")] ?: "",
            tokenEndpoint = prefs[stringPreferencesKey("da_tokenEndpoint")] ?: "",
            userInfoEndpoint = prefs[stringPreferencesKey("da_userInfoEndpoint")] ?: "",
            endSessionEndpoint = prefs[stringPreferencesKey("da_endSessionEndpoint")] ?: "",
            revocationEndpoint = prefs[stringPreferencesKey("da_revocationEndpoint")] ?: "",
            deviceAuthorizationEndpoint = prefs[stringPreferencesKey("da_deviceAuthorizationEndpoint")] ?: "",
            acrValues = prefs[stringPreferencesKey("da_acrValues")] ?: "",
        )
    }

    // Each builder writes to its own global; no ordering dependency.
    jConfig?.let { buildJourney(it) }
    wConfig?.let { buildWeb(it) }
    dvConfig?.let { buildDaVinci(it) }
    daConfig?.let { buildDeviceAuthClient(it) }
}

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

class EnvViewModel : ViewModel() {

    var journeyPresets by mutableStateOf(emptyList<JourneyConfigState>())
        private set

    var daVinciPresets by mutableStateOf(emptyList<OidcConfigState>())
        private set

    var webPresets by mutableStateOf(emptyList<OidcConfigState>())
        private set

    var deviceAuthPresets by mutableStateOf(emptyList<DeviceAuthConfigState>())
        private set

    // -- Currently applied configs -------------------------------------------

    var appliedJourneyConfig by mutableStateOf<JourneyConfigState?>(null)
        private set

    var appliedDaVinciConfig by mutableStateOf<OidcConfigState?>(null)
        private set

    var appliedWebConfig by mutableStateOf<OidcConfigState?>(null)
        private set

    var appliedDeviceAuthConfig by mutableStateOf<DeviceAuthConfigState?>(null)
        private set

    // -- User-defined custom configs -----------------------------------------

    var customJourneyConfigs by mutableStateOf<List<JourneyConfigState>>(emptyList())
        private set

    var customDaVinciConfigs by mutableStateOf<List<OidcConfigState>>(emptyList())
        private set

    var customWebConfigs by mutableStateOf<List<OidcConfigState>>(emptyList())
        private set

    var customDeviceAuthConfigs by mutableStateOf<List<DeviceAuthConfigState>>(emptyList())
        private set

    // -- Init ----------------------------------------------------------------

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val assetConfigs = loadAssetConfigs()
            val jApplied = loadAppliedJourneyFromDataStore()
            val dvApplied = loadAppliedDaVinciFromDataStore()
            val wApplied = loadAppliedWebFromDataStore()
            val daApplied = loadAppliedDeviceAuthConfig()
            val jCustom = loadCustomJourneyFromDataStore()
            val dvCustom = loadCustomDaVinciFromDataStore()
            val wCustom = loadCustomWebFromDataStore()
            val daCustom = loadCustomDeviceAuthFromDataStore()

            withContext(Dispatchers.Main) {
                journeyPresets = assetConfigs.journey
                daVinciPresets = assetConfigs.davinci
                webPresets = assetConfigs.web
                deviceAuthPresets = assetConfigs.deviceAuth
                customJourneyConfigs = jCustom
                customDaVinciConfigs = dvCustom
                customWebConfigs = wCustom
                customDeviceAuthConfigs = daCustom
                appliedJourneyConfig = jApplied
                appliedDaVinciConfig = dvApplied
                appliedWebConfig = wApplied
                appliedDeviceAuthConfig = daApplied
                jApplied?.let { buildJourneyInstance(it) }
                dvApplied?.let { buildDaVinciInstance(it) }
                wApplied?.let { buildWebInstance(it) }
                daApplied?.let { buildDeviceAuthInstance(it) }
            }
        }
    }

    // -- Select (apply a preset or custom config) ----------------------------

    fun selectJourneyConfig(config: JourneyConfigState) {
        buildJourneyInstance(config)
        appliedJourneyConfig = config
        viewModelScope.launch(Dispatchers.IO) { persistAppliedJourney(config) }
    }

    fun selectDaVinciConfig(config: OidcConfigState) {
        buildDaVinciInstance(config)
        appliedDaVinciConfig = config
        viewModelScope.launch(Dispatchers.IO) { persistAppliedDaVinci(config) }
    }

    fun selectWebConfig(config: OidcConfigState) {
        buildWebInstance(config)
        appliedWebConfig = config
        viewModelScope.launch(Dispatchers.IO) { persistAppliedWeb(config) }
    }

    fun selectDeviceAuthConfig(config: DeviceAuthConfigState) {
        buildDeviceAuthClient(config)
        appliedDeviceAuthConfig = config
        viewModelScope.launch(Dispatchers.IO) { persistAppliedDeviceAuthConfig(config) }
    }

    // -- Custom config CRUD --------------------------------------------------

    fun saveCustomJourneyConfig(config: JourneyConfigState, editIndex: Int?) {
        customJourneyConfigs = if (editIndex == null) {
            customJourneyConfigs + config
        } else {
            customJourneyConfigs.toMutableList().also { it[editIndex] = config }
        }
        selectJourneyConfig(config)
        viewModelScope.launch(Dispatchers.IO) { persistCustomJourneyConfigs(customJourneyConfigs) }
    }

    fun deleteCustomJourneyConfig(index: Int) {
        val deleted = customJourneyConfigs[index]
        customJourneyConfigs = customJourneyConfigs.toMutableList().also { it.removeAt(index) }
        if (appliedJourneyConfig?.display == deleted.display) {
            val fallback = journeyPresets.firstOrNull()
            if (fallback != null) selectJourneyConfig(fallback)
            else { appliedJourneyConfig = null; journey = null }
        }
        viewModelScope.launch(Dispatchers.IO) { persistCustomJourneyConfigs(customJourneyConfigs) }
    }

    fun saveCustomDaVinciConfig(config: OidcConfigState, editIndex: Int?) {
        customDaVinciConfigs = if (editIndex == null) {
            customDaVinciConfigs + config
        } else {
            customDaVinciConfigs.toMutableList().also { it[editIndex] = config }
        }
        selectDaVinciConfig(config)
        viewModelScope.launch(Dispatchers.IO) { persistCustomDaVinciConfigs(customDaVinciConfigs) }
    }

    fun deleteCustomDaVinciConfig(index: Int) {
        val deleted = customDaVinciConfigs[index]
        customDaVinciConfigs = customDaVinciConfigs.toMutableList().also { it.removeAt(index) }
        if (appliedDaVinciConfig?.display == deleted.display) {
            val fallback = daVinciPresets.firstOrNull()
            if (fallback != null) selectDaVinciConfig(fallback)
            else { appliedDaVinciConfig = null; daVinci = null }
        }
        viewModelScope.launch(Dispatchers.IO) { persistCustomDaVinciConfigs(customDaVinciConfigs) }
    }

    fun saveCustomWebConfig(config: OidcConfigState, editIndex: Int?) {
        customWebConfigs = if (editIndex == null) {
            customWebConfigs + config
        } else {
            customWebConfigs.toMutableList().also { it[editIndex] = config }
        }
        selectWebConfig(config)
        viewModelScope.launch(Dispatchers.IO) { persistCustomWebConfigs(customWebConfigs) }
    }

    fun deleteCustomWebConfig(index: Int) {
        val deleted = customWebConfigs[index]
        customWebConfigs = customWebConfigs.toMutableList().also { it.removeAt(index) }
        if (appliedWebConfig?.display == deleted.display) {
            val fallback = webPresets.firstOrNull()
            if (fallback != null) selectWebConfig(fallback)
            else { appliedWebConfig = null; web = null }
        }
        viewModelScope.launch(Dispatchers.IO) { persistCustomWebConfigs(customWebConfigs) }
    }

    fun duplicateJourneyConfig(config: JourneyConfigState) {
        customJourneyConfigs = customJourneyConfigs + config.copy(display = "Copy of ${config.display}")
        viewModelScope.launch(Dispatchers.IO) { persistCustomJourneyConfigs(customJourneyConfigs) }
    }

    fun duplicateDaVinciConfig(config: OidcConfigState) {
        customDaVinciConfigs = customDaVinciConfigs + config.copy(display = "Copy of ${config.display}")
        viewModelScope.launch(Dispatchers.IO) { persistCustomDaVinciConfigs(customDaVinciConfigs) }
    }

    fun duplicateWebConfig(config: OidcConfigState) {
        customWebConfigs = customWebConfigs + config.copy(display = "Copy of ${config.display}")
        viewModelScope.launch(Dispatchers.IO) { persistCustomWebConfigs(customWebConfigs) }
    }

    fun duplicateDeviceAuthConfig(config: DeviceAuthConfigState) {
        customDeviceAuthConfigs = customDeviceAuthConfigs + config.copy(display = "Copy of ${config.display}")
        viewModelScope.launch(Dispatchers.IO) { persistCustomDeviceAuthConfigs(customDeviceAuthConfigs) }
    }

    fun saveCustomDeviceAuthConfig(config: DeviceAuthConfigState, editIndex: Int?) {
        customDeviceAuthConfigs = if (editIndex == null) {
            customDeviceAuthConfigs + config
        } else {
            customDeviceAuthConfigs.toMutableList().also { it[editIndex] = config }
        }
        selectDeviceAuthConfig(config)
        viewModelScope.launch(Dispatchers.IO) { persistCustomDeviceAuthConfigs(customDeviceAuthConfigs) }
    }

    fun deleteCustomDeviceAuthConfig(index: Int) {
        val deleted = customDeviceAuthConfigs[index]
        customDeviceAuthConfigs = customDeviceAuthConfigs.toMutableList().also { it.removeAt(index) }
        if (appliedDeviceAuthConfig?.display == deleted.display) {
            val fallback = deviceAuthPresets.firstOrNull()
            if (fallback != null) selectDeviceAuthConfig(fallback)
            else { appliedDeviceAuthConfig = null; oidcDeviceClient = null }
        }
        viewModelScope.launch(Dispatchers.IO) { persistCustomDeviceAuthConfigs(customDeviceAuthConfigs) }
    }

    // -- SDK instance builders (delegate to package-level functions) ---------

    private fun buildJourneyInstance(config: JourneyConfigState) = buildJourney(config)
    private fun buildDaVinciInstance(config: OidcConfigState) = buildDaVinci(config)
    private fun buildWebInstance(config: OidcConfigState) = buildWeb(config)
    private fun buildDeviceAuthInstance(config: DeviceAuthConfigState) = buildDeviceAuthClient(config)

    // -- DataStore: load applied configs -------------------------------------

    private suspend fun loadAppliedJourneyFromDataStore(): JourneyConfigState? {
        val prefs = ContextProvider.context.settingDataStore.data.first()
        val clientId = prefs[stringPreferencesKey("j_clientId")] ?: return null
        return JourneyConfigState(
            serverUrl = prefs[stringPreferencesKey("j_serverUrl")] ?: "",
            realm = prefs[stringPreferencesKey("j_realm")] ?: "",
            cookie = prefs[stringPreferencesKey("j_cookie")] ?: "",
            clientId = clientId,
            discoveryEndpoint = prefs[stringPreferencesKey("j_discoveryEndpoint")] ?: "",
            scopes = prefs[stringPreferencesKey("j_scopes")] ?: "",
            redirectUri = prefs[stringPreferencesKey("j_redirectUri")] ?: "",
            display = prefs[stringPreferencesKey("j_display")] ?: "",
            par = prefs[stringPreferencesKey("j_par")]?.toBooleanStrictOrNull() ?: false,
        )
    }

    private suspend fun loadAppliedDaVinciFromDataStore(): OidcConfigState? {
        val prefs = ContextProvider.context.settingDataStore.data.first()
        val clientId = prefs[stringPreferencesKey("dv_clientId")] ?: return null
        return OidcConfigState(
            clientId = clientId,
            discoveryEndpoint = prefs[stringPreferencesKey("dv_discoveryEndpoint")] ?: "",
            scopes = prefs[stringPreferencesKey("dv_scopes")] ?: "",
            redirectUri = prefs[stringPreferencesKey("dv_redirectUri")] ?: "",
            display = prefs[stringPreferencesKey("dv_display")] ?: "",
            arcValue = prefs[stringPreferencesKey("dv_arcValue")] ?: "",
        )
    }

    private suspend fun loadAppliedWebFromDataStore(): OidcConfigState? {
        val prefs = ContextProvider.context.settingDataStore.data.first()
        val clientId = prefs[stringPreferencesKey("w_clientId")] ?: return null
        return OidcConfigState(
            clientId = clientId,
            discoveryEndpoint = prefs[stringPreferencesKey("w_discoveryEndpoint")] ?: "",
            scopes = prefs[stringPreferencesKey("w_scopes")] ?: "",
            redirectUri = prefs[stringPreferencesKey("w_redirectUri")] ?: "",
            display = prefs[stringPreferencesKey("w_display")] ?: "",
            arcValue = prefs[stringPreferencesKey("w_arcValue")] ?: "",
            par = prefs[stringPreferencesKey("w_par")]?.toBooleanStrictOrNull() ?: false,
        )
    }

    private suspend fun loadAppliedDeviceAuthConfig(): DeviceAuthConfigState? {
        val prefs = ContextProvider.context.settingDataStore.data.first()
        val clientId = prefs[stringPreferencesKey("da_clientId")] ?: return null
        return DeviceAuthConfigState(
            clientId = clientId,
            discoveryEndpoint = prefs[stringPreferencesKey("da_discoveryEndpoint")] ?: "",
            scopes = prefs[stringPreferencesKey("da_scopes")] ?: "",
            display = prefs[stringPreferencesKey("da_display")] ?: "",
            authorizationEndpoint = prefs[stringPreferencesKey("da_authorizationEndpoint")] ?: "",
            tokenEndpoint = prefs[stringPreferencesKey("da_tokenEndpoint")] ?: "",
            userInfoEndpoint = prefs[stringPreferencesKey("da_userInfoEndpoint")] ?: "",
            endSessionEndpoint = prefs[stringPreferencesKey("da_endSessionEndpoint")] ?: "",
            revocationEndpoint = prefs[stringPreferencesKey("da_revocationEndpoint")] ?: "",
            deviceAuthorizationEndpoint = prefs[stringPreferencesKey("da_deviceAuthorizationEndpoint")] ?: "",
            acrValues = prefs[stringPreferencesKey("da_acrValues")] ?: "",
        )
    }

    // -- DataStore: load custom configs --------------------------------------

    private suspend fun loadCustomJourneyFromDataStore(): List<JourneyConfigState> {
        val prefs = ContextProvider.context.settingDataStore.data.first()
        val json = prefs[stringPreferencesKey("j_custom_configs")] ?: return emptyList()
        return deserializeJourneyConfigs(json)
    }

    private suspend fun loadCustomDaVinciFromDataStore(): List<OidcConfigState> {
        val prefs = ContextProvider.context.settingDataStore.data.first()
        val json = prefs[stringPreferencesKey("dv_custom_configs")] ?: return emptyList()
        return deserializeOidcConfigs(json)
    }

    private suspend fun loadCustomWebFromDataStore(): List<OidcConfigState> {
        val prefs = ContextProvider.context.settingDataStore.data.first()
        val json = prefs[stringPreferencesKey("w_custom_configs")] ?: return emptyList()
        return deserializeOidcConfigs(json)
    }

    private suspend fun loadCustomDeviceAuthFromDataStore(): List<DeviceAuthConfigState> {
        val prefs = ContextProvider.context.settingDataStore.data.first()
        val json = prefs[stringPreferencesKey("da_custom_configs")] ?: return emptyList()
        return deserializeDeviceAuthConfigs(json)
    }

    // -- DataStore: persist applied configs ----------------------------------

    private suspend fun persistAppliedJourney(config: JourneyConfigState) {
        ContextProvider.context.settingDataStore.edit { prefs ->
            prefs[stringPreferencesKey("j_serverUrl")] = config.serverUrl
            prefs[stringPreferencesKey("j_realm")] = config.realm
            prefs[stringPreferencesKey("j_cookie")] = config.cookie
            prefs[stringPreferencesKey("j_clientId")] = config.clientId
            prefs[stringPreferencesKey("j_discoveryEndpoint")] = config.discoveryEndpoint
            prefs[stringPreferencesKey("j_scopes")] = config.scopes
            prefs[stringPreferencesKey("j_redirectUri")] = config.redirectUri
            prefs[stringPreferencesKey("j_display")] = config.display
            prefs[stringPreferencesKey("j_par")] = config.par.toString()
        }
    }

    private suspend fun persistAppliedDaVinci(config: OidcConfigState) {
        ContextProvider.context.settingDataStore.edit { prefs ->
            prefs[stringPreferencesKey("dv_clientId")] = config.clientId
            prefs[stringPreferencesKey("dv_discoveryEndpoint")] = config.discoveryEndpoint
            prefs[stringPreferencesKey("dv_scopes")] = config.scopes
            prefs[stringPreferencesKey("dv_redirectUri")] = config.redirectUri
            prefs[stringPreferencesKey("dv_display")] = config.display
            prefs[stringPreferencesKey("dv_arcValue")] = config.arcValue
        }
    }

    private suspend fun persistAppliedWeb(config: OidcConfigState) {
        ContextProvider.context.settingDataStore.edit { prefs ->
            prefs[stringPreferencesKey("w_clientId")] = config.clientId
            prefs[stringPreferencesKey("w_discoveryEndpoint")] = config.discoveryEndpoint
            prefs[stringPreferencesKey("w_scopes")] = config.scopes
            prefs[stringPreferencesKey("w_redirectUri")] = config.redirectUri
            prefs[stringPreferencesKey("w_display")] = config.display
            prefs[stringPreferencesKey("w_arcValue")] = config.arcValue
            prefs[stringPreferencesKey("w_par")] = config.par.toString()
        }
    }

    private suspend fun persistAppliedDeviceAuthConfig(config: DeviceAuthConfigState) {
        ContextProvider.context.settingDataStore.edit { prefs ->
            prefs[stringPreferencesKey("da_clientId")] = config.clientId
            prefs[stringPreferencesKey("da_discoveryEndpoint")] = config.discoveryEndpoint
            prefs[stringPreferencesKey("da_scopes")] = config.scopes
            prefs[stringPreferencesKey("da_display")] = config.display
            prefs[stringPreferencesKey("da_authorizationEndpoint")] = config.authorizationEndpoint
            prefs[stringPreferencesKey("da_tokenEndpoint")] = config.tokenEndpoint
            prefs[stringPreferencesKey("da_userInfoEndpoint")] = config.userInfoEndpoint
            prefs[stringPreferencesKey("da_endSessionEndpoint")] = config.endSessionEndpoint
            prefs[stringPreferencesKey("da_revocationEndpoint")] = config.revocationEndpoint
            prefs[stringPreferencesKey("da_deviceAuthorizationEndpoint")] = config.deviceAuthorizationEndpoint
            prefs[stringPreferencesKey("da_acrValues")] = config.acrValues
        }
    }

    // -- DataStore: persist custom configs -----------------------------------

    private suspend fun persistCustomJourneyConfigs(configs: List<JourneyConfigState>) {
        ContextProvider.context.settingDataStore.edit { prefs ->
            prefs[stringPreferencesKey("j_custom_configs")] = serializeJourneyConfigs(configs)
        }
    }

    private suspend fun persistCustomDaVinciConfigs(configs: List<OidcConfigState>) {
        ContextProvider.context.settingDataStore.edit { prefs ->
            prefs[stringPreferencesKey("dv_custom_configs")] = serializeOidcConfigs(configs)
        }
    }

    private suspend fun persistCustomWebConfigs(configs: List<OidcConfigState>) {
        ContextProvider.context.settingDataStore.edit { prefs ->
            prefs[stringPreferencesKey("w_custom_configs")] = serializeOidcConfigs(configs)
        }
    }

    private suspend fun persistCustomDeviceAuthConfigs(configs: List<DeviceAuthConfigState>) {
        ContextProvider.context.settingDataStore.edit { prefs ->
            prefs[stringPreferencesKey("da_custom_configs")] = serializeDeviceAuthConfigs(configs)
        }
    }

    // -- JSON serialization --------------------------------------------------

    private fun serializeJourneyConfigs(configs: List<JourneyConfigState>): String =
        buildJsonArray {
            configs.forEach { c ->
                add(buildJsonObject {
                    put("serverUrl", c.serverUrl); put("realm", c.realm); put("cookie", c.cookie)
                    put("clientId", c.clientId); put("discoveryEndpoint", c.discoveryEndpoint)
                    put("scopes", c.scopes); put("redirectUri", c.redirectUri); put("display", c.display)
                    put("par", c.par)
                })
            }
        }.toString()

    private fun deserializeJourneyConfigs(json: String): List<JourneyConfigState> = runCatching {
        Json.parseToJsonElement(json).jsonArray.map { el ->
            val o = el.jsonObject
            fun str(k: String) = o[k]?.jsonPrimitive?.content ?: ""
            JourneyConfigState(
                serverUrl = str("serverUrl"), realm = str("realm"), cookie = str("cookie"),
                clientId = str("clientId"), discoveryEndpoint = str("discoveryEndpoint"),
                scopes = str("scopes"), redirectUri = str("redirectUri"), display = str("display"),
                par = o["par"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
            )
        }
    }.getOrDefault(emptyList())

    private fun serializeOidcConfigs(configs: List<OidcConfigState>): String =
        buildJsonArray {
            configs.forEach { c ->
                add(buildJsonObject {
                    put("clientId", c.clientId); put("discoveryEndpoint", c.discoveryEndpoint)
                    put("scopes", c.scopes); put("redirectUri", c.redirectUri)
                    put("display", c.display); put("arcValue", c.arcValue)
                    put("par", c.par)
                })
            }
        }.toString()

    private fun deserializeOidcConfigs(json: String): List<OidcConfigState> = runCatching {
        Json.parseToJsonElement(json).jsonArray.map { el ->
            val o = el.jsonObject
            fun str(k: String) = o[k]?.jsonPrimitive?.content ?: ""
            OidcConfigState(
                clientId = str("clientId"), discoveryEndpoint = str("discoveryEndpoint"),
                scopes = str("scopes"), redirectUri = str("redirectUri"),
                display = str("display"), arcValue = str("arcValue"),
                par = o["par"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
            )
        }
    }.getOrDefault(emptyList())

    private fun serializeDeviceAuthConfigs(configs: List<DeviceAuthConfigState>): String =
        buildJsonArray {
            configs.forEach { c ->
                add(buildJsonObject {
                    put("clientId", c.clientId); put("discoveryEndpoint", c.discoveryEndpoint)
                    put("scopes", c.scopes); put("display", c.display)
                    put("authorizationEndpoint", c.authorizationEndpoint)
                    put("tokenEndpoint", c.tokenEndpoint); put("userInfoEndpoint", c.userInfoEndpoint)
                    put("endSessionEndpoint", c.endSessionEndpoint)
                    put("revocationEndpoint", c.revocationEndpoint)
                    put("deviceAuthorizationEndpoint", c.deviceAuthorizationEndpoint)
                    put("acrValues", c.acrValues)
                })
            }
        }.toString()

    private fun deserializeDeviceAuthConfigs(json: String): List<DeviceAuthConfigState> = runCatching {
        Json.parseToJsonElement(json).jsonArray.map { el ->
            val o = el.jsonObject
            fun str(k: String) = o[k]?.jsonPrimitive?.content ?: ""
            DeviceAuthConfigState(
                clientId = str("clientId"), discoveryEndpoint = str("discoveryEndpoint"),
                scopes = str("scopes"), display = str("display"),
                authorizationEndpoint = str("authorizationEndpoint"),
                tokenEndpoint = str("tokenEndpoint"), userInfoEndpoint = str("userInfoEndpoint"),
                endSessionEndpoint = str("endSessionEndpoint"),
                revocationEndpoint = str("revocationEndpoint"),
                deviceAuthorizationEndpoint = str("deviceAuthorizationEndpoint"),
                acrValues = str("acrValues"),
            )
        }
    }.getOrDefault(emptyList())
}
