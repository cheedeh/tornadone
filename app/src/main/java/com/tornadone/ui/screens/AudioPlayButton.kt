package com.tornadone.ui.screens

import android.media.MediaPlayer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

@Composable
fun AudioPlayButton(audioPath: String, tint: Color) {
    var playing by remember { mutableStateOf(false) }
    val mediaPlayer = remember { MediaPlayer() }

    DisposableEffect(Unit) {
        onDispose { mediaPlayer.release() }
    }

    IconButton(onClick = {
        if (playing) {
            mediaPlayer.stop()
            mediaPlayer.reset()
            playing = false
        } else {
            try {
                mediaPlayer.reset()
                mediaPlayer.setDataSource(audioPath)
                mediaPlayer.prepare()
                mediaPlayer.setOnCompletionListener {
                    playing = false
                    mediaPlayer.reset()
                }
                mediaPlayer.start()
                playing = true
            } catch (_: Exception) {
                playing = false
                mediaPlayer.reset()
            }
        }
    }) {
        Icon(
            if (playing) Icons.Default.Stop else Icons.Default.PlayArrow,
            contentDescription = if (playing) "Stop" else "Play recording",
            tint = tint,
        )
    }
}
