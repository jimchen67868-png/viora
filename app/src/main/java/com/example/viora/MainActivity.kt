package com.example.viora

import android.net.Uri
import android.os.Bundle
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage

// ---------- Data ----------
class Comment(val user: String, val text: String)

class Post(val id: Int, val user: String, val image: Any, caption: String, likes: Int = 0) {
    var caption by mutableStateOf(caption)
    var likes by mutableIntStateOf(likes)
    var liked by mutableStateOf(false)
    val comments = mutableStateListOf<Comment>()
}

class FeedViewModel : ViewModel() {
    val me = "you"
    val storyUsers = listOf(me, "anna", "leo", "mia", "sam", "zoe", "max")
    val posts = mutableStateListOf<Post>()
    private var nextId = 100

    init {
        listOf("anna", "leo", "mia", "sam", "zoe", "max").forEachIndexed { i, u ->
            posts.add(Post(i, u, "https://picsum.photos/seed/$u$i/700/700", "Day ${i + 1} out exploring 🌍", (10..500).random()))
        }
        posts[0].comments.add(Comment("leo", "Amazing shot! 🔥"))
        posts[0].comments.add(Comment("mia", "Where is this?"))
        posts.add(1, Post(50, me, "https://picsum.photos/seed/viora-me/700/700", "My first Viora post ✨", 3))
    }

    fun storyImages(user: String): List<Any> {
        val own = if (user == me) posts.filter { it.user == me }.map { it.image } else emptyList()
        return own.ifEmpty { (1..3).map { "https://picsum.photos/seed/story$user$it/720/1280" } }
    }

    fun toggleLike(p: Post) { p.liked = !p.liked; p.likes += if (p.liked) 1 else -1 }
    fun addPost(uri: Uri, caption: String) { posts.add(0, Post(nextId++, me, uri, caption)) }
    fun deletePost(p: Post) { posts.remove(p) }
    fun addComment(p: Post, text: String) { p.comments.add(Comment(me, text)) }
}

// ---------- Activity ----------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App() } }
    }
}

@Composable
fun App(vm: FeedViewModel = viewModel()) {
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
                else -> Profile(vm)
            }
        }
    }
}

@Composable
fun Avatar(name: String, modifier: Modifier = Modifier) =
    AsyncImage(
        model = "https://picsum.photos/seed/avatar$name/120/120",
        contentDescription = name, contentScale = ContentScale.Crop,
        modifier = modifier.clip(CircleShape)
    )

// ---------- Stories ----------
@Composable
fun Stories(vm: FeedViewModel, onOpen: (String) -> Unit) {
    val ring = Brush.sweepGradient(listOf(Color(0xFFF58529), Color(0xFFDD2A7B), Color(0xFF8134AF), Color(0xFFF58529)))
    LazyRow(contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(vm.storyUsers) { n ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onOpen(n) }) {
                Box(Modifier.size(70.dp).border(3.dp, ring, CircleShape).padding(5.dp)) {
                    Avatar(n, Modifier.fillMaxSize())
                }
                Text(n, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun StoryViewer(user: String, images: List<Any>, onClose: () -> Unit) {
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
                    Avatar(user, Modifier.size(32.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(user, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
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
@Composable
fun Feed(vm: FeedViewModel) {
    var commentsFor by remember { mutableStateOf<Post?>(null) }
    var editing by remember { mutableStateOf<Post?>(null) }
    var deleting by remember { mutableStateOf<Post?>(null) }
    var photo by remember { mutableStateOf<Any?>(null) }
    var story by remember { mutableStateOf<String?>(null) }

    LazyColumn {
        item { Stories(vm) { story = it } }
        items(vm.posts, key = { it.id }) { p ->
            PostCard(p, vm, onComments = { commentsFor = p }, onPhoto = { photo = p.image },
                onEdit = { editing = p }, onDelete = { deleting = p })
        }
    }
    commentsFor?.let { CommentsSheet(it, vm) { commentsFor = null } }
    photo?.let { PhotoViewer(it) { photo = null } }
    story?.let { StoryViewer(it, vm.storyImages(it)) { story = null } }
    editing?.let { p ->
        var text by remember(p) { mutableStateOf(p.caption) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit caption") },
            text = { OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton({ p.caption = text.trim(); editing = null }) { Text("Save") } },
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
fun PostCard(
    p: Post, vm: FeedViewModel,
    onComments: () -> Unit, onPhoto: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.padding(bottom = 12.dp)) {
        Row(Modifier.padding(start = 10.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(p.user, Modifier.size(32.dp))
            Spacer(Modifier.width(8.dp))
            Text(p.user, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (p.user == vm.me) {
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
            if (p.comments.isEmpty()) "Add a comment…" else "View all ${p.comments.size} comments",
            color = Color.Gray,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp).clickable { onComments() }
        )
    }
}

// ---------- Comments ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsSheet(p: Post, vm: FeedViewModel, onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(0.85f).imePadding()) {
            Text("Comments", fontWeight = FontWeight.Bold, fontSize = 16.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp))
            HorizontalDivider()
            if (p.comments.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("No comments yet.\nStart the conversation.", color = Color.Gray)
                }
            } else {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(p.comments) { c ->
                        Row {
                            Avatar(c.user, Modifier.size(36.dp))
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
                Avatar(vm.me, Modifier.size(36.dp))
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    text, { text = it }, placeholder = { Text("Add a comment…") },
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(24.dp), singleLine = true
                )
                IconButton(
                    onClick = { if (text.isNotBlank()) { vm.addComment(p, text.trim()); text = "" } },
                    enabled = text.isNotBlank()
                ) { Icon(Icons.Filled.Send, "Send") }
            }
        }
    }
}

// ---------- New post ----------
@Composable
fun NewPost(vm: FeedViewModel, onDone: () -> Unit) {
    var uri by remember { mutableStateOf<Uri?>(null) }
    var caption by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri = it }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("New post", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Button({ picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
            Text(if (uri == null) "Choose photo" else "Change photo")
        }
        uri?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(1f)) }
        OutlinedTextField(caption, { caption = it }, label = { Text("Write a caption…") }, modifier = Modifier.fillMaxWidth())
        Button(
            onClick = { vm.addPost(uri!!, caption); uri = null; caption = ""; onDone() },
            enabled = uri != null, modifier = Modifier.fillMaxWidth()
        ) { Text("Share") }
    }
}

// ---------- Profile ----------
@Composable
fun Profile(vm: FeedViewModel) {
    val mine = vm.posts.filter { it.user == vm.me }
    var photo by remember { mutableStateOf<Any?>(null) }
    Column {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(vm.me, Modifier.size(80.dp))
            Spacer(Modifier.width(24.dp))
            Column {
                Text(vm.me, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("${mine.size} posts  ·  ${mine.sumOf { it.likes }} likes", color = Color.Gray)
            }
        }
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
}
