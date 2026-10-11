package com.woderplayer.quickshare

object FuzzySearcher {

    // Subsequence match + Levenshtein distance combo for blazing fast Android search
    fun matches(query: String, target: String): Boolean {
        if (query.isEmpty()) return true
        val q = query.trim().lowercase()
        val t = target.lowercase()

        // 1. Direct contains (Instant)
        if (t.contains(q)) return true

        // 2. Subsequence match (e.g. "pbg" matches "pubg mobile")
        var qIdx = 0
        var tIdx = 0
        while (qIdx < q.length && tIdx < t.length) {
            if (q[qIdx] == t[tIdx]) qIdx++
            tIdx++
        }
        if (qIdx == q.length) return true

        // 3. Fuzzy Levenshtein for short typos (only if query is 3+ chars)
        if (q.length >= 3) {
            val words = t.split(" ", "_", "-", ".")
            for (word in words) {
                if (word.length >= q.length - 1 && word.length <= q.length + 2) {
                    if (levenshteinDistance(q, word) <= 1) return true
                }
            }
        }
        return false
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[s1.length][s2.length]
    }
}
