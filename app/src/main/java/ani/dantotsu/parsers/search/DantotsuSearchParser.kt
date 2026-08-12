package ani.dantotsu.parsers.search

class DantotsuSearchParser(private val tokens: List<DantotsuToken>) {
    private var index = 0

    fun parse(): DantotsuSearchNode {
        if (tokens.isEmpty()) return SearchEmptyNode
        val result = parseOr()
        return if (index < tokens.size) result else result
    }

    private fun parseOr(): DantotsuSearchNode {
        val nodes = mutableListOf<DantotsuSearchNode>()
        nodes.add(parseAnd())

        while (match(DantotsuToken.Or)) {
            nodes.add(parseAnd())
        }

        return if (nodes.size == 1) nodes[0] else SearchOrNode(nodes)
    }

    private fun parseAnd(): DantotsuSearchNode {
        val nodes = mutableListOf<DantotsuSearchNode>()
        nodes.add(parseTerm())

        while (index < tokens.size && peek() !is DantotsuToken.RParen && peek() !is DantotsuToken.Or) {
            match(DantotsuToken.And) // Optional explicit &&
            nodes.add(parseTerm())
        }

        return if (nodes.size == 1) nodes[0] else SearchAndNode(nodes)
    }

    private fun parseTerm(): DantotsuSearchNode {
        var negated = false
        if (match(DantotsuToken.Not)) {
            negated = true
        }

        val node = when (val current = next()) {
            is DantotsuToken.LParen -> {
                val subNode = parseOr()
                match(DantotsuToken.RParen)
                subNode
            }
            is DantotsuToken.FieldToken -> {
                SearchFieldNode(current.field, current.value, negated)
            }
            is DantotsuToken.CompToken -> {
                SearchCompNode(current.field, current.comparator, current.value, negated)
            }
            is DantotsuToken.TermToken -> {
                SearchGeneralNode(current.value, negated)
            }
            else -> SearchEmptyNode
        }

        return if (negated && node !is SearchFieldNode && node !is SearchCompNode && node !is SearchGeneralNode) {
            SearchNotNode(node)
        } else {
            node
        }
    }

    private fun peek(): DantotsuToken? = tokens.getOrNull(index)

    private fun next(): DantotsuToken? = tokens.getOrNull(index++)

    private fun match(expected: DantotsuToken): Boolean {
        if (peek() == expected) {
            index++
            return true
        }
        return false
    }
}
