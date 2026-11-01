package com.wingman.core.di

/**
 * Process-wide holder so the container survives Activity recreation without an Application
 * subclass (the manifest is integrator-owned). Pure Kotlin.
 */
object AppContainerProvider {
    @Volatile private var instance: AppContainer? = null

    fun get(create: () -> AppContainer): AppContainer =
        instance ?: synchronized(this) { instance ?: create().also { instance = it } }

    /** Tests only. */
    fun reset() = synchronized(this) { instance = null }
}
