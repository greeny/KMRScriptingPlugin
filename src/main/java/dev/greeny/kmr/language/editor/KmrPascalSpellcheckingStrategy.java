package dev.greeny.kmr.language.editor;

import com.intellij.psi.PsiElement;
import com.intellij.spellchecker.tokenizer.SpellcheckingStrategy;
import com.intellij.spellchecker.tokenizer.Tokenizer;
import dev.greeny.kmr.language.psi.KmrPascalTokenSets;
import org.jetbrains.annotations.NotNull;

/** Spell-check comments, string literals and declared names; leave directives alone. */
public class KmrPascalSpellcheckingStrategy extends SpellcheckingStrategy
{

	@Override
	public @NotNull Tokenizer<?> getTokenizer(PsiElement element)
	{
		if (element.getNode() != null && KmrPascalTokenSets.DIRECTIVES.contains(element.getNode().getElementType())) {
			return EMPTY_TOKENIZER;
		}
		if (element.getNode() != null && KmrPascalTokenSets.STRINGS.contains(element.getNode().getElementType())) {
			return TEXT_TOKENIZER;
		}
		return super.getTokenizer(element);
	}

}
