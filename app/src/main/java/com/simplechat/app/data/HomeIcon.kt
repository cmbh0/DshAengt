package com.simplechat.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 首页（空会话页）正中的那枚图标：默认是应用图标，用户可以换成自己的图。
 *
 * 选中的图**立刻转存进私有目录**，设置里只记这份副本的路径：
 * - 相册给的 `content://` 读权限只在本次进程有效，重启后就读不到了；
 * - 原图可能是一张 4000×3000 的照片，而这里只画 64dp —— 存下来的是一张
 *   边长不超过 [SIDE] 的正方形 PNG，每次冷启动解它只要几毫秒。
 *
 * 文件名带时间戳：换图 = 换路径，界面上按路径记住的位图自然失效，
 * 不会出现「换了图但还显示旧的」。
 */
object HomeIcon {
    /** 存下来的边长上限（px）。64dp 在 4x 屏上是 256px，留一倍余量。 */
    private const val SIDE = 512
    private const val PREFIX = "home_icon_"

    /**
     * 把 [uri] 指向的图片转存成首页图标，返回新文件的绝对路径；读不出来返回 null。
     *
     * 构图规则只有一条，按图片有没有透明边来分：
     * - **不透明的图（照片、截图）**：居中裁成正方形，铺满。
     * - **带透明边的图（抠好的 Logo）**：整张等比放进正方形，不裁 ——
     *   裁掉的话，一个横向的 Logo 只剩中间一截。
     */
    suspend fun import(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

            // 采样到「短边仍不小于 SIDE」为止：之后还要裁正方形，短边才是最终分辨率
            var sample = 1
            while (min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIDE) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                ?: return@runCatching null

            // 相机拍的照片常常是「横着存 + EXIF 里写旋转」，不处理的话图标是躺着的
            val degrees = runCatching {
                resolver.openInputStream(uri)?.use { input ->
                    when (
                        ExifInterface(input).getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL,
                        )
                    ) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                }
            }.getOrNull() ?: 0f
            val upright = if (degrees == 0f) {
                raw
            } else {
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(degrees) }, true)
            }

            val square = compose(upright)
            val dir = context.filesDir
            val target = File(dir, "$PREFIX${System.currentTimeMillis()}.png")
            val temp = File(dir, "${target.name}.tmp")
            temp.outputStream().use { square.compress(Bitmap.CompressFormat.PNG, 100, it) }
            // 先写临时文件再改名：写到一半被杀掉的话，不会留下一张解不开的「图标」
            if (!temp.renameTo(target)) {
                temp.delete()
                return@runCatching null
            }
            // 旧图标不再需要了（只留刚写的这一张）
            dir.listFiles { file -> file.name.startsWith(PREFIX) && file != target }?.forEach { it.delete() }
            target.absolutePath
        }.getOrNull()
    }

    /** 恢复默认图标后，把存过的自定义图标删掉。 */
    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        context.filesDir.listFiles { file -> file.name.startsWith(PREFIX) }?.forEach { it.delete() }
        Unit
    }

    /** 读出存好的图标；文件没了或坏了返回 null（界面回退到默认图标）。 */
    suspend fun load(path: String): Bitmap? = withContext(Dispatchers.IO) {
        if (path.isBlank()) return@withContext null
        runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
    }

    /** 把任意比例的图排进一张边长 ≤ [SIDE] 的正方形里。 */
    internal fun compose(source: Bitmap): Bitmap {
        val fit = hasTransparentEdge(source)
        // 铺满取短边、整张放进去取长边；都不放大（小图放大只会糊）
        val basis = if (fit) max(source.width, source.height) else min(source.width, source.height)
        val side = min(SIDE, basis).coerceAtLeast(1)
        val scale = side.toFloat() / basis
        val drawnWidth = source.width * scale
        val drawnHeight = source.height * scale

        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val left = (side - drawnWidth) / 2f
        val top = (side - drawnHeight) / 2f
        Canvas(out).drawBitmap(
            source,
            null,
            RectF(left, top, left + drawnWidth, top + drawnHeight),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        return out
    }

    /**
     * 四条边上有没有透明像素。
     *
     * 不能只看 `hasAlpha()`：很多 PNG 存成了 RGBA 但整张都不透明（截图、导出的照片），
     * 那种图应该按照片处理。真正「抠过」的图，透明一定会出现在边上。
     */
    internal fun hasTransparentEdge(bitmap: Bitmap): Boolean {
        if (!bitmap.hasAlpha()) return false
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return false
        val stepX = max(1, width / 32)
        val stepY = max(1, height / 32)
        fun transparent(x: Int, y: Int) = (bitmap.getPixel(x, y) ushr 24) < 0xF0
        var x = 0
        while (x < width) {
            if (transparent(x, 0) || transparent(x, height - 1)) return true
            x += stepX
        }
        var y = 0
        while (y < height) {
            if (transparent(0, y) || transparent(width - 1, y)) return true
            y += stepY
        }
        return false
    }
}
