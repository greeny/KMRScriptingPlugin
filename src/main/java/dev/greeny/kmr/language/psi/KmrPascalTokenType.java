package dev.greeny.kmr.language.psi;

import com.intellij.psi.tree.IElementType;
import dev.greeny.kmr.language.KmrPascalLanguage;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

public class KmrPascalTokenType extends IElementType
{

	/** Words for the tokens whose debug name is not their text (Grammar-Kit names keyword and operator tokens by their text). */
	private static final Map<String, String> WORDS = Map.of(
		"IDENTIFIER", "identifier", "NUMBER", "number", "STRING", "string", "CHAR", "character",
		"COMMENT_A", "comment", "COMMENT_B", "comment");

	public KmrPascalTokenType(@NotNull @NonNls String debugName)
	{
		super(debugName, KmrPascalLanguage.INSTANCE);
	}

	/**
	 * How the token reads in parser messages ("';' or end expected, got '.'"): the token text for keywords and
	 * operators, a word for the others. Grammar-Kit quotes anything that does not start like an identifier.
	 */
	@Override
	public String toString()
	{
		String name = super.toString();
		if (name.startsWith("DIRECTIVE")) {
			return "directive";
		}
		return WORDS.getOrDefault(name, name);
	}

}
