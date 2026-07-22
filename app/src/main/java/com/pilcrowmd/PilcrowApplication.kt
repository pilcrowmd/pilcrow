// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd

import android.app.Application
import com.pilcrowmd.di.AppContainer
import com.pilcrowmd.di.DefaultAppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PilcrowApplication : Application() {
    lateinit var container: AppContainer

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(applicationContext)
        // Recover any save interrupted by a previous crash (Safeguard 1). Runs once per process,
        // off the main thread, before the user resumes editing. Independent of the UI entry point.
        applicationScope.launch {
            container.fileRepository.recoverPendingSaves()
        }
    }
}
