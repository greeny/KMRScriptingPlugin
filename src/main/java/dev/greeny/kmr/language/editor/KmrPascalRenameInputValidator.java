package dev.greeny.kmr.language.editor;

import com.intellij.openapi.project.Project;
import com.intellij.patterns.ElementPattern;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.refactoring.rename.RenameInputValidatorEx;
import com.intellij.util.ProcessingContext;
import dev.greeny.kmr.language.KmrPascalLexerAdapter;
import dev.greeny.kmr.language.psi.KmrPascalNamedElement;
import dev.greeny.kmr.language.psi.KmrPascalTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Rename accepts only what the game's lexer reads as one identifier: no spaces or symbols, no reserved words. */
public class KmrPascalRenameInputValidator implements RenameInputValidatorEx
{

	@Override
	public @NotNull ElementPattern<? extends PsiElement> getPattern()
	{
		return PlatformPatterns.psiElement(KmrPascalNamedElement.class);
	}

	@Override
	public boolean isInputValid(@NotNull String newName, @NotNull PsiElement element, @NotNull ProcessingContext context)
	{
		return getErrorMessage(newName, element.getProject()) == null;
	}

	@Override
	public @Nullable String getErrorMessage(@NotNull String newName, @NotNull Project project)
	{
		return validate(newName);
	}

	/** Null when the name is a usable identifier, otherwise the reason. */
	@Nullable
	public static String validate(@NotNull String name)
	{
		if (name.isEmpty()) {
			return "Name must not be empty";
		}
		KmrPascalLexerAdapter lexer = new KmrPascalLexerAdapter();
		lexer.start(name);
		if (lexer.getTokenType() != KmrPascalTypes.IDENTIFIER || lexer.getTokenEnd() != name.length()) {
			lexer.advance();
			return lexer.getTokenType() == null && isWord(name) ? "'" + name + "' is a reserved word" : "'" + name + "' is not a valid identifier";
		}
		return null;
	}

	private static boolean isWord(@NotNull String name)
	{
		return name.matches("[A-Za-z_][A-Za-z0-9_]*");
	}

}
