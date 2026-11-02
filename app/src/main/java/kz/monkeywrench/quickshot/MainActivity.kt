package kz.monkeywrench.quickshot

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.*
import androidx.work.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var tokenField: EditText
    private lateinit var chatField: EditText
    private val executor = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("quickshot", Context.MODE_PRIVATE) }
    private val permissionRequest = 21

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 44, 32, 24)
        }
        root.addView(TextView(this).apply {
            text = "MW P2P QuickShot"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Настрой один раз. Затем нажимай ярлык для отправки последнего скриншота."
        })
        tokenField = EditText(this).apply {
            hint = "Токен Telegram-бота"
            inputType = 129
        }
        root.addView(tokenField)
        chatField = EditText(this).apply {
            hint = "ID группы"
            inputType = 3
            setText(prefs.getString("chat", ""))
        }
        root.addView(chatField)
        root.addView(Button(this).apply {
            text = "Сохранить настройки"
            setOnClickListener { saveSettings() }
        })
        root.addView(Button(this).apply {
            text = "Найти ID группы по /start"
            setOnClickListener { detectGroup() }
        })
        root.addView(Button(this).apply {
            text = "Отправить последний скриншот"
            setOnClickListener { sendLatest() }
        })
        root.addView(Button(this).apply {
            text = "Разрешить доступ к скриншотам"
            setOnClickListener { requestImages() }
        })
        status = TextView(this).apply {
            textSize = 16f
            text = "Ожидание настройки"
            setPadding(0, 18, 0, 0)
        }
        root.addView(status)
        setContentView(ScrollView(this).apply { addView(root) })
        if (SecretStore.hasToken(this) && !prefs.getString("chat", "").isNullOrBlank()) {
            if (hasImages()) sendLatest() else requestImages()
        }
    }

    private fun saveSettings() {
        try {
            val token = tokenField.text.toString().trim()
            if (token.isNotEmpty()) SecretStore.setToken(this, token)
            val chat = chatField.text.toString().trim()
            if (chat.isNotEmpty() && chat.toLongOrNull() == null) {
                status.text = "ID группы должен быть числом"
                return
            }
            if (chat.isNotEmpty()) prefs.edit().putString("chat", chat).apply()
            tokenField.text.clear()
            status.text = "Настройки сохранены только на телефоне"
        } catch (e: Exception) {
            status.text = "Ошибка сохранения: ${e.message}"
        }
    }

    private fun detectGroup() {
        saveSettings()
        val token = SecretStore.getToken(this)
        if (token.isNullOrBlank()) {
            status.text = "Сначала введи токен нового бота"
            return
        }
        status.text = "Ищу команду /start в обновлениях бота…"
        executor.execute {
            try {
                val c = URL("https://api.telegram.org/bot$token/getUpdates?limit=100&timeout=0").openConnection() as HttpURLConnection
                c.connectTimeout = 12000
                c.readTimeout = 12000
                val json = c.inputStream.bufferedReader().use { JSONObject(it.readText()) }
                c.disconnect()
                if (!json.optBoolean("ok")) error("Telegram не подтвердил запрос")
                val updates = json.getJSONArray("result")
                var groupId: String? = null
                for (i in 0 until updates.length()) {
                    val message = updates.getJSONObject(i).optJSONObject("message") ?: continue
                    val chat = message.optJSONObject("chat") ?: continue
                    val type = chat.optString("type")
                    val body = message.optString("text")
                    if ((type == "group" || type == "supergroup") && body.startsWith("/start")) {
                        groupId = chat.optString("id")
                    }
                }
                runOnUiThread {
                    val found = groupId
                    if (found != null) {
                        chatField.setText(found)
                        prefs.edit().putString("chat", found).apply()
                        status.text = "Группа найдена. Можно отправлять скриншоты."
                    } else {
                        status.text = "Группа не найдена. Повтори /start@имя_бота в группе."
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Ошибка поиска: ${e.message}" }
            }
        }
    }

    private fun hasImages(): Boolean = when {
        Build.VERSION.SDK_INT >= 33 -> checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        else -> checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestImages() {
        if (Build.VERSION.SDK_INT >= 34) {
            requestPermissions(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED), permissionRequest)
        } else if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.READ_MEDIA_IMAGES), permissionRequest)
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), permissionRequest)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequest) {
            status.text = if (hasImages()) "Доступ разрешён" else "Выбери полный доступ ко всем фото"
        }
    }

    private fun latestScreenshot(): Pair<Uri, String>? {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH
        )
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        contentResolver.query(uri, projection, null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            var count = 0
            while (cursor.moveToNext() && count++ < 100) {
                val name = cursor.getString(nameColumn) ?: ""
                val path = cursor.getString(pathColumn) ?: ""
                if (path.contains("Screenshot", true) || name.contains("Screenshot", true) || name.contains("Снимок экрана", true)) {
                    val id = cursor.getLong(idColumn)
                    return ContentUris.withAppendedId(uri, id) to id.toString()
                }
            }
        }
        return null
    }

    private fun sendLatest() {
        if (!hasImages()) {
            status.text = "Нет доступа к изображениям"
            requestImages()
            return
        }
        if (!SecretStore.hasToken(this) || prefs.getString("chat", "").isNullOrBlank()) {
            status.text = "Сначала введи токен и найди ID группы"
            return
        }
        val shot = try { latestScreenshot() } catch (e: Exception) {
            status.text = "Не удалось прочитать галерею: ${e.message}"
            null
        }
        if (shot == null) {
            status.text = "Скриншотов не найдено"
            return
        }
        val key = shot.second
        if (prefs.getString("sent", "") == key) {
            status.text = "Этот скриншот уже отправлен"
            return
        }
        val job = OneTimeWorkRequestBuilder<SendWorker>()
            .setInputData(workDataOf("uri" to shot.first.toString(), "id" to key))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork("shot-$key", ExistingWorkPolicy.KEEP, job)
        status.text = "Скриншот в очереди. Проверь фактическую доставку в Telegram."
    }
}
