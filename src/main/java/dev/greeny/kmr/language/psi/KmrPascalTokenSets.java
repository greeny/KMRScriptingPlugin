package dev.greeny.kmr.language.psi;

import com.intellij.psi.tree.TokenSet;

import static dev.greeny.kmr.language.psi.KmrPascalTypes.*;

public interface KmrPascalTokenSets
{

	TokenSet IDENTIFIERS = TokenSet.create(IDENTIFIER);

	/** All {$...} directive kinds. Directives are comments for the parser; the preprocessor reads them. */
	TokenSet DIRECTIVES = TokenSet.create(
		DIRECTIVE, DIRECTIVE_INCLUDE, DIRECTIVE_DEFINE, DIRECTIVE_UNDEF, DIRECTIVE_IFDEF, DIRECTIVE_IFNDEF, DIRECTIVE_ELSE, DIRECTIVE_ENDIF, DIRECTIVE_EVENT
	);

	TokenSet COMMENTS = TokenSet.orSet(TokenSet.create(COMMENT_A, COMMENT_B), DIRECTIVES);

	TokenSet STRINGS = TokenSet.create(STRING, CHAR);

	TokenSet NUMBERS = TokenSet.create(NUMBER);

	TokenSet KEYWORDS = TokenSet.create(
		BEGIN, END, IF, ELSE, WITH, WHILE, REPEAT, UNTIL, FOR, DO, THEN, TYPE, VAR, CONST, PROCEDURE, FUNCTION, OUT,
		TO, DOWNTO, AND, OR, NOT, DIV, MOD, XOR, SHL, SHR, RECORD, ARRAY, SET, CASE, OF, IN, EXIT, BREAK, CONTINUE
	);

	TokenSet CONSTANTS = TokenSet.create(TRUE, FALSE, NIL);

	TokenSet OPERATORS = TokenSet.create(PLUS, MINUS, TIMES, DIVIDE, ASSIGN, NOTEQ, EQ, LT, GT, LE, GE, AT);

	/** Tokens that act as unary/binary operators inside expressions (symbols and keyword operators). */
	TokenSet OPERATOR_TOKENS = TokenSet.create(PLUS, MINUS, TIMES, DIVIDE, NOTEQ, EQ, LT, GT, LE, GE, AND, OR, NOT, DIV, MOD, XOR, SHL, SHR, IN, AT);

	TokenSet DOTS = TokenSet.create(DOT, DOUBLEDOT);

	TokenSet BRACKETS = TokenSet.create(LBRACKET, RBRACKET, LSQUAREBRACKET, RSQUAREBRACKET);

}
