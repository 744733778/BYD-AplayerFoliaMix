package remix.myplayer.lyric

import android.annotation.SuppressLint
import androidx.core.text.HtmlCompat
import remix.myplayer.util.EncodingDetect
import timber.log.Timber
import java.nio.charset.Charset
import kotlin.math.roundToLong

object LrcParser {
  private const val TAG = "LrcParser"

  // 增强型LRC：<mm:ss.xx>，可选词结束 <mm:ss.xx|mm:ss.xx>（|后为词结束）
  private val ENHANCED_WORD_TIME_TAG_REGEX = """<(\d+:){1,2}\d+(\.\d*)?(?:\|(\d+:){1,2}\d+(\.\d*)?)?>""".toRegex()
  // 逐字LRC：[mm:ss.xx]
  private val VERBATIM_WORD_TIME_TAG_REGEX = """\[(\d+:){1,2}\d+(\.\d*)?]""".toRegex()
  private val EMPTY_LINE_WITH_TIME_REGEX = """(\[(\d+:){1,2}\d+(\.\d*)?])*""".toRegex()

  /**
   * 一般格式: `mm:ss.xx`, `mm:ss.xxx`
   *
   * @param offset 正数表示更早，负数表示更晚
   * @return 以毫秒为单位；失败返回 null
   */
  private fun parseTime(timeStr: String, offset: Long): Long? {
    try {
      val parts = timeStr.split(':')
      val minutes = when (parts.size) {
        2 -> parts[0].toLong()
        3 -> parts[0].toLong() * 60 + parts[1].toLong()
        else -> throw Exception("Unknown time format")
      }
      val seconds = minutes * 60 + parts.last().toDouble()
      return (seconds * 1000).roundToLong() - offset
    } catch (t: Throwable) {
      Timber.tag(TAG).w(t, "Failed to parse time: $timeStr")
    }
    return null
  }

  /**
   * 解析增强型精确到字歌词
   *
   * 兼容两种词标记约定：
   *  - 标签在词前（KuGou 转换等）：`<mm:ss.xx|mm:ss.xx>词`
   *  - 标签在词后（传统增强 LRC）：`词<mm:ss.xx>`
   *
   * 统一按「标签切分出的文本段」归属时间：段起点 = 前一个标签的起始（或行首时间），
   * 段终点 = 该标签自带的 |end（优先）或下一个标签的起始。行首/词间的空白段会被丢弃，
   * 避免 KuGou 转换器行首追加的空格被当成一个词，导致逐字进度整体错位一个 token。
   *
   * @param time 整行的开始时间
   */
  private fun parseEnhancedWords(time: Long, offset: Long, content: String): LyricLine {
    val matches = ENHANCED_WORD_TIME_TAG_REGEX.findAll(content).toList()
    if (matches.isEmpty()) {
      return SimpleLyricLine(time, decodeEntities(content))
    }

    data class Tag(val range: IntRange, val start: Long, val end: Long?)

    val tags = matches.map { m ->
      val inner = m.value.substring(1, m.value.lastIndex)
      val parts = inner.split('|')
      Tag(
        m.range,
        parseTime(parts[0], offset) ?: time,
        if (parts.size == 2) parseTime(parts[1], offset) else null
      )
    }

    val words = ArrayList<Word>(tags.size)
    var lastTime = time

    fun addWord(startMs: Long, endMs: Long?, text: String) {
      val decoded = decodeEntities(text)
      if (decoded.isBlank()) {
        return
      }
      // 行内 time 单调不减
      val st = maxOf(startMs, lastTime)
      val en = endMs?.takeIf { it > st }
      words.add(Word(st, decoded, en))
      lastTime = st
    }

    // 标签前的文本（传统增强 LRC 的第一个词，或 KuGou 转换器的行首空格）
    addWord(time, tags[0].start, content.substring(0, tags[0].range.first))

    for (i in tags.indices) {
      val tag = tags[i]
      val textStart = tag.range.last + 1
      val textEnd = if (i + 1 < tags.size) tags[i + 1].range.first else content.length
      // 优先用标签自带的词结束时间；没有则用下一个标签的起始
      val endMs = tag.end ?: tags.getOrNull(i + 1)?.start
      addWord(tag.start, endMs, content.substring(textStart, textEnd))
    }

    if (words.isEmpty()) {
      return SimpleLyricLine(time, decodeEntities(content))
    }
    return PerWordLyricLine(time, words)
  }

  /**
   * 解析逐字LRC（word[time]）
   *
   * 例如：`你[00:12.34]好[00:12.56]`
   */
  private fun parseVerbatimWords(time: Long, offset: Long, content: String): LyricLine {
    val words = ArrayList<Word>()
    var currentTime = time
    var lastStart = 0
    var match =
      VERBATIM_WORD_TIME_TAG_REGEX.find(content) ?: return SimpleLyricLine(time, decodeEntities(content))
    while (true) {
      val word = decodeEntities(content.substring(lastStart, match.range.first))
      if (word.isNotEmpty()) {
        words.add(Word(currentTime, word))
      }
      parseTime(match.value.substring(1, match.value.lastIndex), offset)?.let {
        // 确保同一 LyricsLine 内 time 单调不减
        if (it > currentTime) {
          currentTime = it
        }
      }
      lastStart = match.range.last + 1
      match = match.next() ?: break
    }
    val tail = decodeEntities(content.substring(lastStart))
    if (tail.isNotEmpty() || words.isEmpty()) {
      words.add(Word(currentTime, tail))
    }
    return PerWordLyricLine(time, words)
  }

  fun parse(data: ByteArray): ArrayList<LyricLine> {
    val encoding = runCatching { EncodingDetect.getJavaEncode(data) }.getOrDefault("UTF-8")
    val content = runCatching { String(data, Charset.forName(encoding)) }
      .getOrElse {
        Timber.tag(TAG).w(it, "Failed to decode lyrics with detected encoding: $encoding")
        String(data)
      }
    return parse(content)
  }

  fun parse(data: String): ArrayList<LyricLine> {
    val lines = ArrayList<LyricLine>()
    var offset = 0L

    data.lines().forEach {
      if (it.isBlank()) {
        return@forEach
      }
      if (!it.startsWith('[')) {
        Timber.tag(TAG).w("Ignored unknown line: $it")
        return@forEach
      }

      // [xxx] 标签行。仅当整行只有一个 [] 包裹时才按标签处理，
      // 避免误判 `词[00:12.34]` 这种逐字LRC行为 tag。
      if (it.endsWith(']')) {
        val firstClosing = it.indexOf(']')
        if (firstClosing == it.lastIndex) {
          val tag = it.substring(1, it.lastIndex)
          // [offset:+/-xxx]
          if (tag.startsWith("offset:")) {
            try {
              offset = tag.substring(7).trim().toLong()
            } catch (t: Throwable) {
              Timber.tag(TAG).w("Failed to parse offset, raw tag: $tag")
            }
            return@forEach
          }
          if (!EMPTY_LINE_WITH_TIME_REGEX.matches(it)) {
            Timber.tag(TAG).v("Ignored unknown tag: $tag")
            return@forEach
          }
        }
      }

      var index = 0

      // 解析时间戳
      val times = ArrayList<Long>()
      while (it.startsWith("[", index)) {
        val closing = it.indexOf(']', index)
        if (closing == -1) break
        parseTime(it.substring(index + 1, closing), offset)?.let { time ->
          times.add(time)
        }
        index = closing + 1
      }

      val rawLineContent = it.substring(index)
      val lineContent = decodeEntities(rawLineContent)
      if (times.size == 1) {
        lines.add(
          when {
            ENHANCED_WORD_TIME_TAG_REGEX.containsMatchIn(rawLineContent) ->
              parseEnhancedWords(times[0], offset, rawLineContent)
            VERBATIM_WORD_TIME_TAG_REGEX.containsMatchIn(rawLineContent) ->
              parseVerbatimWords(times[0], offset, rawLineContent)
            else -> SimpleLyricLine(times[0], lineContent)
          }
        )
      } else {
        times.forEach { time ->
          lines.add(SimpleLyricLine(time, lineContent))
        }
      }
    }
    return lines
  }

  /**
   * 合并翻译行。
   *
   * 相同时间戳的两行，认为第二行是第一行的翻译；关闭翻译时忽略后续同时间戳行。
   */
  fun mergeTranslations(lines: List<LyricLine>, translationEnabled: Boolean): ArrayList<LyricLine> {
    val combinedLines = ArrayList<LyricLine>()

    lines.sortedBy { it.time }.forEach { line ->
      val lastLine = combinedLines.lastOrNull()
      if (lastLine?.time == line.time) {
        if (translationEnabled && lastLine.translation == null) {
          combinedLines[combinedLines.lastIndex] = lastLine.withTranslation(line.content)
        }
      } else {
        combinedLines.add(line)
      }
    }

    return combinedLines
  }

  /**
   * 毫秒转 mm:ss.xx（两位小数）
   */
  @SuppressLint("DefaultLocale")
  fun formatMs(ms: Int): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val hundredths = (ms % 1000) / 10
    return String.format("%02d:%02d.%02d", minutes, seconds, hundredths)
  }

  /**
   * 处理歌词中的 HTML 转义字符（如 &apos;），保证逐词歌词显示正常
   */
  private fun decodeEntities(text: String): String {
    if (!text.contains('&')) return text
    return HtmlCompat.fromHtml(text, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
  }
}
