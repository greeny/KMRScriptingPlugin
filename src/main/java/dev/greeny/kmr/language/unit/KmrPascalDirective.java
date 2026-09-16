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

	/** A directive name the game understands, for completion. */
	public static final class Known
	{
		public final String name;
		public final Kind kind;
		/** Placeholder describing the argument, empty when the directive takes none. */
		public final String argument;
		public final String description;

		private Known(String name, Kind kind, String argument, String description)
		{
			this.name = name;
			this.kind = kind;
			this.argument = argument;
			this.description = description;
		}

		public boolean hasArgument()
		{
			return !argument.isEmpty();
		}
	}

	/**
	 * Every directive the game processes: PascalScript's own preprocessor directives (uPSPreProcessor.pas) plus the
	 * ones KaM Remake adds in KM_ScriptPreProcessor.pas; any other name is an error for the game's preprocessor.
	 */
	public static final List<Known> KNOWN = List.of(
		new Known("I", Kind.INCLUDE, "file.script", "Include another script file"),
		new Known("INCLUDE", Kind.INCLUDE, "file.script", "Include another script file"),
		new Known("DEFINE", Kind.DEFINE, "SYMBOL", "Define a preprocessor symbol"),
		new Known("UNDEF", Kind.UNDEF, "SYMBOL", "Undefine a preprocessor symbol"),
		new Known("IFDEF", Kind.IFDEF, "SYMBOL", "Keep the code below only when the symbol is defined"),
		new Known("IFNDEF", Kind.IFNDEF, "SYMBOL", "Keep the code below only when the symbol is not defined"),
		new Known("ELSE", Kind.ELSE, "", "Invert the enclosing {$IFDEF} / {$IFNDEF}"),
		new Known("ENDIF", Kind.ENDIF, "", "End the enclosing {$IFDEF} / {$IFNDEF}"),
		new Known("EVENT", Kind.EVENT, "evtName:Handler", "Register a procedure as an additional handler of a game event"),
		new Known("COMMAND", Kind.OTHER, "name:Handler", "Register a procedure as a console command"),
		new Known("CMD", Kind.OTHER, "name:Handler", "Register a procedure as a console command (short form)"),
		new Known("CUSTOM_TH_TROOP_COST", Kind.OTHER, "1,1,1,1,1", "Townhall troop costs: 5 values of 1..255"),
		new Known("CUSTOM_MARKET_GOLD_PRICE_X", Kind.OTHER, "1.0,1.0", "Market gold ore and gold price multipliers")
	);

	/** One half of {@code {$EVENT evtX:Handler}}: its text and its range relative to the token start. */
	public static final class Part
	{
		public final String name;
		public final TextRange range;

		private Part(String name, TextRange range)
		{
			this.name = name;
			this.range = range;
		}
	}

	private static final Pattern PATTERN = Pattern.compile("^\\{\\$\\s*[A-Za-z_]+[ \\t]*(.*?)\\s*}?$", Pattern.DOTALL);

	public final Kind kind;
	/** The argument (include file name, symbol name), trimmed; empty when absent. */
	public final String argument;
	/** Range of the argument relative to the token start, or null when empty. */
	@Nullable
	public final TextRange argumentRange;
	/** Absolute range of the whole directive token in its file. */
	public final TextRange range;
	/** {$EVENT} only: the event name before the ":"; null when the directive has no argument. */
	@Nullable
	public final Part eventPart;
	/** {$EVENT} only: the handler name after the ":"; null when there is no ":". */
	@Nullable
	public final Part handlerPart;

	private KmrPascalDirective(Kind kind, String argument, @Nullable TextRange argumentRange, TextRange range,
							   @Nullable Part eventPart, @Nullable Part handlerPart)
	{
		this.kind = kind;
		this.argument = argument;
		this.argumentRange = argumentRange;
		this.range = range;
		this.eventPart = eventPart;
		this.handlerPart = handlerPart;
	}

	/** The known directive with that name (case-insensitive), or null. */
	@Nullable
	public static Known known(@NotNull String name)
	{
		for (Known known : KNOWN) {
			if (known.name.equalsIgnoreCase(name)) {
				return known;
			}
		}
		return null;
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
		Part eventPart = null;
		Part handlerPart = null;
		if (kind == Kind.EVENT && argumentRange != null) {
			int colon = argument.indexOf(':');
			int start = argumentRange.getStartOffset();
			if (colon < 0) {
				eventPart = part(text, argumentRange);
			} else {
				eventPart = part(text, TextRange.create(start, start + colon));
				handlerPart = part(text, TextRange.create(start + colon + 1, argumentRange.getEndOffset()));
			}
		}
		return new KmrPascalDirective(kind, argument, argumentRange, TextRange.from(startOffset, text.length()), eventPart, handlerPart);
	}

	/** The text of a range with the surrounding whitespace dropped from both the text and the range. */
	@NotNull
	private static Part part(@NotNull CharSequence text, @NotNull TextRange range)
	{
		int start = range.getStartOffset();
		int end = range.getEndOffset();
		while (start < end && Character.isWhitespace(text.charAt(start))) start++;
		while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
		return new Part(text.subSequence(start, end).toString(), TextRange.create(start, end));
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
