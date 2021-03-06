/*
 * Circolapp
 * Copyright (C) 2019-2020  Matteo Schiff
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package net.underdesk.circolapp.works

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.TaskStackBuilder
import androidx.preference.PreferenceManager
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import net.underdesk.circolapp.MainActivity
import net.underdesk.circolapp.R
import net.underdesk.circolapp.data.AppDatabase
import net.underdesk.circolapp.data.Circular
import net.underdesk.circolapp.server.DataFetcher
import java.io.IOException
import java.util.concurrent.TimeUnit

class PollWork(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    companion object {
        const val CHANNEL_ID = "net.underdesk.circolapp.NEW_CIRCULAR"

        private const val pollWorkName = "net.underdesk.circolapp.POLL_WORK"
        private const val repeatIntervalMin: Long = 15
        private const val flexIntervalMin: Long = 10

        private fun getPollWorkRequest(repeatInterval: Long): PeriodicWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            return PeriodicWorkRequestBuilder<PollWork>(
                repeatInterval,
                TimeUnit.MINUTES,
                flexIntervalMin,
                TimeUnit.MINUTES
            ).setConstraints(constraints).build()
        }

        fun enqueue(context: Context) {
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

            if (sharedPreferences.getBoolean("notify_new_circulars", true)) {
                WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(
                        pollWorkName,
                        ExistingPeriodicWorkPolicy.KEEP,
                        getPollWorkRequest(
                            sharedPreferences.getString(
                                "poll_interval",
                                null
                            )?.toLong() ?: repeatIntervalMin
                        )
                    )
            } else {
                WorkManager.getInstance(context)
                    .cancelUniqueWork(pollWorkName)
            }
        }
    }

    override suspend fun doWork(): Result = coroutineScope {
        val fetcher = DataFetcher()

        withContext(Dispatchers.IO) {
            val oldCirculars =
                AppDatabase.getInstance(applicationContext).circularDao().getCirculars()
            val newCirculars = try {
                fetcher.getCircularsFromServer()
            } catch (exception: IOException) {
                return@withContext Result.retry()
            }

            if (newCirculars.size != oldCirculars.size) {
                val oldCircularsSize =
                    if (newCirculars.size < oldCirculars.size) 0 else oldCirculars.size

                withContext(Dispatchers.Main) {
                    createNotificationChannel()

                    val summaryStyle = NotificationCompat.InboxStyle()
                        .setBigContentTitle(applicationContext.getString(R.string.notification_summary_title))
                        .setSummaryText(applicationContext.getString(R.string.notification_summary))

                    val circularCount = newCirculars.size - oldCircularsSize

                    for (i in 0 until circularCount) {
                        createNotification(newCirculars[i])
                        summaryStyle.addLine(newCirculars[i].name)
                    }

                    val summaryNotification =
                        NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                            .setContentTitle(applicationContext.getString(R.string.notification_summary_title))
                            .setContentText(
                                applicationContext.resources.getQuantityString(
                                    R.plurals.notification_summary_text,
                                    circularCount,
                                    circularCount
                                )
                            )
                            .setSmallIcon(R.drawable.ic_notification)
                            .setStyle(summaryStyle)
                            .setGroup(CHANNEL_ID)
                            .setGroupSummary(true)
                            .build()

                    with(NotificationManagerCompat.from(applicationContext)) {
                        notify(-1, summaryNotification)
                    }
                }

                if (newCirculars.size < oldCirculars.size) {
                    AppDatabase.getInstance(applicationContext).circularDao()
                        .deleteAll()
                }

                AppDatabase.getInstance(applicationContext).circularDao()
                    .insertAll(newCirculars)
            }
            Result.success()
        }
    }

    private fun createNotification(circular: Circular) {
        val mainIntent = Intent(applicationContext, MainActivity::class.java)
        val viewIntent = Intent(Intent.ACTION_VIEW)
        viewIntent.setDataAndType(Uri.parse(circular.url), "application/pdf").apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val taskStackBuilder = TaskStackBuilder.create(applicationContext)
        taskStackBuilder.addParentStack(MainActivity::class.java)
        taskStackBuilder.addNextIntent(mainIntent)
        taskStackBuilder.addNextIntent(viewIntent)

        val pendingIntent = taskStackBuilder.getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT)

        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.notification_title, circular.id))
            .setContentText(circular.name)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setGroup(CHANNEL_ID)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(circular.name)
            )

        with(NotificationManagerCompat.from(applicationContext)) {
            notify(circular.id.toInt(), builder.build())
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = applicationContext.getString(R.string.channel_name_new)
            val descriptionText = applicationContext.getString(R.string.channel_description_new)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }

            val notificationManager: NotificationManager =
                applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
