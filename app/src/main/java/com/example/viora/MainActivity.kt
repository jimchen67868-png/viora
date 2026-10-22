package com.example.viora

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage

// ---------- Data ----------
class Post(val id: Int, val user: String, val image: Any, val caption: String, likes: Int = 0) {
    var likes by mutableIntStateOf(likes)
    var liked by mutableStateOf(false)
    val comments = mutableStateListOf<String>()
}

class FeedViewModel : ViewModel() {
    val me = "you"
    val posts = mutableStateListOf<Post>()
    private var nextId = 100

    init {
        val users = listOf("anna", "leo", "mia", "sam", "zoe", "max")
        users.forEachIndexed { i, u ->
            posts.add(Post(i, u, "https://picsum.photos/seed/$u$i/700/700", "Day ${i + 1} out exploring 🌍", (10..500).random()))
        }
    }

    fun toggleLike(p: Post) {
        p.liked = !p.liked
        p.likes += if (p.liked) 1 else -1
    }

    fun addPost(uri: Uri, caption: String) {
        posts.add(0, Post(nextId++, me, uri, caption))
    }
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

// ---------- Feed ----------
@Composable
fun Avatar(name: String, modifier: Modifier = Modifier) =
    AsyncImage(
        model = "https://picsum.photos/seed/avatar$name/120/120",
        contentDescription = name,
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(CircleShape)
    )

@Composable
fun Stories(vm: FeedViewModel) {
    val names = listOf(vm.me) + vm.posts.map { it.user }.filter { it != vm.me }.distinct()
    val ring = Brush.sweepGradient(listOf(Color(0xFFF58529), Color(0xFFDD2A7B), Color(0xFF8134AF), Color(0xFFF58529)))
    LazyRow(contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(names) { n ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(70.dp).border(3.dp, ring, CircleShape).padding(5.dp)) {
                    Avatar(n, Modifier.fillMaxSize())
                }
                Text(n, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun Feed(vm: FeedViewModel) {
    var commentsFor by remember { mutableStateOf<Post?>(null) }
    LazyColumn {
        item { Stories(vm) }
        items(vm.posts, key = { it.id }) { PostCard(it, vm) { commentsFor = it } }
    }
    commentsFor?.let { CommentsDialog(it) { commentsFor = null } }
}

@Composable
fun PostCard(p: Post, vm: FeedViewModel, onComments: () -> Unit) {
    Column(Modifier.padding(bottom = 12.dp)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(p.user, Modifier.size(32.dp))
            Spacer(Modifier.width(8.dp))
            Text(p.user, fontWeight = FontWeight.Bold)
        }
        AsyncImage(
            model = p.image, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { if (!p.liked) vm.toggleLike(p) }) }
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
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(p.user + " ") }
                append(p.caption)
            },
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        if (p.comments.isNotEmpty())
            Text(
                "View all ${p.comments.size} comments", color = Color.Gray,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp).clickable { onComments() }
            )
    }
}

@Composable
fun CommentsDialog(p: Post, onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Comments") },
        text = {
            Column {
                if (p.comments.isEmpty()) Text("No comments yet.", color = Color.Gray)
                p.comments.forEach { Text("you  $it", Modifier.padding(vertical = 2.dp)) }
                OutlinedTextField(text, { text = it }, placeholder = { Text("Add a comment…") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton({ if (text.isNotBlank()) { p.comments.add(text.trim()); text = "" } }) { Text("Post") }
        },
        dismissButton = { TextButton(onClose) { Text("Close") } }
    )
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
        uri?.let {
            AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
        }
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
                AsyncImage(it.image, null, contentScale = ContentScale.Crop, modifier = Modifier.aspectRatio(1f))
            }
        }
    }
}
