/*
 * Copyright (c) 2025 - 2026 Ping Identity Corporation. All rights reserved.
 *
 * This software may be modified and distributed under the terms
 * of the MIT license. See the LICENSE file for details.
 */

package com.pingidentity.journey

/**
 * Constants used in the Journey SDK.
 */
internal object Constants {
    const val REALM: String = "root"
    const val COOKIE: String = "iPlanetDirectoryPro"

    const val START_REQUEST = "com.pingidentity.journey.START_REQUEST"
    const val FORCE_AUTH = "com.pingidentity.journey.FORCE_AUTH"
    const val NO_SESSION = "com.pingidentity.journey.NO_SESSION"
    const val AUTH_INDEX_TYPE = "authIndexType"
    const val AUTH_INDEX_VALUE = "authIndexValue"
    const val SERVICE = "service"
    const val SUSPENDED_ID = "suspendedId"
    /** The `authIndexType` value AM places in a backchannel `redirectUri`. */
    const val TRANSACTION: String = "transaction"

    const val FORCE_AUTH_PARAM = "ForceAuth"
    const val NO_SESSION_PARAM = "noSession"

    const val SESSION_CONFIG = "com.pingidentity.journey.SESSION_CONFIG"
    const val RESOURCE31 = "resource=3.1, protocol=1.0"

    const val ACCEPT_API_VERSION = "Accept-API-Version"
    const val ACCEPT_LANGUAGE = "Accept-Language"
    const val RESOURCE_2_1_PROTOCOL_1_0 = "resource=2.1, protocol=1.0"

    const val AUTH_ID = "authId"
    const val CALLBACKS = "callbacks"
    const val CONTENT_TYPE = "Content-Type"
    const val APPLICATION_JSON = "application/json"
    const val TOKEN_ID = "tokenId"
    const val SUCCESS_URL = "successUrl"
    const val REALM_NAME = "realm"
    const val ACCEPT = "Accept"
    const val DECISION = "decision"
    const val ALLOW = "allow"
    const val CSRF = "csrf"

    // Constant key used to store and retrieve the OIDC client from the shared context
    const val OIDC_CLIENT = "com.pingidentity.journey.OIDC_CLIENT"

}