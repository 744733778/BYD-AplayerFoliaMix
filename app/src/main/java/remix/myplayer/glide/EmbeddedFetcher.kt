package remix.myplayer.glide

import android.media.MediaMetadataRetriever
import android.net.Uri
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.engine.GlideException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * created by Remix on 2021/4/27
 */
class EmbeddedFetcher(private val fileUri: Uri) : DataFetcher<InputStream> {
  private var stream: InputStream? = null

  override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
    val mediaDataRetriever = MediaMetadataRetriever()
    try {
      mediaDataRetriever.setDataSource(fileUri.path)
      val bytes = mediaDataRetriever.embeddedPicture
      stream = if (bytes != null) {
        ByteArrayInputStream(bytes)
      } else{
        AudioFileCoverUtils.fallback(fileUri.path)
      }
      if (stream != null) {
        callback.onDataReady(stream)
      } else {
        // 内嵌封面不存在且 folder.jpg 兜底也失败：显式失败让 Glide 走 failure 占位图，
        // 回调 null 流在部分版本会被当成坏数据
        callback.onLoadFailed(GlideException("No embedded picture for $fileUri"))
      }
    } catch (e: Exception) {
      callback.onLoadFailed(GlideException(e.message, e))
    } finally {
      mediaDataRetriever.release()
    }

  }

  override fun cleanup() {
    try {
      stream?.close()
    } catch (ignore: IOException) {
    }
  }

  override fun cancel() {

  }

  override fun getDataClass(): Class<InputStream> {
    return InputStream::class.java
  }

  override fun getDataSource(): DataSource {
    return DataSource.LOCAL
  }
}