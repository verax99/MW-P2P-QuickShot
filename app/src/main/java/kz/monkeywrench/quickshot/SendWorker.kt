package kz.monkeywrench.quickshot

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class SendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences("quickshot", Context.MODE_PRIVATE)
        val token = SecretStore.getToken(applicationContext) ?: return@withContext Result.failure()
        val chatId = prefs.getString("chat", null) ?: return@withContext Result.failure()
        val id = inputData.getString("id") ?: return@withContext Result.failure()
        val uri = inputData.getString("uri")?.let(Uri::parse) ?: return@withContext Result.failure()
        if (prefs.getString("sent", "") == id) return@withContext Result.success()
        try {
            val boundary = "----MWQuickShot${UUID.randomUUID()}"
            val c = URL("https://api.telegram.org/bot$token/sendDocument").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 20000
            c.readTimeout = 45000
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            c.outputStream.use { out ->
                fun write(value: String) = out.write(value.toByteArray(Charsets.UTF_8))
                write("--$boundary\r\nContent-Disposition: form-data; name=\"chat_id\"\r\n\r\n$chatId\r\n")
                write("--$boundary\r\nContent-Disposition: form-data; name=\"document\"; filename=\"P2P_$id.png\"\r\nContent-Type: image/png\r\n\r\n")
                applicationContext.contentResolver.openInputStream(uri)?.use { it.copyTo(out) } ?: error("Скриншот недоступен")
                write("\r\n--$boundary--\r\n")
            }
            val status = c.responseCode
            val body = (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            c.disconnect()
            if (status in 200..299 && JSONObject(body).optBoolean("ok")) {
                prefs.edit().putString("sent", id).apply()
                return@withContext Result.success()
            }
            if (status == 429 || status >= 500) Result.retry() else Result.failure()
        } catch (_: Exception) { if (runAttemptCount < 10) Result.retry() else Result.failure() }
    }
}
