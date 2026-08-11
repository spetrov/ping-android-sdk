/*
 * Copyright (c) 2024 - 2026 Ping Identity Corporation. All rights reserved.
 *
 * This software may be modified and distributed under the terms
 * of the MIT license. See the LICENSE file for details.
 */

package com.pingidentity.journey

import android.net.Uri
import android.os.LocaleList
import com.pingidentity.journey.Constants.ACCEPT_API_VERSION
import com.pingidentity.journey.Constants.ACCEPT_LANGUAGE
import com.pingidentity.journey.Constants.AUTH_INDEX_TYPE
import com.pingidentity.journey.Constants.AUTH_INDEX_VALUE
import com.pingidentity.journey.Constants.COOKIE
import com.pingidentity.journey.Constants.FORCE_AUTH
import com.pingidentity.journey.Constants.NO_SESSION
import com.pingidentity.journey.Constants.REALM
import com.pingidentity.journey.Constants.RESOURCE_2_1_PROTOCOL_1_0
import com.pingidentity.journey.Constants.SERVICE
import com.pingidentity.journey.Constants.START_REQUEST
import com.pingidentity.journey.Constants.SUSPENDED_ID
import com.pingidentity.journey.module.NodeTransform
import com.pingidentity.journey.module.Oidc
import com.pingidentity.journey.module.RequestUrl
import com.pingidentity.journey.module.Session
import com.pingidentity.exception.ApiException
import com.pingidentity.oidc.JsonConfigKey
import com.pingidentity.oidc.JsonConfigParser
import com.pingidentity.oidc.update
import com.pingidentity.orchestrate.FailureNode
import com.pingidentity.orchestrate.Node
import com.pingidentity.orchestrate.Setup
import com.pingidentity.orchestrate.SharedContext
import com.pingidentity.orchestrate.Workflow
import com.pingidentity.orchestrate.WorkflowConfig
import com.pingidentity.orchestrate.module.CustomHeader
import com.pingidentity.utils.toAcceptLanguage
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonObject
import com.pingidentity.network.HttpRequest as Request

typealias Journey = Workflow

/**
 * JourneyConfig is a configuration class for the Journey workflow.
 *
 * @property serverUrl The URL of the server.
 * @property realm The realm to use (default is "root").
 * @property cookie The cookie name (default is "iPlanetDirectoryPro").
 */
class JourneyConfig : WorkflowConfig() {
    lateinit var serverUrl: String
    var realm: String = REALM
    var cookie: String = COOKIE
}

/**
 * Extension property to get the server URL from the JourneyConfig.
 */
val Journey.options: JourneyConfig
    get() = this.config as JourneyConfig

/**
 * Extension property to get the workflow from the Setup.
 */
val <T : Any> Setup<T>.journey: Journey
    get() = this.workflow

/**
 * Starts the authentication journey with the specified options.
 *
 * @param journeyName The name of the journey to start.
 * @param option A lambda function to configure additional options for the journey.
 * @return A Node representing the result of the journey start.
 */
suspend fun Journey.start(journeyName: String, option: Option.() -> Unit = {}): Node {
    return start {
        START_REQUEST to fun Request.() {
            parameter(AUTH_INDEX_TYPE, SERVICE)
            parameter(AUTH_INDEX_VALUE, journeyName)
        }
        option(this, option)
    }
}

/**
 * Resumes the authentication journey with the specified URI and options.
 *
 * @param uri The URI to resume the journey from.
 * @param option A lambda function to configure additional options for the journey.
 * @return A Node representing the result of the journey resume.
 */
suspend fun Journey.resume(uri: Uri, option: Option.() -> Unit = {}): Node {
    return start {
        uri.getQueryParameter(SUSPENDED_ID)?.let {
            START_REQUEST to fun Request.() {
                parameter(SUSPENDED_ID, it)
            }
        }
        option(this, option)
    }
}

/**
 * Starts the authentication journey from a backchannel (transactional) URI received via
 * push notification, QR code, or deep link.
 *
 * The URI's `authIndexType` and `authIndexValue` query parameters are extracted and forwarded
 * to the AM authenticate endpoint. All other URI components (host, path, realm) are ignored;
 * the authenticate endpoint is always reconstructed from [JourneyConfig.serverUrl] and
 * [JourneyConfig.realm].
 *
 * @param backchannelUri The URI supplied by the backchannel initiation (e.g. from a push
 *   notification payload or QR code). Must be a hierarchical URI containing `authIndexType`
 *   and `authIndexValue` query parameters.
 * @param option A lambda to configure additional options (e.g. [Option.forceAuth],
 *   [Option.noSession]) for this request.
 * @return A [Node] representing the result. Returns [FailureNode] immediately (without a
 *   network call) if the Journey is not configured with [JourneyConfig], if the URI is
 *   unparseable, or if either required query parameter is absent or blank.
 */
suspend fun Journey.start(backchannelUri: Uri, option: Option.() -> Unit = {}): Node {
    if (config !is JourneyConfig) {
        return FailureNode(ApiException(400, "JourneyConfig missing"))
    }

    val authIndexType: String?
    val authIndexValue: String?
    try {
        authIndexType = backchannelUri.getQueryParameter(AUTH_INDEX_TYPE)
        authIndexValue = backchannelUri.getQueryParameter(AUTH_INDEX_VALUE)
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        return FailureNode(ApiException(400, "Invalid URI"))
    }

    if (authIndexType.isNullOrEmpty() || authIndexValue.isNullOrEmpty()) {
        return FailureNode(ApiException(400, "Missing authIndexType or authIndexValue"))
    }

    return start {
        START_REQUEST to fun Request.() {
            parameter(AUTH_INDEX_TYPE, authIndexType)
            parameter(AUTH_INDEX_VALUE, authIndexValue)
        }
        option(this, option)
    }
}

private fun option(context: SharedContext, block: Option.() -> Unit = {}) {
    val option = Option().apply(block)
    context.apply {
        FORCE_AUTH to option.forceAuth
        NO_SESSION to option.noSession
        option.parameters.forEach { (key, value) ->
            this[key] = value
        }
    }
}

/**
 * Creates a new Journey instance with the provided configuration block.
 * @param block The configuration block for the Journey.
 * @return A new Journey instance.
 */
fun Journey(block: JourneyConfig.() -> Unit = {}): Journey {
    val config = JourneyConfig()

    // Apply default
    config.apply {
        module(CustomHeader) {
            header(ACCEPT_API_VERSION, RESOURCE_2_1_PROTOCOL_1_0)
            header(ACCEPT_LANGUAGE, LocaleList.getDefault().toAcceptLanguage())
        }
        module(RequestUrl)
        module(Session) // Persist the Session after success
        module(NodeTransform)
    }

    // Apply custom
    config.apply(block)

    /*
    config.apply {
        module(Cookie, mode = OverrideMode.IGNORE) {//Ignore if already exist
            //config.cookie is only available after config.apply(block)
            persist = mutableListOf(config.cookie)
        }
    }
     */

    return Journey(config)
}

/**
 * Creates a [Journey] instance from a JSON configuration.
 *
 * Journey-specific fields are nested under `journey`; OIDC fields under `oidc`. Example:
 * ```json
 * {
 *   "timeout": 30000,
 *   "log": "STANDARD",
 *   "journey": {
 *     "serverUrl": "https://openam.example.com/am",
 *     "realm": "alpha",
 *     "cookieName": "iPlanetDirectoryPro"
 *   },
 *   "oidc": {
 *     "clientId": "my-client-id",
 *     "discoveryEndpoint": "https://openam.example.com/am/oauth2/alpha/.well-known/openid-configuration",
 *     "scopes": ["openid", "profile"],
 *     "redirectUri": "myapp://oauth2redirect",
 *     "signOutRedirectUri": "myapp://logout",
 *     "refreshThreshold": 60,
 *     "loginHint": "user@example.com",
 *     "state": "custom-state",
 *     "nonce": "custom-nonce",
 *     "display": "page",
 *     "prompt": "login",
 *     "uiLocales": "en-US",
 *     "acrValues": "Level3",
 *     "par": true,
 *     "additionalParameters": { "max_age": "3600" },
 *     "openId": {
 *       "authorizationEndpoint": "https://openam.example.com/authorize",
 *       "tokenEndpoint": "https://openam.example.com/token",
 *       "userinfoEndpoint": "https://openam.example.com/userinfo",
 *       "endSessionEndpoint": "https://openam.example.com/logout",
 *       "revocationEndpoint": "https://openam.example.com/revoke"
 *     }
 *   }
 * }
 * ```
 *
 * @param json The JSON configuration object.
 * @return A [Result] containing the [Journey] instance or an exception if the configuration is invalid.
 */
fun Journey(json: JsonObject): Result<Journey> {
    return runCatching {
        val jsonConfigParser = JsonConfigParser(json)
        val journeyJsonConfig = JsonConfigParser(jsonConfigParser.required<JsonObject>(JsonConfigKey.JOURNEY))
        val oidcJsonConfig = JsonConfigParser(jsonConfigParser.required<JsonObject>(JsonConfigKey.OIDC))
        Journey {
            logger = jsonConfigParser.logLevel()
            timeout = jsonConfigParser.timeoutMillis()

            serverUrl = journeyJsonConfig.required<String>(JsonConfigKey.SERVER_URL)
            realm = journeyJsonConfig.optional<String>(JsonConfigKey.REALM, REALM)
            cookie = journeyJsonConfig.optional<String>(JsonConfigKey.COOKIE_NAME, COOKIE)

            module(Oidc) {
                clientId = oidcJsonConfig.required<String>(JsonConfigKey.CLIENT_ID)
                discoveryEndpoint = oidcJsonConfig.required<String>(JsonConfigKey.DISCOVERY_ENDPOINT)
                redirectUri = oidcJsonConfig.required<String>(JsonConfigKey.REDIRECT_URI)
                scopes = oidcJsonConfig.scopeSet(JsonConfigKey.SCOPES)
                update(oidcJsonConfig)
            }
        }
    }
}

/**
 * Option class to configure additional options for the journey.
 *
 * @property forceAuth Whether to force authentication (default is false).
 * @property noSession Whether to return new session (default is false).
 * @property parameters Additional key-value parameters to include in the authorization request.
 */
data class Option(
    var forceAuth: Boolean = false,
    var noSession: Boolean = false,
    internal val parameters: MutableMap<String, Any> = mutableMapOf(),
) : MutableMap<String, Any> by parameters {

    infix fun String.to(value: Any) {
        this@Option[this] = value
    }
}