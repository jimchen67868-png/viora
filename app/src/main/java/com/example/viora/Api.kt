package com.example.viora

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Your server's address. Change this to your Render URL (no trailing slash). */
const val SERVER_URL = "https://viora-server.onrender.com"

class ApiException(val code: Int, message: String) : Exception(message)

class Profile(
    val username: String, val bio: String, val avatar: String?,
    val email: String? = null, val emailVerified: Boolean = false
)
class Comment(val user: String, val avatar: String?, val text: String)
class Story(val user: String, val avatar: String?, val images: List<String>)

class Post(
    val id: Int, val user: String, val avatar: String?, val image: String,
    caption: String, likes: Int, liked: Boolean, commentCount: Int
) {
    var caption by mutableStateOf(caption)
    var likes by mutableIntStateOf(likes)
    var liked by mutableStateOf(liked)
    var commentCount by mutableIntStateOf(commentCount)
}

private fun JSONObject.str(k: String): String? = if (isNull(k)) null else getString(k)
private fun JSONObject.toProfile() =
    Profile(getString("username"), optString("bio", ""), str("avatar"), str("email"), optBoolean("email_verified", false))
private fun JSONObject.toComment() = Comment(getString("user"), str("avatar"), getString("text"))
private fun JSONObject.toPost() = Post(
    getInt("id"), getString("user"), str("avatar"), getString("image"),
    getString("caption"), getInt("likes"), getBoolean("liked"), getInt("comments")
)

class Api(var base: String, var token: String?) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).build()
    private val JSON = "application/json".toMediaType()
    private val JPEG = "image/jpeg".toMediaType()

    private suspend fun send(method: String, path: String, body: RequestBody? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val b = Request.Builder().url(base.trimEnd('/') + path)
            token?.let { b.header("Authorization", "Bearer $it") }
            b.method(method, body ?: if (method == "GET") null else ByteArray(0).toRequestBody())
            client.newCall(b.build()).execute().use { r ->
                val txt = r.body?.string().orEmpty()
                val j = try { if (txt.isBlank()) JSONObject() else JSONObject(txt) } catch (e: Exception) { JSONObject() }
                if (!r.isSuccessful) throw ApiException(r.code, j.optString("error", "Server error (${r.code})"))
                j
            }
        }

    private fun json(vararg p: Pair<String, String>) = JSONObject(mapOf(*p)).toString().toRequestBody(JSON)
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    suspend fun auth(path: String, user: String, pw: String): Pair<String, Profile> {
        val j = send("POST", path, json("username" to user, "password" to pw))
        return j.getString("token") to j.getJSONObject("user").toProfile()
    }
    suspend fun me() = send("GET", "/api/me").getJSONObject("user").toProfile()
    suspend fun updateProfile(username: String, bio: String) =
        send("PUT", "/api/me", json("username" to username, "bio" to bio)).getJSONObject("user").toProfile()
    suspend fun setAvatar(img: ByteArray) = send("POST", "/api/me/avatar", img.toRequestBody(JPEG)).getJSONObject("user").toProfile()
    suspend fun setEmail(email: String) = send("PUT", "/api/me/email", json("email" to email)).getJSONObject("user").toProfile()
    suspend fun sendEmailCode() { send("POST", "/api/me/email/send") }
    suspend fun verifyEmail(code: String) = send("POST", "/api/me/email/verify", json("code" to code)).getJSONObject("user").toProfile()
    suspend fun removeEmail() = send("DELETE", "/api/me/email").getJSONObject("user").toProfile()
    suspend fun forgotPassword(email: String) { send("POST", "/api/password/forgot", json("email" to email)) }
    suspend fun resetPassword(email: String, code: String, password: String) {
        send("POST", "/api/password/reset", json("email" to email, "code" to code, "password" to password))
    }
    suspend fun removeAvatar() = send("DELETE", "/api/me/avatar").getJSONObject("user").toProfile()

    suspend fun posts(): List<Post> {
        val a = send("GET", "/api/posts").getJSONArray("posts")
        return List(a.length()) { a.getJSONObject(it).toPost() }
    }
    suspend fun createPost(img: ByteArray, caption: String) =
        send("POST", "/api/posts?caption=" + enc(caption), img.toRequestBody(JPEG)).getJSONObject("post").toPost()
    suspend fun editPost(id: Int, caption: String) { send("PUT", "/api/posts/$id", json("caption" to caption)) }
    suspend fun deletePost(id: Int) { send("DELETE", "/api/posts/$id") }
    suspend fun like(id: Int): Pair<Boolean, Int> {
        val j = send("POST", "/api/posts/$id/like")
        return j.getBoolean("liked") to j.getInt("likes")
    }
    suspend fun comments(id: Int): List<Comment> {
        val a = send("GET", "/api/posts/$id/comments").getJSONArray("comments")
        return List(a.length()) { a.getJSONObject(it).toComment() }
    }
    suspend fun addComment(id: Int, text: String) =
        send("POST", "/api/posts/$id/comments", json("text" to text)).getJSONObject("comment").toComment()

    suspend fun stories(): List<Story> {
        val a = send("GET", "/api/stories").getJSONArray("stories")
        return List(a.length()) {
            val o = a.getJSONObject(it); val im = o.getJSONArray("images")
            Story(o.getString("user"), o.str("avatar"), List(im.length()) { i -> im.getString(i) })
        }
    }
    suspend fun createStory(img: ByteArray) { send("POST", "/api/stories", img.toRequestBody(JPEG)) }
}

/** Reads a picked image, fixes rotation, downsizes to <=1080px and returns JPEG bytes. */
fun readJpeg(ctx: Context, uri: Uri, max: Int = 1080): ByteArray {
    val cr = ctx.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > max * 2) sample *= 2
    val bmp = cr.openInputStream(uri)!!.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: throw IllegalArgumentException("Can't read this image")
    val deg = when (cr.openInputStream(uri)!!.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }) {
        6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
    }
    val scale = minOf(1f, max.toFloat() / maxOf(bmp.width, bmp.height))
    val m = Matrix().apply { postRotate(deg); postScale(scale, scale) }
    val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    return ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
}
