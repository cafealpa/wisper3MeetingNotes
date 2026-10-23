package com.example.groqtranscribe

/** 일부 겹말 때문에 전체 긴 발화를 버리지 않고, 겹친 시간만 제외한다. */
fun cleanSpeakerRegions(turns: List<SpeakerTurn>, speaker: Int): List<Pair<Double, Double>> {
    val others = turns.filter { it.speaker != speaker }.sortedBy { it.start }
    return buildList {
        for (turn in turns.filter { it.speaker == speaker }) {
            var start = turn.start
            for (other in others) {
                if (other.end <= start || other.start >= turn.end) continue
                if (other.start - start >= 0.3) add(start to minOf(other.start, turn.end))
                start = maxOf(start, other.end)
                if (start >= turn.end) break
            }
            if (turn.end - start >= 0.3) add(start to turn.end)
        }
    }.sortedByDescending { it.second - it.first }
}
