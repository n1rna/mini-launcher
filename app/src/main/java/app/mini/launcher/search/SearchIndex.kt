package app.mini.launcher.search

import java.text.Normalizer

/**
 * Pure-Kotlin search engine (no Android deps → unit-testable, fast on any thread).
 *
 * Matching rule (Olauncher-style): an app matches when its *normalized* label contains the
 * normalized query as a substring. Normalization = lowercase, strip accents, drop everything
 * that is not a letter/digit ("Google Maps" -> "googlemaps").
 *
 * Auto-launch rule ([resolve]):
 *  1. exactly one app matches -> launch it, or
 *  2. the query equals one app's full label and every *other* match only contains the query at
 *     its very end (so typing more letters can never select them). This makes "Maps" launchable
 *     even though "Google Maps" also contains "maps".
 *
 * Hints ([hint]): for every app, the shortest string that — typed letter by letter — makes
 * [resolve] return that app. Ties prefer the start of the label, then the start of a word.
 */
class SearchIndex(items: Map<String, String>) {

    class Entry(val id: String, val label: String, val key: String, val wordStarts: BooleanArray)

    val entries: List<Entry> = items.map { (id, label) -> normalizeWithWords(id, label) }
    private val byId: Map<String, Entry> = entries.associateBy { it.id }

    /** For each substring (len <= MAX_SUB), how many *entries* contain it. */
    private val substringCounts: HashMap<String, Int> = HashMap<String, Int>(entries.size * 64).also { map ->
        val seen = HashSet<String>()
        for (e in entries) {
            seen.clear()
            val k = e.key
            for (i in k.indices) {
                val maxEnd = minOf(k.length, i + MAX_SUB)
                for (end in i + 1..maxEnd) seen.add(k.substring(i, end))
            }
            for (s in seen) map[s] = (map[s] ?: 0) + 1
        }
    }

    private val hints: Map<String, String?> = entries.associate { it.id to computeHint(it) }

    fun hint(id: String): String? = hints[id]

    /** Matching ids, best first. [score] is a usage score (higher = used more). */
    fun search(rawQuery: String, score: (String) -> Double = { 0.0 }): List<String> {
        val q = normalize(rawQuery)
        if (q.isEmpty()) return entries.sortedBy { it.label.lowercase() }.map { it.id }
        return entries.asSequence()
            .mapNotNull { e ->
                val idx = e.key.indexOf(q)
                if (idx < 0) null else Triple(e, rank(e, q, idx), score(e.id))
            }
            .sortedWith(compareBy<Triple<Entry, Int, Double>> { it.second }
                .thenByDescending { it.third }
                .thenBy { it.first.label.lowercase() })
            .map { it.first.id }
            .toList()
    }

    /** The app that typing [rawQuery] should open right away, or null if still ambiguous. */
    fun resolve(rawQuery: String): String? = resolveNormalized(normalize(rawQuery))

    /** Range in the *original* label that the query matched (for highlighting), or null. */
    fun matchRange(id: String, rawQuery: String): IntRange? {
        val e = byId[id] ?: return null
        val q = normalize(rawQuery)
        if (q.isEmpty()) return null
        val idx = e.key.indexOf(q)
        if (idx < 0) return null
        // map normalized indices back to label indices
        var keyPos = 0
        var start = -1
        var end = -1
        val decomposed = e.label
        for ((i, c) in decomposed.withIndex()) {
            val n = normalize(c.toString())
            if (n.isEmpty()) continue
            if (keyPos == idx && start < 0) start = i
            keyPos += n.length
            if (keyPos >= idx + q.length) { end = i; break }
        }
        return if (start >= 0 && end >= start) start..end else null
    }

    private fun resolveNormalized(q: String): String? {
        if (q.isEmpty()) return null
        var only: Entry? = null
        var count = 0
        var exact: Entry? = null
        var exactCount = 0
        var othersAllEndWithQ = true
        for (e in entries) {
            val idx = e.key.indexOf(q)
            if (idx < 0) continue
            count++
            only = e
            if (e.key.length == q.length) { exact = e; exactCount++ }
            else if (idx != e.key.length - q.length) othersAllEndWithQ = false
        }
        if (count == 1) return only!!.id
        if (exactCount == 1 && othersAllEndWithQ) return exact!!.id
        return null
    }

    private fun computeHint(e: Entry): String? {
        val k = e.key
        if (k.isEmpty()) return null
        for (len in 1..minOf(k.length, MAX_SUB)) {
            for (i in candidateStarts(e, len)) {
                val s = k.substring(i, i + len)
                if (substringCounts[s] == 1 && typingReaches(s, e.id)) return s
            }
        }
        // No unique substring: maybe the full label works via the exact-match rule.
        return if (typingReaches(k, e.id)) k else null
    }

    /** Typing [s] char by char never opens a *different* app first, and ends on [id]. */
    private fun typingReaches(s: String, id: String): Boolean {
        for (j in 1 until s.length) {
            val r = resolveNormalized(s.substring(0, j))
            if (r != null) return r == id
        }
        return resolveNormalized(s) == id
    }

    /**
     * Start positions for substrings of [len], most "natural" first:
     * label start → word starts → inside a word → spanning two words (e.g. "em" in "GooglE Maps").
     */
    private fun candidateStarts(e: Entry, len: Int): List<Int> {
        val last = e.key.length - len
        if (last < 0) return emptyList()
        fun crosses(i: Int) = (i + 1 until i + len).any { e.wordStarts[it] }
        fun tier(i: Int) = when {
            i == 0 -> 0
            crosses(i) -> 3
            e.wordStarts[i] -> 1
            else -> 2
        }
        return (0..last).sortedBy { tier(it) } // stable: keeps left-to-right order within a tier
    }

    /** 0 exact, 1 prefix, 2 word-start, 3 anywhere. */
    private fun rank(e: Entry, q: String, idx: Int): Int {
        if (e.key.length == q.length) return 0
        if (idx == 0) return 1
        var i = idx
        while (i >= 0) {
            if (e.wordStarts[i]) return 2
            i = e.key.indexOf(q, i + 1)
        }
        return 3
    }

    companion object {
        private const val MAX_SUB = 24

        fun normalize(s: String): String {
            val d = Normalizer.normalize(s, Normalizer.Form.NFD)
            val sb = StringBuilder(d.length)
            for (c in d) if (c.isLetterOrDigit()) sb.append(c.lowercaseChar())
            return sb.toString()
        }

        /** Normalizes and records which key positions begin a word (space/punct or camelCase). */
        private fun normalizeWithWords(id: String, label: String): Entry {
            val d = Normalizer.normalize(label, Normalizer.Form.NFD)
            val sb = StringBuilder(d.length)
            val starts = ArrayList<Boolean>(d.length)
            var prevWasSep = true
            var prevLower = false
            for (c in d) {
                if (!c.isLetterOrDigit()) {
                    if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) prevWasSep = true
                    continue
                }
                starts.add(prevWasSep || (prevLower && c.isUpperCase()))
                sb.append(c.lowercaseChar())
                prevWasSep = false
                prevLower = c.isLowerCase()
            }
            return Entry(id, label, sb.toString(), starts.toBooleanArray())
        }
    }
}
