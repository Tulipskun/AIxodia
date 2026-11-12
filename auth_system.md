# Auth System: Cloudflare Access Service Token (ผู้ใช้กรอกเองครั้งเดียว)

แอปนี้ใช้คนเดียว จึงเลือกแบบ **ให้ผู้ใช้กรอก Service Token เองในหน้า Settings ครั้งเดียว** แล้วเก็บแบบเข้ารหัสในเครื่อง
ไม่มี token อยู่ในซอร์สโค้ด, `BuildConfig`, หรือ APK เลย

## หลักการ

1. สร้าง Service Token ใน Cloudflare Zero Trust
   - Access → Service Auth → Service Tokens → Create Service Token
   - ก๊อป `Client ID` และ `Client Secret` ทันที (Secret แสดงครั้งเดียว)
2. ตั้ง Policy ของ Application ที่ครอบ hostname ของ tunnel
   - Action ต้องเป็น **Service Auth** (ไม่ใช่ Allow)
   - Include เลือก token ที่สร้างไว้
   - จำกัดเฉพาะ hostname ที่แอปเรียกจริง
3. เปิดแอป → Settings → วาง Client ID / Client Secret ครั้งเดียว
4. แอปเก็บค่าด้วย `EncryptedSharedPreferences` (คีย์อยู่ใน Android Keystore)
5. ทุก request ไปยัง hostname ที่กำหนด แอปแนบ header:
   - `CF-Access-Client-Id`
   - `CF-Access-Client-Secret`

## Dependency

```kotlin
// app/build.gradle.kts
implementation("androidx.security:security-crypto:1.1.0-alpha06")
implementation("com.squareup.okhttp3:okhttp:4.12.0")
```

## ตัวเก็บ token

```kotlin
import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class CfAccessStore(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "cf_access",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var clientId: String?
        get() = prefs.getString(KEY_ID, null)
        set(value) = prefs.edit().putString(KEY_ID, value).apply()

    var clientSecret: String?
        get() = prefs.getString(KEY_SECRET, null)
        set(value) = prefs.edit().putString(KEY_SECRET, value).apply()

    val isConfigured: Boolean
        get() = !clientId.isNullOrBlank() && !clientSecret.isNullOrBlank()

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val KEY_ID = "client_id"
        const val KEY_SECRET = "client_secret"
    }
}
```

## OkHttp Interceptor

แนบ header เฉพาะ host ของเรา เพื่อไม่ให้ token รั่วไปยัง host อื่น

```kotlin
import okhttp3.Interceptor
import okhttp3.Response

class CfAccessInterceptor(
    private val store: CfAccessStore,
    private val allowedHost: String   // เช่น "mcp.example.com"
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != allowedHost || !store.isConfigured) {
            return chain.proceed(request)
        }
        val authed = request.newBuilder()
            .header("CF-Access-Client-Id", store.clientId!!)
            .header("CF-Access-Client-Secret", store.clientSecret!!)
            .build()
        return chain.proceed(authed)
    }
}
```

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(CfAccessInterceptor(store, allowedHost = "mcp.example.com"))
    .build()
```

## ข้อควรระวัง

- **ห้าม log header**: ถ้าใช้ `HttpLoggingInterceptor` ให้ `redactHeader("CF-Access-Client-Secret")` และ `redactHeader("CF-Access-Client-Id")`
- **กัน backup**: ตั้ง `android:allowBackup="false"` หรือ exclude ไฟล์ `cf_access` ใน `backup_rules.xml` / `data_extraction_rules.xml`
- **ห้าม commit token** ลง git ถ้าเผลอ ให้ revoke ใน Zero Trust แล้วสร้างใหม่ทันที (ลบ commit ไม่พอ เพราะยังอยู่ใน history)
- **เครื่องหาย / สงสัยว่าหลุด**: Zero Trust → Service Auth → Service Tokens → Revoke แล้วสร้างใหม่
- **ตั้งอายุ token** ตามต้องการ แล้วใส่หน้าจอเตือนในแอปเมื่อใกล้หมดอายุ (ถ้าไม่เลือก Non-expiring)
- ถ้ารับ 401/403 จาก Cloudflare ให้แสดงข้อความให้ผู้ใช้ตรวจ token ใน Settings แทนการ retry วนซ้ำ

## ทางเลือกที่ปลอดภัยกว่า (ถ้าต้องการในอนาคต)

**mTLS client certificate**: เก็บ private key ใน Android Keystore แบบ non-exportable ต่อให้ได้ APK ไปก็ดึง key ไม่ได้ และไม่ต้องมี header
