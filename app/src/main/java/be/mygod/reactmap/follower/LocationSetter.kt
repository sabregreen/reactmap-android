package be.mygod.reactmap.follower

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import be.mygod.reactmap.App.Companion.app
import be.mygod.reactmap.MainActivity
import be.mygod.reactmap.R
import be.mygod.reactmap.ReactMapHttpEngine
import be.mygod.reactmap.util.findErrorStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException

class LocationSetter(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    companion object {
        const val CHANNEL_ID = "locationSetter"
        const val CHANNEL_ID_ERROR = "locationSetterError"
        const val KEY_LATITUDE = "latitude"
        const val KEY_LONGITUDE = "longitude"

        fun notifyError(message: CharSequence) {
            app.nm.notify(3, NotificationCompat.Builder(app, CHANNEL_ID_ERROR).apply {
                setWhen(0)
                color = app.getColor(R.color.main_orange)
                setCategory(NotificationCompat.CATEGORY_ALARM)
                setContentTitle("Failed to update location")
                setContentText(message)
                setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                setSmallIcon(R.drawable.ic_notification_sync_problem)
                priority = NotificationCompat.PRIORITY_MAX
                setContentIntent(PendingIntent.getActivity(app, 2,
                    Intent(app, MainActivity::class.java).setAction(MainActivity.ACTION_CONFIGURE),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }.build())
        }
    }

    override suspend fun doWork() = try {
        val lat = inputData.getDouble(KEY_LATITUDE, Double.NaN)
        val lon = inputData.getDouble(KEY_LONGITUDE, Double.NaN)
        val conn = ReactMapHttpEngine.openConnection(app.activeUrl.toUri().buildUpon().apply {
            path("/graphql")
        }.build().toString()) {
            doOutput = true
            requestMethod = "POST"
            addRequestProperty("Content-Type", "application/json")
            outputStream.bufferedWriter().use {
                it.write(JSONObject().apply {
                    put("operationName", "Webhook")
                    put("variables", JSONObject().apply {
                        put("category", "setLocation")
                        put("data", JSONArray(arrayOf(lat, lon)))
                        put("status", "POST")
                    })
                    // epic graphql query yay >:(
                    put("query", "mutation Webhook(\$data: JSON, \$category: String!, \$status: String!) {" +
                            "webhook(data: \$data, category: \$category, status: \$status) { __typename } }")
                }.toString())
            }
        }
        when (val code = conn.responseCode) {
            200 -> {
                withContext(Dispatchers.Main) {
                    BackgroundLocationReceiver.onLocationSubmitted(Location("").apply {
                        latitude = lat
                        longitude = lon
                    })
                }
                Result.success()
            }
            else -> {
                val error = conn.findErrorStream.bufferedReader().readText()
                Timber.w(Exception(error))
                val json = JSONObject(error).getJSONArray("errors")
                notifyError((0 until json.length()).joinToString { json.getJSONObject(it).getString("message") })
                if (code == 401 || code == 511) {
                    // TODO: handle 511 session expired
                    Result.failure()
                } else Result.retry()
            }
        }
    } catch (e: IOException) {
        Timber.d(e)
        Result.retry()
    } catch (e: Exception) {
        Timber.w(e)
        notifyError(e.localizedMessage ?: e.javaClass.name)
        Result.failure()
    }

    override suspend fun getForegroundInfo() = ForegroundInfo(2, NotificationCompat.Builder(app, CHANNEL_ID).apply {
        setWhen(0)
        color = app.getColor(R.color.main_blue)
        setCategory(NotificationCompat.CATEGORY_SERVICE)
        setContentTitle("Updating location")
        setContentText("${inputData.getDouble(KEY_LATITUDE, Double.NaN)}, " +
                inputData.getDouble(KEY_LONGITUDE, Double.NaN))
        setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        setSmallIcon(R.drawable.ic_notification_sync)
        setProgress(0, 0, true)
        priority = NotificationCompat.PRIORITY_LOW
        setContentIntent(PendingIntent.getActivity(app, 2,
            Intent(app, MainActivity::class.java).setAction(MainActivity.ACTION_CONFIGURE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
    }.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
}
