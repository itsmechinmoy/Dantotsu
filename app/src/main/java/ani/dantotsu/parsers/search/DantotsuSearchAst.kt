package ani.dantotsu.parsers.search

sealed interface DantotsuSearchNode {
    companion object {
        fun from(query: String): DantotsuSearchNode {
            val tokens = DantotsuSearchLexer.tokenize(query)
            return DantotsuSearchParser(tokens).parse()
        }
    }
}

data class SearchAndNode(val children: List<DantotsuSearchNode>) : DantotsuSearchNode
data class SearchOrNode(val children: List<DantotsuSearchNode>) : DantotsuSearchNode
data class SearchNotNode(val child: DantotsuSearchNode) : DantotsuSearchNode
object SearchEmptyNode : DantotsuSearchNode

data class SearchGeneralNode(val value: String, val negated: Boolean) : DantotsuSearchNode

data class SearchFieldNode(
    val field: String,
    val value: String,
    val negated: Boolean
) : DantotsuSearchNode

data class SearchCompNode(
    val field: String,
    val comparator: String,
    val value: String,
    val negated: Boolean
) : DantotsuSearchNode
