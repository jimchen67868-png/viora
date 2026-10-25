package com.example.viora

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.android.billingclient.api.Purchase
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------- ViewModel ----------
class AppVm(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("viora", Context.MODE_PRIVATE)
    val api = Api(SERVER_URL, prefs.getString("token", null))
    var me by mutableStateOf<Profile?>(null)
    val posts = mutableStateListOf<Post>()
    val stories = mutableStateListOf<Story>()
    val forYou = mutableStateListOf<Post>()
    val followingPosts = mutableStateListOf<Post>()
    val ads = mutableStateListOf<Post>()
    private val seenAds = HashSet<Int>()
    private val viewedPosts = HashSet<Int>()
    private val viewQueue = ArrayList<Int>()
    private var viewJob: kotlinx.coroutines.Job? = null

    /** Counts a view when a post appears on screen: batched, once per post per session, never for your own posts. */
    fun postSeen(p: Post) {
        if (p.user == me?.username || !viewedPosts.add(p.id)) return
        viewQueue.add(p.id)
        if (viewJob?.isActive == true) return
        viewJob = viewModelScope.launch {
            delay(2000)
            val ids = viewQueue.toList(); viewQueue.clear()
            try { api.postsSeen(ids) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { }
        }
    }
    var pendingPostId by mutableStateOf<Int?>(null)
    var sharedPost by mutableStateOf<Post?>(null)
    var unreadMessages by mutableIntStateOf(0)
    var unreadActivity by mutableIntStateOf(0)
    var pendingChat by mutableStateOf<String?>(null)
    var pendingUser by mutableStateOf<String?>(null)

    /** Registers this phone for push notifications. Does nothing in builds without google-services.json. */
    fun registerPush() {
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token.addOnSuccessListener { tok ->
                if (!tok.isNullOrBlank()) viewModelScope.launch {
                    try { api.registerDevice(tok); prefs.edit().putString("push_token", tok).apply() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { }
                }
            }
        } catch (e: Exception) { /* Firebase isn't set up in this build */ }
    }

    fun refreshUnread() {
        viewModelScope.launch {
            try {
                val (m, a) = api.unreadCounts(); unreadMessages = m; unreadActivity = a
                prefs.edit().putInt("notified_total", m + a).apply()   // what you've already seen: the background check only alerts about more than this
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { }
        }
    }
    val play = PlayBilling(app, { list -> onPlayPurchases(list) }, { msg -> toast(msg) })
    var forYouPersonal by mutableStateOf(true)
    var feedMode by mutableIntStateOf(0)
    var refreshing by mutableStateOf(false)
    var busy by mutableStateOf(false)

    init {
        if (api.token != null) {
            me = Profile(prefs.getString("user", "")!!, prefs.getString("bio", "")!!, prefs.getString("avatar", null))
            refresh()
        }
    }

    fun toast(msg: String?) = Toast.makeText(getApplication<Application>(), msg ?: "Something went wrong", Toast.LENGTH_LONG).show()

    private fun safe(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() }
        catch (e: ApiException) { if (e.code == 401) logout(); toast(e.message) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { toast(e.message ?: "Can't reach the server") }
    }

    private fun saveMe(p: Profile) {
        me = p
        prefs.edit().putString("user", p.username).putString("bio", p.bio).putString("avatar", p.avatar).apply()
    }

    private suspend fun load() {
        val p = api.posts(); val s = api.stories()
        posts.clear(); posts.addAll(p); stories.clear(); stories.addAll(s)
        loadAds()
    }

    /** Sponsored (boosted) posts shown between normal posts. A failure here never breaks the feed. */
    private suspend fun loadAds() {
        try { val a = api.ads(); ads.clear(); ads.addAll(a) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { ads.clear() }
    }

    fun adSeen(id: Int) {
        if (!seenAds.add(id)) return
        viewModelScope.launch {
            try { api.adSeen(id) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { }
        }
    }

    fun auth(register: Boolean, user: String, pw: String) = safe {
        busy = true
        try {
            val (token, profile) = api.auth(if (register) "/api/register" else "/api/login", user.trim(), pw)
            api.token = token
            prefs.edit().putString("token", token).apply()
            saveMe(profile); load()
        } finally { busy = false }
    }

    fun logout() {
        val oldToken = api.token; val pushToken = prefs.getString("push_token", null)
        if (oldToken != null && pushToken != null) viewModelScope.launch {   // stop pushes for this account on this phone
            try { Api(SERVER_URL, oldToken).removeDevice(pushToken) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { }
        }
        prefs.edit().remove("push_token").remove("notified_total").apply()
        unreadActivity = 0; pendingChat = null; pendingUser = null
        api.token = null; me = null; posts.clear(); stories.clear(); forYou.clear(); followingPosts.clear(); ads.clear(); seenAds.clear(); viewedPosts.clear(); viewQueue.clear(); feedMode = 0; sharedPost = null; unreadMessages = 0
        prefs.edit().remove("token").apply()
    }

    fun refresh() = safe {
        refreshing = true
        try {
            saveMe(api.me()); load()
            syncPlay()
            if (me?.vip != true && feedMode == 1) feedMode = 0
            if (feedMode == 1) loadForYou() else if (feedMode == 2) loadFollowing()
        } finally { refreshing = false }
    }

    private fun onPlayPurchases(list: List<Purchase>) = safe {
        busy = true
        try {
            for (p in list) {
                if (p.purchaseState == Purchase.PurchaseState.PURCHASED) { saveMe(api.verifyGoogle(p.purchaseToken)); toast("VIP activated ⭐") }
                else if (p.purchaseState == Purchase.PurchaseState.PENDING) toast("Payment pending. VIP starts when it completes.")
            }
            load()
        } finally { busy = false }
    }

    /** Re-verifies Play subscriptions with the server (new purchases, renewals, cancellations, refunds). */
    private suspend fun syncPlay() {
        if (!USE_PLAY_BILLING) return
        try {
            for (p in play.owned()) if (p.purchaseState == Purchase.PurchaseState.PURCHASED) saveMe(api.verifyGoogle(p.purchaseToken))
            saveMe(api.syncGoogle())
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { /* Play not available on this device: ignore */ }
    }

    private suspend fun loadForYou() {
        try {
            val (list, personal) = api.forYou()
            forYou.clear(); forYou.addAll(list); forYouPersonal = personal
        } catch (e: ApiException) { if (e.code == 403) feedMode = 0; throw e }
    }
    fun setFollowingPrivacy(mode: String) = safe { saveMe(api.setFollowingPrivacy(mode)) }
    /** Opens the post from a shared link once the user is logged in. */
    fun openPendingPost() {
        val id = pendingPostId ?: return
        pendingPostId = null
        safe { sharedPost = api.post(id) }
    }
    fun openForYou() { feedMode = 1; safe { loadForYou() } }
    private suspend fun loadFollowing() { val l = api.following(); followingPosts.clear(); followingPosts.addAll(l) }
    fun openFollowing() { feedMode = 2; safe { loadFollowing() } }

    fun toggleLike(p: Post) {
        val wasLiked = p.liked; val wasCount = p.likes
        p.liked = !wasLiked; p.likes = wasCount + if (wasLiked) -1 else 1
        safe {
            try { val (l, n) = api.like(p.id); p.liked = l; p.likes = n }
            catch (e: Exception) { p.liked = wasLiked; p.likes = wasCount; throw e }
        }
    }

    fun comment(p: Post, text: String, added: (Comment) -> Unit) = safe {
        val c = api.addComment(p.id, text); p.commentCount++; added(c)
    }

    fun deletePost(p: Post) = safe { api.deletePost(p.id); posts.remove(p) }
    fun editCaption(p: Post, text: String) = safe { api.editPost(p.id, text); p.caption = text }

    fun shareVideo(uri: Uri, caption: String, done: () -> Unit) = safe {
        busy = true
        var file: File? = null
        try {
            val durationMs = withContext(Dispatchers.IO) { videoDurationMs(getApplication<Application>(), uri) }
            file = transcodeVideo(getApplication<Application>(), uri, durationMs)
            val bytes = withContext(Dispatchers.IO) { file!!.readBytes() }
            if (bytes.size > MAX_VIDEO_UPLOAD_BYTES) throw IllegalStateException("This video is still too large after compression. Choose a shorter clip.")
            posts.add(0, api.createVideoPost(bytes, caption))
            done()
        } finally { busy = false; file?.delete() }
    }

    fun share(uri: Uri, caption: String, asStory: Boolean, done: () -> Unit) = safe {
        busy = true
        try {
            val bytes = withContext(Dispatchers.IO) { readJpeg(getApplication<Application>(), uri) }
            if (asStory) { api.createStory(bytes); val s = api.stories(); stories.clear(); stories.addAll(s) }
            else posts.add(0, api.createPost(bytes, caption))
            done()
        } finally { busy = false }
    }


    fun setEmail(email: String, ok: () -> Unit) = safe {
        busy = true
        try { saveMe(api.setEmail(email)); toast("Verification code sent"); ok() } finally { busy = false }
    }
    fun resendEmailCode() = safe { api.sendEmailCode(); toast("Code sent") }
    fun verifyEmail(code: String, ok: () -> Unit) = safe {
        busy = true
        try { saveMe(api.verifyEmail(code)); toast("Email verified ✓"); ok() } finally { busy = false }
    }
    fun removeEmail(ok: () -> Unit) = safe { saveMe(api.removeEmail()); ok() }
    fun forgotPassword(email: String, ok: () -> Unit) = safe {
        busy = true
        try { api.forgotPassword(email); toast("If that email is registered, a code was sent"); ok() } finally { busy = false }
    }
    fun resetPassword(email: String, code: String, pw: String, ok: () -> Unit) = safe {
        busy = true
        try { api.resetPassword(email, code, pw); toast("Password changed. Please log in."); ok() } finally { busy = false }
    }

    fun follow(name: String, on: Boolean, done: (Int) -> Unit) = safe {
        busy = true
        try { val n = api.follow(name, on); saveMe(api.me()); done(n) } finally { busy = false }
    }

    fun startCheckout(plan: String, open: (String) -> Unit) = safe {
        busy = true
        try { open(api.checkout(plan)) } finally { busy = false }
    }
    fun openPortal(open: (String) -> Unit) = safe { open(api.portal()) }

    fun saveProfile(username: String, bio: String, newAvatar: Uri?, removeAvatar: Boolean) = safe {
        busy = true
        try {
            var p = api.updateProfile(username, bio)
            if (newAvatar != null) {
                val bytes = withContext(Dispatchers.IO) { readJpeg(getApplication<Application>(), newAvatar, 512) }
                p = api.setAvatar(bytes)
            } else if (removeAvatar) p = api.removeAvatar()
            saveMe(p); load()
        } finally { busy = false }
    }
}

// ---------- Activity ----------
class MainActivity : ComponentActivity() {
    private val vm: AppVm by viewModels()
    private var firstResume = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 26)
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("viora_activity", "Messages and activity", NotificationManager.IMPORTANCE_DEFAULT))
        setContent { MaterialTheme { Surface { App() } } }
        scheduleNotifyWorker(applicationContext)
        if (savedInstanceState == null) handleLink(intent)
    }

    override fun onStart() { super.onStart(); AppState.foreground = true }
    override fun onStop() { super.onStop(); AppState.foreground = false }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    /** viora://p/123 or https://<server>/p/123 -> remember post 123 and open it. */
    private fun handleLink(i: Intent?) {
        if (i == null) return
        // tapped a push notification
        when (i.getStringExtra("viora_type")) {
            "message" -> i.getStringExtra("viora_user")?.let { vm.pendingChat = it }
            "follow" -> i.getStringExtra("viora_user")?.let { vm.pendingUser = it }
            "like", "comment" -> i.getStringExtra("viora_post")?.toIntOrNull()?.let { vm.pendingPostId = it }
        }
        val id = i.data?.lastPathSegment?.toIntOrNull() ?: return
        vm.pendingPostId = id
    }

    // Coming back from the payment page: reload the profile so VIP shows up.
    override fun onResume() {
        super.onResume()
        if (firstResume) firstResume = false else if (vm.me != null) vm.refresh()
    }
}

@Composable
fun App(vm: AppVm = viewModel()) {
    if (vm.me == null) { Login(vm); return }
    var tab by remember { mutableIntStateOf(0) }
    LaunchedEffect(vm.pendingPostId) { if (vm.pendingPostId != null) vm.openPendingPost() }
    LaunchedEffect(vm.me?.username) { while (true) { vm.refreshUnread(); delay(30_000) } }
    LaunchedEffect(vm.pendingChat) { if (vm.pendingChat != null) tab = 3 }
    val ctx = LocalContext.current
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.registerPush() }
    LaunchedEffect(vm.me?.username) {   // ask once after login (Android 13+), then register this phone for pushes
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else vm.registerPush()
    }
    Scaffold(bottomBar = {
        NavigationBar {
            NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Filled.Home, "Home") })
            NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Filled.Search, "Search") })
            NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Filled.AddBox, "New post") })
            NavigationBarItem(tab == 3, { tab = 3 }, {
                val unread = vm.unreadMessages + vm.unreadActivity
                BadgedBox(badge = {
                    if (unread > 0) Badge { Text(if (unread > 9) "9+" else unread.toString()) }
                }) { Icon(Icons.Filled.Email, "Messages") }
            })
            NavigationBarItem(tab == 4, { tab = 4 }, { Icon(Icons.Filled.Person, "Profile") })
        }
    }) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> Feed(vm)
                1 -> SearchScreen(vm)
                2 -> NewPost(vm) { tab = 0 }
                3 -> MessagesScreen(vm)
                else -> ProfileScreen(vm)
            }
        }
    }
    vm.sharedPost?.let { PostDetail(vm, it) { vm.sharedPost = null } }
    vm.pendingUser?.let { UserScreen(vm, it) { vm.pendingUser = null } }
}

// ---------- Login ----------
@Composable
fun Login(vm: AppVm) {
    var user by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var resetOpen by remember { mutableStateOf(false) }
    if (resetOpen) ResetDialog(vm) { resetOpen = false }
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Viora", fontSize = 42.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            pw, { pw = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        Button({ vm.auth(false, user, pw) }, enabled = !vm.busy && user.isNotBlank() && pw.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(if (vm.busy) "Please wait…" else "Log in")
        }
        TextButton({ vm.auth(true, user, pw) }, enabled = !vm.busy && user.isNotBlank() && pw.isNotEmpty()) { Text("Create account") }
        TextButton({ resetOpen = true }) { Text("Forgot password?") }
    }
}


@Composable
fun ResetDialog(vm: AppVm, onClose: () -> Unit) {
    var stage by remember { mutableIntStateOf(0) }
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Reset password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (stage == 0) {
                    Text("Enter the verified email on your account and we'll send you a code.", color = Color.Gray, fontSize = 13.sp)
                    OutlinedTextField(email, { email = it.trim() }, label = { Text("Email") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                } else {
                    Text("Enter the 6-digit code sent to $email and choose a new password.", color = Color.Gray, fontSize = 13.sp)
                    OutlinedTextField(code, { if (it.length <= 6 && it.all(Char::isDigit)) code = it }, label = { Text("Code") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(pw, { pw = it }, label = { Text("New password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    TextButton({ stage = 0 }) { Text("Use a different email") }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (stage == 0) vm.forgotPassword(email) { stage = 1 } else vm.resetPassword(email, code, pw, onClose) },
                enabled = !vm.busy && (if (stage == 0) email.contains("@") else code.length == 6 && pw.length >= 6)
            ) { Text(if (stage == 0) "Send code" else "Reset password") }
        },
        dismissButton = { TextButton(onClose) { Text("Cancel") } }
    )
}

@Composable
fun EmailDialog(vm: AppVm, onClose: () -> Unit) {
    val me = vm.me ?: return
    var stage by remember { mutableIntStateOf(if (me.email != null && !me.emailVerified) 1 else 0) }
    var email by remember { mutableStateOf(me.email ?: "") }
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (stage == 0) "Your email" else "Verify your email") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (stage == 0) {
                    Text("Used to verify your account and to reset your password if you forget it.", color = Color.Gray, fontSize = 13.sp)
                    OutlinedTextField(email, { email = it.trim() }, label = { Text("Email") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                } else {
                    Text("Enter the 6-digit code we sent to ${me.email ?: email}.", color = Color.Gray, fontSize = 13.sp)
                    OutlinedTextField(code, { if (it.length <= 6 && it.all(Char::isDigit)) code = it }, label = { Text("Code") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    Row {
                        TextButton({ vm.resendEmailCode() }) { Text("Resend code") }
                        TextButton({ stage = 0 }) { Text("Change email") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (stage == 0) vm.setEmail(email) { stage = 1; code = "" } else vm.verifyEmail(code, onClose) },
                enabled = !vm.busy && (if (stage == 0) email.contains("@") else code.length == 6)
            ) { Text(if (stage == 0) "Send code" else "Verify") }
        },
        dismissButton = {
            Row {
                if (stage == 0 && me.email != null) TextButton({ vm.removeEmail(onClose) }) { Text("Remove", color = Color.Red) }
                TextButton(onClose) { Text("Close") }
            }
        }
    )
}


/** Opens a web page in a Chrome Custom Tab (Chrome is where Google Pay shows up); falls back to any browser. */
fun openWeb(ctx: Context, url: String) {
    try { CustomTabsIntent.Builder().build().launchUrl(ctx, Uri.parse(url)) }
    catch (e: Exception) { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is android.content.ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

class PlanRow(val label: String, val onClick: () -> Unit)

fun sharePost(ctx: Context, p: Post) {
    val text = buildString {
        append(p.user).append(" on Viora")
        if (p.caption.isNotBlank()) append(": ").append(p.caption)
        append("\n").append("$SERVER_URL/p/${p.id}")
    }
    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
    ctx.startActivity(Intent.createChooser(send, "Share post"))
}

fun timeAgo(epochSeconds: Long): String {
    if (epochSeconds <= 0) return ""
    val s = System.currentTimeMillis() / 1000 - epochSeconds
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86400 -> "${s / 3600} h ago"
        s < 7 * 86400 -> "${s / 86400} d ago"
        else -> fmtDate(epochSeconds)
    }
}
fun fmtDate(epochSeconds: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochSeconds * 1000))

@Composable
fun VipDialog(vm: AppVm, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var rows by remember { mutableStateOf<List<PlanRow>?>(null) }
    LaunchedEffect(Unit) {
        try {
            rows = if (USE_PLAY_BILLING) {
                vm.play.plans().map { pl ->
                    PlanRow("${pl.name} · ${pl.price}") {
                        val act = ctx.findActivity()
                        if (act != null) { vm.play.buy(act, pl, vm.me?.accountId ?: ""); onClose() }
                    }
                }
            } else {
                vm.api.plans().map { plan ->
                    PlanRow(if (plan.price.isBlank()) plan.name else "${plan.name} · ${plan.price}") {
                        vm.startCheckout(plan.id) { url -> openWeb(ctx, url); onClose() }
                    }
                }
            }
        }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { vm.toast(e.message); rows = emptyList() }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("⭐ Viora VIP") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Get a personalised “For you” feed based on the posts you like.", color = Color.Gray, fontSize = 13.sp)
                val list = rows
                if (list == null) Text("Loading plans…")
                else if (list.isEmpty()) Text("Subscriptions aren't available yet.", color = Color.Gray)
                else list.forEach { row ->
                    Button(onClick = row.onClick, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text(row.label) }
                }
                Text(
                    if (USE_PLAY_BILLING) "Billed by Google Play. Cancel any time in Play Store subscriptions."
                    else "Secure payment by Stripe. Cancel any time with Manage.",
                    color = Color.Gray, fontSize = 12.sp
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClose) { Text("Not now") } }
    )
}

// ---------- Shared pieces ----------
@Composable
fun Avatar(name: String, url: String?, modifier: Modifier = Modifier) {
    if (url != null) AsyncImage(url, name, contentScale = ContentScale.Crop, modifier = modifier.clip(CircleShape))
    else Box(modifier.clip(CircleShape).background(Color(0xFF8134AF)), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
    }
}

// ---------- Stories ----------
@Composable
fun Stories(vm: AppVm, onOpen: (Story) -> Unit) {
    val me = vm.me!!
    val ring = Brush.sweepGradient(listOf(Color(0xFFF58529), Color(0xFFDD2A7B), Color(0xFF8134AF), Color(0xFFF58529)))
    val grey = Brush.linearGradient(listOf(Color.LightGray, Color.LightGray))
    val mine = vm.stories.firstOrNull { it.user == me.username }
    val others = vm.stories.filter { it.user != me.username }
    LazyRow(contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.clickable { if (mine != null) onOpen(mine) else Toast.makeText(vm.getApplication<Application>(), "Add a story from the + tab", Toast.LENGTH_SHORT).show() },
                horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(70.dp).border(3.dp, if (mine != null) ring else grey, CircleShape).padding(5.dp)) {
                    Avatar(me.username, me.avatar, Modifier.fillMaxSize())
                }
                Text("Your story", fontSize = 12.sp)
            }
        }
        items(others, key = { it.user }) { s ->
            Column(Modifier.clickable { onOpen(s) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(70.dp).border(3.dp, ring, CircleShape).padding(5.dp)) {
                    Avatar(s.user, s.avatar, Modifier.fillMaxSize())
                }
                Text(s.user, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun StoryViewer(story: Story, onClose: () -> Unit) {
    val images = story.images
    var idx by remember { mutableIntStateOf(0) }
    val progress = remember(idx) { Animatable(0f) }
    LaunchedEffect(idx) {
        progress.animateTo(1f, tween(4000, easing = LinearEasing))
        if (idx < images.lastIndex) idx++ else onClose()
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(images[idx], null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight().pointerInput(idx) { detectTapGestures { if (idx > 0) idx-- } })
                Box(Modifier.weight(1f).fillMaxHeight().pointerInput(idx) {
                    detectTapGestures { if (idx < images.lastIndex) idx++ else onClose() }
                })
            }
            Column(Modifier.padding(top = 28.dp, start = 8.dp, end = 8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    images.indices.forEach { i ->
                        LinearProgressIndicator(
                            progress = { if (i < idx) 1f else if (i == idx) progress.value else 0f },
                            modifier = Modifier.weight(1f).height(3.dp),
                            color = Color.White, trackColor = Color.White.copy(alpha = 0.3f)
                        )
                    }
                }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(story.user, story.avatar, Modifier.size(32.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(story.user, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClose) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
                }
            }
        }
    }
}


const val MAX_VIDEO_MINUTES = 15
const val MAX_VIDEO_UPLOAD_BYTES = 50 * 1024 * 1024

/** Length of a video in milliseconds, or 0 if it can't be read. */
fun videoDurationMs(ctx: Context, uri: Uri): Long = try {
    val r = MediaMetadataRetriever()
    r.setDataSource(ctx, uri)
    val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    r.release()
    d
} catch (e: Exception) { 0L }

/**
 * Trims to the first 15 minutes and re-encodes to H.264. The longer the video, the lower the bitrate and resolution,
 * so the file stays under the server's 50 MB limit (720p at 2 Mbit/s for short clips, 360p for 15 minutes).
 * Must be called on the main thread.
 */
@OptIn(UnstableApi::class)
suspend fun transcodeVideo(ctx: Context, uri: Uri, durationMs: Long): File = suspendCancellableCoroutine { cont ->
    val out = File(ctx.cacheDir, "upload_${System.currentTimeMillis()}.mp4")
    val maxMs = MAX_VIDEO_MINUTES * 60_000L
    val seconds = (if (durationMs > 0) minOf(durationMs, maxMs) else maxMs) / 1000.0
    val budgetBits = 40.0 * 1024 * 1024 * 8   // aim well under the 50 MB cap: encoders often overshoot their target
    val videoBps = (budgetBits / seconds - 128_000).coerceIn(150_000.0, 2_000_000.0).toInt()
    val height = when { videoBps >= 1_200_000 -> 720; videoBps >= 500_000 -> 480; else -> 360 }
    val item = MediaItem.Builder().setUri(uri)
        .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(maxMs).build()).build()
    val edited = EditedMediaItem.Builder(item)
        .setEffects(Effects(emptyList(), listOf(Presentation.createForHeight(height)))).build()
    val transformer = Transformer.Builder(ctx)
        .setVideoMimeType(MimeTypes.VIDEO_H264)
        .setAudioMimeType(MimeTypes.AUDIO_AAC)
        .setEncoderFactory(
            DefaultEncoderFactory.Builder(ctx)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(videoBps).build()).build()
        )
        .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) { cont.resume(out) }
            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                cont.resumeWithException(IllegalStateException("Couldn't prepare this video (${exportException.errorCodeName})"))
            }
        }).build()
    transformer.start(edited, out.absolutePath)
    cont.invokeOnCancellation { transformer.cancel() }
}

fun videoPoster(ctx: Context, uri: Uri): Bitmap? = try {
    val r = MediaMetadataRetriever()
    r.setDataSource(ctx, uri)
    val frame = r.getFrameAtTime(0)
    r.release()
    frame
} catch (e: Exception) { null }

@Composable
fun VideoPlayerDialog(url: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val exo = remember(url) {
        ExoPlayer.Builder(ctx).build().apply { setMediaItem(MediaItem.fromUri(url)); prepare(); playWhenReady = true }
    }
    DisposableEffect(exo) { onDispose { exo.release() } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = { c -> PlayerView(c).apply { this.player = exo } }, modifier = Modifier.fillMaxSize())
            IconButton(onClose, Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = 8.dp)) {
                Icon(Icons.Filled.Close, "Close", tint = Color.White)
            }
        }
    }
}

// ---------- Photo viewer (pinch to zoom) ----------
@Composable
fun PhotoViewer(image: Any, onClose: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(
                image, null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    }
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
            )
            IconButton(onClose, Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = 8.dp)) {
                Icon(Icons.Filled.Close, "Close", tint = Color.White)
            }
        }
    }
}

// ---------- Feed ----------
class PostUi {
    var commentsFor by mutableStateOf<Post?>(null)
    var editing by mutableStateOf<Post?>(null)
    var deleting by mutableStateOf<Post?>(null)
    var photo by mutableStateOf<String?>(null)
    var video by mutableStateOf<String?>(null)
    fun open(p: Post) { if (p.isVideo) video = p.image else photo = p.image }
    var userView by mutableStateOf<String?>(null)
    val removed = mutableStateListOf<Int>()
}

@Composable
fun PostOverlays(vm: AppVm, ui: PostUi) {
    ui.commentsFor?.let { CommentsSheet(it, vm, { n -> ui.commentsFor = null; ui.userView = n }) { ui.commentsFor = null } }
    ui.photo?.let { PhotoViewer(it) { ui.photo = null } }
    ui.video?.let { VideoPlayerDialog(it) { ui.video = null } }
    ui.userView?.let { UserScreen(vm, it) { ui.userView = null } }
    ui.editing?.let { p ->
        var text by remember(p) { mutableStateOf(p.caption) }
        AlertDialog(
            onDismissRequest = { ui.editing = null },
            title = { Text("Edit caption") },
            text = { OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton({ vm.editCaption(p, text.trim()); ui.editing = null }) { Text("Save") } },
            dismissButton = { TextButton({ ui.editing = null }) { Text("Cancel") } }
        )
    }
    ui.deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { ui.deleting = null },
            title = { Text("Delete post?") },
            text = { Text("This can't be undone.") },
            confirmButton = { TextButton({ vm.deletePost(p); ui.removed.add(p.id); ui.deleting = null }) { Text("Delete", color = Color.Red) } },
            dismissButton = { TextButton({ ui.deleting = null }) { Text("Cancel") } }
        )
    }
}

class FeedRow(val post: Post, val ad: Boolean, val key: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Feed(vm: AppVm) {
    val ui = remember { PostUi() }
    var story by remember { mutableStateOf<Story?>(null) }

    PullToRefreshBox(isRefreshing = vm.refreshing, onRefresh = { vm.refresh() }) {
        LazyColumn(Modifier.fillMaxSize()) {
            item { Stories(vm) { story = it } }
            item {
                Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(vm.feedMode == 0, { vm.feedMode = 0 }, label = { Text("Latest") })
                    FilterChip(vm.feedMode == 2, { vm.openFollowing() }, label = { Text("Following") })
                    if (vm.me?.vip == true) FilterChip(vm.feedMode == 1, { vm.openForYou() }, label = { Text("⭐ For you") })
                }
                if (vm.feedMode == 1 && !vm.forYouPersonal)
                    Text("Like a few posts and this feed will learn your taste. Showing popular posts for now.",
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = Color.Gray, fontSize = 13.sp)
            }
            val list = when {
                vm.feedMode == 1 && vm.me?.vip == true -> vm.forYou
                vm.feedMode == 2 -> vm.followingPosts
                else -> vm.posts
            }
            if (list.isEmpty() && !vm.refreshing)
                item { Text(when (vm.feedMode) { 1 -> "Nothing to recommend yet."; 2 -> "Nothing here yet. Follow people from the search tab to see their posts."; else -> "No posts yet. Share the first one from the + tab!" }, Modifier.padding(24.dp), color = Color.Gray) }
            val rows = ArrayList<FeedRow>()
            val showAds = vm.ads.isNotEmpty() && vm.me?.vip != true
            list.forEachIndexed { i, p ->
                rows.add(FeedRow(p, false, "p${p.id}"))
                if (showAds && (i + 1) % 5 == 0) {
                    val slot = (i + 1) / 5 - 1
                    val ad = vm.ads[slot % vm.ads.size]
                    rows.add(FeedRow(ad, true, "ad$slot-${ad.id}"))
                }
            }
            items(rows, key = { it.key }) { r ->
                if (r.ad) LaunchedEffect(r.key) { vm.adSeen(r.post.id) }
                PostCard(r.post, vm, onComments = { ui.commentsFor = r.post }, onPhoto = { ui.open(r.post) },
                    onEdit = { ui.editing = r.post }, onDelete = { ui.deleting = r.post }, onUser = { ui.userView = it },
                    sponsored = r.ad)
            }
        }
    }
    story?.let { StoryViewer(it) { story = null } }
    PostOverlays(vm, ui)
}


// ---------- A single post, full view ----------
@Composable
fun PostDetail(vm: AppVm, post: Post, onDeleted: () -> Unit = {}, onClose: () -> Unit) {
    val ui = remember { PostUi() }
    LaunchedEffect(ui.removed.size) { if (post.id in ui.removed) { onDeleted(); onClose() } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(top = 24.dp)) {
                Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClose) { Icon(Icons.Filled.Close, "Close") }
                    Text("Post", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PostCard(
                        post, vm, onComments = { ui.commentsFor = post }, onPhoto = { ui.open(post) },
                        onEdit = { ui.editing = post }, onDelete = { ui.deleting = post }, onUser = { onClose() }
                    )
                }
            }
            PostOverlays(vm, ui)
        }
    }
}

// ---------- Search ----------
@Composable
fun SearchScreen(vm: AppVm) {
    val ui = remember { PostUi() }
    var query by remember { mutableStateOf("") }
    var users by remember { mutableStateOf<List<Profile>>(emptyList()) }
    var posts by remember { mutableStateOf<List<Post>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf("") }

    LaunchedEffect(query) {
        val term = query.trim()
        if (term.isEmpty()) { users = emptyList(); posts = emptyList(); searched = ""; return@LaunchedEffect }
        delay(350)
        searching = true
        try {
            val (u, p) = vm.api.search(term)
            users = u; posts = p; searched = term
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { vm.toast(e.message) }
        finally { searching = false }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            query, { query = it }, placeholder = { Text("Search people and posts") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Filled.Close, "Clear") } },
            singleLine = true, shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().padding(12.dp)
        )
        LazyColumn(Modifier.weight(1f)) {
            if (searching && users.isEmpty() && posts.isEmpty())
                item { Text("Searching…", Modifier.padding(16.dp), color = Color.Gray) }
            if (!searching && searched.isNotEmpty() && users.isEmpty() && posts.isEmpty())
                item { Text("Nothing found for “$searched”", Modifier.padding(16.dp), color = Color.Gray) }
            if (users.isNotEmpty()) {
                item { Text("People", fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                items(users, key = { "u" + it.username }) { u ->
                    Row(
                        Modifier.fillMaxWidth().clickable { ui.userView = u.username }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(u.username, u.avatar, Modifier.size(44.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(u.username, fontWeight = FontWeight.Bold)
                            if (u.bio.isNotBlank()) Text(u.bio, color = Color.Gray, fontSize = 13.sp, maxLines = 1)
                        }
                    }
                }
            }
            if (posts.isNotEmpty()) {
                item { Text("Posts", fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                items(posts.filter { it.id !in ui.removed }, key = { "p" + it.id }) { p ->
                    PostCard(p, vm, onComments = { ui.commentsFor = p }, onPhoto = { ui.open(p) },
                        onEdit = { ui.editing = p }, onDelete = { ui.deleting = p }, onUser = { ui.userView = it })
                }
            }
        }
    }
    PostOverlays(vm, ui)
}

@Composable
fun PostCard(
    p: Post, vm: AppVm, onComments: () -> Unit, onPhoto: () -> Unit,
    onEdit: () -> Unit, onDelete: () -> Unit, onUser: (String) -> Unit, sponsored: Boolean = false
) {
    var menu by remember { mutableStateOf(false) }
    var boostOpen by remember { mutableStateOf(false) }
    LaunchedEffect(p.id) { vm.postSeen(p) }
    Column(Modifier.padding(bottom = 12.dp)) {
        Row(Modifier.padding(start = 10.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable { onUser(p.user) }, verticalAlignment = Alignment.CenterVertically) {
                Avatar(p.user, p.avatar, Modifier.size(32.dp))
                Spacer(Modifier.width(8.dp))
                Text(p.user, fontWeight = FontWeight.Bold)
                if (sponsored) Text("  Sponsored", color = Color.Gray, fontSize = 12.sp)
            }
            if (p.user == vm.me?.username) {
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("🚀 Boost post") }, onClick = { menu = false; boostOpen = true })
                        DropdownMenuItem(text = { Text("Edit caption") }, onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text("Delete", color = Color.Red) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            AsyncImage(
                model = p.preview, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures(onTap = { onPhoto() }, onDoubleTap = { if (!p.liked) vm.toggleLike(p) })
                }
            )
            if (p.isVideo) Icon(
                Icons.Filled.PlayArrow, "Play video", tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(64.dp).background(Color.Black.copy(alpha = 0.4f), CircleShape)
            )
        }
        Row(Modifier.padding(horizontal = 6.dp)) {
            IconButton({ vm.toggleLike(p) }) {
                Icon(
                    if (p.liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Like",
                    tint = if (p.liked) Color.Red else LocalContentColor.current
                )
            }
            IconButton(onComments) { Icon(Icons.Outlined.ChatBubbleOutline, "Comments") }
            val ctx = LocalContext.current
            IconButton({ sharePost(ctx, p) }) { Icon(Icons.Filled.Share, "Share") }
        }
        Text("${p.likes} likes", fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp))
        if (p.caption.isNotBlank())
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(p.user + " ") }
                    append(p.caption)
                },
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        Text(
            if (p.commentCount == 0) "Add a comment…" else "View all ${p.commentCount} comments",
            color = Color.Gray,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp).clickable { onComments() }
        )
        val meta = listOfNotNull(
            "${p.views} view${if (p.views == 1) "" else "s"}",
            if (p.created > 0) timeAgo(p.created).uppercase() else null
        ).joinToString("  ·  ")
        Text(meta, color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp))
        if (boostOpen) BoostDialog(vm, p) { boostOpen = false }
    }
}



@Composable
fun BoostDialog(vm: AppVm, p: Post, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<BoostInfo?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(p.id) {
        try { info = vm.api.boostInfo(p.id) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { vm.toast(e.message); onClose() }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Boost this post") },
        text = {
            Column {
                val i = info
                if (i == null) Text("Loading…", color = Color.Gray)
                else {
                    Text("A boosted post is shown as “Sponsored” in other people's feeds.", fontSize = 13.sp, color = Color.Gray)
                    val until = i.until
                    if (until != null)
                        Text((if (i.active) "Active until " else "Ended ") + fmtDate(until) + "  ·  ${i.views} views",
                            Modifier.padding(top = 8.dp), fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    if (i.plans.isEmpty()) Text("Boosting isn't available right now.", color = Color.Gray)
                    for (plan in i.plans) {
                        Button(
                            onClick = {
                                busy = true
                                scope.launch {
                                    try { openWeb(ctx, vm.api.boostCheckout(p.id, plan.days)); onClose() }
                                    catch (e: CancellationException) { throw e }
                                    catch (e: Exception) { vm.toast(e.message) }
                                    busy = false
                                }
                            },
                            enabled = !busy, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        ) { Text("${plan.days} day${if (plan.days == 1) "" else "s"}  ·  ${plan.price}") }
                    }
                    if (i.active) Text("Buying again extends the boost.", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(top = 4.dp))
                }
            }
        },
        confirmButton = { TextButton(onClose) { Text("Close") } }
    )
}

@Composable
fun FollowCounts(followers: Int, following: Int, onFollowers: () -> Unit, onFollowing: () -> Unit) {
    Row {
        Text("$followers followers", fontWeight = FontWeight.Medium, modifier = Modifier.clickable { onFollowers() })
        Text("  ·  ")
        Text("$following following", fontWeight = FontWeight.Medium, modifier = Modifier.clickable { onFollowing() })
    }
}

@Composable
fun FollowListDialog(vm: AppVm, username: String, kind: String, onClose: () -> Unit) {
    var users by remember { mutableStateOf<List<Profile>?>(null) }
    var userView by remember { mutableStateOf<String?>(null) }
    var denied by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(username, kind) {
        try { users = vm.api.followList(username, kind) }
        catch (e: CancellationException) { throw e }
        catch (e: ApiException) { if (e.code == 403) denied = e.message else vm.toast(e.message); users = emptyList() }
        catch (e: Exception) { vm.toast(e.message); users = emptyList() }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(top = 24.dp)) {
                Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClose) { Icon(Icons.Filled.Close, "Close") }
                    Text(if (kind == "followers") "Followers" else "Following", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                val list = users
                if (list == null) Text("Loading…", Modifier.padding(16.dp), color = Color.Gray)
                else if (denied != null) Text("🔒 $denied", Modifier.padding(16.dp), color = Color.Gray)
                else if (list.isEmpty()) Text(if (kind == "followers") "No followers yet." else "Not following anyone yet.", Modifier.padding(16.dp), color = Color.Gray)
                else LazyColumn {
                    items(list, key = { it.username }) { u ->
                        Row(
                            Modifier.fillMaxWidth().clickable { userView = u.username }.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Avatar(u.username, u.avatar, Modifier.size(44.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(u.username, fontWeight = FontWeight.Bold)
                                if (u.bio.isNotBlank()) Text(u.bio, color = Color.Gray, fontSize = 13.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
            userView?.let { UserScreen(vm, it) { userView = null } }
        }
    }
}

// ---------- Another user's profile ----------
@Composable
fun UserScreen(vm: AppVm, username: String, onClose: () -> Unit) {
    var profile by remember { mutableStateOf<Profile?>(null) }
    val posts = remember { mutableStateListOf<Post>() }
    var loading by remember { mutableStateOf(true) }
    var openPost by remember { mutableStateOf<Post?>(null) }
    var followers by remember { mutableIntStateOf(0) }
    var following by remember { mutableIntStateOf(0) }
    var iFollow by remember { mutableStateOf(false) }
    var chatOpen by remember { mutableStateOf(false) }
    var listKind by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(username) {
        try {
            val (p, list) = vm.api.user(username)
            profile = p; posts.clear(); posts.addAll(list)
            followers = p.followers; following = p.following; iFollow = p.isFollowing
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { vm.toast(e.message) }
        loading = false
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(top = 24.dp)) {
                Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClose) { Icon(Icons.Filled.Close, "Close") }
                    Text(username, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                val p = profile
                if (p == null) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(if (loading) "Loading…" else "Couldn't load this profile", color = Color.Gray)
                    }
                } else {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(p.username, p.avatar, Modifier.size(80.dp))
                        Spacer(Modifier.width(24.dp))
                        Column {
                            Text(p.username, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text("${posts.size} posts  ·  ${posts.sumOf { it.likes }} likes", color = Color.Gray)
                            FollowCounts(followers, following, { listKind = "followers" }, { listKind = "following" })
                        }
                    }
                    if (p.bio.isNotBlank()) Text(p.bio, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    if (p.username != vm.me?.username) {
                        if (iFollow)
                            OutlinedButton(
                                { vm.follow(p.username, false) { n -> iFollow = false; followers = n } },
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), enabled = !vm.busy
                            ) { Text("Following ✓") }
                        else
                            Button(
                                { vm.follow(p.username, true) { n -> iFollow = true; followers = n } },
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), enabled = !vm.busy
                            ) { Text("Follow") }
                        OutlinedButton({ chatOpen = true }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) { Text("Message") }
                    }
                    HorizontalDivider()
                    if (posts.isEmpty()) Text("No posts yet.", Modifier.padding(16.dp), color = Color.Gray)
                    LazyVerticalGrid(GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(posts, key = { it.id }) {
                            Box(Modifier.aspectRatio(1f).clickable { openPost = it }) {
                                AsyncImage(it.preview, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                if (it.isVideo) Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp))
                            }
                        }
                    }
                }
            }
            openPost?.let { p -> PostDetail(vm, p, onDeleted = { posts.remove(p) }) { openPost = null } }
            listKind?.let { FollowListDialog(vm, username, it) { listKind = null } }
            if (chatOpen) ChatScreen(vm, username) { chatOpen = false }
        }
    }
}

// ---------- Messages ----------
@Composable
fun MessagesScreen(vm: AppVm) {
    var convos by remember { mutableStateOf<List<Convo>?>(null) }
    var chatWith by remember { mutableStateOf<String?>(null) }
    var activityOpen by remember { mutableStateOf(false) }
    LaunchedEffect(vm.pendingChat) { vm.pendingChat?.let { chatWith = it; vm.pendingChat = null } }
    LaunchedEffect(chatWith) {
        if (chatWith == null) {   // load on entry and when a chat closes, then keep the list fresh
            while (true) {
                try { convos = vm.api.conversations(); vm.refreshUnread() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (convos == null) convos = emptyList() }
                delay(10_000)
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Messages", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton({ activityOpen = true }) {
                BadgedBox(badge = { if (vm.unreadActivity > 0) Badge { Text(if (vm.unreadActivity > 9) "9+" else vm.unreadActivity.toString()) } }) {
                    Icon(Icons.Filled.Notifications, "Activity")
                }
            }
        }
        val list = convos
        when {
            list == null -> Text("Loading…", Modifier.padding(16.dp), color = Color.Gray)
            list.isEmpty() -> Text("No messages yet. Open someone's profile and tap Message to start a chat.", Modifier.padding(16.dp), color = Color.Gray)
            else -> LazyColumn {
                items(list, key = { it.user }) { c ->
                    Row(
                        Modifier.fillMaxWidth().clickable { chatWith = c.user }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(c.user, c.avatar, Modifier.size(48.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.user, fontWeight = if (c.unread > 0) FontWeight.Bold else FontWeight.Medium)
                            Text((if (c.mine) "You: " else "") + c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = Color.Gray, fontWeight = if (c.unread > 0) FontWeight.Bold else FontWeight.Normal)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            if (c.created > 0) Text(timeAgo(c.created), fontSize = 11.sp, color = Color.Gray)
                            if (c.unread > 0) Badge { Text(c.unread.toString()) }
                        }
                    }
                }
            }
        }
    }
    chatWith?.let { ChatScreen(vm, it) { chatWith = null } }
    if (activityOpen) ActivityDialog(vm) { activityOpen = false }
}

@Composable
fun ActivityDialog(vm: AppVm, onClose: () -> Unit) {
    var acts by remember { mutableStateOf<List<ActivityItem>?>(null) }
    var userView by remember { mutableStateOf<String?>(null) }
    var openPost by remember { mutableStateOf<Post?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        try { acts = vm.api.activity(); vm.refreshUnread() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { vm.toast(e.message); acts = emptyList() }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(top = 24.dp)) {
                Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClose) { Icon(Icons.Filled.Close, "Close") }
                    Text("Activity", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                HorizontalDivider()
                val list = acts
                when {
                    list == null -> Text("Loading…", Modifier.padding(16.dp), color = Color.Gray)
                    list.isEmpty() -> Text("Nothing yet. Likes, comments and new followers show up here.", Modifier.padding(16.dp), color = Color.Gray)
                    else -> LazyColumn {
                        items(list, key = { it.id }) { a ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .background(if (a.isNew) Color(0x1A8134AF) else Color.Transparent)
                                    .clickable {
                                        val pid = a.postId
                                        if (pid == null) userView = a.user
                                        else scope.launch {
                                            try { openPost = vm.api.post(pid) }
                                            catch (e: CancellationException) { throw e }
                                            catch (e: Exception) { vm.toast(e.message) }
                                        }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Avatar(a.user, a.avatar, Modifier.size(44.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(a.user) }; append(" " + a.text) })
                                    if (a.created > 0) Text(timeAgo(a.created), fontSize = 11.sp, color = Color.Gray)
                                }
                            }
                        }
                    }
                }
            }
            userView?.let { UserScreen(vm, it) { userView = null } }
            openPost?.let { PostDetail(vm, it) { openPost = null } }
        }
    }
}

@Composable
fun ChatScreen(vm: AppVm, username: String, onClose: () -> Unit) {
    val msgs = remember { mutableStateListOf<Dm>() }
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var avatar by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(username) {   // fetch new messages every few seconds while the chat is open
        while (true) {
            try {
                val res = vm.api.thread(username, msgs.lastOrNull()?.id ?: 0)
                val fresh = res.messages
                avatar = res.avatar
                if (fresh.isNotEmpty()) msgs.addAll(fresh.filter { n -> msgs.none { it.id == n.id } })
                loaded = true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (!loaded) { vm.toast(e.message); loaded = true } }
            delay(3000)
        }
    }
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) listState.scrollToItem(msgs.size - 1) }
    DisposableEffect(Unit) { onDispose { vm.refreshUnread() } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {   // keeps the text box above the keyboard and the navigation bar
                Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClose) { Icon(Icons.Filled.Close, "Close") }
                    Avatar(username, avatar, Modifier.size(36.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(username, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                HorizontalDivider()
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(), state = listState,
                    contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (loaded && msgs.isEmpty()) item { Text("Say hi to $username 👋", color = Color.Gray) }
                    items(msgs, key = { it.id }) { m ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = if (m.mine) Alignment.CenterEnd else Alignment.CenterStart) {
                            Column(Modifier.widthIn(max = 300.dp), horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start) {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = if (m.mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(
                                        m.text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        color = if (m.mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (m.created > 0) Text(timeAgo(m.created), fontSize = 10.sp, color = Color.Gray)
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        text, { if (it.length <= 1000) text = it }, Modifier.weight(1f),
                        placeholder = { Text("Message…") }, maxLines = 4
                    )
                    IconButton(
                        onClick = {
                            val t = text.trim()
                            sending = true
                            scope.launch {
                                try {
                                    val m = vm.api.sendDm(username, t)
                                    if (msgs.none { it.id == m.id }) msgs.add(m)
                                    text = ""
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) { vm.toast(e.message) }
                                sending = false
                            }
                        },
                        enabled = text.isNotBlank() && !sending
                    ) { Icon(Icons.Filled.Send, "Send") }
                }
            }
        }
    }
}

// ---------- Comments ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsSheet(p: Post, vm: AppVm, onUser: (String) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    val comments = remember { mutableStateListOf<Comment>() }
    LaunchedEffect(p.id) {
        try { comments.addAll(vm.api.comments(p.id)) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { }
        loading = false
    }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(0.85f).imePadding()) {
            Text("Comments", fontWeight = FontWeight.Bold, fontSize = 16.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp))
            HorizontalDivider()
            if (comments.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(if (loading) "Loading…" else "No comments yet.\nStart the conversation.", color = Color.Gray)
                }
            } else {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(comments) { c ->
                        Row(Modifier.clickable { onUser(c.user) }) {
                            Avatar(c.user, c.avatar, Modifier.size(36.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(c.user, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    if (c.created > 0) Text("  " + timeAgo(c.created), color = Color.Gray, fontSize = 12.sp)
                                }
                                Text(c.text)
                            }
                        }
                    }
                }
            }
            HorizontalDivider()
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(vm.me!!.username, vm.me!!.avatar, Modifier.size(36.dp))
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    text, { text = it }, placeholder = { Text("Add a comment…") },
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(24.dp), singleLine = true
                )
                IconButton(
                    onClick = { val t = text.trim(); text = ""; vm.comment(p, t) { comments.add(it) } },
                    enabled = text.isNotBlank()
                ) { Icon(Icons.Filled.Send, "Send") }
            }
        }
    }
}

// ---------- New post / story ----------
@Composable
fun NewPost(vm: AppVm, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var uri by remember { mutableStateOf<Uri?>(null) }
    var caption by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri = it }
    val isVideo = uri?.let { ctx.contentResolver.getType(it)?.startsWith("video") == true } == true
    val poster by produceState<Bitmap?>(null, uri) {
        val u = uri
        value = if (u != null && isVideo) withContext(Dispatchers.IO) { videoPoster(ctx, u) } else null
    }
    val durationMs by produceState(0L, uri) {
        val u = uri
        value = if (u != null && isVideo) withContext(Dispatchers.IO) { videoDurationMs(ctx, u) } else 0L
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("New post", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Button({ picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }) {
            Text(if (uri == null) "Choose photo or video" else "Change")
        }
        if (isVideo) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Color.Black), contentAlignment = Alignment.Center) {
                poster?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(56.dp))
            }
            val mins = durationMs / 60_000
            val len = if (durationMs > 0) "  ·  ${mins}:${"%02d".format(durationMs / 1000 % 60)} long" else ""
            Text("Videos can be up to $MAX_VIDEO_MINUTES minutes$len. Longer ones are trimmed to the first $MAX_VIDEO_MINUTES minutes, " +
                "and long videos are compressed to a lower quality before upload.", color = Color.Gray, fontSize = 12.sp)
            if (durationMs > MAX_VIDEO_MINUTES * 60_000L)
                Text("⚠ Only the first $MAX_VIDEO_MINUTES minutes of this video will be posted.", color = Color(0xFFB26A00), fontSize = 13.sp)
        } else uri?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(1f)) }
        OutlinedTextField(caption, { caption = it }, label = { Text("Write a caption…") }, modifier = Modifier.fillMaxWidth())
        Button(
            onClick = {
                val reset = { uri = null; caption = ""; onDone() }
                if (isVideo) vm.shareVideo(uri!!, caption.trim()) { reset() } else vm.share(uri!!, caption.trim(), false) { reset() }
            },
            enabled = uri != null && !vm.busy, modifier = Modifier.fillMaxWidth()
        ) { Text(if (vm.busy) (if (isVideo) "Preparing video…" else "Uploading…") else "Share post") }
        OutlinedButton(
            onClick = { vm.share(uri!!, "", true) { uri = null; caption = ""; onDone() } },
            enabled = uri != null && !isVideo && !vm.busy, modifier = Modifier.fillMaxWidth()
        ) { Text("Add to your story (24h)") }
    }
}

// ---------- Profile ----------
@Composable
fun ProfileScreen(vm: AppVm) {
    val me = vm.me!!
    val mine = vm.posts.filter { it.user == me.username }
    var openPost by remember { mutableStateOf<Post?>(null) }
    var editing by remember { mutableStateOf(false) }
    var emailOpen by remember { mutableStateOf(false) }
    var vipOpen by remember { mutableStateOf(false) }
    var privacyOpen by remember { mutableStateOf(false) }
    var listKind by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    var draftName by remember { mutableStateOf("") }
    var draftBio by remember { mutableStateOf("") }
    var draftAvatar by remember { mutableStateOf<Uri?>(null) }
    var removing by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
        if (it != null) { draftAvatar = it; removing = false }
    }

    Column {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(me.username, me.avatar, Modifier.size(80.dp))
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Text(if (me.vip) "${me.username}  ⭐ VIP" else me.username, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("${mine.size} posts  ·  ${mine.sumOf { it.likes }} likes", color = Color.Gray)
                FollowCounts(me.followers, me.following, { listKind = "followers" }, { listKind = "following" })
            }
            TextButton({ vm.logout() }) { Text("Log out") }
        }
        if (me.bio.isNotBlank()) Text(me.bio, Modifier.padding(horizontal = 16.dp))
        val unverified = me.email != null && !me.emailVerified
        Text(
            when {
                me.email == null -> "＋ Add email (needed to reset your password)"
                unverified -> "${me.email} · not verified — tap to verify"
                else -> "${me.email} ✓ verified"
            },
            color = if (me.email == null || unverified) Color(0xFFD97706) else Color.Gray,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).clickable { emailOpen = true }
        )
        Text(
            "🔒 Who can see who you follow: " + when (me.followingPrivacy) { "followers" -> "Followers"; "me" -> "Only me"; else -> "Everyone" },
            color = Color.Gray, fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).clickable { privacyOpen = true }
        )
        if (me.vip) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (me.vipUntil != null) "⭐ VIP until ${fmtDate(me.vipUntil)}" else "⭐ VIP",
                    color = Color(0xFFB45309), fontSize = 13.sp, modifier = Modifier.weight(1f)
                )
                if (me.vipUntil != null)
                    TextButton({
                        if (USE_PLAY_BILLING)
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/account/subscriptions?sku=$PLAY_PRODUCT_ID&package=${ctx.packageName}")))
                        else vm.openPortal { url -> openWeb(ctx, url) }
                    }) { Text("Manage") }
            }
        } else {
            Button({ vipOpen = true }, Modifier.padding(horizontal = 16.dp)) { Text("⭐ Get VIP") }
        }
        OutlinedButton(
            onClick = { draftName = me.username; draftBio = me.bio; draftAvatar = null; removing = false; editing = true },
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) { Text("Edit profile") }
        HorizontalDivider()
        if (mine.isEmpty()) Text("Share your first photo from the + tab.", Modifier.padding(16.dp), color = Color.Gray)
        LazyVerticalGrid(GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(mine, key = { it.id }) {
                Box(Modifier.aspectRatio(1f).clickable { openPost = it }) {
                    AsyncImage(it.preview, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    if (it.isVideo) Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp))
                }
            }
        }
    }
    openPost?.let { PostDetail(vm, it) { openPost = null } }
    listKind?.let { FollowListDialog(vm, me.username, it) { listKind = null } }
    if (privacyOpen) {
        AlertDialog(
            onDismissRequest = { privacyOpen = false },
            title = { Text("Who can see who you follow?") },
            text = {
                Column {
                    for ((mode, label) in listOf("everyone" to "Everyone", "followers" to "Only my followers", "me" to "Only me")) {
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.setFollowingPrivacy(mode); privacyOpen = false },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(me.followingPrivacy == mode, { vm.setFollowingPrivacy(mode); privacyOpen = false })
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = { TextButton({ privacyOpen = false }) { Text("Close") } }
        )
    }
    if (emailOpen) EmailDialog(vm) { emailOpen = false }
    if (vipOpen) VipDialog(vm) { vipOpen = false }

    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Edit profile") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val previewUrl: Any? = draftAvatar ?: if (removing) null else me.avatar
                    val pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    if (previewUrl != null)
                        AsyncImage(previewUrl, "Profile photo", contentScale = ContentScale.Crop,
                            modifier = Modifier.size(96.dp).clip(CircleShape).clickable { pick() })
                    else Avatar(me.username, null, Modifier.size(96.dp).clickable { pick() })
                    Row {
                        TextButton(pick) { Text("Change photo") }
                        if (previewUrl != null) TextButton({ draftAvatar = null; removing = true }) { Text("Remove") }
                    }
                    OutlinedTextField(
                        draftName, { if (it.length <= 20) draftName = it.trim() },
                        label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        draftBio, { if (it.length <= 150) draftBio = it },
                        label = { Text("Bio") }, supportingText = { Text("${draftBio.length}/150") },
                        modifier = Modifier.fillMaxWidth(), minLines = 3
                    )
                }
            },
            confirmButton = {
                TextButton({ vm.saveProfile(draftName, draftBio.trim(), draftAvatar, removing); editing = false }) { Text("Save") }
            },
            dismissButton = { TextButton({ editing = false }) { Text("Cancel") } }
        )
    }
}
