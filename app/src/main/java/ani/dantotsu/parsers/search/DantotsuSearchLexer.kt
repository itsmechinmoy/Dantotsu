package ani.dantotsu.parsers.search

sealed interface DantotsuToken {
    object LParen : DantotsuToken
    object RParen : DantotsuToken
    object Not : DantotsuToken
    object Or : DantotsuToken
    object And : DantotsuToken

    data class CompToken(
        val field: String,
        val comparator: String,
        val value: String
    ) : DantotsuToken

    data class FieldToken(
        val field: String,
        val value: String
    ) : DantotsuToken

    data class TermToken(
        val value: String
    ) : DantotsuToken
}

object DantotsuSearchLexer {
    private val regex = Regex(
        """
            (?<LParen> \( )|
            (?<RParen> \) )|
            (?<NOT> -(?![\s,]) )|
            (?<OR> \|\| )|
            (?<AND> && )|
            (?<CompField> [a-zA-Z_][a-zA-Z0-9_]* )(?<Comparator> >=|<=|>|<|= )(?: " (?<CompValQuoted> [^"]* ) " | ' (?<CompValSingleQuoted> [^']* ) ' | (?<CompVal> [^\s,()]+ ))|
            (?<Field> [a-zA-Z_][a-zA-Z0-9_]* ) : (?: " (?<FieldValQuoted> [^"]* ) " | ' (?<FieldValSingleQuoted> [^']* ) ' | (?<FieldVal> [^\s,()]+ ))|
            (?: " (?<GeneralQuoted> [^"]* ) " | ' (?<GeneralSingleQuoted> [^']* ) ' | (?<General> [^\s,()]+ ))
        """.trimIndent(),
        RegexOption.COMMENTS
    )

    fun tokenize(query: String): List<DantotsuToken> {
        val tokens = mutableListOf<DantotsuToken>()
        for (match in regex.findAll(query)) {
            val groups = match.groups
            when {
                groups["LParen"] != null -> tokens.add(DantotsuToken.LParen)
                groups["RParen"] != null -> tokens.add(DantotsuToken.RParen)
                groups["NOT"] != null -> tokens.add(DantotsuToken.Not)
                groups["OR"] != null -> tokens.add(DantotsuToken.Or)
                groups["AND"] != null -> tokens.add(DantotsuToken.And)
                groups["CompField"] != null -> {
                    val field = groups["CompField"]!!.value
                    val comp = groups["Comparator"]!!.value
                    val valStr = groups["CompValQuoted"]?.value
                        ?: groups["CompValSingleQuoted"]?.value
                        ?: groups["CompVal"]?.value
                        ?: ""
                    tokens.add(DantotsuToken.CompToken(field, comp, valStr))
                }
                groups["Field"] != null -> {
                    val field = groups["Field"]!!.value
                    val valStr = groups["FieldValQuoted"]?.value
                        ?: groups["FieldValSingleQuoted"]?.value
                        ?: groups["FieldVal"]?.value
                        ?: ""
                    tokens.add(DantotsuToken.FieldToken(field, valStr))
                }
                else -> {
                    val valStr = groups["GeneralQuoted"]?.value
                        ?: groups["GeneralSingleQuoted"]?.value
                        ?: groups["General"]?.value
                        ?: ""
                    if (valStr.isNotEmpty()) {
                        tokens.add(DantotsuToken.TermToken(valStr))
                    }
                }
            }
        }
        return tokens
    }
}
