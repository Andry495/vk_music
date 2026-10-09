package com.vkmusic.app.vk

/**
 * Декодер ссылок вида …?extra=…#… (audio_api_unavailable).
 * Порт логики python273/vk_api audio_url_decoder.
 */
object AudioUrlDecoder {
    private const val VK_STR =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMN0PQRSTUVWXYZO123456789+/="

    fun decode(raw: String, userId: Long): String? {
        if (!raw.contains("?extra=")) {
            return raw.takeIf { it.startsWith("http") }
        }
        return runCatching {
            val extra = raw.substringAfter("?extra=")
            val hashIdx = extra.indexOf('#')
            if (hashIdx < 0) return null
            var tstr = vkO(extra.substring(0, hashIdx))
            val ops = vkO(extra.substring(hashIdx + 1)).split('\u0009').asReversed()
            for (opData in ops) {
                val split = opData.split('\u000b')
                val cmd = split[0]
                val arg = split.getOrNull(1)
                tstr = when (cmd) {
                    "v" -> tstr.reversed()
                    "r" -> if (arg != null) vkR(tstr, arg) else tstr
                    "x" -> if (arg != null) vkXor(tstr, arg) else tstr
                    "s" -> if (arg != null) vkS(tstr, arg.toIntOrNull() ?: return null) else tstr
                    "i" -> if (arg != null) {
                        vkS(tstr, (arg.toIntOrNull() ?: return null) xor userId.toInt())
                    } else {
                        tstr
                    }
                    else -> return null
                }
            }
            tstr.takeIf { it.startsWith("http") }
        }.getOrNull()
    }

    private fun vkO(string: String): String {
        val result = StringBuilder()
        var index2 = 0
        var acc = 0
        for (s in string) {
            val symIndex = VK_STR.indexOf(s)
            if (symIndex == -1) continue
            if (index2 % 4 != 0) {
                index2 += 1
                acc = (acc shl 6) + symIndex
                result.append((0xFF and (acc shr (-2 * index2 and 6))).toChar())
            } else {
                acc = symIndex
                index2 += 1
            }
        }
        return result.toString()
    }

    private fun vkR(string: String, i: String): String {
        val table = VK_STR + VK_STR
        val shift = i.toIntOrNull() ?: return string
        return buildString {
            for (ch in string) {
                val index = table.indexOf(ch)
                if (index != -1) {
                    var offset = index - shift
                    if (offset < 0) offset += table.length
                    append(table[offset])
                } else {
                    append(ch)
                }
            }
        }
    }

    private fun vkXor(string: String, i: String): String {
        if (i.isEmpty()) return string
        val xorVal = i[0].code
        return buildString {
            for (ch in string) append((ch.code xor xorVal).toChar())
        }
    }

    private fun vkS(t: String, e: Int): String {
        val n = t.length
        if (n == 0) return t
        val o = vkSChild(n, e)
        val chars = t.toMutableList()
        for (a in 1 until n) {
            val removed = splice(chars, o[n - 1 - a], 1, chars[a])
            chars[a] = removed.first()
        }
        return chars.joinToString("")
    }

    private fun vkSChild(n: Int, e: Int): IntArray {
        var ee = e
        val o = ArrayList<Int>(n)
        for (a in n - 1 downTo 0) {
            ee = ((n * (a + 1)) xor (ee + a)) % n
            o.add(ee)
        }
        return o.asReversed().toIntArray()
    }

    private fun splice(
        list: MutableList<Char>,
        start: Int,
        deleteCount: Int,
        item: Char
    ): List<Char> {
        val s = start.coerceIn(0, list.size)
        val removed = ArrayList<Char>(deleteCount)
        repeat(deleteCount) {
            if (s < list.size) removed.add(list.removeAt(s))
        }
        list.add(s, item)
        return removed
    }
}
