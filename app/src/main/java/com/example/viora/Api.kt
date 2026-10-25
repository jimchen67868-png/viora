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
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Your server's address. Change this to your Render URL (no trailing slash). */
const val SERVER_URL = "https://viora-server.onrender.com"

class ApiException(val code: Int, message: String) : Exception(message)

class Profile(
    val username: String, val bio: String, val avatar: String?,
    val email: String? = null, val emailVerified: Boolean = false, val vip: Boolean = false, val vipUntil: Long? = null, val accountId: String? = null,
    val followers: Int = 0, val following: Int = 0, val isFollowing: Boolean = false,
    val followingPrivacy: String = "everyone"
)
class Plan(val id: String, val name: String, val price: String)
class Convo(val user: String, val avatar: String?, val text: String, val mine: Boolean, val created: Long, val unread: Int)
class Dm(val id: Int, val mine: Boolean, val text: String, val created: Long)
class DmThread(val messages: List<Dm>, val avatar: String?)
class ActivityItem(val id: Int, val kind: String, val user: String, val avatar: String?, val postId: Int?, val text: String, val created: Long, val isNew: Boolean)
class BoostPlan(val days: Int, val price: String)
class BoostInfo(val plans: List<BoostPlan>, val until: Long?, val active: Boolean, val views: Int)
class Comment(val user: String, val avatar: String?, val text: String, val created: Long = 0)
class Story(val user: String, val avatar: String?, val images: List<String>)

class Post(
    val id: Int, val user: String, val avatar: String?, val image: String,
    initialCaption: String, initialLikes: Int, initialLiked: Boolean, initialCommentCount: Int,
    val kind: String = "image", val thumb: String? = null, val created: Long = 0, val views: Int = 0
) {
    val isVideo: Boolean get() = kind == "video"
    /** What to show as the picture: the photo itself, or a video's poster frame. */
    val preview: String get() = if (isVideo) (thumb ?: image) else image
    // Plain MutableState fields (no property delegates) keep the Kotlin compiler happy.
    private val captionState = mutableStateOf(initialCaption)
    private val likesState = mutableIntStateOf(initialLikes)
    private val likedState = mutableStateOf(initialLiked)
    private val commentCountState = mutableIntStateOf(initialCommentCount)

    var caption: String
        get() = captionState.value
        set(v) { captionState.value = v }
    var likes: Int
        get() = likesState.intValue
        set(v) { likesState.intValue = v }
    var liked: Boolean
        get() = likedState.value
        set(v) { likedState.value = v }
    var commentCount: Int
        get() = commentCountState.intValue
        set(v) { commentCountState.intValue = v }
}

private fun JSONObject.str(k: String): String? = if (isNull(k)) null else getString(k)
private fun JSONObject.toProfile() =
    Profile(getString("username"), optString("bio", ""), str("avatar"), str("email"), optBoolean("email_verified", false), optBoolean("vip", false),
        if (has("vip_until") && !isNull("vip_until")) getLong("vip_until") else null,
        str("account_id"),
        followers = optInt("followers", 0), following = optInt("following", 0), isFollowing = optBoolean("is_following", false),
        followingPrivacy = optString("following_privacy", "everyone"))
private fun JSONObject.toDm() = Dm(getInt("id"), getBoolean("mine"), getString("text"), optLong("created", 0L))
private fun JSONObject.toComment() = Comment(getString("user"), str("avatar"), getString("text"), optLong("created", 0L))
private fun JSONObject.toPost() = Post(
    getInt("id"), getString("user"), str("avatar"), getString("image"),
    getString("caption"), getInt("likes"), getBoolean("liked"), getInt("comments"),
    optString("kind", "image"), str("thumb"), optLong("created", 0L), optInt("views", 0)
)

class Api(var base: String, var token: String?) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).build()
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
    suspend fun setFollowingPrivacy(mode: String) =
        send("PUT", "/api/me/privacy", json("following_privacy" to mode)).getJSONObject("user").toProfile()
    suspend fun removeAvatar() = send("DELETE", "/api/me/avatar").getJSONObject("user").toProfile()

    suspend fun posts(): List<Post> {
        val a = send("GET", "/api/posts").getJSONArray("posts")
        return List(a.length()) { a.getJSONObject(it).toPost() }
    }
    suspend fun post(id: Int): Post = send("GET", "/api/posts/$id").getJSONObject("post").toPost()
    suspend fun ads(): List<Post> {
        val a = send("GET", "/api/ads").getJSONArray("posts")
        return List(a.length()) { a.getJSONObject(it).toPost() }
    }
    suspend fun postsSeen(ids: List<Int>) {
        send("POST", "/api/posts/seen", JSONObject().put("ids", JSONArray(ids)).toString().toRequestBody(JSON))
    }
    suspend fun adSeen(id: Int) { send("POST", "/api/ads/seen", json("post_id" to id.toString())) }
    suspend fun boostInfo(postId: Int): BoostInfo {
        val j = send("GET", "/api/boost/plans?post=$postId")
        val a = j.getJSONArray("plans")
        val plans = List(a.length()) { val o = a.getJSONObject(it); BoostPlan(o.getInt("days"), o.getString("price")) }
        val s = j.optJSONObject("status")
        return BoostInfo(plans, s?.optLong("until"), s?.optBoolean("active") ?: false, s?.optInt("views") ?: 0)
    }
    suspend fun boostCheckout(postId: Int, days: Int) =
        send("POST", "/api/boost/checkout", json("post_id" to postId.toString(), "days" to days.toString())).getString("url")
    suspend fun conversations(): List<Convo> {
        val a = send("GET", "/api/messages").getJSONArray("conversations")
        return List(a.length()) {
            val o = a.getJSONObject(it)
            Convo(o.getString("user"), o.str("avatar"), o.getString("text"), o.getBoolean("mine"), o.optLong("created", 0L), o.optInt("unread", 0))
        }
    }
    suspend fun thread(name: String, after: Int): DmThread {
        val j = send("GET", "/api/messages/with/" + enc(name) + "?after=$after")
        val a = j.getJSONArray("messages")
        return DmThread(List(a.length()) { a.getJSONObject(it).toDm() }, j.optJSONObject("user")?.str("avatar"))
    }
    suspend fun sendDm(name: String, text: String): Dm =
        send("POST", "/api/messages/to/" + enc(name), json("text" to text)).getJSONObject("message").toDm()
    /** (unread messages, unread activity) */
    suspend fun deleteChat(name: String) { send("DELETE", "/api/messages/with/" + enc(name)) }
    suspend fun unreadCounts(): Pair<Int, Int> {
        val j = send("GET", "/api/messages/unread")
        return j.getInt("count") to j.optInt("activity", 0)
    }
    suspend fun activity(): List<ActivityItem> {
        val a = send("GET", "/api/notifications").getJSONArray("notifications")
        return List(a.length()) {
            val o = a.getJSONObject(it)
            ActivityItem(o.getInt("id"), o.getString("kind"), o.getString("user"), o.str("avatar"),
                if (o.isNull("post_id")) null else o.getInt("post_id"), o.optString("text", ""), o.optLong("created", 0L), o.optBoolean("new", false))
        }
    }
    suspend fun registerDevice(token: String) { send("POST", "/api/devices", json("token" to token)) }
    suspend fun removeDevice(token: String) { send("POST", "/api/devices/remove", json("token" to token)) }
    suspend fun following(): List<Post> {
        val a = send("GET", "/api/feed/following").getJSONArray("posts")
        return List(a.length()) { a.getJSONObject(it).toPost() }
    }
    suspend fun user(name: String): Pair<Profile, List<Post>> {
        val j = send("GET", "/api/users/" + enc(name))
        val a = j.getJSONArray("posts")
        return j.getJSONObject("user").toProfile() to List(a.length()) { a.getJSONObject(it).toPost() }
    }
    suspend fun search(q: String): Pair<List<Profile>, List<Post>> {
        val j = send("GET", "/api/search?q=" + enc(q))
        val u = j.getJSONArray("users"); val p = j.getJSONArray("posts")
        return List(u.length()) { u.getJSONObject(it).toProfile() } to List(p.length()) { p.getJSONObject(it).toPost() }
    }
    suspend fun forYou(): Pair<List<Post>, Boolean> {
        val j = send("GET", "/api/feed/for-you")
        val a = j.getJSONArray("posts")
        return List(a.length()) { a.getJSONObject(it).toPost() } to j.optBoolean("personalized", false)
    }
    suspend fun plans(): List<Plan> {
        val a = send("GET", "/api/plans").getJSONArray("plans")
        return List(a.length()) { val o = a.getJSONObject(it); Plan(o.getString("id"), o.getString("name"), o.optString("price", "")) }
    }
    suspend fun checkout(plan: String) = send("POST", "/api/billing/checkout", json("plan" to plan)).getString("url")
    suspend fun portal() = send("POST", "/api/billing/portal").getString("url")
    suspend fun verifyGoogle(token: String) =
        send("POST", "/api/billing/google/verify", json("purchase_token" to token)).getJSONObject("user").toProfile()
    suspend fun syncGoogle() = send("POST", "/api/billing/google/sync").getJSONObject("user").toProfile()
    suspend fun follow(name: String, on: Boolean): Int =
        send(if (on) "POST" else "DELETE", "/api/users/" + enc(name) + "/follow").getInt("followers")
    suspend fun followList(name: String, kind: String): List<Profile> {
        val a = send("GET", "/api/users/" + enc(name) + "/" + kind).getJSONArray("users")
        return List(a.length()) { a.getJSONObject(it).toProfile() }
    }
    suspend fun createVideoPost(video: ByteArray, caption: String) =
        send("POST", "/api/posts?kind=video&caption=" + enc(caption), video.toRequestBody("video/mp4".toMediaType())).getJSONObject("post").toPost()
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
