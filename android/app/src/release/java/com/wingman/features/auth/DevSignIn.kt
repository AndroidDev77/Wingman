package com.wingman.features.auth

/*
 * RELEASE SOURCE SET (I-011): the dev-token sign-in does not exist in release builds. No UI, no
 * paste field; AuthSession.signInWithDevToken also refuses because BuildConfig.DEBUG is false.
 * DevSignInSourceSetTest fails if this file ever enables it.
 */

internal const val DEV_SIGN_IN_AVAILABLE: Boolean = false

internal val devSignInPanel: DevSignInPanel? = null
