package com.example.viora

import android.app.Application
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
        api.token = null; me = null; posts.clear(); stories.clear(); forYou.clear(); followingPosts.clear(); feedMode = 0
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
            file = transcodeVideo(getApplication<Application>(), uri)
            val bytes = withContext(Dispatchers.IO) { file!!.readBytes() }
            if (bytes.size > 20 * 1024 * 1024) throw IllegalStateException("This video is still too large after compression. Choose a shorter clip.")
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
        setContent { MaterialTheme { Surface { App() } } }
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
    Scaffold(bottomBar = {
        NavigationBar {
            NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Filled.Home, "Home") })
            NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Filled.Search, "Search") })
            NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Filled.AddBox, "New post") })
            NavigationBarItem(tab == 3, { tab = 3 }, { Icon(Icons.Filled.Person, "Profile") })
        }
    }) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> Feed(vm)
                1 -> SearchScreen(vm)
                2 -> NewPost(vm) { tab = 0 }
                else -> ProfileScreen(vm)
            }
        }
    }
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
        append("\n").append(p.image)
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


/** Trims to 30 s and re-encodes to 720p H.264 at about 2 Mbit/s so uploads stay small. Must be called on the main thread. */
@OptIn(UnstableApi::class)
suspend fun transcodeVideo(ctx: Context, uri: Uri): File = suspendCancellableCoroutine { cont ->
    val out = File(ctx.cacheDir, "upload_${System.currentTimeMillis()}.mp4")
    val item = MediaItem.Builder().setUri(uri)
        .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(30_000).build()).build()
    val edited = EditedMediaItem.Builder(item)
        .setEffects(Effects(emptyList(), listOf(Presentation.createForHeight(720)))).build()
    val transformer = Transformer.Builder(ctx)
        .setVideoMimeType(MimeTypes.VIDEO_H264)
        .setAudioMimeType(MimeTypes.AUDIO_AAC)
        .setEncoderFactory(
            DefaultEncoderFactory.Builder(ctx)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(2_000_000).build()).build()
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
            items(list, key = { it.id }) { p ->
                PostCard(p, vm, onComments = { ui.commentsFor = p }, onPhoto = { ui.open(p) },
                    onEdit = { ui.editing = p }, onDelete = { ui.deleting = p }, onUser = { ui.userView = it })
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
    onEdit: () -> Unit, onDelete: () -> Unit, onUser: (String) -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.padding(bottom = 12.dp)) {
        Row(Modifier.padding(start = 10.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable { onUser(p.user) }, verticalAlignment = Alignment.CenterVertically) {
                Avatar(p.user, p.avatar, Modifier.size(32.dp))
                Spacer(Modifier.width(8.dp))
                Text(p.user, fontWeight = FontWeight.Bold)
            }
            if (p.user == vm.me?.username) {
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
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
        if (p.created > 0) Text(timeAgo(p.created).uppercase(), color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp))
    }
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
            Text("Videos are trimmed to 30 seconds and compressed before upload.", color = Color.Gray, fontSize = 12.sp)
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
