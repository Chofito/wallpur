package dev.chofito.wallpurr.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import dev.chofito.wallpurr.AppContainer
import dev.chofito.wallpurr.WallpurrApplication

fun Context.container(): AppContainer =
    (applicationContext as WallpurrApplication).container

@Composable
inline fun <reified VM : ViewModel> wallpurrViewModel(
    crossinline create: (AppContainer) -> VM,
): VM {
    val context = LocalContext.current
    return viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                create(context.container()) as T
        },
    )
}
