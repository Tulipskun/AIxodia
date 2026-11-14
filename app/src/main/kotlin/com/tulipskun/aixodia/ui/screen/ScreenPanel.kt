package com.tulipskun.aixodia.ui.screen

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tulipskun.aixodia.data.remote.ScreenSocket
import kotlin.math.roundToInt

/**
 * The engine's screen, on the phone, under a finger.
 *
 * This is the VNC-shaped part without the VNC: the engine streams tiles of the
 * display and this view paints them, then turns touches into pointer events the
 * same way a remote-desktop viewer does — tap is a click, a held drag is a drag,
 * two fingers scroll, and a long press is the right button.
 *
 * All the picture state lives in [ScreenSurface] rather than in Compose: a frame
 * arrives tens of times a second, and rebuilding a composable that often would
 * spend more time in the framework than in drawing. Compose is only told about
 * the things a person reads — the connection state and the error.
 */
@Composable
fun ScreenPanel(
    socket: ScreenSocket,
    baseUrl: String,
    modifier: Modifier = Modifier,
) {
    val state by socket.state.collectAsStateWithLifecycle()
    var hello by remember { mutableStateOf<ScreenSocket.Hello?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var quality by remember { mutableIntStateOf(62) }
    var keyboardShown by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(false) }
    val surface = remember { ScreenSurface() }
    var view by remember { mutableStateOf<ScreenView?>(null) }
    // The video decoder, created only when the engine says it is sending video.
    // Building one for a JPEG stream would take a decoder from another app for
    // nothing.
    val video = remember { VideoSurface(onFirstFrame = {}) }
    var videoMode by remember { mutableStateOf(false) }
    var videoHasPicture by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { video.stop() }
    }

    DisposableEffect(baseUrl) {
        socket.connect(baseUrl)
        onDispose { socket.close() }
    }

    LaunchedEffect(socket) {
        socket.stream.collect { event ->
            when (event) {
                is ScreenSocket.Event.Ready -> {
                    hello = event.hello
                    quality = event.hello.quality
                    failure = null
                    surface.sizeTo(event.hello.width, event.hello.height)
                    // The engine decides; the client does not ask for video it
                    // cannot be given.
                    videoMode = event.hello.codec == "h264"
                    if (videoMode) video.sizeTo(event.hello.width, event.hello.height)
                }
                is ScreenSocket.Event.Video -> {
                    // A video frame goes straight to the decoder; there are no
                    // tiles to paint into a bitmap.
                    videoMode = true
                    video.submit(event.accessUnit, event.keyframe)
                    surface.cursorTo(event.cursor)
                    view?.invalidateFrame()
                    if (video.hasPicture) videoHasPicture = true
                }
                is ScreenSocket.Event.Patch -> {
                    surface.cursorTo(event.cursor)
                    surface.paint(event)
                    // The frame is one bitmap, so a repaint is a single draw; no
                    // composable re-runs for it.
                    view?.invalidateFrame()
                }
                is ScreenSocket.Event.Failed -> {
                    videoHasPicture = false
                    failure = event.reason
                    // The picture on screen is now a photograph of the past: say
                    // so rather than leaving it looking live.
                    surface.clear()
                    view?.cleared()
                }
                ScreenSocket.Event.Closed -> {
                    surface.clear()
                    view?.cleared()
                }
            }
        }
    }

    // The system bars are drawn over the app (edge-to-edge), so the picture is
    // laid out inside them: without this the quality strip sits under the
    // navigation bar and cannot be reached.
    Column(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // The decoded video sits under the touch view, which is transparent
            // and owns the gestures: one view reads the fingers whether the
            // picture beneath it is a bitmap or a video surface.
            if (videoMode) {
                AndroidView(
                    factory = { context ->
                        TextureView(context).also { textured ->
                            textured.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(
                                    texture: SurfaceTexture,
                                    width: Int,
                                    height: Int,
                                ) {
                                    video.attach(Surface(texture))
                                }

                                override fun onSurfaceTextureSizeChanged(
                                    texture: SurfaceTexture,
                                    width: Int,
                                    height: Int,
                                ) = Unit

                                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture) = true

                                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            AndroidView(
                factory = { context ->
                    ScreenView(context).also {
                        it.attach(socket, surface)
                        // In video mode the arrow is drawn over the video rather
                        // than into a bitmap, and the picture itself is not drawn
                        // here at all.
                        it.videoMode = videoMode
                        view = it
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            val problem = failure
            when {
                problem != null -> Centered(problem)
                // Before the first frame there is nothing to look at. A spinner
                // says the picture is coming; a black rectangle says the feature
                // is broken, and both are the same black rectangle otherwise.
                hello == null -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }
        }
        ScreenBar(
            hello = hello,
            quality = quality,
            keyboard = keyboardShown,
            expanded = showControls,
            socket = socket,
            onToggle = { showControls = !showControls },
            onKeyboard = { keyboardShown = !keyboardShown },
            onQuality = { value ->
                quality = value
                socket.settings(quality = value)
            },
        )
    }
}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(24.dp),
        )
    }
}

/**
 * The strip under the picture: what the link is doing, and the one control worth
 * having on a phone — how sharp the picture is, for when the link is slow.
 */
@Composable
private fun ScreenBar(
    hello: ScreenSocket.Hello?,
    quality: Int,
    keyboard: Boolean,
    expanded: Boolean,
    socket: ScreenSocket,
    onToggle: () -> Unit,
    onKeyboard: () -> Unit,
    onQuality: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                hello?.let { "${it.width}×${it.height} · ${it.fps}fps" } ?: "connecting…",
                style = MaterialTheme.typography.labelMedium,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The keys a phone keyboard does not have: Escape, Tab, arrows,
                // function keys, and a field that types any language.
                Text(
                    "⌨",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .padding(end = 16.dp)
                        .pointerTap(onKeyboard, size = 20.dp),
                )
                Text(
                    "${quality}%",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        if (keyboard) {
            KeyboardBar(socket)
        }
        if (expanded) {
            Slider(
                value = quality.toFloat(),
                onValueChange = { onQuality(it.roundToInt()) },
                valueRange = 10f..95f,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(
                "tap to adjust quality",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 2.dp).pointerTap(onToggle, size = 14.dp),
            )
        }
    }
}

@Composable
private fun Modifier.pointerTap(onTap: () -> Unit, size: androidx.compose.ui.unit.Dp): Modifier =
    this.pointerInput(size) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type == PointerEventType.Press) onTap()
            }
        }
    }.padding(vertical = 2.dp)
