package dev.greeny.kmr.language;

import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.HighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import dev.greeny.kmr.language.psi.KmrPascalTokenSets;
import dev.greeny.kmr.language.psi.KmrPascalTypes;
import org.jetbrains.annotations.NotNull;

import static com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey;

public class KmrPascalSyntaxHighlighter extends SyntaxHighlighterBase
{

	// The external names below are stored in users' colour schemes; do not rename them.
	public static final TextAttributesKey STRING = createTextAttributesKey("KMR_PASCAL_STRING", DefaultLanguageHighlighterColors.STRING);
	public static final TextAttributesKey NUMBER = createTextAttributesKey("KMR_PASCAL_NUMBER", DefaultLanguageHighlighterColors.NUMBER);
	public static final TextAttributesKey KEYWORD = createTextAttributesKey("KMR_PASCAL_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD);
	public static final TextAttributesKey COMMENT = createTextAttributesKey("KMR_PASCAL_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT);
	public static final TextAttributesKey DIRECTIVE = createTextAttributesKey("KMR_PASCAL_DIRECTIVE", DefaultLanguageHighlighterColors.DOC_COMMENT);
	/** {@code @since}, {@code @param} ... at the start of a comment line (like PHPDoc tags). */
	public static final TextAttributesKey DOC_TAG = createTextAttributesKey("KMR_PASCAL_DOC_TAG", DefaultLanguageHighlighterColors.DOC_COMMENT_TAG);
	/** The rest of a {@code @tag} line. */
	public static final TextAttributesKey DOC_TAG_VALUE = createTextAttributesKey("KMR_PASCAL_DOC_TAG_VALUE", DefaultLanguageHighlighterColors.DOC_COMMENT_TAG_VALUE);
	public static final TextAttributesKey OPERATOR = createTextAttributesKey("KMR_PASCAL_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN);
	public static final TextAttributesKey DOT = createTextAttributesKey("KMR_PASCAL_DOT", DefaultLanguageHighlighterColors.DOT);
	public static final TextAttributesKey COMMA = createTextAttributesKey("KMR_PASCAL_COMMA", DefaultLanguageHighlighterColors.COMMA);
	public static final TextAttributesKey SEMICOLON = createTextAttributesKey("KMR_PASCAL_SEMICOLON", DefaultLanguageHighlighterColors.SEMICOLON);
	public static final TextAttributesKey BRACKETS = createTextAttributesKey("KMR_PASCAL_BRACKETS", DefaultLanguageHighlighterColors.BRACKETS);
	public static final TextAttributesKey CONSTANTS = createTextAttributesKey("KMR_PASCAL_CONSTANT", DefaultLanguageHighlighterColors.CONSTANT);
	public static final TextAttributesKey VARIABLES = createTextAttributesKey("KMR_PASCAL_VARIABLES", DefaultLanguageHighlighterColors.GLOBAL_VARIABLE);
	public static final TextAttributesKey BAD_CHARACTER = createTextAttributesKey("KMR_PASCAL_BAD_CHARACTER", HighlighterColors.BAD_CHARACTER);
	/** Code removed by the preprocessor ($IFDEF ... ) in the current compilation unit; defaults come from colorSchemes/*.xml. */
	public static final TextAttributesKey INACTIVE_CODE = createTextAttributesKey("KMR_PASCAL_INACTIVE_CODE");

	private static final TextAttributesKey[] EMPTY_KEYS = new TextAttributesKey[0];

	@NotNull
	@Override
	public Lexer getHighlightingLexer()
	{
		return new KmrPascalLexerAdapter();
	}

	@Override
	public TextAttributesKey @NotNull [] getTokenHighlights(IElementType tokenType)
	{
		if (KmrPascalTokenSets.STRINGS.contains(tokenType)) {
			return pack(STRING);
		}
		if (KmrPascalTokenSets.NUMBERS.contains(tokenType)) {
			return pack(NUMBER);
		}
		if (KmrPascalTokenSets.KEYWORDS.contains(tokenType)) {
			return pack(KEYWORD);
		}
		if (KmrPascalTokenSets.CONSTANTS.contains(tokenType)) {
			return pack(CONSTANTS);
		}
		if (KmrPascalTokenSets.OPERATORS.contains(tokenType)) {
			return pack(OPERATOR);
		}
		if (KmrPascalTokenSets.DIRECTIVES.contains(tokenType)) {
			return pack(DIRECTIVE);
		}
		if (KmrPascalTokenSets.COMMENTS.contains(tokenType)) {
			return pack(COMMENT);
		}
		if (KmrPascalTokenSets.DOTS.contains(tokenType)) {
			return pack(DOT);
		}
		if (tokenType == KmrPascalTypes.COMMA) {
			return pack(COMMA);
		}
		if (tokenType == KmrPascalTypes.SEMI) {
			return pack(SEMICOLON);
		}
		if (KmrPascalTokenSets.BRACKETS.contains(tokenType)) {
			return pack(BRACKETS);
		}
		if (tokenType == KmrPascalTypes.IDENTIFIER) {
			return pack(VARIABLES);
		}
		if (tokenType == TokenType.BAD_CHARACTER) {
			return pack(BAD_CHARACTER);
		}
		return EMPTY_KEYS;
	}

}
