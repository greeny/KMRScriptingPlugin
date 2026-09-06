package dev.greeny.kmr.language.unit;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.SyntaxTraverser;
import com.intellij.psi.tree.IElementType;
import dev.greeny.kmr.language.psi.KmrPascalTokenSets;
import dev.greeny.kmr.language.psi.KmrPascalTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A parsed {@code {$...}} directive. Directives are comment tokens for the parser; this class gives the
 * preprocessor and the include reference a structured view of them.
 */
public final class KmrPascalDirective
{

	public enum Kind
	{
		INCLUDE, DEFINE, UNDEF, IFDEF, IFNDEF, ELSE, ENDIF, EVENT, OTHER
	}

	private static final Pattern PATTERN = Pattern.compile("^\\{\\$\\s*[A-Za-z]+[ \\t]*(.*?)\\s*}?$", Pattern.DOTALL);

	public final Kind kind;
	/** The argument (include file name, symbol name), trimmed; empty when absent. */
	public final String argument;
	/** Range of the argument relative to the token start, or null when empty. */
	@Nullable
	public final TextRange argumentRange;
	/** Absolute range of the whole directive token in its file. */
	public final TextRange range;

	private KmrPascalDirective(Kind kind, String argument, @Nullable TextRange argumentRange, TextRange range)
	{
		this.kind = kind;
		this.argument = argument;
		this.argumentRange = argumentRange;
		this.range = range;
	}

	@NotNull
	public static Kind kindOf(@Nullable IElementType type)
	{
		if (type == KmrPascalTypes.DIRECTIVE_INCLUDE) return Kind.INCLUDE;
		if (type == KmrPascalTypes.DIRECTIVE_DEFINE) return Kind.DEFINE;
		if (type == KmrPascalTypes.DIRECTIVE_UNDEF) return Kind.UNDEF;
		if (type == KmrPascalTypes.DIRECTIVE_IFDEF) return Kind.IFDEF;
		if (type == KmrPascalTypes.DIRECTIVE_IFNDEF) return Kind.IFNDEF;
		if (type == KmrPascalTypes.DIRECTIVE_ELSE) return Kind.ELSE;
		if (type == KmrPascalTypes.DIRECTIVE_ENDIF) return Kind.ENDIF;
		if (type == KmrPascalTypes.DIRECTIVE_EVENT) return Kind.EVENT;
		return Kind.OTHER;
	}

	@NotNull
	public static KmrPascalDirective parse(@NotNull IElementType type, @NotNull CharSequence text, int startOffset)
	{
		Kind kind = kindOf(type);
		Matcher matcher = PATTERN.matcher(text);
		String argument = "";
		TextRange argumentRange = null;
		if (matcher.matches() && matcher.end(1) > matcher.start(1)) {
			argument = matcher.group(1);
			argumentRange = TextRange.create(matcher.start(1), matcher.end(1));
		}
		return new KmrPascalDirective(kind, argument, argumentRange, TextRange.from(startOffset, text.length()));
	}

	/** The directive represented by a comment token, or null if the comment is not a directive. */
	@Nullable
	public static KmrPascalDirective of(@NotNull PsiElement comment)
	{
		IElementType type = comment.getNode().getElementType();
		if (!KmrPascalTokenSets.DIRECTIVES.contains(type)) {
			return null;
		}
		return parse(type, comment.getText(), comment.getTextRange().getStartOffset());
	}

	/** All directives of a file in text order. */
	@NotNull
	public static List<KmrPascalDirective> collect(@NotNull PsiFile file)
	{
		List<KmrPascalDirective> result = new ArrayList<>();
		for (PsiComment comment : SyntaxTraverser.psiTraverser(file).filter(PsiComment.class)) {
			KmrPascalDirective directive = of(comment);
			if (directive != null) {
				result.add(directive);
			}
		}
		return result;
	}

	/** Lower-cased file name without directories, the key used by {@link KmrPascalIncludeIndex}. */
	@NotNull
	public static String includeKey(@NotNull String includeArgument)
	{
		String normalized = includeArgument.replace('\\', '/');
		int slash = normalized.lastIndexOf('/');
		return (slash < 0 ? normalized : normalized.substring(slash + 1)).toLowerCase();
	}

	@Override
	public String toString()
	{
		return "{$" + kind + " " + argument + "}@" + range;
	}

}
