package com.seungmin.multiview

import android.content.ClipDescription
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.DragEvent
import android.view.DragAndDropPermissions
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.seungmin.multiview.ui.MultiVideoScreen
import com.seungmin.multiview.ui.MultiViewTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var replaceTileId: String? = null
    private var preserveReplaceSettings: Boolean = true
    private val dragPermissions = mutableListOf<DragAndDropPermissions>()

    private val openOne = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importUris(listOf(it), replaceTileId, preserveReplaceSettings) }
        replaceTileId = null
    }
    private val openMany = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.importUris(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        enterImmersive()
        handleIntent(intent)
        installDropTarget()
        setContent {
            MultiViewTheme {
                MultiVideoScreen(
                    viewModel = viewModel,
                    onPickOne = { tileId, preserve ->
                        replaceTileId = tileId
                        preserveReplaceSettings = preserve
                        openOne.launch(arrayOf("video/*"))
                    },
                    onPickMany = { openMany.launch(arrayOf("video/*")) }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    override fun onStop() {
        viewModel.capturePlaybackPositions()
        viewModel.playerManager.pauseAll()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        viewModel.playerManager.restoreProject(viewModel.ui.value.project)
    }

    private fun enterImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun handleIntent(intent: Intent?) {
        val extraUris = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.parcelableUri(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.parcelableUriList(Intent.EXTRA_STREAM)
            else -> emptyList()
        }
        val uris = if (extraUris.isNotEmpty()) extraUris else {
            intent?.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }.orEmpty()
        }
        if (uris.isNotEmpty()) {
            viewModel.importSharedUris(uris)
            intent?.action = null
        }
    }

    private fun installDropTarget() {
        window.decorView.setOnDragListener { view, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> event.clipDescription?.hasMimeType("video/*") == true ||
                    event.clipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST) == true
                DragEvent.ACTION_DROP -> {
                    requestDragAndDropPermissions(event)?.let(dragPermissions::add)
                    val clip = event.clipData ?: return@setOnDragListener false
                    val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
                    if (uris.isEmpty()) return@setOnDragListener false
                    val x = (event.x / view.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    val y = (event.y / view.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                    val target = viewModel.ui.value.project.tiles
                        .filter { x in it.tileX..(it.tileX + it.tileWidth) && y in it.tileY..(it.tileY + it.tileHeight) }
                        .maxByOrNull { it.zIndex }
                    if (target != null && uris.size == 1) viewModel.importUris(uris, target.id, true)
                    else viewModel.importUris(uris)
                    true
                }
                else -> true
            }
        }
    }

    override fun onDestroy() {
        dragPermissions.forEach { runCatching { it.release() } }
        dragPermissions.clear()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun Intent.parcelableUri(key: String): Uri? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(key, Uri::class.java)
    } else getParcelableExtra(key)

    @Suppress("DEPRECATION")
    private fun Intent.parcelableUriList(key: String): List<Uri> = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableArrayListExtra(key, Uri::class.java).orEmpty()
    } else getParcelableArrayListExtra<Uri>(key).orEmpty()
}
