// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.pilcrowmd.R
import com.pilcrowmd.rendering.mermaid.MermaidDiagramRenderer
import com.pilcrowmd.rendering.mermaid.MermaidDiagramRequest
import com.pilcrowmd.rendering.mermaid.MermaidStyle
import com.pilcrowmd.ui.theme.mdColors

/**
 * S8-A7: a Mermaid diagram full screen, on the panel colour the diagram was drawn against (so Dark and
 * Light follow the token layer). Pinch to zoom (fit = 1x, up to 5x), drag to pan, double-tap toggles fit
 * and 2.5x about the tap; the back gesture or the labelled close button closes it.
 */
@Composable
fun DiagramViewer(picture: ImageBitmap, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val colors = mdColors()
        val view = LocalView.current
        DisposableEffect(colors.codeBlockBg) {
            val window = (view.parent as DialogWindowProvider).window
            // Compose sizes the content to the whole screen, but a dialog window still fits the system bars,
            // so its frame was shorter than its content and the bottom was cut off (landscape, API 35+).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.attributes = window.attributes.apply { fitInsetsTypes = 0 }
            }
            // The window now runs behind both bars, so both draw their icons for the panel's lightness.
            val lightPanel = colors.codeBlockBg.luminance() > LIGHT_PANEL_LUMINANCE
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightPanel
                isAppearanceLightNavigationBars = lightPanel
            }
            onDispose {}
        }
        var zoom by remember(picture) {
            mutableStateOf(DiagramZoom(picture = Size(picture.width.toFloat(), picture.height.toFloat())))
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.codeBlockBg)
                .onSizeChanged { zoom = zoom.layout(it.toSize(), zoom.picture) },
        ) {
            // The pointer input sits outside the graphics layer, so gesture positions are viewport positions.
            Image(
                bitmap = picture,
                contentDescription = stringResource(R.string.diagram_content_description),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, pan, gestureZoom, _ ->
                            zoom = zoom.transform(centroid, pan, gestureZoom)
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { zoom = zoom.doubleTap(it) })
                    }
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = zoom.scale
                        scaleY = zoom.scale
                        translationX = zoom.offset.x
                        translationY = zoom.offset.y
                    },
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(8.dp),
            ) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.diagram_close_description),
                    tint = colors.primaryText,
                )
            }
        }
    }
}

/**
 * S8-A7: [request]'s diagram full screen. It asks [renderer] again, which answers a diagram already drawn
 * from its cache; if there is no picture after all, nothing opens and [onClose] runs. While it waits,
 * nothing shows.
 */
@Composable
fun OpenDiagramViewer(
    request: MermaidDiagramRequest,
    renderer: MermaidDiagramRenderer,
    style: MermaidStyle,
    onClose: () -> Unit,
) {
    var picture by remember(request, style) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(request, style) {
        val drawn = renderer.render(request.source, request.widthPx, style)
        if (drawn == null) onClose() else picture = drawn
    }
    picture?.let { drawn ->
        DiagramViewer(remember(drawn) { drawn.asImageBitmap() }, onClose)
    }
}

/**
 * Saves the open diagram across rotation; a closed viewer (null) saves nothing. A source longer than
 * [MAX_SAVED_DIAGRAM_SOURCE] is not saved either (the viewer closes on rotation): Mermaid accepts up to
 * 50 000 characters, and the saved state shares one ~1 MB transaction with the whole activity.
 */
val MermaidDiagramRequestSaver = Saver<MermaidDiagramRequest?, List<Any>>(
    save = { request ->
        request?.takeIf { it.source.length <= MAX_SAVED_DIAGRAM_SOURCE }?.let { listOf(it.source, it.widthPx) }
    },
    restore = { MermaidDiagramRequest(it[0] as String, it[1] as Int) },
)

/** A panel brighter than this gets dark status and navigation bar icons. */
private const val LIGHT_PANEL_LUMINANCE = 0.5f

/** The longest diagram source kept across rotation (characters; 40 KB as UTF-16). */
internal const val MAX_SAVED_DIAGRAM_SOURCE = 20_000
