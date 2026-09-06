package dev.greeny.kmr.language.formatter;

import com.intellij.formatting.*;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.tree.TokenSet;
import dev.greeny.kmr.language.KmrPascalLanguage;
import org.jetbrains.annotations.NotNull;

import static dev.greeny.kmr.language.psi.KmrPascalTypes.*;

/** Reformat Code support: block structure from {@link KmrPascalBlock}, spacing rules below. Line breaks are kept as written. */
public class KmrPascalFormattingModelBuilder implements FormattingModelBuilder
{

	private static final TokenSet BINARY_OPERATORS = TokenSet.create(PLUS, MINUS, TIMES, DIVIDE, EQ, NOTEQ, LT, GT, LE, GE, DIV, MOD, AND, OR, XOR, SHL, SHR, IN);
	private static final TokenSet KEYWORDS_FOLLOWED_BY_SPACE = TokenSet.create(IF, WHILE, FOR, WITH, CASE, UNTIL, PROCEDURE, FUNCTION, ARRAY, SET, OF, TO, DOWNTO,
		THEN, DO, ELSE, VAR, CONST, TYPE, NOT);
	private static final TokenSet KEYWORDS_PRECEDED_BY_SPACE = TokenSet.create(THEN, DO, OF, TO, DOWNTO, ELSE, UNTIL, IN);
	private static final TokenSet ROUTINE_HEADERS = TokenSet.create(PROCEDURE_DECLARATION, FUNCTION_DECLARATION, PROCEDURE_TYPE, FUNCTION_TYPE);

	@Override
	public @NotNull FormattingModel createModel(@NotNull FormattingContext context)
	{
		CodeStyleSettings settings = context.getCodeStyleSettings();
		int indentSize = settings.getIndentOptionsByFile(context.getContainingFile()).INDENT_SIZE;
		KmrPascalBlock root = new KmrPascalBlock(context.getNode(), null, Indent.getNoneIndent(), createSpacingBuilder(settings), indentSize);
		return FormattingModelProvider.createFormattingModelForPsiFile(context.getContainingFile(), root, settings);
	}

	static SpacingBuilder createSpacingBuilder(@NotNull CodeStyleSettings settings)
	{
		return new SpacingBuilder(settings, KmrPascalLanguage.INSTANCE)
			// punctuation
			.around(ASSIGN).spaces(1)
			.before(COMMA).none()
			.after(COMMA).spaces(1)
			.before(SEMI).none()
			.before(COLON).none()
			.after(COLON).spaces(1)
			.around(DOUBLEDOT).none()
			.around(DOT).none()
			.after(AT).none()
			// brackets
			.beforeInside(LBRACKET, CALL_EXPRESSION).none()
			.beforeInside(LBRACKET, ROUTINE_HEADERS).none()
			.after(LBRACKET).none()
			.before(RBRACKET).none()
			.beforeInside(LSQUAREBRACKET, INDEX_EXPRESSION).none()
			.after(LSQUAREBRACKET).none()
			.before(RSQUAREBRACKET).none()
			// unary operators stick to their operand
			.afterInside(MINUS, UNARY_EXPRESSION).none()
			.afterInside(PLUS, UNARY_EXPRESSION).none()
			// binary operators and keywords
			.around(BINARY_OPERATORS).spaces(1)
			.before(KEYWORDS_PRECEDED_BY_SPACE).spaces(1)
			.after(KEYWORDS_FOLLOWED_BY_SPACE).spaces(1);
	}

}
