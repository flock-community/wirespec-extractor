package community.flock.wirespec.extractor.extract.graphql

/**
 * Just enough of a GraphQL SDL reader to learn the declared type of every field and
 * argument: `type`, `interface`, and `input` definitions and their `extend`s. Everything
 * else — scalars, enums, unions, directives, the schema definition, descriptions, default
 * values, even executable documents in a `.graphql` file — is skipped over.
 */
internal object GraphQlSdl {

    /** A GraphQL type reference: `Name`, `[Type]`, or `Type!`. */
    sealed interface TypeRef {
        data class Named(val name: String) : TypeRef
        data class ListOf(val type: TypeRef) : TypeRef
        data class NonNull(val type: TypeRef) : TypeRef
    }

    data class Field(val name: String, val type: TypeRef, val arguments: Map<String, TypeRef> = emptyMap())

    /** A `type`, `interface`, or `input` (or an extension of one) with the fields it declares. */
    data class TypeDefinition(val name: String, val fields: List<Field>)

    class SyntaxException(message: String) : RuntimeException(message)

    fun parse(source: String): List<TypeDefinition> = Parser(tokenize(source)).definitions()

    private val DEFINITION_KEYWORDS = setOf(
        "type", "interface", "input", "extend", "schema", "scalar", "union", "enum", "directive",
        // Executable definitions, so a `.graphql` file holding operations is skipped, not misread.
        "query", "mutation", "subscription", "fragment",
    )

    private sealed interface Token {
        data class Name(val value: String) : Token
        data class Punctuator(val value: String) : Token
        /** Strings, numbers: values and descriptions, never anything this reader needs. */
        data object Literal : Token
    }

    private fun tokenize(source: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                c.isWhitespace() || c == ',' || c == '﻿' -> i++
                c == '#' -> while (i < source.length && source[i] != '\n' && source[i] != '\r') i++
                source.startsWith("\"\"\"", i) -> {
                    i += 3
                    while (i < source.length && !source.startsWith("\"\"\"", i)) i += if (source.startsWith("\\\"\"\"", i)) 4 else 1
                    if (i >= source.length) throw SyntaxException("unterminated block string")
                    i += 3
                    tokens += Token.Literal
                }
                c == '"' -> {
                    i++
                    while (i < source.length && source[i] != '"') {
                        if (source[i] == '\n') throw SyntaxException("unterminated string")
                        i += if (source[i] == '\\') 2 else 1
                    }
                    if (i >= source.length) throw SyntaxException("unterminated string")
                    i++
                    tokens += Token.Literal
                }
                source.startsWith("...", i) -> { tokens += Token.Punctuator("..."); i += 3 }
                c in "!$&()=:@[]{}|" -> { tokens += Token.Punctuator(c.toString()); i++ }
                c == '_' || c.isLetter() -> {
                    val start = i
                    while (i < source.length && (source[i] == '_' || source[i].isLetterOrDigit())) i++
                    tokens += Token.Name(source.substring(start, i))
                }
                c == '-' || c.isDigit() -> {
                    i++
                    while (i < source.length && (source[i].isLetterOrDigit() || source[i] in ".+-")) i++
                    tokens += Token.Literal
                }
                else -> throw SyntaxException("unexpected character '$c'")
            }
        }
        return tokens
    }

    private class Parser(private val tokens: List<Token>) {
        private var position = 0

        fun definitions(): List<TypeDefinition> {
            val definitions = mutableListOf<TypeDefinition>()
            while (position < tokens.size) {
                // An anonymous query: `{ … }`.
                if (peekPunctuator("{")) { skipBalanced(); continue }
                skipDescription()
                if (name() == "extend") position++
                when (name()) {
                    "type", "interface" -> definitions += implementing()
                    "input" -> definitions += input()
                    else -> skipDefinition()
                }
            }
            return definitions
        }

        private fun implementing(): TypeDefinition {
            position++
            val name = expectName()
            if (name() == "implements") {
                position++
                // `A & B`, or the legacy `A B`; a following definition's keyword ends the list.
                while (peekPunctuator("&") || (name() != null && !peekDefinitionStart())) position++
            }
            skipDirectives()
            return TypeDefinition(name, block { fieldDefinition() })
        }

        private fun input(): TypeDefinition {
            position++
            val name = expectName()
            skipDirectives()
            return TypeDefinition(name, block { inputValue().let { (n, t) -> Field(n, t) } })
        }

        private fun fieldDefinition(): Field {
            skipDescription()
            val name = expectName()
            val arguments = if (peekPunctuator("(")) {
                position++
                buildMap { while (!peekPunctuator(")")) inputValue().let { (n, t) -> put(n, t) } }.also { position++ }
            } else emptyMap()
            expectPunctuator(":")
            val type = type()
            skipDirectives()
            return Field(name, type, arguments)
        }

        private fun inputValue(): Pair<String, TypeRef> {
            skipDescription()
            val name = expectName()
            expectPunctuator(":")
            val type = type()
            if (peekPunctuator("=")) { position++; skipValue() }
            skipDirectives()
            return name to type
        }

        private fun type(): TypeRef {
            val base = if (peekPunctuator("[")) {
                position++
                TypeRef.ListOf(type()).also { expectPunctuator("]") }
            } else TypeRef.Named(expectName())
            return if (peekPunctuator("!")) { position++; TypeRef.NonNull(base) } else base
        }

        /** `{ item* }`, or nothing — a type may be declared without fields. */
        private fun <T> block(item: () -> T): List<T> {
            if (!peekPunctuator("{")) return emptyList()
            position++
            val items = mutableListOf<T>()
            while (!peekPunctuator("}")) {
                if (position >= tokens.size) throw SyntaxException("unterminated block")
                items += item()
            }
            position++
            return items
        }

        private fun skipDescription() { if (peek() == Token.Literal) position++ }

        private fun skipDirectives() {
            while (peekPunctuator("@")) {
                position++
                expectName()
                if (peekPunctuator("(")) skipBalanced()
            }
        }

        private fun skipValue() {
            when {
                peekPunctuator("[") || peekPunctuator("{") -> skipBalanced()
                peekPunctuator("$") -> position += 2
                else -> position++
            }
        }

        /** Skips a bracketed group — `(…)`, `[…]`, or `{…}` — whatever it holds. */
        private fun skipBalanced() {
            var depth = 0
            do {
                val token = tokens.getOrNull(position++) ?: throw SyntaxException("unbalanced brackets")
                if (token is Token.Punctuator) when (token.value) {
                    "(", "[", "{" -> depth++
                    ")", "]", "}" -> depth--
                }
            } while (depth > 0)
        }

        /** Skips to the next top-level definition: past the keyword, then any balanced groups. */
        private fun skipDefinition() {
            position++
            while (position < tokens.size && !peekDefinitionStart()) {
                if (peekPunctuator("(") || peekPunctuator("[") || peekPunctuator("{")) skipBalanced() else position++
            }
        }

        private fun peekDefinitionStart(): Boolean {
            val token = peek()
            return (token is Token.Name && token.value in DEFINITION_KEYWORDS) ||
                (token == Token.Literal && (tokens.getOrNull(position + 1) as? Token.Name)?.value in DEFINITION_KEYWORDS)
        }

        private fun peek(): Token? = tokens.getOrNull(position)
        private fun name(): String? = (peek() as? Token.Name)?.value
        private fun peekPunctuator(value: String) = (peek() as? Token.Punctuator)?.value == value

        private fun expectName(): String =
            name()?.also { position++ } ?: throw SyntaxException("expected a name, found ${describe()}")

        private fun expectPunctuator(value: String) {
            if (!peekPunctuator(value)) throw SyntaxException("expected '$value', found ${describe()}")
            position++
        }

        private fun describe(): String = when (val token = peek()) {
            null -> "end of file"
            is Token.Name -> "'${token.value}'"
            is Token.Punctuator -> "'${token.value}'"
            Token.Literal -> "a literal"
        }
    }
}
