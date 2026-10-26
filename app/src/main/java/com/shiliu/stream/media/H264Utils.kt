package com.shiliu.stream.media

/** H.264 Annex-B 码流的简单工具方法。 */
object H264Utils {

    /** 按起始码拆分 NAL 单元，返回值不含起始码。 */
    fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val result = ArrayList<ByteArray>(4)
        var index = 0
        var start = -1

        while (index < data.size - 2) {
            if (data[index].toInt() == 0 && data[index + 1].toInt() == 0) {
                val threeByte = index + 2 < data.size && data[index + 2].toInt() == 1
                val fourByte = index + 3 < data.size &&
                    data[index + 2].toInt() == 0 && data[index + 3].toInt() == 1
                if (threeByte || fourByte) {
                    if (start >= 0) {
                        var end = index
                        while (end > start && data[end - 1].toInt() == 0) end--
                        if (end > start) result.add(data.copyOfRange(start, end))
                    }
                    index += if (threeByte) 3 else 4
                    start = index
                    continue
                }
            }
            index++
        }

        if (start >= 0) {
            var end = data.size
            while (end > start && data[end - 1].toInt() == 0) end--
            if (end > start) result.add(data.copyOfRange(start, end))
        }

        // 某些平台的 csd 数据没有起始码，此时整体视为一个 NAL
        if (result.isEmpty() && data.isNotEmpty()) result.add(data)
        return result
    }

    fun nalType(nal: ByteArray): Int =
        if (nal.isEmpty()) -1 else nal[0].toInt() and 0x1F

    /** SPS 的 profile_idc / constraints / level_idc 三元组，用于 SDP 的 profile-level-id。 */
    fun profileLevelId(sps: ByteArray): String {
        if (sps.size < 4) return "42e01f"
        return String.format(
            "%02x%02x%02x",
            sps[1].toInt() and 0xFF,
            sps[2].toInt() and 0xFF,
            sps[3].toInt() and 0xFF
        )
    }
}
