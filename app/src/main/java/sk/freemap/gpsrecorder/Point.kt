package sk.freemap.gpsrecorder

/**
 * One recorded fix, as it is handed to HTTP clients. Optional fields stay null when the platform
 * reported them as absent, rather than collapsing to zero.
 */
class Point(
    val seq: Long,
    val ts: Long,
    val lat: Double,
    val lon: Double,
    val alt: Double?,
    val acc: Double?,
    val spd: Double?,
    val brg: Double?,
    val altMsl: Double?,
    val altAcc: Double?,
    val spdAcc: Double?,
    val brgAcc: Double?,
    val sat: Int?,
    val src: String?,
    val seg: Long,
) {

    /** Positional encoding, in [FIELDS] order. */
    fun appendJson(sb: StringBuilder) {
        sb.append('[').append(seq).append(',').append(ts).append(',')
        num(sb, lat, COORD_DECIMALS)
        sb.append(',')
        num(sb, lon, COORD_DECIMALS)
        sb.append(',')
        num(sb, alt, METRE_DECIMALS)
        sb.append(',')
        num(sb, acc, METRE_DECIMALS)
        sb.append(',')
        num(sb, spd, METRE_DECIMALS)
        sb.append(',')
        num(sb, brg, METRE_DECIMALS)
        sb.append(',')
        num(sb, altMsl, METRE_DECIMALS)
        sb.append(',')
        num(sb, altAcc, METRE_DECIMALS)
        sb.append(',')
        num(sb, spdAcc, METRE_DECIMALS)
        sb.append(',')
        num(sb, brgAcc, METRE_DECIMALS)
        sb.append(',')
        if (sat == null) sb.append("null") else sb.append(sat)
        sb.append(',')
        str(sb, src)
        sb.append(',').append(seg).append(']')
    }

    companion object {
        /**
         * Field order of the positional arrays, echoed back in the `/track` payload. Append-only:
         * clients decode rows by this list, so a new column at the end costs a reader nothing and
         * one that does not know it simply ignores it — but reordering would silently corrupt every
         * client at once.
         */
        val FIELDS = listOf(
            "seq", "ts", "lat", "lon", "alt", "acc", "spd", "brg",
            "altMsl", "altAcc", "spdAcc", "brgAcc", "sat", "src", "seg",
        )

        val FIELDS_JSON = FIELDS.joinToString(",", "[", "]") { "\"$it\"" }

        /** ~1 cm, an order of magnitude finer than any fix we will ever see. */
        private const val COORD_DECIMALS = 7
        private const val METRE_DECIMALS = 2

        /**
         * Rounds before printing. Raw doubles serialise as things like `48.70631999999999` — noise
         * at this scale, and over a long track those digits are a real share of the payload.
         */
        private fun num(sb: StringBuilder, value: Double?, decimals: Int) {
            if (value == null || value.isNaN() || value.isInfinite()) {
                sb.append("null")
                return
            }
            var factor = 1.0
            repeat(decimals) { factor *= 10 }
            sb.append(Math.round(value * factor) / factor)
        }

        /** Provider names are tame, but they come from the platform rather than from us. */
        private fun str(sb: StringBuilder, value: String?) {
            if (value == null) {
                sb.append("null")
                return
            }
            sb.append('"')
            for (c in value) {
                when {
                    c == '"' || c == '\\' -> sb.append('\\').append(c)
                    c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                    else -> sb.append(c)
                }
            }
            sb.append('"')
        }
    }
}
