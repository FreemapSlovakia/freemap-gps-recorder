package sk.freemap.tracker

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
) {

    /** Positional encoding — `[seq,ts,lat,lon,alt,acc,spd,brg]`, in [FIELDS] order. */
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
        sb.append(']')
    }

    companion object {
        /** Field order of the positional arrays, echoed back in the `/track` payload. */
        val FIELDS = listOf("seq", "ts", "lat", "lon", "alt", "acc", "spd", "brg")

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
    }
}
