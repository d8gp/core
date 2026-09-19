package com.d8gp.plugins.protocol

// Minimal semver: the manifest only uses plain `x.y.z`. No ranges, no
// pre-release tags — anything fancier is rejected by the manifest validator.
object Semver {
    private val SEMVER_RE = Regex("""^\d+\.\d+\.\d+$""")

    fun isSemver(value: String): Boolean = SEMVER_RE.matches(value)

    // -1 if a < b, 0 if equal, 1 if a > b. Both must be valid `x.y.z`.
    fun compareSemver(a: String, b: String): Int {
        val pa = a.split('.')
        val pb = b.split('.')
        for (i in 0..2) {
            val c = compareDigits(pa[i], pb[i])
            if (c != 0) return if (c < 0) -1 else 1
        }
        return 0
    }

    // Numeric order of two ASCII digit strings of any length, without parsing
    // them into a bounded integer type.
    private fun compareDigits(a: String, b: String): Int {
        val x = a.trimStart('0')
        val y = b.trimStart('0')
        if (x.length != y.length) return x.length.compareTo(y.length)
        return x.compareTo(y)
    }

    // True when `have` satisfies `min` (have >= min). Used for minAppVersion gating.
    fun satisfiesMin(have: String, min: String): Boolean = compareSemver(have, min) >= 0
}
