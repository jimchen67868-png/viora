package com.example.viora

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
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
import androidx.compose.material.icons.filled.Send
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------- ViewModel ----------
class AppVm(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("viora", Context.MODE_PRIVATE)
    val api = Api(prefs.getString("base", "http://127.0.0.1:8000")!!, prefs.getString("token", null))
    var me by mutableStateOf<Profile?>(null)
    val posts = mutableStateListOf<Post>()
    val stories = mutableStateListOf<Story>()
    var refreshing by mutableStateOf(false)
    var busy by mutableStateOf(false)

    init {
        if (api.token != null) {
            me = Profile(prefs.getString("user", "")!!, prefs.getString("bio", "")!!, prefs.getString("avatar", null))
            refresh()
        }
    }

    private fun toast(msg: String?) = Toast.makeText(getApplication<Application>(), msg ?: "Something went wrong", Toast.LENGTH_LONG).show()

    private fun safe(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() }
        catch (e: ApiException) { if (e.code == 401) logout(); toast(e.message) }
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

    fun auth(register: Boolean, base: String, user: String, pw: String) = safe {
        busy = true
        try {
            api.base = base.trim().trimEnd('/')
            val (token, profile) = api.auth(if (register) "/api/register" else "/api/login", user.trim(), pw)
            api.token = token
            prefs.edit().putString("base", api.base).putString("token", token).apply()
            saveMe(profile); load()
        } finally { busy = false }
    }

    fun logout() {
        api.token = null; me = null; posts.clear(); stories.clear()
        prefs.edit().remove("token").apply()
    }

    fun refresh() = safe {
        refreshing = true
        try { saveMe(api.me()); load() } finally { refreshing = false }
    }

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

    fun share(uri: Uri, caption: String, asStory: Boolean, done: () -> Unit) = safe {
        busy = true
        try {
            val bytes = withContext(Dispatchers.IO) { readJpeg(getApplication<Application>(), uri) }
            if (asStory) { api.createStory(bytes); val s = api.stories(); stories.clear(); stories.addAll(s) }
            else posts.add(0, api.createPost(bytes, caption))
            done()
        } finally { busy = false }
    }

    fun saveProfile(bio: String, newAvatar: Uri?, removeAvatar: Boolean) = safe {
        busy = true
        try {
            var p = api.updateBio(bio)
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface { App() } } }
    }
}

@Composable
fun App(vm: AppVm = viewModel()) {
    if (vm.me == null) { Login(vm); return }
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(bottomBar = {
        NavigationBar {
            NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Filled.Home, "Home") })
            NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Filled.AddBox, "New post") })
            NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Filled.Person, "Profile") })
        }
    }) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> Feed(vm)
                1 -> NewPost(vm) { tab = 0 }
                else -> ProfileScreen(vm)
            }
        }
    }
}

// ---------- Login ----------
@Composable
fun Login(vm: AppVm) {
    var base by remember { mutableStateOf(vm.api.base) }
    var user by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Viora", fontSize = 42.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(base, { base = it }, label = { Text("Server URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            pw, { pw = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        Button({ vm.auth(false, base, user, pw) }, enabled = !vm.busy && user.isNotBlank() && pw.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(if (vm.busy) "Please wait…" else "Log in")
        }
        TextButton({ vm.auth(true, base, user, pw) }, enabled = !vm.busy && user.isNotBlank() && pw.isNotEmpty()) { Text("Create account") }
    }
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Feed(vm: AppVm) {
    var commentsFor by remember { mutableStateOf<Post?>(null) }
    var editing by remember { mutableStateOf<Post?>(null) }
    var deleting by remember { mutableStateOf<Post?>(null) }
    var photo by remember { mutableStateOf<String?>(null) }
    var story by remember { mutableStateOf<Story?>(null) }

    PullToRefreshBox(isRefreshing = vm.refreshing, onRefresh = { vm.refresh() }) {
        LazyColumn(Modifier.fillMaxSize()) {
            item { Stories(vm) { story = it } }
            if (vm.posts.isEmpty() && !vm.refreshing)
                item { Text("No posts yet. Share the first one from the + tab!", Modifier.padding(24.dp), color = Color.Gray) }
            items(vm.posts, key = { it.id }) { p ->
                PostCard(p, vm, onComments = { commentsFor = p }, onPhoto = { photo = p.image },
                    onEdit = { editing = p }, onDelete = { deleting = p })
            }
        }
    }
    commentsFor?.let { CommentsSheet(it, vm) { commentsFor = null } }
    photo?.let { PhotoViewer(it) { photo = null } }
    story?.let { StoryViewer(it) { story = null } }
    editing?.let { p ->
        var text by remember(p) { mutableStateOf(p.caption) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit caption") },
            text = { OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton({ vm.editCaption(p, text.trim()); editing = null }) { Text("Save") } },
            dismissButton = { TextButton({ editing = null }) { Text("Cancel") } }
        )
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete post?") },
            text = { Text("This can't be undone.") },
            confirmButton = { TextButton({ vm.deletePost(p); deleting = null }) { Text("Delete", color = Color.Red) } },
            dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } }
        )
    }
}

@Composable
fun PostCard(p: Post, vm: AppVm, onComments: () -> Unit, onPhoto: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.padding(bottom = 12.dp)) {
        Row(Modifier.padding(start = 10.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(p.user, p.avatar, Modifier.size(32.dp))
            Spacer(Modifier.width(8.dp))
            Text(p.user, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
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
        AsyncImage(
            model = p.image, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).pointerInput(Unit) {
                detectTapGestures(onTap = { onPhoto() }, onDoubleTap = { if (!p.liked) vm.toggleLike(p) })
            }
        )
        Row(Modifier.padding(horizontal = 6.dp)) {
            IconButton({ vm.toggleLike(p) }) {
                Icon(
                    if (p.liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Like",
                    tint = if (p.liked) Color.Red else LocalContentColor.current
                )
            }
            IconButton(onComments) { Icon(Icons.Outlined.ChatBubbleOutline, "Comments") }
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
    }
}

// ---------- Comments ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsSheet(p: Post, vm: AppVm, onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    val comments = remember { mutableStateListOf<Comment>() }
    LaunchedEffect(p.id) {
        try { comments.addAll(vm.api.comments(p.id)) } catch (_: Exception) { }
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
                        Row {
                            Avatar(c.user, c.avatar, Modifier.size(36.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(c.user, fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
    var uri by remember { mutableStateOf<Uri?>(null) }
    var caption by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri = it }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("New post", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Button({ picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
            Text(if (uri == null) "Choose photo" else "Change photo")
        }
        uri?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(1f)) }
        OutlinedTextField(caption, { caption = it }, label = { Text("Write a caption…") }, modifier = Modifier.fillMaxWidth())
        Button(
            onClick = { vm.share(uri!!, caption.trim(), false) { uri = null; caption = ""; onDone() } },
            enabled = uri != null && !vm.busy, modifier = Modifier.fillMaxWidth()
        ) { Text(if (vm.busy) "Uploading…" else "Share post") }
        OutlinedButton(
            onClick = { vm.share(uri!!, "", true) { uri = null; caption = ""; onDone() } },
            enabled = uri != null && !vm.busy, modifier = Modifier.fillMaxWidth()
        ) { Text("Add to your story (24h)") }
    }
}

// ---------- Profile ----------
@Composable
fun ProfileScreen(vm: AppVm) {
    val me = vm.me!!
    val mine = vm.posts.filter { it.user == me.username }
    var photo by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
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
                Text(me.username, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("${mine.size} posts  ·  ${mine.sumOf { it.likes }} likes", color = Color.Gray)
            }
            TextButton({ vm.logout() }) { Text("Log out") }
        }
        if (me.bio.isNotBlank()) Text(me.bio, Modifier.padding(horizontal = 16.dp))
        OutlinedButton(
            onClick = { draftBio = me.bio; draftAvatar = null; removing = false; editing = true },
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) { Text("Edit profile") }
        HorizontalDivider()
        if (mine.isEmpty()) Text("Share your first photo from the + tab.", Modifier.padding(16.dp), color = Color.Gray)
        LazyVerticalGrid(GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(mine, key = { it.id }) {
                AsyncImage(it.image, null, contentScale = ContentScale.Crop,
                    modifier = Modifier.aspectRatio(1f).clickable { photo = it.image })
            }
        }
    }
    photo?.let { PhotoViewer(it) { photo = null } }

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
                        draftBio, { if (it.length <= 150) draftBio = it },
                        label = { Text("Bio") }, supportingText = { Text("${draftBio.length}/150") },
                        modifier = Modifier.fillMaxWidth(), minLines = 3
                    )
                }
            },
            confirmButton = {
                TextButton({ vm.saveProfile(draftBio.trim(), draftAvatar, removing); editing = false }) { Text("Save") }
            },
            dismissButton = { TextButton({ editing = false }) { Text("Cancel") } }
        )
    }
}
