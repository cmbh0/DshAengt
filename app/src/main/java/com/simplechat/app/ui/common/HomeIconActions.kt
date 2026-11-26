package com.simplechat.app.ui.common

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.simplechat.app.App
import com.simplechat.app.R
import com.simplechat.app.data.HomeIcon
import kotlinx.coroutines.launch

/** 首页图标的两个动作：从相册换一张、恢复默认。 */
@Stable
class HomeIconActions(
    val pick: () -> Unit,
    val reset: () -> Unit,
)

/**
 * 首页图标的更换入口，首页和设置页共用。
 *
 * 两处都能换（首页点图标、设置 → 应用 → 首页图标），所以把「开相册 → 转存 →
 * 落库」收在一处：两个入口各写一遍的话，迟早有一边忘了清旧文件。
 */
@Composable
fun rememberHomeIconActions(): HomeIconActions {
    val context = LocalContext.current
    val app = context.applicationContext
    val store = (app as App).container.settingsStore
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val path = HomeIcon.import(app, uri)
            if (path == null) {
                Toast.makeText(app, R.string.err_image_read, Toast.LENGTH_SHORT).show()
            } else {
                store.setHomeIcon(path)
            }
        }
    }

    return remember(picker, store) {
        HomeIconActions(
            pick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            reset = {
                scope.launch {
                    // 先改设置再删文件：反过来的话，中间那一瞬界面还指着一张已经不存在的图
                    store.setHomeIcon("")
                    HomeIcon.clear(app)
                }
            },
        )
    }
}
