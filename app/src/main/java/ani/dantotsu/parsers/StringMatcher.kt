package ani.dantotsu.parsers

import ani.dantotsu.util.Logger

class StringMatcher {
    companion object {
        fun levenshteinDistance(s1: String, s2: String): Int {
            if (s1 == s2) return 0
            if (s1.isEmpty()) return s2.length
            if (s2.isEmpty()) return s1.length

            val len1 = s1.length
            val len2 = s2.length

            var prev = IntArray(len2 + 1) { it }
            var curr = IntArray(len2 + 1)

            for (i in 1..len1) {
                curr[0] = i
                val c1 = s1[i - 1]
                for (j in 1..len2) {
                    val cost = if (c1 == s2[j - 1]) 0 else 1
                    curr[j] = minOf(
                        prev[j] + 1,       // deletion
                        curr[j - 1] + 1,   // insertion
                        prev[j - 1] + cost // substitution
                    )
                }
                val temp = prev
                prev = curr
                curr = temp
            }
            return prev[len2]
        }

        fun closestString(target: String, list: List<String>): Pair<String, Int> {
            var minDistance = Int.MAX_VALUE
            var closestString = ""
            var closestIndex = -1

            for (index in 0 until list.size) {
                val str = list[index]
                val distance = levenshteinDistance(target, str)
                if (distance < minDistance) {
                    minDistance = distance
                    closestString = str
                    closestIndex = index
                }
            }

            return Pair(closestString, closestIndex)
        }

        fun closestStringMovedToTop(target: String, list: List<String>): List<String> {
            val (_, closestIndex) = closestString(target, list)
            if (closestIndex == -1 || closestIndex == 0) {
                return list
            }
            val result = ArrayList<String>(list.size)
            result.add(list[closestIndex])
            for (i in 0 until closestIndex) {
                result.add(list[i])
            }
            for (i in (closestIndex + 1) until list.size) {
                result.add(list[i])
            }
            return result
        }

        fun closestShowMovedToTop(target: String, shows: List<ShowResponse>): List<ShowResponse> {
            val closestShowAndIndex = closestShow(target, shows)
            val closestIndex = closestShowAndIndex.second
            if (closestIndex == -1 || closestIndex == 0) {
                Logger.log("No closest show found for $target")
                return shows
            }
            Logger.log("Closest show found for $target is ${closestShowAndIndex.first.name}")
            val result = ArrayList<ShowResponse>(shows.size)
            result.add(shows[closestIndex])
            for (i in 0 until closestIndex) {
                result.add(shows[i])
            }
            for (i in (closestIndex + 1) until shows.size) {
                result.add(shows[i])
            }
            return result
        }

        private fun closestShow(
            target: String,
            shows: List<ShowResponse>
        ): Pair<ShowResponse, Int> {
            var minDistance = Int.MAX_VALUE
            var closestShow = ShowResponse("", "", "")
            var closestIndex = -1

            for (index in 0 until shows.size) {
                val show = shows[index]
                val distance = levenshteinDistance(target, show.name)
                if (distance < minDistance) {
                    minDistance = distance
                    closestShow = show
                    closestIndex = index
                }
            }

            return Pair(closestShow, closestIndex)
        }
    }
}
