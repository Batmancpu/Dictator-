/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.patrickgold.florisboard.R

/**
 * Keeps the phone's process running while it transcribes for the watch (#363).
 *
 * [DictateWearService] is woken by Play services only for as long as it takes to hand over the watch's
 * audio. Once that call returns, nothing holds the process any more: Android 14 and later freeze it a few
 * seconds later, mid-request — the provider's answer sits unread in the socket, and the watch hears
 * nothing until its next message happens to thaw the phone. On the A55 the process was found frozen with
 * the transcript a request away. Doze, on top, cuts a background app off the network. The partial wake
 * lock held since #218 keeps the CPU awake and helps against neither.
 *
 * A foreground service is Android's answer to both. The short-service type fits the work — seconds as a
 * rule, a few minutes at worst — and needs neither a further permission nor a Play Console declaration;
 * its three-minute cap ([onTimeout]) only ends the protection, and the watch reports a phone that went
 * quiet after that. It is started while Play services is still bound to [DictateWearService], since an
 * app in the background may not start one on its own.
 */
class WearTetherService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService() must be answered with startForeground(), even when the work it was
        // started for has already ended — stopping before that is what crashes.
        goForeground()
        synchronized(Companion) {
            instance = this
            if (holders == 0) stop()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        synchronized(Companion) { if (instance === this) instance = null }
        super.onDestroy()
    }

    /** The short-service limit (Android 14). The work goes on unprotected; [onTimeout] must stop or it ANRs. */
    @Deprecated("Superseded by onTimeout(Int, Int) on Android 15")
    override fun onTimeout(startId: Int) {
        Log.w(TAG, "tether: three-minute limit reached, the work goes on unprotected")
        stop()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "tether: three-minute limit reached, the work goes on unprotected")
        stop()
    }

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun goForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.dictate__wear_tether_notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app_icon_monochrome)
            .setContentTitle(getString(R.string.dictate__wear_tether_notif_title))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }

    companion object {
        private const val TAG = "DictateWear"
        private const val CHANNEL_ID = "dictate_wear_tether"
        private const val NOTIF_ID = 4363

        /** Requests for the watch still being worked on; the service lives while this is above zero. */
        private var holders = 0
        private var instance: WearTetherService? = null

        /**
         * Call while Play services is still bound to [DictateWearService] — from the callback that
         * delivered the request — and pair with [release] when the work ends, however it ends.
         */
        fun hold(context: Context) {
            val start = synchronized(this) { ++holders == 1 && instance == null }
            if (!start) return
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, WearTetherService::class.java))
            }.onFailure {
                // Not allowed from the background on this phone: the work runs as before, unprotected.
                Log.w(TAG, "tether: could not keep the phone awake for the watch", it)
            }
        }

        fun release() {
            synchronized(this) {
                holders = (holders - 1).coerceAtLeast(0)
                if (holders == 0) instance?.stop()
            }
        }
    }
}
